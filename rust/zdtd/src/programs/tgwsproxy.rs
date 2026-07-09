use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    fs::{self, OpenOptions},
    io::Read,
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream, UdpSocket},
    os::unix::process::CommandExt,
    path::Path,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

pub const TGWS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tg-ws-proxy";
pub const TGWS_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy";
pub const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/active.json";
pub const SETTING_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/setting.json";
pub const GENERATED_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/generated";
pub const LOG_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/log";
pub const LOG_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/tgwsproxy/log/tg-ws-proxy.log";

const PORT_WAIT: Duration = Duration::from_secs(8);

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct EnabledJson {
    #[serde(default)]
    pub enabled: bool,
}

fn default_port() -> u16 { 1443 }
fn default_host_mode() -> String { "local".to_string() }
fn default_host() -> String { "127.0.0.1".to_string() }
fn default_buf_kb() -> u32 { 256 }
fn default_pool_size() -> u32 { 4 }
fn default_ws_connect_timeout() -> u64 { 10 }
fn default_ws_fail_probe_timeout() -> u64 { 2 }
fn default_ws_fail_cooldown() -> u64 { 30 }
fn default_ws_redirect_cooldown() -> u64 { 300 }
fn default_handshake_timeout() -> u64 { 10 }
fn default_tcp_fallback_timeout() -> u64 { 10 }
fn default_upstream_connect_timeout() -> u64 { 5 }
fn default_upstream_fail_cooldown() -> u64 { 60 }
fn default_cf_connect_timeout() -> u64 { 10 }
fn default_cf_fail_cooldown() -> u64 { 60 }
fn default_fronting_cooldown() -> u64 { 1800 }
fn default_pool_max_age() -> u64 { 55 }

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Setting {
    #[serde(default = "default_port")]
    pub port: u16,
    #[serde(default = "default_host_mode")]
    pub host_mode: String,
    #[serde(default = "default_host")]
    pub host: String,
    #[serde(default)]
    pub link_ip: String,

    #[serde(default)]
    pub secrets: Vec<String>,
    #[serde(default)]
    pub listen_faketls_enabled: bool,
    #[serde(default)]
    pub listen_faketls_domain: String,

    #[serde(default)]
    pub dc_ip: Vec<String>,

    #[serde(default = "default_buf_kb")]
    pub buf_kb: u32,
    #[serde(default = "default_pool_size")]
    pub pool_size: u32,
    #[serde(default)]
    pub max_connections: u32,

    #[serde(default)]
    pub verbose: bool,
    #[serde(default)]
    pub quiet: bool,
    #[serde(default)]
    pub log_file: String,

    #[serde(default)]
    pub mtproto_proxies: Vec<String>,

    #[serde(default)]
    pub cf_domains: Vec<String>,
    #[serde(default)]
    pub cf_worker_domains: Vec<String>,
    #[serde(default)]
    pub cf_priority: bool,
    #[serde(default)]
    pub cf_balance: bool,
    #[serde(default)]
    pub default_domains: bool,

    #[serde(default)]
    pub fronting_domain: String,
    #[serde(default = "default_fronting_cooldown")]
    pub fronting_cooldown: u64,

    #[serde(default = "default_ws_connect_timeout")]
    pub ws_connect_timeout: u64,
    #[serde(default = "default_ws_fail_probe_timeout")]
    pub ws_fail_probe_timeout: u64,
    #[serde(default = "default_ws_fail_cooldown")]
    pub ws_fail_cooldown: u64,
    #[serde(default = "default_ws_redirect_cooldown")]
    pub ws_redirect_cooldown: u64,
    #[serde(default = "default_handshake_timeout")]
    pub handshake_timeout: u64,
    #[serde(default = "default_tcp_fallback_timeout")]
    pub tcp_fallback_timeout: u64,
    #[serde(default = "default_upstream_connect_timeout")]
    pub upstream_connect_timeout: u64,
    #[serde(default = "default_upstream_fail_cooldown")]
    pub upstream_fail_cooldown: u64,
    #[serde(default = "default_cf_connect_timeout")]
    pub cf_connect_timeout: u64,
    #[serde(default = "default_cf_fail_cooldown")]
    pub cf_fail_cooldown: u64,
    #[serde(default = "default_pool_max_age")]
    pub pool_max_age: u64,

    #[serde(default)]
    pub outbound_proxy: String,
    #[serde(default)]
    pub no_outbound_proxy: bool,
    #[serde(default)]
    pub no_proxy: String,

    #[serde(default)]
    pub skip_tls_verify: bool,
}

impl Default for Setting {
    fn default() -> Self {
        Self {
            port: default_port(),
            host_mode: default_host_mode(),
            host: default_host(),
            link_ip: String::new(),
            secrets: Vec::new(),
            listen_faketls_enabled: false,
            listen_faketls_domain: String::new(),
            dc_ip: Vec::new(),
            buf_kb: default_buf_kb(),
            pool_size: default_pool_size(),
            max_connections: 0,
            verbose: false,
            quiet: false,
            log_file: String::new(),
            mtproto_proxies: Vec::new(),
            cf_domains: Vec::new(),
            cf_worker_domains: Vec::new(),
            cf_priority: false,
            cf_balance: false,
            default_domains: false,
            fronting_domain: String::new(),
            fronting_cooldown: default_fronting_cooldown(),
            ws_connect_timeout: default_ws_connect_timeout(),
            ws_fail_probe_timeout: default_ws_fail_probe_timeout(),
            ws_fail_cooldown: default_ws_fail_cooldown(),
            ws_redirect_cooldown: default_ws_redirect_cooldown(),
            handshake_timeout: default_handshake_timeout(),
            tcp_fallback_timeout: default_tcp_fallback_timeout(),
            upstream_connect_timeout: default_upstream_connect_timeout(),
            upstream_fail_cooldown: default_upstream_fail_cooldown(),
            cf_connect_timeout: default_cf_connect_timeout(),
            cf_fail_cooldown: default_cf_fail_cooldown(),
            pool_max_age: default_pool_max_age(),
            outbound_proxy: String::new(),
            no_outbound_proxy: false,
            no_proxy: String::new(),
            skip_tls_verify: false,
        }
    }
}

pub fn ensure_layout() -> Result<()> {
    fs::create_dir_all(TGWS_ROOT).with_context(|| format!("mkdir {TGWS_ROOT}"))?;
    fs::create_dir_all(GENERATED_DIR).with_context(|| format!("mkdir {GENERATED_DIR}"))?;
    fs::create_dir_all(LOG_DIR).with_context(|| format!("mkdir {LOG_DIR}"))?;

    if !Path::new(ACTIVE_JSON).exists() {
        write_json_pretty(Path::new(ACTIVE_JSON), &EnabledJson::default())?;
    }
    if !Path::new(SETTING_JSON).exists() {
        let mut setting = Setting::default();
        setting.secrets.push(generate_secret_hex().unwrap_or_else(|_| "0123456789abcdef0123456789abcdef".to_string()));
        write_json_pretty(Path::new(SETTING_JSON), &setting)?;
    }
    if !Path::new(LOG_FILE).exists() {
        OpenOptions::new().create(true).append(true).open(LOG_FILE).ok();
    }
    Ok(())
}

pub fn is_installed() -> bool {
    Path::new(TGWS_BIN).is_file()
}

pub fn load_enabled() -> Result<EnabledJson> {
    ensure_layout()?;
    read_json(Path::new(ACTIVE_JSON)).or_else(|_| Ok(EnabledJson::default()))
}

pub fn save_enabled(enabled: bool) -> Result<()> {
    ensure_layout()?;
    write_json_pretty(Path::new(ACTIVE_JSON), &EnabledJson { enabled })
}

pub fn read_setting() -> Result<Setting> {
    ensure_layout()?;
    let mut setting: Setting = read_json(Path::new(SETTING_JSON)).unwrap_or_default();
    normalize_setting(&mut setting);
    if setting.secrets.is_empty() {
        setting.secrets.push(generate_secret_hex().unwrap_or_else(|_| "0123456789abcdef0123456789abcdef".to_string()));
        write_setting(&setting)?;
    }
    Ok(setting)
}

pub fn write_setting(setting: &Setting) -> Result<()> {
    ensure_layout()?;
    let mut next = setting.clone();
    normalize_setting(&mut next);
    validate_setting(&next)?;
    write_json_pretty(Path::new(SETTING_JSON), &next)?;
    write_generated_files(&next).ok();
    Ok(())
}

fn normalize_setting(setting: &mut Setting) {
    setting.host_mode = match setting.host_mode.trim().to_ascii_lowercase().as_str() {
        "lan" | "network" | "open" => "lan".to_string(),
        _ => "local".to_string(),
    };
    setting.host = effective_host(setting).to_string();
    setting.secrets = clean_list(&setting.secrets);
    setting.dc_ip = clean_list(&setting.dc_ip);
    setting.mtproto_proxies = clean_list(&setting.mtproto_proxies);
    setting.cf_domains = clean_list(&setting.cf_domains);
    setting.cf_worker_domains = clean_list(&setting.cf_worker_domains);
    if setting.quiet {
        setting.verbose = false;
    }
}

pub fn effective_host(setting: &Setting) -> &'static str {
    if setting.host_mode.trim().eq_ignore_ascii_case("lan") {
        "0.0.0.0"
    } else {
        "127.0.0.1"
    }
}

pub fn is_local_protected_mode(setting: &Setting) -> bool {
    !setting.host_mode.trim().eq_ignore_ascii_case("lan")
}

pub fn protected_local_port() -> Option<u16> {
    let enabled = load_enabled().ok()?.enabled;
    if !enabled { return None; }
    let setting = read_setting().ok()?;
    if is_local_protected_mode(&setting) && setting.port > 0 { Some(setting.port) } else { None }
}

fn clean_list(items: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    for item in items {
        for part in item.split(',') {
            let s = part.trim();
            if !s.is_empty() && !out.iter().any(|x: &String| x == s) {
                out.push(s.to_string());
            }
        }
    }
    out
}

fn validate_setting(setting: &Setting) -> Result<()> {
    if setting.port == 0 {
        anyhow::bail!("port must be 1..65535");
    }
    if setting.buf_kb == 0 {
        anyhow::bail!("buf_kb must be greater than 0");
    }
    if setting.pool_size == 0 {
        anyhow::bail!("pool_size must be greater than 0");
    }
    for secret in &setting.secrets {
        validate_hex_secret(secret)?;
    }
    if setting.listen_faketls_enabled && setting.listen_faketls_domain.trim().is_empty() {
        anyhow::bail!("FakeTLS domain is required when FakeTLS is enabled");
    }
    for item in &setting.dc_ip {
        validate_dc_ip(item)?;
    }
    for item in &setting.mtproto_proxies {
        validate_mtproto_proxy(item)?;
    }
    Ok(())
}

fn cli_secret_key(secret: &str) -> Result<String> {
    let s = secret.trim();
    let without_dd = s.strip_prefix("dd").or_else(|| s.strip_prefix("DD")).unwrap_or(s);
    let key = if let Some(rest) = without_dd.strip_prefix("ee").or_else(|| without_dd.strip_prefix("EE")) {
        if rest.len() < 32 { anyhow::bail!("FakeTLS secret is too short"); }
        &rest[..32]
    } else {
        without_dd
    };
    if key.len() != 32 || !key.chars().all(|c| c.is_ascii_hexdigit()) {
        anyhow::bail!("secret must be 32 hex chars");
    }
    Ok(key.to_ascii_lowercase())
}

fn validate_hex_secret(secret: &str) -> Result<()> {
    cli_secret_key(secret).map(|_| ())
}

fn validate_dc_ip(item: &str) -> Result<()> {
    let (dc, ip) = item.split_once(':').ok_or_else(|| anyhow::anyhow!("dc_ip must be DC:IP"))?;
    let dc_num: u16 = dc.trim().parse().map_err(|_| anyhow::anyhow!("invalid DC number in {item}"))?;
    if dc_num == 0 { anyhow::bail!("invalid DC number in {item}"); }
    ip.trim().parse::<IpAddr>().map_err(|_| anyhow::anyhow!("invalid IP in {item}"))?;
    Ok(())
}

fn validate_mtproto_proxy(item: &str) -> Result<()> {
    let parts: Vec<&str> = item.rsplitn(3, ':').collect();
    if parts.len() != 3 {
        anyhow::bail!("mtproto proxy must be HOST:PORT:SECRET");
    }
    validate_hex_secret(parts[0])?;
    let port: u16 = parts[1].parse().map_err(|_| anyhow::anyhow!("invalid upstream MTProto proxy port"))?;
    if port == 0 { anyhow::bail!("invalid upstream MTProto proxy port"); }
    if parts[2].trim().is_empty() { anyhow::bail!("upstream MTProto proxy host is required"); }
    Ok(())
}

fn push_opt(args: &mut Vec<String>, key: &str, value: impl ToString) {
    args.push(key.to_string());
    args.push(value.to_string());
}

pub fn build_args(setting: &Setting) -> Vec<String> {
    let mut args = Vec::new();
    push_opt(&mut args, "--port", setting.port);
    push_opt(&mut args, "--host", effective_host(setting));

    for secret in clean_list(&setting.secrets) {
        if let Ok(key) = cli_secret_key(&secret) {
            push_opt(&mut args, "--secret", key);
        }
    }
    if setting.listen_faketls_enabled && !setting.listen_faketls_domain.trim().is_empty() {
        push_opt(&mut args, "--listen-faketls-domain", setting.listen_faketls_domain.trim());
    }
    for item in clean_list(&setting.dc_ip) { push_opt(&mut args, "--dc-ip", item); }

    if setting.buf_kb != default_buf_kb() { push_opt(&mut args, "--buf-kb", setting.buf_kb); }
    if setting.pool_size != default_pool_size() { push_opt(&mut args, "--pool-size", setting.pool_size); }
    if setting.max_connections > 0 { push_opt(&mut args, "--max-connections", setting.max_connections); }

    if setting.verbose && !setting.quiet { args.push("--verbose".to_string()); }
    if setting.skip_tls_verify { args.push("--danger-accept-invalid-certs".to_string()); }
    if setting.quiet { args.push("--quiet".to_string()); }

    if !setting.quiet {
        let log_file = setting.log_file.trim();
        push_opt(&mut args, "--log-file", if log_file.is_empty() { LOG_FILE } else { log_file });
    }

    for item in clean_list(&setting.mtproto_proxies) { push_opt(&mut args, "--mtproto-proxy", item); }

    if !setting.link_ip.trim().is_empty() { push_opt(&mut args, "--link-ip", setting.link_ip.trim()); }
    for item in clean_list(&setting.cf_domains) { push_opt(&mut args, "--cf-domain", item); }
    for item in clean_list(&setting.cf_worker_domains) { push_opt(&mut args, "--cf-worker-domain", item); }
    if setting.cf_priority { args.push("--cf-priority".to_string()); }
    if setting.cf_balance { args.push("--cf-balance".to_string()); }

    if setting.ws_connect_timeout != default_ws_connect_timeout() { push_opt(&mut args, "--ws-connect-timeout", setting.ws_connect_timeout); }
    if setting.ws_fail_probe_timeout != default_ws_fail_probe_timeout() { push_opt(&mut args, "--ws-fail-probe-timeout", setting.ws_fail_probe_timeout); }
    if setting.ws_fail_cooldown != default_ws_fail_cooldown() { push_opt(&mut args, "--ws-fail-cooldown", setting.ws_fail_cooldown); }
    if setting.ws_redirect_cooldown != default_ws_redirect_cooldown() { push_opt(&mut args, "--ws-redirect-cooldown", setting.ws_redirect_cooldown); }
    if setting.handshake_timeout != default_handshake_timeout() { push_opt(&mut args, "--handshake-timeout", setting.handshake_timeout); }
    if setting.tcp_fallback_timeout != default_tcp_fallback_timeout() { push_opt(&mut args, "--tcp-fallback-timeout", setting.tcp_fallback_timeout); }
    if setting.upstream_connect_timeout != default_upstream_connect_timeout() { push_opt(&mut args, "--upstream-connect-timeout", setting.upstream_connect_timeout); }
    if setting.upstream_fail_cooldown != default_upstream_fail_cooldown() { push_opt(&mut args, "--upstream-fail-cooldown", setting.upstream_fail_cooldown); }
    if setting.cf_connect_timeout != default_cf_connect_timeout() { push_opt(&mut args, "--cf-connect-timeout", setting.cf_connect_timeout); }
    if setting.cf_fail_cooldown != default_cf_fail_cooldown() { push_opt(&mut args, "--cf-fail-cooldown", setting.cf_fail_cooldown); }

    if !setting.fronting_domain.trim().is_empty() { push_opt(&mut args, "--fronting-domain", setting.fronting_domain.trim()); }
    if setting.fronting_cooldown != default_fronting_cooldown() { push_opt(&mut args, "--fronting-cooldown", setting.fronting_cooldown); }
    if setting.pool_max_age != default_pool_max_age() { push_opt(&mut args, "--pool-max-age", setting.pool_max_age); }
    if setting.default_domains { args.push("--default-domains".to_string()); }

    if !setting.outbound_proxy.trim().is_empty() { push_opt(&mut args, "--outbound-proxy", setting.outbound_proxy.trim()); }
    if setting.no_outbound_proxy { args.push("--no-outbound-proxy".to_string()); }
    if !setting.no_proxy.trim().is_empty() { push_opt(&mut args, "--no-proxy", setting.no_proxy.trim()); }

    args
}

pub fn generated_command_line(setting: &Setting) -> String {
    let mut parts = Vec::new();
    parts.push(shell_quote_for_sh(TGWS_BIN));
    for arg in build_args(setting) {
        parts.push(shell_quote_for_sh(&arg));
    }
    parts.join(" ")
}

pub fn command_preview_json() -> Result<serde_json::Value> {
    let setting = read_setting()?;
    let args = build_args(&setting);
    let command_line = generated_command_line(&setting);
    let local_link = proxy_link_for_server(&setting, "127.0.0.1");
    let lan_ip = detect_lan_ip().unwrap_or_default();
    let lan_link = if lan_ip.is_empty() { String::new() } else { proxy_link_for_server(&setting, &lan_ip) };
    write_generated_files(&setting).ok();
    Ok(json!({
        "ok": true,
        "command_line": command_line,
        "args": args,
        "log_file": if setting.log_file.trim().is_empty() { LOG_FILE } else { setting.log_file.trim() },
        "local_link": local_link,
        "lan_ip": lan_ip,
        "lan_link": lan_link,
        "protected_by_proxyinfo": is_local_protected_mode(&setting),
    }))
}

fn write_generated_files(setting: &Setting) -> Result<()> {
    fs::create_dir_all(GENERATED_DIR).ok();
    fs::write(Path::new(GENERATED_DIR).join("command.txt"), generated_command_line(setting))?;
    fs::write(Path::new(GENERATED_DIR).join("link-local.txt"), proxy_link_for_server(setting, "127.0.0.1"))?;
    if let Some(ip) = detect_lan_ip() {
        fs::write(Path::new(GENERATED_DIR).join("link-lan.txt"), proxy_link_for_server(setting, &ip))?;
    }
    Ok(())
}

fn proxy_link_for_server(setting: &Setting, server: &str) -> String {
    let secret = link_secret(setting);
    format!("tg://proxy?server={}&port={}&secret={}", server, setting.port, secret)
}

fn link_secret(setting: &Setting) -> String {
    let first = setting.secrets.first().map(|s| s.trim()).filter(|s| !s.is_empty()).unwrap_or("0123456789abcdef0123456789abcdef");
    let key = first
        .strip_prefix("dd").or_else(|| first.strip_prefix("DD"))
        .unwrap_or(first);
    let key = key.strip_prefix("ee").or_else(|| key.strip_prefix("EE")).map(|rest| &rest[..rest.len().min(32)]).unwrap_or(key);
    if setting.listen_faketls_enabled && !setting.listen_faketls_domain.trim().is_empty() {
        format!("ee{}{}", key, hex::encode(setting.listen_faketls_domain.trim().as_bytes()))
    } else if first.starts_with("dd") || first.starts_with("DD") || first.starts_with("ee") || first.starts_with("EE") {
        first.to_string()
    } else {
        format!("dd{}", key)
    }
}

fn detect_lan_ip() -> Option<String> {
    let sock = UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("8.8.8.8:80").ok()?;
    match sock.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_unspecified() => Some(ip.to_string()),
        _ => None,
    }
}

fn generate_secret_hex() -> Result<String> {
    let mut bytes = [0u8; 16];
    let mut f = fs::File::open("/dev/urandom").context("open /dev/urandom")?;
    f.read_exact(&mut bytes).context("read /dev/urandom")?;
    Ok(hex::encode(bytes))
}

fn shell_quote_for_sh(s: &str) -> String {
    if s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '_' | '-' | ':' | '=' | ',')) {
        s.to_string()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}

pub fn start_if_enabled() -> Result<()> {
    ensure_layout()?;
    if !load_enabled()?.enabled {
        return Ok(());
    }
    if !is_installed() {
        log::warn!("tgwsproxy: binary is missing: {TGWS_BIN}");
        crate::logging::user_warn("Telegram WS Proxy: бинарник не установлен");
        return Ok(());
    }
    let setting = read_setting()?;
    validate_setting(&setting)?;
    let args = build_args(&setting);
    fs::create_dir_all(LOG_DIR).ok();
    let logf = OpenOptions::new().create(true).append(true).open(LOG_FILE)?;
    let logf_err = logf.try_clone()?;

    let mut cmd = Command::new(TGWS_BIN);
    cmd.args(&args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd.spawn().with_context(|| format!("spawn {TGWS_BIN}"))?;
    log::info!("tgwsproxy: started pid={} args={:?}", child.id(), args);

    if let Err(e) = wait_tcp_port("127.0.0.1", setting.port, PORT_WAIT) {
        log::warn!("tgwsproxy: readiness probe failed: {e:#}");
    }
    Ok(())
}

pub fn is_running() -> bool {
    match crate::shell::run_quiet("pidof", &["tg-ws-proxy"], crate::shell::Capture::Stdout) {
        Ok((0, out)) => out.split_whitespace().any(|s| s.parse::<u32>().map(|p| p > 1).unwrap_or(false)),
        _ => false,
    }
}

fn wait_tcp_port(host: &str, port: u16, timeout: Duration) -> Result<()> {
    let ip: IpAddr = host.parse().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let addr = SocketAddr::new(ip, port);
    let start = Instant::now();
    while start.elapsed() < timeout {
        if TcpStream::connect_timeout(&addr, Duration::from_millis(350)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(150));
    }
    anyhow::bail!("port {} did not open on {}", port, host)
}

fn read_text(p: &Path) -> Result<String> {
    fs::read_to_string(p).map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))
}

fn write_text_atomic(p: &Path, content: &str) -> Result<()> {
    if let Some(parent) = p.parent() { fs::create_dir_all(parent).ok(); }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, content.as_bytes()).map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p).map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(p: &Path) -> Result<T> {
    let txt = read_text(p)?;
    serde_json::from_str(&txt).map_err(|e| anyhow::anyhow!("bad JSON {}: {e}", p.display()))
}

fn write_json_pretty<T: Serialize>(p: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(p, &txt)
}
