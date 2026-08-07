use crate::{backend::BackendPool, config::Config, socks5::connect_via_socks5, status::RuntimeStats, target::TargetAddr};
use anyhow::{anyhow, Context, Result};
use std::{sync::Arc, time::Instant};
use tokio::net::TcpStream;
#[cfg(test)]
use tokio::net::TcpListener;
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

        let stream = match connect_direct(target, &self.config).await {
            Ok(stream) => stream,
            Err(error) => {
                if failures.is_empty() {
                    warn!(%target, %error, "DIRECT fallback failed with no GREEN SOCKS5 backends");
                } else {
                    warn!(%target, failures = %failures.join(" | "), %error, "SOCKS5 backends failed and DIRECT fallback also failed");
                }
                return Err(error);
            }
        };
        if failures.is_empty() {
            info!(%target, "no GREEN SOCKS5 backends; DIRECT fallback established");
        } else {
            warn!(%target, failures = %failures.join(" | "), "all selected SOCKS5 backends failed; DIRECT fallback established");
        }
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
    let addr = match target {
        TargetAddr::Ip(addr) => *addr,
        TargetAddr::Domain(host, port) => {
            return Err(anyhow!(
                "DIRECT fallback refused for domain target {host}:{port}: resolving it through the system DNS can recurse back into DNSCrypt/D2S"
            ));
        }
    };

    match tokio::time::timeout(config.direct_connect_timeout(), TcpStream::connect(addr)).await {
        Ok(Ok(stream)) => {
            let _ = stream.set_nodelay(config.tcp_nodelay);
            Ok(stream)
        }
        Ok(Err(error)) => Err(anyhow!("DIRECT connect to {addr} failed: {error}"))
            .with_context(|| format!("direct fallback failed for {target}")),
        Err(_) => Err(anyhow!(
            "DIRECT connect to {addr} timed out after {} ms",
            config.direct_connect_timeout_ms
        ))
        .with_context(|| format!("direct fallback failed for {target}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config() -> Config {
        toml::from_str(
            r#"
backends = []
direct_fallback = true
"#,
        )
        .expect("test config")
    }

    #[tokio::test]
    async fn direct_fallback_rejects_domain_without_system_dns_resolution() {
        let config = test_config();
        let target = TargetAddr::Domain("dns.example".to_string(), 443);
        let error = connect_direct(&target, &config).await.expect_err("domain DIRECT must fail fast");
        assert!(error.to_string().contains("DIRECT fallback refused for domain target"));
    }

    #[tokio::test]
    async fn direct_fallback_connects_ip_target() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind test listener");
        let addr = listener.local_addr().expect("test listener address");
        let accept = tokio::spawn(async move { listener.accept().await.expect("accept test connection") });

        let config = test_config();
        let target = TargetAddr::Ip(addr);
        let stream = connect_direct(&target, &config).await.expect("IP DIRECT should connect");
        drop(stream);
        let _ = accept.await.expect("accept task");
    }
}
