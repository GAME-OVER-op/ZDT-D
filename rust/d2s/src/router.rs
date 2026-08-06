use crate::{backend::BackendPool, config::Config, socks5::connect_via_socks5, status::RuntimeStats, target::TargetAddr};
use anyhow::{anyhow, Context, Result};
use std::{sync::Arc, time::Instant};
use tokio::net::TcpStream;
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
}

impl Router {
    pub fn new(config: Arc<Config>, pool: BackendPool, stats: Arc<RuntimeStats>) -> Self {
        Self { config, pool, stats }
    }

    pub async fn connect(&self, target: &TargetAddr) -> Result<RoutedStream> {
        self.reject_recursive_target(target)?;
        let candidates = self.pool.candidate_order().await;
        let mut failures = Vec::new();

        for backend in candidates {
            self.pool.mark_attempt(backend).await;
            let started = Instant::now();
            let attempt = tokio::time::timeout(
                self.config.backend_attempt_timeout(),
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
                    self.stats.upstream_connections.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    debug!(%backend, %target, "routed connection through SOCKS5 backend");
                    return Ok(RoutedStream { stream, route: RouteKind::Socks });
                }
                Ok(Err(error)) => {
                    let message = error.to_string();
                    self.pool.mark_runtime_failure(backend, &message).await;
                    failures.push(format!("{backend}: {message}"));
                }
                Err(_) => {
                    let message = format!(
                        "backend attempt exceeded {} ms",
                        self.config.backend_attempt_timeout_ms
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

        if failures.is_empty() {
            info!(%target, "no GREEN SOCKS5 backends; using DIRECT fallback");
        } else {
            warn!(%target, failures = %failures.join(" | "), "all selected SOCKS5 backends failed; using DIRECT fallback");
        }
        let stream = connect_direct(target, &self.config).await?;
        self.stats.direct_connections.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        Ok(RoutedStream { stream, route: RouteKind::Direct })
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

async fn connect_direct(target: &TargetAddr, config: &Config) -> Result<TcpStream> {
    let addresses = target.resolve().await?;
    let mut errors = Vec::new();
    for addr in addresses {
        match tokio::time::timeout(config.direct_connect_timeout(), TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(config.tcp_nodelay);
                return Ok(stream);
            }
            Ok(Err(error)) => errors.push(format!("{addr}: {error}")),
            Err(_) => errors.push(format!("{addr}: timeout")),
        }
    }
    Err(anyhow!("DIRECT connect to {target} failed: {}", errors.join(" | ")))
        .with_context(|| format!("direct fallback failed for {target}"))
}
