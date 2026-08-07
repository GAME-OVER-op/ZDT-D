use crate::{
    backend::BackendPool,
    config::Config,
    socks5::connect_via_socks5,
    status::RuntimeStats,
    target::TargetAddr,
};
use anyhow::{anyhow, Context, Result};
use std::{
    collections::HashSet,
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Instant,
};
use tokio::{net::TcpStream, sync::Semaphore};
use tracing::{debug, info, warn};

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

#[derive(Clone)]
pub struct Router {
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    connecting: Arc<Semaphore>,
    direct_fallback_active: Arc<AtomicBool>,
}

struct AttemptFailure {
    message: String,
    budget_exhausted: bool,
}

impl Router {
    pub fn new(config: Arc<Config>, pool: BackendPool, stats: Arc<RuntimeStats>) -> Self {
        let max_connecting = config.max_connecting;
        Self {
            config,
            pool,
            stats,
            connecting: Arc::new(Semaphore::new(max_connecting)),
            direct_fallback_active: Arc::new(AtomicBool::new(false)),
        }
    }

    pub async fn connect(&self, target: &TargetAddr) -> Result<RoutedStream> {
        self.reject_recursive_target(target)?;
        let deadline = Instant::now() + self.config.route_timeout();
        let mut failures = Vec::new();
        let mut attempted = HashSet::new();
        let mut attempts = 0usize;

        for backend in self.pool.candidate_order().await {
            if attempts >= self.config.max_backend_attempts || Instant::now() >= deadline {
                break;
            }
            if !attempted.insert(backend) {
                continue;
            }
            attempts += 1;
            match self.attempt_backend(backend, target, deadline).await {
                Ok(stream) => return Ok(self.socks_route(stream, backend, target)),
                Err(failure) => {
                    failures.push(format!("{backend}: {}", failure.message));
                    if failure.budget_exhausted {
                        break;
                    }
                }
            }
        }

        // Yellow/Red backends are half-open: only one real connection or
        // health probe may test a degraded backend at a time.
        while attempts < self.config.max_backend_attempts && Instant::now() < deadline {
            let Some(backend) = self.pool.claim_degraded_candidate(&attempted).await else {
                break;
            };
            attempted.insert(backend);
            attempts += 1;
            match self.attempt_backend(backend, target, deadline).await {
                Ok(stream) => return Ok(self.socks_route(stream, backend, target)),
                Err(failure) => {
                    failures.push(format!("{backend}: {}", failure.message));
                    if failure.budget_exhausted {
                        break;
                    }
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
        let stream = connect_direct(target, &self.config, self.connecting.clone()).await?;
        self.stats
            .direct_connections
            .fetch_add(1, Ordering::Relaxed);
        Ok(RoutedStream {
            stream,
            route: RouteKind::Direct,
            backend: None,
        })
    }

    async fn attempt_backend(
        &self,
        backend: SocketAddr,
        target: &TargetAddr,
        deadline: Instant,
    ) -> std::result::Result<TcpStream, AttemptFailure> {
        self.pool.mark_attempt(backend).await;
        let started = Instant::now();
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(AttemptFailure {
                message: "SOCKS5 route time budget exhausted".to_string(),
                budget_exhausted: true,
            });
        }

        let permit = match tokio::time::timeout(
            remaining,
            self.connecting.clone().acquire_owned(),
        )
        .await
        {
            Ok(Ok(permit)) => permit,
            Ok(Err(_)) => {
                return Err(AttemptFailure {
                    message: "outbound connect limiter closed".to_string(),
                    budget_exhausted: false,
                });
            }
            Err(_) => {
                return Err(AttemptFailure {
                    message: "SOCKS5 route time budget exhausted while waiting to connect"
                        .to_string(),
                    budget_exhausted: true,
                });
            }
        };

        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            drop(permit);
            return Err(AttemptFailure {
                message: "SOCKS5 route time budget exhausted".to_string(),
                budget_exhausted: true,
            });
        }
        let attempt_timeout = self.config.backend_attempt_timeout().min(remaining);
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
        drop(permit);

        match attempt {
            Ok(Ok(stream)) => {
                self.pool
                    .mark_runtime_success(backend, started.elapsed())
                    .await;
                Ok(stream)
            }
            Ok(Err(error)) => {
                let message = error.to_string();
                if error.affects_backend_health() {
                    self.pool.mark_runtime_failure(backend, &message).await;
                } else {
                    self.pool.mark_target_failure(backend, &message).await;
                }
                Err(AttemptFailure {
                    message,
                    budget_exhausted: false,
                })
            }
            Err(_) => {
                let message = format!("backend attempt exceeded {} ms", attempt_timeout.as_millis());
                self.pool.mark_runtime_failure(backend, &message).await;
                Err(AttemptFailure {
                    message,
                    budget_exhausted: Instant::now() >= deadline,
                })
            }
        }
    }

    fn socks_route(
        &self,
        stream: TcpStream,
        backend: SocketAddr,
        target: &TargetAddr,
    ) -> RoutedStream {
        self.stats
            .upstream_connections
            .fetch_add(1, Ordering::Relaxed);
        if self.direct_fallback_active.swap(false, Ordering::Relaxed) {
            info!("SOCKS5 routing restored; leaving DIRECT fallback");
        }
        debug!(%backend, %target, "routed connection through SOCKS5 backend");
        RoutedStream {
            stream,
            route: RouteKind::Socks,
            backend: Some(backend),
        }
    }

    fn note_direct_fallback(&self, target: &TargetAddr, failures: &[String]) {
        let first = !self
            .direct_fallback_active
            .swap(true, Ordering::Relaxed);
        if first {
            if failures.is_empty() {
                info!(%target, "SOCKS5 pool unavailable; entering DIRECT fallback");
            } else {
                warn!(
                    %target,
                    failures = %failures.join(" | "),
                    "SOCKS5 attempts failed; entering DIRECT fallback"
                );
            }
        } else {
            debug!(%target, failures = %failures.join(" | "), "using DIRECT fallback");
        }
    }

    fn reject_recursive_target(&self, target: &TargetAddr) -> Result<()> {
        match target {
            TargetAddr::Ip(addr) => {
                let listener_loop = addr.port() == self.config.listen.port()
                    && addr.ip().is_loopback()
                    && (self.config.listen.ip().is_loopback()
                        || self.config.listen.ip().is_unspecified());
                if listener_loop || self.config.backends.contains(addr) {
                    return Err(anyhow!("refusing recursive D2S target {addr}"));
                }
            }
            TargetAddr::Domain(host, port) => {
                if *port == self.config.listen.port()
                    && (host.eq_ignore_ascii_case("localhost")
                        || host == "127.0.0.1"
                        || host == "::1")
                {
                    return Err(anyhow!("refusing recursive D2S target {host}:{port}"));
                }
            }
        }
        Ok(())
    }
}

async fn connect_direct(
    target: &TargetAddr,
    config: &Config,
    connecting: Arc<Semaphore>,
) -> Result<TcpStream> {
    let addresses = target.resolve().await?;
    let mut errors = Vec::new();
    for addr in addresses {
        let permit = match tokio::time::timeout(
            config.direct_connect_timeout(),
            connecting.clone().acquire_owned(),
        )
        .await
        {
            Ok(Ok(permit)) => permit,
            Ok(Err(_)) => return Err(anyhow!("outbound connect limiter closed")),
            Err(_) => {
                errors.push(format!("{addr}: connect limiter timeout"));
                continue;
            }
        };
        let result = tokio::time::timeout(config.direct_connect_timeout(), TcpStream::connect(addr)).await;
        drop(permit);
        match result {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(config.tcp_nodelay);
                return Ok(stream);
            }
            Ok(Err(error)) => errors.push(format!("{addr}: {error}")),
            Err(_) => errors.push(format!("{addr}: timeout")),
        }
    }
    Err(anyhow!(
        "DIRECT connect to {target} failed: {}",
        errors.join(" | ")
    ))
    .with_context(|| format!("direct fallback failed for {target}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn destination_specific_socks_errors_do_not_poison_backend() {
        for code in 0x02..=0x06 {
            assert!(!crate::socks5::SocksClientError::ConnectReply(code).affects_backend_health());
        }
        for code in [0x01, 0x07, 0x08, 0x09] {
            assert!(crate::socks5::SocksClientError::ConnectReply(code).affects_backend_health());
        }
    }
}
