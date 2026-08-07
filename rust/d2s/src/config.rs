use crate::target::TargetAddr;
use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    path::{Path, PathBuf},
    time::Duration,
};

fn default_listen() -> SocketAddr { "127.0.0.1:11990".parse().unwrap() }
fn default_dnscrypt_timeout_ms() -> u64 { 5_000 }
fn default_true() -> bool { true }
fn default_connect_timeout_ms() -> u64 { 500 }
fn default_handshake_timeout_ms() -> u64 { 1_000 }
fn default_backend_attempt_timeout_ms() -> u64 { 1_200 }
fn default_direct_connect_timeout_ms() -> u64 { 2_000 }
fn default_client_handshake_timeout_ms() -> u64 { 3_000 }
fn default_probe_timeout_ms() -> u64 { 1_200 }
fn default_healthy_probe_interval_secs() -> u64 { 30 }
fn default_recovery_probe_interval_secs() -> u64 { 5 }
fn default_failure_threshold() -> u32 { 3 }
fn default_runtime_cooldown_ms() -> u64 { 2_000 }
fn default_max_connections() -> usize { 1_024 }
fn default_status_interval_secs() -> u64 { 5 }
fn default_shutdown_grace_period_ms() -> u64 { 5_000 }
fn default_log_level() -> String { "info".to_string() }
fn default_probe_targets() -> Vec<String> {
    vec!["1.1.1.1:443".to_string(), "8.8.8.8:443".to_string()]
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    /// Runtime listener. It is intentionally not read from d2s.toml; the value
    /// comes from the active `proxy` entry in dnscrypt-proxy.toml.
    #[serde(skip, default = "default_listen")]
    pub listen: SocketAddr,

    /// Runtime-only DNSCrypt query timeout, loaded from dnscrypt-proxy.toml.
    /// D2S uses it as an upper bound for route establishment because
    /// dnscrypt-proxy's SOCKS dialer can otherwise outlive the query timeout.
    #[serde(skip, default = "default_dnscrypt_timeout_ms")]
    pub dnscrypt_timeout_ms: u64,

    #[serde(default)]
    pub backends: Vec<SocketAddr>,

    #[serde(default = "default_true")]
    pub direct_fallback: bool,

    #[serde(default = "default_connect_timeout_ms")]
    pub connect_timeout_ms: u64,

    #[serde(default = "default_handshake_timeout_ms")]
    pub upstream_handshake_timeout_ms: u64,

    #[serde(default = "default_backend_attempt_timeout_ms")]
    pub backend_attempt_timeout_ms: u64,

    #[serde(default = "default_direct_connect_timeout_ms")]
    pub direct_connect_timeout_ms: u64,

    // Compatibility-only fields written by short-lived experimental builds.
    // The stable transport does not use them; accepting them keeps upgrades
    // from failing on an existing d2s.toml.
    #[serde(default)]
    pub route_timeout_ms: Option<u64>,

    #[serde(default)]
    pub max_backend_attempts: Option<usize>,

    #[serde(default)]
    pub max_connecting: Option<usize>,

    #[serde(default = "default_client_handshake_timeout_ms")]
    pub client_handshake_timeout_ms: u64,

    #[serde(default = "default_probe_timeout_ms")]
    pub probe_timeout_ms: u64,

    #[serde(default = "default_healthy_probe_interval_secs")]
    pub healthy_probe_interval_secs: u64,

    #[serde(default = "default_recovery_probe_interval_secs")]
    pub recovery_probe_interval_secs: u64,

    #[serde(default = "default_failure_threshold")]
    pub failure_threshold: u32,

    #[serde(default = "default_runtime_cooldown_ms")]
    pub runtime_cooldown_ms: u64,

    // Compatibility-only; idle health sleeping is intentionally disabled in
    // the stable baseline because it changed network-switch behaviour.
    #[serde(default)]
    pub idle_after_secs: Option<u64>,

    #[serde(default = "default_probe_targets")]
    pub probe_targets: Vec<String>,

    #[serde(default = "default_max_connections")]
    pub max_connections: usize,

    #[serde(default = "default_true")]
    pub tcp_nodelay: bool,

    #[serde(default = "default_log_level")]
    pub log_level: String,

    #[serde(default)]
    pub status_file: Option<PathBuf>,

    #[serde(default = "default_status_interval_secs")]
    pub status_interval_secs: u64,

    #[serde(default = "default_shutdown_grace_period_ms")]
    pub shutdown_grace_period_ms: u64,
}

#[derive(Debug, Deserialize)]
struct DnscryptConfig {
    proxy: Option<String>,
    timeout: Option<u64>,
}

#[derive(Debug)]
struct DnscryptRuntime {
    listen: SocketAddr,
    timeout_ms: u64,
}

impl Config {
    pub fn load(path: impl AsRef<Path>, dnscrypt_path: impl AsRef<Path>) -> Result<Self> {
        let path = path.as_ref();
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("read configuration {}", path.display()))?;
        let mut config: Self = toml::from_str(&raw)
            .with_context(|| format!("parse configuration {}", path.display()))?;
        let dnscrypt = read_dnscrypt_runtime(dnscrypt_path)?;
        config.listen = dnscrypt.listen;
        config.dnscrypt_timeout_ms = dnscrypt.timeout_ms;
        config.validate()?;
        Ok(config)
    }

    pub fn validate(&self) -> Result<()> {
        if !self.listen.ip().is_loopback() {
            return Err(anyhow!("D2S listener must be loopback: {}", self.listen));
        }
        if self.backends.is_empty() && !self.direct_fallback {
            return Err(anyhow!(
                "at least one SOCKS5 backend is required when direct_fallback=false"
            ));
        }
        if self.backends.contains(&self.listen) {
            return Err(anyhow!("a backend points to the D2S listener itself: {}", self.listen));
        }
        let mut unique = HashSet::new();
        for backend in &self.backends {
            if !unique.insert(*backend) {
                return Err(anyhow!("duplicate backend: {backend}"));
            }
        }
        if self.connect_timeout_ms == 0
            || self.upstream_handshake_timeout_ms == 0
            || self.backend_attempt_timeout_ms == 0
            || self.direct_connect_timeout_ms == 0
            || self.client_handshake_timeout_ms == 0
            || self.probe_timeout_ms == 0
        {
            return Err(anyhow!("all timeout values must be greater than zero"));
        }
        if self.healthy_probe_interval_secs == 0 || self.recovery_probe_interval_secs == 0 {
            return Err(anyhow!("probe intervals must be greater than zero"));
        }
        if self.failure_threshold == 0 {
            return Err(anyhow!("failure_threshold must be greater than zero"));
        }
        if self.max_connections == 0 {
            return Err(anyhow!("max_connections must be greater than zero"));
        }
        if self.status_interval_secs == 0 {
            return Err(anyhow!("status_interval_secs must be greater than zero"));
        }
        if self.shutdown_grace_period_ms == 0 {
            return Err(anyhow!("shutdown_grace_period_ms must be greater than zero"));
        }
        if !self.backends.is_empty() {
            if self.probe_targets.is_empty() {
                return Err(anyhow!("probe_targets must contain at least one HOST:PORT target"));
            }
            for target in &self.probe_targets {
                target
                    .parse::<TargetAddr>()
                    .with_context(|| format!("invalid probe target {target}"))?;
            }
        }
        validate_log_level(&self.log_level)?;
        Ok(())
    }

    pub fn connect_timeout(&self) -> Duration { Duration::from_millis(self.connect_timeout_ms) }
    pub fn upstream_handshake_timeout(&self) -> Duration { Duration::from_millis(self.upstream_handshake_timeout_ms) }
    pub fn backend_attempt_timeout(&self) -> Duration { Duration::from_millis(self.backend_attempt_timeout_ms) }
    pub fn direct_connect_timeout(&self) -> Duration { Duration::from_millis(self.direct_connect_timeout_ms) }
    pub fn client_handshake_timeout(&self) -> Duration { Duration::from_millis(self.client_handshake_timeout_ms) }
    pub fn probe_timeout(&self) -> Duration { Duration::from_millis(self.probe_timeout_ms) }
    pub fn healthy_probe_interval(&self) -> Duration { Duration::from_secs(self.healthy_probe_interval_secs) }
    pub fn recovery_probe_interval(&self) -> Duration { Duration::from_secs(self.recovery_probe_interval_secs) }
    pub fn runtime_cooldown(&self) -> Duration { Duration::from_millis(self.runtime_cooldown_ms) }
    pub fn shutdown_grace_period(&self) -> Duration { Duration::from_millis(self.shutdown_grace_period_ms) }

    /// Keep D2S route establishment inside dnscrypt-proxy's own query timeout.
    /// A small margin leaves time for DNSCrypt to process the SOCKS result.
    pub fn route_budget(&self) -> Duration {
        const SAFETY_MARGIN_MS: u64 = 500;
        const MIN_ROUTE_BUDGET_MS: u64 = 1_000;
        let budget_ms = self
            .dnscrypt_timeout_ms
            .saturating_sub(SAFETY_MARGIN_MS)
            .max(MIN_ROUTE_BUDGET_MS);
        Duration::from_millis(budget_ms)
    }

    pub fn parsed_probe_targets(&self) -> Result<Vec<TargetAddr>> {
        self.probe_targets
            .iter()
            .map(|target| target.parse::<TargetAddr>().with_context(|| format!("invalid probe target {target}")))
            .collect()
    }
}

fn read_dnscrypt_runtime(path: impl AsRef<Path>) -> Result<DnscryptRuntime> {
    let path = path.as_ref();
    let raw = std::fs::read_to_string(path)
        .with_context(|| format!("read dnscrypt configuration {}", path.display()))?;
    let config: DnscryptConfig = toml::from_str(&raw)
        .with_context(|| format!("parse dnscrypt configuration {}", path.display()))?;
    let proxy = config
        .proxy
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| anyhow!("dnscrypt configuration has no active proxy entry"))?;
    let listen = parse_local_socks5_proxy(proxy)
        .with_context(|| format!("invalid dnscrypt proxy entry in {}", path.display()))?;
    let timeout_ms = config.timeout.unwrap_or_else(default_dnscrypt_timeout_ms);
    if timeout_ms == 0 {
        return Err(anyhow!("dnscrypt timeout must be greater than zero"));
    }
    Ok(DnscryptRuntime { listen, timeout_ms })
}

pub fn read_dnscrypt_proxy_listener(path: impl AsRef<Path>) -> Result<SocketAddr> {
    Ok(read_dnscrypt_runtime(path)?.listen)
}

pub fn parse_local_socks5_proxy(proxy: &str) -> Result<SocketAddr> {
    let value = proxy.trim();
    let endpoint = value
        .strip_prefix("socks5://")
        .ok_or_else(|| anyhow!("proxy must use socks5://"))?;
    if endpoint.is_empty()
        || endpoint.contains('@')
        || endpoint.contains('/')
        || endpoint.contains('?')
        || endpoint.contains('#')
    {
        return Err(anyhow!("proxy must be an unauthenticated SOCKS5 host:port"));
    }

    let addr = if endpoint.eq_ignore_ascii_case("localhost") {
        return Err(anyhow!("proxy is missing a port"));
    } else if let Some(port) = endpoint.strip_prefix("localhost:") {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), parse_port(port)?)
    } else {
        endpoint
            .parse::<SocketAddr>()
            .with_context(|| format!("parse SOCKS5 endpoint {endpoint}"))?
    };

    if addr.port() == 0 {
        return Err(anyhow!("proxy port must be greater than zero"));
    }
    if !matches!(addr.ip(), IpAddr::V4(ip) if ip == Ipv4Addr::LOCALHOST)
        && !matches!(addr.ip(), IpAddr::V6(ip) if ip == Ipv6Addr::LOCALHOST)
    {
        return Err(anyhow!("D2S proxy endpoint must be loopback: {addr}"));
    }
    Ok(addr)
}

fn parse_port(value: &str) -> Result<u16> {
    let port = value.parse::<u16>().with_context(|| format!("parse proxy port {value}"))?;
    if port == 0 {
        return Err(anyhow!("proxy port must be greater than zero"));
    }
    Ok(port)
}

fn validate_log_level(level: &str) -> Result<()> {
    tracing_subscriber::EnvFilter::try_new(level)
        .map(|_| ())
        .map_err(|error| anyhow!("invalid log_level {level}: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_loopback_proxy_endpoints() {
        assert_eq!(
            parse_local_socks5_proxy("socks5://127.0.0.1:11990").unwrap(),
            "127.0.0.1:11990".parse().unwrap()
        );
        assert_eq!(
            parse_local_socks5_proxy("socks5://[::1]:12000").unwrap(),
            "[::1]:12000".parse().unwrap()
        );
        assert_eq!(
            parse_local_socks5_proxy("socks5://localhost:13000").unwrap(),
            "127.0.0.1:13000".parse().unwrap()
        );
    }

    #[test]
    fn rejects_remote_or_authenticated_proxy() {
        assert!(parse_local_socks5_proxy("socks5://10.0.0.1:1080").is_err());
        assert!(parse_local_socks5_proxy("socks5://user:pass@127.0.0.1:1080").is_err());
    }

    #[test]
    fn accepts_legacy_experimental_fields_as_noop_compatibility() {
        let config: Config = toml::from_str(
            r#"
backends = []
direct_fallback = true
route_timeout_ms = 2500
max_backend_attempts = 3
max_connecting = 32
idle_after_secs = 60
"#,
        )
        .unwrap();

        assert_eq!(config.route_timeout_ms, Some(2500));
        assert_eq!(config.max_backend_attempts, Some(3));
        assert_eq!(config.max_connecting, Some(32));
        assert_eq!(config.idle_after_secs, Some(60));
    }

    #[test]
    fn reads_dnscrypt_timeout_with_default_and_explicit_value() {
        let dir = std::env::temp_dir();
        let explicit = dir.join(format!("d2s-dnscrypt-explicit-{}.toml", std::process::id()));
        std::fs::write(
            &explicit,
            "proxy = 'socks5://127.0.0.1:11990'\ntimeout = 7000\n",
        )
        .unwrap();
        let runtime = read_dnscrypt_runtime(&explicit).unwrap();
        assert_eq!(runtime.listen, "127.0.0.1:11990".parse().unwrap());
        assert_eq!(runtime.timeout_ms, 7000);
        let _ = std::fs::remove_file(&explicit);

        let defaulted = dir.join(format!("d2s-dnscrypt-default-{}.toml", std::process::id()));
        std::fs::write(
            &defaulted,
            "proxy = 'socks5://127.0.0.1:11990'\n",
        )
        .unwrap();
        let runtime = read_dnscrypt_runtime(&defaulted).unwrap();
        assert_eq!(runtime.timeout_ms, 5000);
        let _ = std::fs::remove_file(&defaulted);
    }

    #[test]
    fn route_budget_tracks_dnscrypt_timeout_with_margin() {
        let mut config: Config = toml::from_str("backends = []\ndirect_fallback = true\n").unwrap();
        config.dnscrypt_timeout_ms = 7000;
        assert_eq!(config.route_budget(), Duration::from_millis(6500));
        config.dnscrypt_timeout_ms = 1200;
        assert_eq!(config.route_budget(), Duration::from_millis(1000));
    }

}
