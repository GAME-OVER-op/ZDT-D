use crate::api::support::paths::is_safe_segment;
use anyhow::{Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{collections::{BTreeSet, HashMap}, fs, net::TcpStream, path::{Path, PathBuf}, net::SocketAddr};
use super::super::super::{ContentReq, EnabledReq};
use super::shared::{active_json_path, program_root};
use super::super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::super::support::fs::{read_json, read_text, write_json_pretty, write_text_atomic};
use super::super::super::support::paths::{ensure_safe_segment};
use super::super::super::support::model::{EnabledActive};

// Safety guard for dnscrypt setting files (some lists can be enormous and will crash the app/UI if returned whole).
// Per Danil: limit reads/edits to ~200KB.
const DNSCRYPT_SETTING_MAX_BYTES: u64 = 200 * 1024;



fn d2s_default_true() -> bool { true }
fn d2s_default_connect_timeout_ms() -> u64 { 500 }
fn d2s_default_upstream_handshake_timeout_ms() -> u64 { 1_000 }
fn d2s_default_backend_attempt_timeout_ms() -> u64 { 1_200 }
fn d2s_default_direct_connect_timeout_ms() -> u64 { 2_000 }
fn d2s_default_client_handshake_timeout_ms() -> u64 { 3_000 }
fn d2s_default_probe_timeout_ms() -> u64 { 1_200 }
fn d2s_default_healthy_probe_interval_secs() -> u64 { 30 }
fn d2s_default_recovery_probe_interval_secs() -> u64 { 5 }
fn d2s_default_failure_threshold() -> u32 { 3 }
fn d2s_default_runtime_cooldown_ms() -> u64 { 2_000 }
fn d2s_default_probe_targets() -> Vec<String> {
    vec!["1.1.1.1:443".to_string(), "8.8.8.8:443".to_string()]
}
fn d2s_default_max_connections() -> usize { 1_024 }
fn d2s_default_log_level() -> String { "info".to_string() }
fn d2s_default_status_interval_secs() -> u64 { 5 }
fn d2s_status_interval_is_default(value: &u64) -> bool { *value == d2s_default_status_interval_secs() }
fn d2s_default_shutdown_grace_period_ms() -> u64 { 5_000 }

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
struct D2sFileConfig {
    backends: Vec<String>,
    direct_fallback: bool,
    connect_timeout_ms: u64,
    upstream_handshake_timeout_ms: u64,
    backend_attempt_timeout_ms: u64,
    direct_connect_timeout_ms: u64,
    client_handshake_timeout_ms: u64,
    probe_timeout_ms: u64,
    healthy_probe_interval_secs: u64,
    recovery_probe_interval_secs: u64,
    failure_threshold: u32,
    runtime_cooldown_ms: u64,
    probe_targets: Vec<String>,
    max_connections: usize,
    tcp_nodelay: bool,
    log_level: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    status_file: Option<PathBuf>,
    #[serde(skip_serializing_if = "d2s_status_interval_is_default")]
    status_interval_secs: u64,
    shutdown_grace_period_ms: u64,
    // Compatibility-only fields from experimental D2S builds. They are not
    // exposed by the API and the stable D2S transport ignores them.
    #[serde(skip_serializing_if = "Option::is_none")]
    idle_after_secs: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    route_timeout_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_backend_attempts: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    max_connecting: Option<usize>,
}

impl Default for D2sFileConfig {
    fn default() -> Self {
        Self {
            backends: Vec::new(),
            direct_fallback: d2s_default_true(),
            connect_timeout_ms: d2s_default_connect_timeout_ms(),
            upstream_handshake_timeout_ms: d2s_default_upstream_handshake_timeout_ms(),
            backend_attempt_timeout_ms: d2s_default_backend_attempt_timeout_ms(),
            direct_connect_timeout_ms: d2s_default_direct_connect_timeout_ms(),
            client_handshake_timeout_ms: d2s_default_client_handshake_timeout_ms(),
            probe_timeout_ms: d2s_default_probe_timeout_ms(),
            healthy_probe_interval_secs: d2s_default_healthy_probe_interval_secs(),
            recovery_probe_interval_secs: d2s_default_recovery_probe_interval_secs(),
            failure_threshold: d2s_default_failure_threshold(),
            runtime_cooldown_ms: d2s_default_runtime_cooldown_ms(),
            probe_targets: d2s_default_probe_targets(),
            max_connections: d2s_default_max_connections(),
            tcp_nodelay: d2s_default_true(),
            log_level: d2s_default_log_level(),
            status_file: None,
            status_interval_secs: d2s_default_status_interval_secs(),
            shutdown_grace_period_ms: d2s_default_shutdown_grace_period_ms(),
            idle_after_secs: None,
            route_timeout_ms: None,
            max_backend_attempts: None,
            max_connecting: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct D2sConfigReq {
    backends: Vec<String>,
    direct_fallback: bool,
    connect_timeout_ms: u64,
    upstream_handshake_timeout_ms: u64,
    backend_attempt_timeout_ms: u64,
    direct_connect_timeout_ms: u64,
    client_handshake_timeout_ms: u64,
    probe_timeout_ms: u64,
    healthy_probe_interval_secs: u64,
    recovery_probe_interval_secs: u64,
    failure_threshold: u32,
    runtime_cooldown_ms: u64,
    probe_targets: Vec<String>,
    max_connections: usize,
    tcp_nodelay: bool,
    log_level: String,
    shutdown_grace_period_ms: u64,
}

impl From<&D2sFileConfig> for D2sConfigReq {
    fn from(value: &D2sFileConfig) -> Self {
        Self {
            backends: value.backends.clone(),
            direct_fallback: value.direct_fallback,
            connect_timeout_ms: value.connect_timeout_ms,
            upstream_handshake_timeout_ms: value.upstream_handshake_timeout_ms,
            backend_attempt_timeout_ms: value.backend_attempt_timeout_ms,
            direct_connect_timeout_ms: value.direct_connect_timeout_ms,
            client_handshake_timeout_ms: value.client_handshake_timeout_ms,
            probe_timeout_ms: value.probe_timeout_ms,
            healthy_probe_interval_secs: value.healthy_probe_interval_secs,
            recovery_probe_interval_secs: value.recovery_probe_interval_secs,
            failure_threshold: value.failure_threshold,
            runtime_cooldown_ms: value.runtime_cooldown_ms,
            probe_targets: value.probe_targets.clone(),
            max_connections: value.max_connections,
            tcp_nodelay: value.tcp_nodelay,
            log_level: value.log_level.clone(),
            shutdown_grace_period_ms: value.shutdown_grace_period_ms,
        }
    }
}

impl D2sFileConfig {
    fn apply_request(&mut self, req: D2sConfigReq) {
        self.backends = req.backends;
        self.direct_fallback = req.direct_fallback;
        self.connect_timeout_ms = req.connect_timeout_ms;
        self.upstream_handshake_timeout_ms = req.upstream_handshake_timeout_ms;
        self.backend_attempt_timeout_ms = req.backend_attempt_timeout_ms;
        self.direct_connect_timeout_ms = req.direct_connect_timeout_ms;
        self.client_handshake_timeout_ms = req.client_handshake_timeout_ms;
        self.probe_timeout_ms = req.probe_timeout_ms;
        self.healthy_probe_interval_secs = req.healthy_probe_interval_secs;
        self.recovery_probe_interval_secs = req.recovery_probe_interval_secs;
        self.failure_threshold = req.failure_threshold;
        self.runtime_cooldown_ms = req.runtime_cooldown_ms;
        self.probe_targets = req.probe_targets;
        self.max_connections = req.max_connections;
        self.tcp_nodelay = req.tcp_nodelay;
        self.log_level = req.log_level;
        self.shutdown_grace_period_ms = req.shutdown_grace_period_ms;
    }
}

fn read_d2s_file_config(path: &Path) -> Result<D2sFileConfig> {
    let raw = read_text(path)?;
    toml::from_str(&raw).map_err(|e| anyhow::anyhow!("bad D2S TOML {}: {e}", path.display()))
}

fn write_d2s_file_config(path: &Path, config: &D2sFileConfig) -> Result<()> {
    let raw = toml::to_string_pretty(config)
        .map_err(|e| anyhow::anyhow!("serialize D2S TOML {}: {e}", path.display()))?;
    write_text_atomic(path, &raw)
}

fn validate_d2s_host_port(value: &str, field: &str) -> Result<()> {
    let value = value.trim();
    if value.is_empty() || value.chars().any(char::is_whitespace) {
        anyhow::bail!("{field} must be HOST:PORT");
    }
    if let Ok(addr) = value.parse::<SocketAddr>() {
        if addr.port() == 0 {
            anyhow::bail!("{field} port must be greater than zero");
        }
        return Ok(());
    }
    let (host, port) = value
        .rsplit_once(':')
        .ok_or_else(|| anyhow::anyhow!("{field} must be HOST:PORT"))?;
    if host.is_empty() || host.contains('/') || host.contains('@') {
        anyhow::bail!("{field} must be HOST:PORT");
    }
    let port = port.parse::<u16>()
        .map_err(|_| anyhow::anyhow!("{field} has an invalid port"))?;
    if port == 0 {
        anyhow::bail!("{field} port must be greater than zero");
    }
    Ok(())
}

fn validate_d2s_file_config(config: &D2sFileConfig, listener: Option<SocketAddr>) -> Result<()> {
    if config.backends.is_empty() && !config.direct_fallback {
        anyhow::bail!("direct_fallback must be enabled when no SOCKS5 backends are configured");
    }

    let mut seen_backends = BTreeSet::new();
    for backend in &config.backends {
        let addr: SocketAddr = backend.trim().parse()
            .map_err(|_| anyhow::anyhow!("invalid D2S backend: {backend}"))?;
        if !addr.ip().is_loopback() || addr.port() == 0 {
            anyhow::bail!("D2S backends must use a loopback HOST:PORT: {backend}");
        }
        if Some(addr) == listener {
            anyhow::bail!("D2S backend points to its listener: {backend}");
        }
        if !seen_backends.insert(addr) {
            anyhow::bail!("duplicate D2S backend: {backend}");
        }
    }

    for (name, value) in [
        ("connect_timeout_ms", config.connect_timeout_ms),
        ("upstream_handshake_timeout_ms", config.upstream_handshake_timeout_ms),
        ("backend_attempt_timeout_ms", config.backend_attempt_timeout_ms),
        ("direct_connect_timeout_ms", config.direct_connect_timeout_ms),
        ("client_handshake_timeout_ms", config.client_handshake_timeout_ms),
        ("probe_timeout_ms", config.probe_timeout_ms),
        ("healthy_probe_interval_secs", config.healthy_probe_interval_secs),
        ("recovery_probe_interval_secs", config.recovery_probe_interval_secs),
        ("runtime_cooldown_ms", config.runtime_cooldown_ms),
        ("shutdown_grace_period_ms", config.shutdown_grace_period_ms),
    ] {
        if value == 0 {
            anyhow::bail!("{name} must be greater than zero");
        }
    }
    if config.failure_threshold == 0 {
        anyhow::bail!("failure_threshold must be greater than zero");
    }
    if config.max_connections == 0 {
        anyhow::bail!("max_connections must be greater than zero");
    }
    if config.status_interval_secs == 0 {
        anyhow::bail!("status_interval_secs must be greater than zero");
    }
    if !config.backends.is_empty() && config.probe_targets.is_empty() {
        anyhow::bail!("probe_targets must not be empty when SOCKS5 backends are configured");
    }
    for target in &config.probe_targets {
        validate_d2s_host_port(target, "probe target")?;
    }
    let level = config.log_level.trim();
    if level.is_empty() || level.len() > 128 || level.chars().any(char::is_control) {
        anyhow::bail!("log_level must be a non-empty logging filter");
    }
    Ok(())
}



pub(in crate::api) fn handle(
    stream: TcpStream,
    method: &str,
    seg: &[&str],
    _headers: &HashMap<String, String>,
    body: &[u8],
    _services_running: bool,
) -> Result<()> {
    match (method, seg) {
        // --- dnscrypt enabled/config
        ("GET", ["api", "programs", "dnscrypt", "enabled"]) => {
            let p = active_json_path("dnscrypt");
            let active: EnabledActive = read_json(&p).unwrap_or_default();
            write_json(stream, 200, json!({"ok": true, "enabled": active.enabled}))
        }
        ("PUT", ["api", "programs", "dnscrypt", "enabled"]) => {
            let res = (|| -> Result<()> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = active_json_path("dnscrypt");
                let mut active: EnabledActive = read_json(&p).unwrap_or_default();
                active.enabled = req.enabled;
                write_json_pretty(&p, &active)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "programs", "dnscrypt", "config"]) => {
            let p = program_root("dnscrypt").join("setting/dnscrypt-proxy.toml");
            let res = read_text(&p);
            match res {
                Ok(content) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "dnscrypt", "config"]) => {
            let res = (|| -> Result<()> {
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let p = program_root("dnscrypt").join("setting/dnscrypt-proxy.toml");
                write_text_atomic(&p, &req.content)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        ("PUT", ["api", "programs", "dnscrypt", "d2s-connect"]) => {
            let res = crate::programs::dnscrypt::connect_d2s_proxy();
            match res {
                Ok(listener) => write_json(
                    stream,
                    200,
                    json!({"ok": true, "listener": listener.to_string()}),
                ),
                Err(e) => write_err(stream, e),
            }
        }

        ("GET", ["api", "programs", "dnscrypt", "d2s-config"]) => {
            let p = program_root("dnscrypt").join("d2set/d2s.toml");
            let res = (|| -> Result<serde_json::Value> {
                crate::programs::dnscrypt::ensure_d2s_config_exists()?;
                let config = read_d2s_file_config(&p)?;
                // For the UI, an unsupported/external proxy simply means
                // D2S is not connected. Runtime startup keeps the stricter
                // parser and still logs malformed local D2S endpoints.
                let listener = crate::programs::dnscrypt::configured_d2s_listen_addr()
                    .ok()
                    .flatten()
                    .map(|addr| addr.to_string());
                let visible = D2sConfigReq::from(&config);
                let mut value = serde_json::to_value(visible)?;
                let object = value.as_object_mut()
                    .ok_or_else(|| anyhow::anyhow!("serialize D2S API response"))?;
                object.insert("ok".to_string(), json!(true));
                object.insert("listener".to_string(), json!(listener));
                Ok(value)
            })();
            match res {
                Ok(value) => write_json(stream, 200, value),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "dnscrypt", "d2s-config"]) => {
            let res = (|| -> Result<()> {
                let req: D2sConfigReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad D2S JSON body: {e}"))?;
                let p = program_root("dnscrypt").join("d2set/d2s.toml");
                crate::programs::dnscrypt::ensure_d2s_config_exists()?;
                let mut config = read_d2s_file_config(&p)?;
                config.apply_request(req);
                let listener = crate::programs::dnscrypt::configured_d2s_listen_addr()?;
                validate_d2s_file_config(&config, listener)?;
                write_d2s_file_config(&p, &config)?;
                Ok(())
            })();
            match res {
                Ok(_) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }

        // --- dnscrypt extra setting files (read/write only; no create/delete)
        ("GET", ["api", "programs", "dnscrypt", "setting-files"]) => {
            let dir = program_root("dnscrypt").join("setting");
            let res = (|| -> Result<(Vec<String>, serde_json::Map<String, serde_json::Value>, serde_json::Map<String, serde_json::Value>)> {
                let mut out = Vec::new();
                let mut sizes = serde_json::Map::new();
                let mut editable = serde_json::Map::new();
                for ent in fs::read_dir(&dir)? {
                    let ent = ent?;
                    let ft = ent.file_type()?;
                    if !ft.is_file() {
                        continue;
                    }
                    let name = ent.file_name().to_string_lossy().to_string();
                    // Expose only files that already exist; main toml is handled via /config.
                    if name == "dnscrypt-proxy.toml" {
                        continue;
                    }
                    // Protect against weird names even though read_dir returns local FS entries.
                    if name == "." || name == ".." || !is_safe_segment(&name) {
                        continue;
                    }
                    let sz = fs::metadata(ent.path())?.len();
                    sizes.insert(name.clone(), json!(sz));
                    editable.insert(name.clone(), json!(sz <= DNSCRYPT_SETTING_MAX_BYTES));
                    out.push(name);
                }
                out.sort();
                Ok((out, sizes, editable))
            })();
            match res {
                Ok((files, sizes, editable)) => write_json(
                    stream,
                    200,
                    json!({"ok": true, "files": files, "sizes": sizes, "editable": editable, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Err(e) => write_err(stream, e),
            }
        }

("GET", ["api", "programs", "dnscrypt", "setting-files", fname]) => {
            let res = (|| -> Result<(Option<String>, Option<u64>)> {
                ensure_safe_segment(fname, "filename")?;
                if *fname == "." || *fname == ".." {
                    anyhow::bail!("invalid filename");
                }
                let p = program_root("dnscrypt").join("setting").join(fname);
                // Do not allow creating files via GET; must already exist.
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                let sz = fs::metadata(&p)?.len();
                if sz > DNSCRYPT_SETTING_MAX_BYTES {
                    return Ok((None, Some(sz)));
                }
                Ok((Some(read_text(&p)?), None))
            })();
            match res {
                Ok((Some(content), _)) => write_json(stream, 200, json!({"ok": true, "content": content})),
                Ok((None, Some(sz))) => write_json(
                    stream,
                    200,
                    json!({"ok": false, "error": "too_large", "size": sz, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Ok((None, None)) => write_json(stream, 200, json!({"ok": false, "error": "unknown"})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "programs", "dnscrypt", "setting-files", fname]) => {
            let res = (|| -> Result<Option<usize>> {
                ensure_safe_segment(fname, "filename")?;
                if *fname == "." || *fname == ".." {
                    anyhow::bail!("invalid filename");
                }
                let p = program_root("dnscrypt").join("setting").join(fname);
                // Do not allow creating new files: only update existing ones.
                if !p.is_file() {
                    anyhow::bail!("file not found");
                }
                let req: ContentReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;

                let bytes = req.content.as_bytes().len();
                if bytes as u64 > DNSCRYPT_SETTING_MAX_BYTES {
                    return Ok(Some(bytes));
                }
                write_text_atomic(&p, &req.content)?;
                Ok(None)
            })();
            match res {
                Ok(None) => write_ok(stream),
                Ok(Some(bytes)) => write_json(
                    stream,
                    200,
                    json!({"ok": false, "error": "too_large", "size": bytes, "limit": DNSCRYPT_SETTING_MAX_BYTES}),
                ),
                Err(e) => write_err(stream, e),
            }
        }

        _ => write_empty_404(stream),
    }
}
