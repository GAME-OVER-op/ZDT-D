use crate::{
    backend::BackendPool,
    config::Config,
    socks5::connect_via_socks5,
    status::RuntimeStats,
    target::TargetAddr,
};
use anyhow::{anyhow, Context, Result};
use std::{sync::{Arc, atomic::{AtomicBool, Ordering}}, time::{Duration, Instant}};
use tokio::{net::TcpStream, time::Instant as TokioInstant};
use tracing::{debug, info, warn};

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum RouteKind {
    Socks,
    Direct,
}

pub struct RoutedStream {
    pub stream: TcpStream,
    pub route: RouteKind,
}

#[derive(Clone)]
pub struct Router {
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    direct_fallback_active: Arc<AtomicBool>,
}

impl Router {
    pub fn new(config: Arc<Config>, pool: BackendPool, stats: Arc<RuntimeStats>) -> Self {
        Self {
            config,
            pool,
            stats,
            direct_fallback_active: Arc::new(AtomicBool::new(false)),
        }
    }

    pub async fn connect(&self, target: &TargetAddr) -> Result<RoutedStream> {
        self.reject_recursive_target(target)?;

        // dnscrypt-proxy uses a plain SOCKS Dialer in several paths and that
        // dial can outlive the caller context. Keep the complete D2S routing
        // decision inside DNSCrypt's own query timeout instead of allowing a
        // chain of backend attempts to stall a DNS query indefinitely.
        let deadline = TokioInstant::now() + self.config.route_budget();
        let candidates = self.pool.candidate_order().await;
        let single_backend_mode = candidates.len() == 1;
        let mut failures = Vec::new();

        for backend in candidates {
            let now = TokioInstant::now();
            if now >= deadline {
                failures.push("dnscrypt route budget exhausted before trying all backends".to_string());
                break;
            }
            let remaining = deadline - now;
            let attempt_timeout = self.config.backend_attempt_timeout().min(remaining);

            self.pool.mark_attempt(backend).await;
            let started = Instant::now();
            let attempt = tokio::time::timeout(
                attempt_timeout,
                connect_via_socks5(
                    backend,
                    target,
                    self.config.connect_timeout(),
                    self.config.upstream_handshake_timeout(),
                    self.config.tcp_nodelay,
                ),
            )
            .await;
            match attempt {
                Ok(Ok(stream)) => {
                    self.pool.mark_runtime_success(backend, started.elapsed()).await;
                    self.pool.record_recent_target(target).await;
                    self.stats
                        .upstream_connections
                        .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    self.note_socks_restored();
                    debug!(%backend, %target, "routed connection through SOCKS5 backend");
                    return Ok(RoutedStream { stream, route: RouteKind::Socks });
                }
                Ok(Err(mut error)) => {
                    // dnscrypt-proxy does not retry the same resolver after a
                    // SOCKS network error. In single-backend mode, one quick
                    // reconnect for REP 0x03/0x04 can absorb the short route
                    // transition window seen during Wi-Fi/mobile handover.
                    if single_backend_mode && error.should_retry_once_on_single_backend() {
                        let retry_delay = Duration::from_millis(75);
                        let now = TokioInstant::now();
                        if now < deadline && deadline - now > retry_delay {
                            tokio::time::sleep(retry_delay).await;
                            let now = TokioInstant::now();
                            if now < deadline {
                                let remaining = deadline - now;
                                let retry_timeout = self.config.backend_attempt_timeout().min(remaining);
                                let retry_started = Instant::now();
                                match tokio::time::timeout(
                                    retry_timeout,
                                    connect_via_socks5(
                                        backend,
                                        target,
                                        self.config.connect_timeout(),
                                        self.config.upstream_handshake_timeout(),
                                        self.config.tcp_nodelay,
                                    ),
                                )
                                .await
                                {
                                    Ok(Ok(stream)) => {
                                        self.pool.mark_runtime_success(backend, retry_started.elapsed()).await;
                                        self.pool.record_recent_target(target).await;
                                        self.stats
                                            .upstream_connections
                                            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                                        self.note_socks_restored();
                                        debug!(%backend, %target, "single-backend transient retry succeeded");
                                        return Ok(RoutedStream { stream, route: RouteKind::Socks });
                                    }
                                    Ok(Err(retry_error)) => {
                                        error = retry_error;
                                    }
                                    Err(_) => {
                                        let message = format!(
                                            "single-backend retry exceeded {} ms",
                                            retry_timeout.as_millis()
                                        );
                                        self.pool.mark_runtime_failure(backend, &message).await;
                                        failures.push(format!("{backend}: {message}"));
                                        continue;
                                    }
                                }
                            }
                        }
                    }

                    let message = error.to_string();
                    if error.is_target_path_failure() {
                        self.pool.mark_runtime_target_failure(backend, &message).await;
                    } else {
                        self.pool.mark_runtime_failure(backend, &message).await;
                    }
                    failures.push(format!("{backend}: {message}"));
                }
                Err(_) => {
                    let message = format!(
                        "backend attempt exceeded {} ms",
                        attempt_timeout.as_millis()
                    );
                    self.pool.mark_runtime_failure(backend, &message).await;
                    failures.push(format!("{backend}: {message}"));
                }
            }
        }

        if !self.config.direct_fallback {
            return Err(anyhow!(
                "no SOCKS5 backend could reach {target}; direct fallback is disabled; failures: {}",
                failures.join(" | ")
            ));
        }

        self.note_direct_fallback(target, &failures);
        let stream = connect_direct(target, &self.config, deadline).await?;
        // A target that DNSCrypt reached successfully through DIRECT is also a
        // valuable recovery probe for the SOCKS backend. This lets D2S learn
        // the actual resolver set even while the backend is temporarily down.
        self.pool.record_recent_target(target).await;
        self.stats
            .direct_connections
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        Ok(RoutedStream { stream, route: RouteKind::Direct })
    }

    fn note_direct_fallback(&self, target: &TargetAddr, failures: &[String]) {
        if !self.direct_fallback_active.swap(true, Ordering::Relaxed) {
            if failures.is_empty() {
                info!(%target, "no GREEN SOCKS5 backends; entering DIRECT fallback");
            } else {
                warn!(%target, failures = %failures.join(" | "), "SOCKS5 backends failed; entering DIRECT fallback");
            }
        } else {
            debug!(%target, "using DIRECT fallback");
        }
    }

    fn note_socks_restored(&self) {
        if self.direct_fallback_active.swap(false, Ordering::Relaxed) {
            info!("SOCKS5 routing restored; leaving DIRECT fallback");
        }
    }

    fn reject_recursive_target(&self, target: &TargetAddr) -> Result<()> {
        match target {
            TargetAddr::Ip(addr) => {
                let listener_loop = addr.port() == self.config.listen.port()
                    && addr.ip().is_loopback()
                    && (self.config.listen.ip().is_loopback() || self.config.listen.ip().is_unspecified());
                if listener_loop || self.config.backends.contains(addr) {
                    return Err(anyhow!("refusing recursive D2S target {addr}"));
                }
            }
            TargetAddr::Domain(host, port) => {
                if *port == self.config.listen.port()
                    && (host.eq_ignore_ascii_case("localhost") || host == "127.0.0.1" || host == "::1")
                {
                    return Err(anyhow!("refusing recursive D2S target {host}:{port}"));
                }
            }
        }
        Ok(())
    }
}

async fn connect_direct(target: &TargetAddr, config: &Config, deadline: TokioInstant) -> Result<TcpStream> {
    if TokioInstant::now() >= deadline {
        return Err(anyhow!("DIRECT connect to {target} skipped: dnscrypt route budget exhausted"));
    }

    let addresses = tokio::time::timeout_at(deadline, target.resolve())
        .await
        .map_err(|_| anyhow!("DIRECT target resolution for {target} exceeded dnscrypt route budget"))??;
    let mut errors = Vec::new();
    for addr in addresses {
        let now = TokioInstant::now();
        if now >= deadline {
            errors.push("dnscrypt route budget exhausted".to_string());
            break;
        }
        let remaining = deadline - now;
        let attempt_timeout = config.direct_connect_timeout().min(remaining);
        match tokio::time::timeout(attempt_timeout, TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(config.tcp_nodelay);
                return Ok(stream);
            }
            Ok(Err(error)) => errors.push(format!("{addr}: {error}")),
            Err(_) => errors.push(format!("{addr}: timeout after {} ms", attempt_timeout.as_millis())),
        }
    }
    Err(anyhow!("DIRECT connect to {target} failed: {}", errors.join(" | ")))
        .with_context(|| format!("direct fallback failed for {target}"))
}
