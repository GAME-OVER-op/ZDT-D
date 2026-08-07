use crate::{
    backend::BackendPool,
    config::Config,
    socks5::{connect_via_socks5, RuntimeFailureClass},
    status::RuntimeStats,
    target::TargetAddr,
};
use anyhow::{anyhow, Context, Result};
use std::{
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{net::TcpStream, time::Instant as TokioInstant};
use tracing::{debug, info, warn};

const DIRECT_FAILURE_THRESHOLD: u32 = 3;
const DIRECT_FAILURE_COOLDOWN_MS: u64 = 5_000;

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum RouteKind {
    Socks,
    Direct,
}

pub struct RoutedStream {
    pub stream: TcpStream,
    pub route: RouteKind,
    pub backend: Option<SocketAddr>,
}

#[derive(Default)]
struct DirectHealth {
    failures: AtomicU32,
    cooldown_until_ms: AtomicU64,
}

impl DirectHealth {
    fn allowed(&self) -> bool {
        now_ms() >= self.cooldown_until_ms.load(Ordering::Relaxed)
    }

    fn note_failure(&self) {
        let failures = self.failures.fetch_add(1, Ordering::Relaxed).saturating_add(1);
        if failures >= DIRECT_FAILURE_THRESHOLD {
            self.cooldown_until_ms
                .store(now_ms().saturating_add(DIRECT_FAILURE_COOLDOWN_MS), Ordering::Relaxed);
        }
    }

    fn note_success(&self) {
        self.failures.store(0, Ordering::Relaxed);
        self.cooldown_until_ms.store(0, Ordering::Relaxed);
    }
}

#[derive(Clone)]
pub struct Router {
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    direct_fallback_active: Arc<AtomicBool>,
    direct_health: Arc<DirectHealth>,
}

impl Router {
    pub fn new(config: Arc<Config>, pool: BackendPool, stats: Arc<RuntimeStats>) -> Self {
        Self {
            config,
            pool,
            stats,
            direct_fallback_active: Arc::new(AtomicBool::new(false)),
            direct_health: Arc::new(DirectHealth::default()),
        }
    }

    pub async fn connect(&self, target: &TargetAddr) -> Result<RoutedStream> {
        self.reject_recursive_target(target)?;

        // dnscrypt-proxy uses a plain SOCKS Dialer in several paths and that
        // dial can outlive the caller context. Keep route establishment inside
        // DNSCrypt's own query timeout.
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
                    // Runtime success does not promote an unverified backend to
                    // GREEN; only the strict Full health probe may do that.
                    self.pool.mark_runtime_success(backend, started.elapsed()).await;
                    self.stats
                        .upstream_connections
                        .fetch_add(1, Ordering::Relaxed);
                    self.note_socks_restored();
                    debug!(%backend, %target, "routed connection through SOCKS5 backend");
                    return Ok(RoutedStream {
                        stream,
                        route: RouteKind::Socks,
                        backend: Some(backend),
                    });
                }
                Ok(Err(mut error)) => {
                    // Keep the existing DNS-specific one-shot retry for short
                    // Android route transitions. It changes only the current
                    // DNSCrypt request; health is still decided by Full probe.
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
                                        self.stats
                                            .upstream_connections
                                            .fetch_add(1, Ordering::Relaxed);
                                        self.note_socks_restored();
                                        debug!(%backend, %target, "single-backend transient retry succeeded");
                                        return Ok(RoutedStream {
                                            stream,
                                            route: RouteKind::Socks,
                                            backend: Some(backend),
                                        });
                                    }
                                    Ok(Err(retry_error)) => error = retry_error,
                                    Err(_) => {
                                        let message = format!(
                                            "single-backend retry exceeded {} ms",
                                            retry_timeout.as_millis()
                                        );
                                        self.pool
                                            .mark_runtime_failure(
                                                backend,
                                                RuntimeFailureClass::Soft,
                                                &message,
                                            )
                                            .await;
                                        failures.push(format!("{backend}: {message}"));
                                        continue;
                                    }
                                }
                            }
                        }
                    }

                    let class = error.runtime_failure_class();
                    let message = error.to_string();
                    self.pool.mark_runtime_failure(backend, class, &message).await;
                    failures.push(format!("{backend}: {message}"));
                }
                Err(_) => {
                    let message = format!(
                        "backend attempt exceeded {} ms",
                        attempt_timeout.as_millis()
                    );
                    self.pool
                        .mark_runtime_failure(backend, RuntimeFailureClass::Soft, &message)
                        .await;
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

        // T2S tracks DIRECT independently from SOCKS health. D2S keeps a small,
        // DNS-specific version: after repeated direct failures, skip the same
        // doomed path briefly instead of making every DNS query wait for it.
        if !self.direct_health.allowed() {
            return Err(anyhow!(
                "DIRECT fallback temporarily suppressed after repeated failures; SOCKS failures: {}",
                failures.join(" | ")
            ));
        }

        self.note_direct_fallback(target, &failures);
        let stream = match connect_direct(target, &self.config, deadline).await {
            Ok(stream) => stream,
            Err(error) => {
                self.direct_health.note_failure();
                return Err(error);
            }
        };
        self.stats.direct_connections.fetch_add(1, Ordering::Relaxed);
        Ok(RoutedStream {
            stream,
            route: RouteKind::Direct,
            backend: None,
        })
    }

    pub async fn report_relay_failure(
        &self,
        route: RouteKind,
        backend: Option<SocketAddr>,
        error: &str,
    ) {
        match (route, backend) {
            (RouteKind::Socks, Some(addr)) => self.pool.mark_relay_suspect(addr, error).await,
            (RouteKind::Direct, _) => self.direct_health.note_failure(),
            _ => {}
        }
    }

    pub fn report_relay_success(
        &self,
        route: RouteKind,
        _backend: Option<SocketAddr>,
        remote_to_client: u64,
    ) {
        // For DIRECT, receiving actual payload is stronger evidence than TCP
        // connect alone and clears the failure cooldown.
        if route == RouteKind::Direct && remote_to_client > 0 {
            self.direct_health.note_success();
        }
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

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn direct_health_cools_down_after_repeated_failures_and_recovers_on_payload() {
        let health = DirectHealth::default();
        assert!(health.allowed());
        health.note_failure();
        health.note_failure();
        assert!(health.allowed());
        health.note_failure();
        assert!(!health.allowed());
        health.note_success();
        assert!(health.allowed());
    }
}
