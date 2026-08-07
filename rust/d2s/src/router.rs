use crate::{
    backend::BackendPool,
    config::Config,
    socks5::connect_via_socks5,
    status::RuntimeStats,
    target::TargetAddr,
};
use anyhow::{anyhow, Context, Result};
use std::{
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    time::Instant,
};
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
    pub backend: Option<SocketAddr>,
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
        let candidates = self.pool.candidate_order().await;
        let mut failures = Vec::new();

        // Reliability takes priority here: try every currently healthy backend
        // with its own bounded timeout. Do not impose a second global route
        // deadline or a global connect semaphore; both can turn a DNSCrypt
        // connection burst into artificial timeouts.
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
                    self.pool
                        .mark_runtime_success(backend, started.elapsed())
                        .await;
                    return Ok(self.socks_route(stream, backend, target));
                }
                Ok(Err(error)) => {
                    let message = error.to_string();
                    if error.affects_backend_health() {
                        self.pool.mark_runtime_failure(backend, &message).await;
                    } else {
                        // RFC 1928 destination/path failures (for example
                        // REP=0x04 Host unreachable) do not mean that the local
                        // SOCKS5 backend itself is broken.
                        self.pool.mark_target_failure(backend, &message).await;
                    }
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

        self.note_direct_fallback(target, &failures);
        let stream = connect_direct(target, &self.config).await?;
        self.stats
            .direct_connections
            .fetch_add(1, Ordering::Relaxed);
        Ok(RoutedStream {
            stream,
            route: RouteKind::Direct,
            backend: None,
        })
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
        let first = !self.direct_fallback_active.swap(true, Ordering::Relaxed);
        if first {
            if failures.is_empty() {
                info!(%target, "no GREEN SOCKS5 backends; entering DIRECT fallback");
            } else {
                warn!(
                    %target,
                    failures = %failures.join(" | "),
                    "all GREEN SOCKS5 backends failed; entering DIRECT fallback"
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
    Err(anyhow!(
        "DIRECT connect to {target} failed: {}",
        errors.join(" | ")
    ))
    .with_context(|| format!("direct fallback failed for {target}"))
}
