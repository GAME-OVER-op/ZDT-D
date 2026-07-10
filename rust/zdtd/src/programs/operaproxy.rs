
use anyhow::{Context, Result};
use super::common::*;
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    fs::{self, OpenOptions},
    io::{Read, Write},
    net::{TcpStream, ToSocketAddrs},
    str::FromStr,
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
    sync::mpsc,
};

use crate::{
    android::pkg_uid::{self, Sha256Tracker, Mode as UidMode},
    iptables::{
        hotspot,
        iptables_port::{DpiTunnelOptions, ProtoChoice},
    },
    programs::dnscrypt,
    settings,
    shell::{self, Capture},
};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

const OPERA_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/active.json";
const PORT_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/port.json";
const SNI_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/sni.json";
const SERVER_TXT: &str = "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/server.txt";
const API_PROXY_CHECK_TARGET_HOST: &str = "api2.sec-tunnel.com";
const API_PROXY_CHECK_TARGET_PORT: u16 = 443;
const API_PROXY_CHECK_PARALLEL: usize = 5;
const API_PROXY_CHECK_TIMEOUT: Duration = Duration::from_secs(4);
const API_PROXY_LIST_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/api_proxy_list.txt";
const API_PROXY_LIST_DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(15);
const API_PROXY_LIST_MAX_BYTES: usize = 1024 * 1024;
// Opera-proxy CA bundle for certificate verification. We keep it fixed to prevent config tampering.
const OPERA_CAFILE: &str = "/data/adb/modules/ZDT-D/strategic/certificate/ca.bundle";

// Configurable opera-proxy arguments stored as JSON (editable from the app UI).
const OPERA_ARGS_JSON: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/args.json";

// Configurable bootstrap DNS resolvers stored as a JSON array of strings (editable from the app UI).
const BOOTSTRAP_DNS_JSON: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/config/bootstrap_dns.json";

// Built-in fallback bootstrap DNS resolvers used when bootstrap_dns.json is missing or empty.
const DEFAULT_BOOTSTRAP_DNS: &str = "tls://dns.geohide.ru,https://xbox-dns.ru/dns-query,tls://9.9.9.9:853,https://ru-mow.doh.sb/dns-query,https://1.1.1.3/dns-query,https://security.cloudflare-dns.com/dns-query,https://wikimedia-dns.org/dns-query,https://dns.adguard-dns.com/dns-query,https://dns.quad9.net/dns-query,https://dns.comss.one/dns-query,https://router.comss.one/dns-query";

const APP_UID_USER: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/user_program";
const APP_OUT_USER: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/user_program";

// Optional per-interface lists (same naming as nfqws)
const APP_UID_MOBILE: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/mobile_program";
const APP_UID_WIFI: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/uid/wifi_program";

const APP_OUT_MOBILE: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/mobile_program";
const APP_OUT_WIFI: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out/wifi_program";

// byedpi args (text file, contents appended after "-i 127.0.0.1 -p <port> -x 1")
const BYEDPI_START_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/byedpi/config/start.txt";
const BYEDPI_RESTART_TXT: &str =
    "/data/adb/modules/ZDT-D/working_folder/operaproxy/byedpi/config/restart.txt";

#[derive(Debug, Deserialize)]
struct ActiveJson {
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct PortJson {
    // Port where t2s listens (transparent proxy)
    t2s_port: u16,
    // Starting port for opera-proxy socks servers (sequence +1)
    opera_start_port: u16,
    // byedpi (ciadpi-zdt) local socks upstream
    byedpi_port: u16,

    // Optional interface names for per-interface app lists.
    // Defaults to "auto" for backwards compatibility.
    #[serde(default = "default_iface")]
    iface_mobile: String,
    #[serde(default = "default_iface")]
    iface_wifi: String,
}

/// Configurable opera-proxy arguments.
/// All fields correspond directly to opera-proxy CLI flags.
/// Stored in config/args.json and edited from the app UI.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperaArgs {
    /// -api-proxy: HTTP(S)/SOCKS5 proxy used by opera-proxy to reach Opera backend API.
    /// Accepts one proxy URL, several proxy URLs separated by commas,
    /// an http(s) URL to a plain text proxy list, or an absolute local
    /// path to a plain text proxy list stored on the device.
    /// Examples:
    ///   "socks5://10.0.0.1:1080,socks5://10.0.0.1:1081"
    ///   "https://example.com/socks5-list.txt"
    ///   "/sdcard/proxies.txt"
    #[serde(default = "default_api_proxy")]
    pub api_proxy: String,

    /// -api-user-agent: User-Agent sent to Opera backend API.
    #[serde(default = "default_api_user_agent")]
    pub api_user_agent: String,

    /// -init-retry-interval: retry interval on startup failure. Example: "3s", "5s".
    #[serde(default = "default_init_retry_interval")]
    pub init_retry_interval: String,

    /// -server-selection: strategy for choosing the fastest server.
    /// Typical values: "fastest", "random".
    #[serde(default = "default_server_selection")]
    pub server_selection: String,

    /// -server-selection-dl-limit: download limit in bytes for speed test. Example: 204800.
    #[serde(default = "default_server_selection_dl_limit")]
    pub server_selection_dl_limit: u64,

    /// -server-selection-test-url: URL used for the speed test.
    #[serde(default = "default_server_selection_test_url")]
    pub server_selection_test_url: String,

    /// -verbosity: logging verbosity level (integer). Default: 50.
    #[serde(default = "default_verbosity")]
    pub verbosity: u32,
}

// ---- Default value functions required by serde ----

fn default_api_proxy() -> String {
    "socks5://193.221.203.192:1080".to_string()
}

fn default_api_user_agent() -> String {
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 OPR/98.0.0.0".to_string()
}

fn default_init_retry_interval() -> String {
    "3s".to_string()
}

fn default_server_selection() -> String {
    "fastest".to_string()
}

fn default_server_selection_dl_limit() -> u64 {
    204800
}

fn default_server_selection_test_url() -> String {
    "https://ajax.googleapis.com/ajax/libs/angularjs/1.8.2/angular.min.js".to_string()
}

fn default_verbosity() -> u32 {
    50
}

impl Default for OperaArgs {
    fn default() -> Self {
        Self {
            api_proxy: default_api_proxy(),
            api_user_agent: default_api_user_agent(),
            init_retry_interval: default_init_retry_interval(),
            server_selection: default_server_selection(),
            server_selection_dl_limit: default_server_selection_dl_limit(),
            server_selection_test_url: default_server_selection_test_url(),
            verbosity: default_verbosity(),
        }
    }
}

/// Load configurable opera-proxy arguments from config/args.json.
/// If the file is missing or unparseable, returns built-in defaults.
pub fn read_opera_args() -> OperaArgs {
    let path = Path::new(OPERA_ARGS_JSON);
    if !path.is_file() {
        return OperaArgs::default();
    }
    match fs::read_to_string(path) {
        Ok(s) => serde_json::from_str::<OperaArgs>(&s).unwrap_or_else(|e| {
            warn!("operaproxy: failed to parse args.json: {} -> using defaults", e);
            OperaArgs::default()
        }),
        Err(e) => {
            warn!("operaproxy: failed to read args.json: {} -> using defaults", e);
            OperaArgs::default()
        }
    }
}

/// Write configurable opera-proxy arguments to config/args.json.
pub fn write_opera_args(args: &OperaArgs) -> Result<()> {
    let path = Path::new(OPERA_ARGS_JSON);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let json = serde_json::to_string_pretty(args)
        .with_context(|| "serialize OperaArgs")?;
    fs::write(path, json.as_bytes())
        .with_context(|| format!("write {}", path.display()))?;
    Ok(())
}


#[derive(Debug, Clone, Deserialize, Serialize)]
struct OperaSniEntry {
    sni: String,
    #[serde(default)]
    use_byedpi: bool,
    #[serde(default)]
    override_proxy_address: Option<String>,
}

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(OPERA_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;
    if !active.enabled {
        info!("operaproxy: disabled via active.json");
        return Ok(());
    }

    crate::logging::user_info("Opera: byedpi");

    // Ensure app dirs exist
    let uid_dir = Path::new(OPERA_ROOT).join("app/uid");
    let out_dir = Path::new(OPERA_ROOT).join("app/out");
    ensure_dir(&uid_dir)?;
    ensure_dir(&out_dir)?;

    // Ensure list files exist (backwards compatible: create empty wifi/mobile lists if missing)
    ensure_file_empty(Path::new(APP_UID_USER))?;
    ensure_file_empty(Path::new(APP_UID_MOBILE))?;
    ensure_file_empty(Path::new(APP_UID_WIFI))?;

    // Resolve app uid map -> out (sha256 gated, but rebuild if output empty/missing)
    // IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
    // Never introduce module-specific *.flag.sha256 files here.
    let tracker = Sha256Tracker::new(settings::SHARED_SHA_FLAG_FILE);
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_USER),
        Path::new(APP_UID_USER),
    )?;
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_MOBILE),
        Path::new(APP_UID_MOBILE),
    )?;
    let _ = pkg_uid::unified_processing(
        UidMode::Default,
        &tracker,
        Path::new(APP_OUT_WIFI),
        Path::new(APP_UID_WIFI),
    )?;

    let resolved_user = count_valid_uid_pairs(Path::new(APP_OUT_USER))?;
    let resolved_mobile = count_valid_uid_pairs(Path::new(APP_OUT_MOBILE))?;
    let resolved_wifi = count_valid_uid_pairs(Path::new(APP_OUT_WIFI))?;
    let resolved_total = resolved_user + resolved_mobile + resolved_wifi;
    let has_launch_marker = pkg_uid::file_has_launch_marker(Path::new(APP_UID_USER)).unwrap_or(false)
        || pkg_uid::file_has_launch_marker(Path::new(APP_UID_MOBILE)).unwrap_or(false)
        || pkg_uid::file_has_launch_marker(Path::new(APP_UID_WIFI)).unwrap_or(false);

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_t2s = api_settings.hotspot_t2s_for_operaproxy();
    info!(
        "operaproxy: hotspot settings enabled={} mode='{}' program='{}' profile='{}' legacy_target='{}' hotspot_t2s={}",
        api_settings.hotspot_t2s_enabled,
        api_settings.hotspot_mode,
        api_settings.hotspot_program,
        api_settings.hotspot_profile,
        api_settings.hotspot_t2s_target,
        hotspot_t2s,
    );

    if resolved_total == 0 && !has_launch_marker && !hotspot_t2s {
        warn!("operaproxy: no apps resolved and hotspot disabled -> skip start/iptables");
        return Ok(());
    }
    if resolved_total == 0 && (has_launch_marker || hotspot_t2s) {
        info!("operaproxy: starting without routing app UIDs (launch_marker={} hotspot_t2s={})", has_launch_marker, hotspot_t2s);
    }

    // sni list required
    let sni_path = Path::new(SNI_JSON);
    if !sni_path.is_file() {
        warn!("operaproxy: sni.json not found -> skip");
        return Ok(());
    }
    let sni_list = read_sni_entries(sni_path)?;
    if sni_list.is_empty() {
        warn!("operaproxy: no valid SNI entries -> skip");
        return Ok(());
    }

    let port_cfg: PortJson = read_json(Path::new(PORT_JSON))
        .with_context(|| format!("read {}", PORT_JSON))?;

    let needs_t2s = resolved_total > 0 || hotspot_t2s;

    // opera-proxy region (EU/AS/AM) from config/server.txt, validated against whitelist
    let country = read_server_region(Path::new(SERVER_TXT));

    // Load configurable opera-proxy args (falls back to defaults if file missing/invalid)
    let opera_args = read_opera_args();
    info!(
        "operaproxy: args loaded: api_proxy='{}' verbosity={} server_selection='{}' init_retry='{}'",
        opera_args.api_proxy,
        opera_args.verbosity,
        opera_args.server_selection,
        opera_args.init_retry_interval,
    );

    let service_count = sni_list.len();
    let any_uses_byedpi = sni_list.iter().any(|x| x.use_byedpi);

    // Port intersection guard: byedpi, optional t2s, and all opera ports must be unique
    if has_port_intersection(&port_cfg, service_count, needs_t2s) {
        warn!(
            "operaproxy: port intersection detected (t2s_port={}, byedpi_port={}, opera_start_port={}) -> skip",
            port_cfg.t2s_port, port_cfg.byedpi_port, port_cfg.opera_start_port
        );
        return Ok(());
    }

    // Prepare log dir (cleaned globally by runtime, but ensure exists)
    let log_dir = Path::new(OPERA_ROOT).join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;

    // Resolve -api-proxy before spawning opera-proxy instances.
    // The user may provide one proxy URL, several proxy URLs separated by commas,
    // or an http(s) URL to a plain text proxy list. URL lists are downloaded
    // again on every start and saved to config/api_proxy_list.txt. We only pass
    // one checked working proxy to opera-proxy; if none works or the list cannot
    // be downloaded, opera-proxy is started as if api_proxy was empty.
    let api_proxy_check_log = log_dir.join("api_proxy_check.log");
    truncate_file(&api_proxy_check_log)?;
    let selected_api_proxy = select_working_api_proxy(&opera_args.api_proxy, &api_proxy_check_log);

    // 1) Start byedpi (initial) only if at least one SNI entry requests it.
    let mut byedpi_bin_opt: Option<PathBuf> = None;
    let bye_log = log_dir.join("bye_opera.log");
    let mut byedpi_start_pid: Option<u32> = None;
    if any_uses_byedpi {
        let byedpi_bin = find_bin("byedpi")?;
        byedpi_bin_opt = Some(byedpi_bin.clone());
        truncate_file(&bye_log)?;
        let start_args = normalize_config_args(&fs::read_to_string(BYEDPI_START_TXT)
            .with_context(|| format!("read {}", BYEDPI_START_TXT))?);

        let pid = spawn_byedpi(&byedpi_bin, port_cfg.byedpi_port, &start_args, &bye_log)?;
        byedpi_start_pid = Some(pid);
        info!("operaproxy: byedpi started on port {}", port_cfg.byedpi_port);

        // warmup pause (reduced)
        std::thread::sleep(Duration::from_millis(1500));
    } else {
        info!("operaproxy: all sni entries use direct mode (without byedpi upstream)");
    }

    crate::logging::user_info("Opera: opera-proxy");
    // 2) Start opera-proxy instances
    let opera_bin = find_bin("opera-proxy")?;
    let mut socks_ports: Vec<u16> = Vec::new();

    let bootstrap_dns = build_bootstrap_dns_list()?;
    for (idx, entry) in sni_list.iter().take(service_count).enumerate() {
        let port = port_cfg.opera_start_port.saturating_add(idx as u16);
        socks_ports.push(port);

        let safe = sanitize_for_filename(&entry.sni);
        let log_path = log_dir.join(format!("opera_proxy{}_{}.log", idx, safe));
        truncate_file(&log_path)?;

        spawn_opera_proxy(
            &opera_bin,
            port,
            &entry.sni,
            if entry.use_byedpi { Some(port_cfg.byedpi_port) } else { None },
            entry.override_proxy_address.as_deref(),
            &country,
            &bootstrap_dns,
            selected_api_proxy.as_deref(),
            &opera_args,
            &log_path,
        )?;

        std::thread::sleep(Duration::from_millis(2500));
    }

    let ports_csv = socks_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(",");
    info!("operaproxy: started {} opera-proxy instances ports={}", socks_ports.len(), ports_csv);

    // 3) SOCKS check loop (up to 20s) with more frequent polling and shorter per-check time.
    let min_ok = if socks_ports.len() <= 2 { 1 } else { 2 };
    let check_log = log_dir.join("socks_check.log");
    truncate_file(&check_log)?;
    let ok = wait_for_socks(
        min_ok,
        &socks_ports,
        Duration::from_secs(20),
        Duration::from_millis(500),
        Duration::from_millis(500),
        &check_log,
    )?;
    if !ok {
        warn!("operaproxy: required working socks servers not reached (min_ok={}) -> continue", min_ok);
    }

    if any_uses_byedpi {
        crate::logging::user_info("Opera: byedpi (restart)");
        let kill_log = log_dir.join("bye_opera_kill.log");
        truncate_file(&kill_log)?;
        if let Some(start_pid) = byedpi_start_pid {
            if let Ok(Some(ss_pid)) = find_pid_by_listen_port(port_cfg.byedpi_port, &kill_log) {
                if ss_pid != start_pid.to_string() {
                    warn!("operaproxy: ss reports pid={} on :{}, but our started pid={} -> will kill only started pid",
                        ss_pid, port_cfg.byedpi_port, start_pid);
                }
            }

            let _ = shell::run("kill", &["-9", &start_pid.to_string()], Capture::None);
            std::thread::sleep(Duration::from_millis(200));
            let _ = find_pid_by_listen_port(port_cfg.byedpi_port, &kill_log);

            if let Some(ref byedpi_bin) = byedpi_bin_opt {
                let restart_args = normalize_config_args(&fs::read_to_string(BYEDPI_RESTART_TXT)
                    .with_context(|| format!("read {}", BYEDPI_RESTART_TXT))?);
                let _byedpi_restart_pid = spawn_byedpi(byedpi_bin, port_cfg.byedpi_port, &restart_args, &bye_log)?;
                info!("operaproxy: byedpi restarted");
            }
        }
    } else {
        info!("operaproxy: skipping byedpi restart (no sni entries require byedpi)");
    }

    if needs_t2s {
        crate::logging::user_info("Opera: t2s");
        // 5) Start t2s (hardcoded args + dynamic listen port + socks ports)
        let t2s_listen_addr = if hotspot_t2s { "0.0.0.0" } else { "127.0.0.1" };

        let t2s_bin = find_bin("t2s")?;
        let t2s_log = log_dir.join("t2s.log");
        truncate_file(&t2s_log)?;

        spawn_t2s_proxy(T2sSpawnConfig {
            bin: &t2s_bin,
            listen_addr: t2s_listen_addr,
            listen_port: port_cfg.t2s_port,
            socks_host: "127.0.0.1",
            socks_ports_csv: &ports_csv,
            web_port: None,
            program: "operaproxy",
            profile: "main",
            scope: "program/operaproxy",
            log_path: &t2s_log,
            ..Default::default()
        })?;
        info!(
            "operaproxy: t2s started listen_addr={} listen_port={} socks_ports={}",
            t2s_listen_addr,
            port_cfg.t2s_port,
            ports_csv
        );
        if hotspot_t2s {
            hotspot::ensure_prerouting_redirect(hotspot::HotspotRedirectConfig {
                owner: "operaproxy",
                listen_port: port_cfg.t2s_port,
                capture_all: api_settings.hotspot_t2s_capture_all,
                bypass_ports: hotspot::DEFAULT_HOTSPOT_BYPASS_PORTS,
            })?;
        }
    } else {
        info!("operaproxy: launch marker only and hotspot disabled; skipping t2s");
    }

    crate::logging::user_info("Opera: iptables");
    // 6) Apply iptables_port for selected apps -> t2s_port, proto tcp
    // - user list applies to ALL interfaces (no -o)
    // - mobile/wifi lists apply to specified interfaces from port.json
    let opt = DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() };

    apply_t2s_routing(
        Path::new(APP_OUT_USER),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        None,
        opt.clone(),
    )?;
    apply_t2s_routing(
        Path::new(APP_OUT_MOBILE),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        Some(port_cfg.iface_mobile.as_str()),
        opt.clone(),
    )?;
    apply_t2s_routing(
        Path::new(APP_OUT_WIFI),
        port_cfg.t2s_port,
        ProtoChoice::Tcp,
        Some(port_cfg.iface_wifi.as_str()),
        opt,
    )?;

    info!("operaproxy: started successfully");
    Ok(())
}

/// Read the bootstrap DNS resolver list from config/bootstrap_dns.json.
/// Returns a comma-separated string of resolver URLs.
/// Falls back to DEFAULT_BOOTSTRAP_DNS if the file is missing, empty, or invalid.
pub fn read_bootstrap_dns() -> String {
    let path = Path::new(BOOTSTRAP_DNS_JSON);
    if !path.is_file() {
        return DEFAULT_BOOTSTRAP_DNS.to_string();
    }
    let s = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            warn!("operaproxy: failed to read bootstrap_dns.json: {} -> using defaults", e);
            return DEFAULT_BOOTSTRAP_DNS.to_string();
        }
    };
    // Parse as JSON array of strings
    let arr: Vec<String> = match serde_json::from_str::<Vec<String>>(&s) {
        Ok(v) => v.into_iter().map(|e| e.trim().to_string()).filter(|e| !e.is_empty()).collect(),
        Err(e) => {
            warn!("operaproxy: failed to parse bootstrap_dns.json: {} -> using defaults", e);
            return DEFAULT_BOOTSTRAP_DNS.to_string();
        }
    };
    if arr.is_empty() {
        warn!("operaproxy: bootstrap_dns.json is empty -> using defaults");
        return DEFAULT_BOOTSTRAP_DNS.to_string();
    }
    arr.join(",")
}

/// Write bootstrap DNS resolver list to config/bootstrap_dns.json as a JSON array.
pub fn write_bootstrap_dns(resolvers: &[String]) -> Result<()> {
    let path = Path::new(BOOTSTRAP_DNS_JSON);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let json = serde_json::to_string_pretty(resolvers)
        .with_context(|| "serialize bootstrap DNS list")?;
    fs::write(path, json.as_bytes())
        .with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn build_bootstrap_dns_list() -> Result<String> {
    // Load configurable external resolvers (falls back to defaults if file missing/invalid)
    let external = read_bootstrap_dns();
    // Prepend local dnscrypt resolver if it is currently enabled
    if let Some(port) = dnscrypt::active_listen_port()? {
        let local = format!("https://127.0.0.1:{},{}", port, external);
        Ok(local)
    } else {
        Ok(external)
    }
}

fn read_sni_entries(path: &Path) -> Result<Vec<OperaSniEntry>> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let parsed = serde_json::from_str::<Vec<OperaSniEntry>>(&s)
        .with_context(|| format!("parse {}", path.display()))?;
    let mut out = Vec::new();
    for item in parsed {
        let t = item.sni.trim();
        if t.is_empty() {
            continue;
        }

        let override_proxy_address = match item.override_proxy_address.as_deref().map(str::trim) {
            Some("") | None => None,
            Some(v) => {
                if is_valid_override_proxy_address(v) {
                    Some(v.to_string())
                } else {
                    warn!(
                        "operaproxy: invalid override_proxy_address '{}' for sni '{}' -> ignored",
                        v,
                        t
                    );
                    None
                }
            }
        };

        out.push(OperaSniEntry {
            sni: t.to_string(),
            use_byedpi: item.use_byedpi,
            override_proxy_address,
        });
    }
    Ok(out)
}

fn is_valid_override_proxy_address(value: &str) -> bool {
    if value.is_empty() {
        return false;
    }

    if std::net::SocketAddr::from_str(value).is_ok() {
        return true;
    }

    let Some((host, port)) = value.rsplit_once(':') else {
        return false;
    };

    let host = host.trim();
    if host.is_empty() {
        return false;
    }

    port.parse::<u16>().is_ok()
}

/// Reads Opera server region from config file.
/// Allowed values: EU, AS, AM (case-insensitive). Any other value falls back to EU.
fn read_server_region(path: &Path) -> String {
    let default = "EU".to_string();
    let s = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(_) => return default,
    };

    for line in s.lines() {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') {
            continue;
        }
        let first = t.split_whitespace().next().unwrap_or("");
        let up = first.to_uppercase();
        match up.as_str() {
            "EU" | "AS" | "AM" => return up,
            _ => {
                warn!(
                    "operaproxy: invalid server region '{}' in {} -> using EU",
                    first,
                    path.display()
                );
                return default;
            }
        }
    }

    default
}

fn has_port_intersection(cfg: &PortJson, service_count: usize, include_t2s: bool) -> bool {
    let mut set = BTreeSet::new();
    let mut all = Vec::new();
    if include_t2s {
        all.push(cfg.t2s_port);
    }
    all.push(cfg.byedpi_port);
    for i in 0..service_count {
        all.push(cfg.opera_start_port.saturating_add(i as u16));
    }
    for p in all {
        if !set.insert(p) {
            return true;
        }
    }
    false
}


#[derive(Debug, Clone)]
struct ApiProxyCandidate {
    original: String,
    scheme: String,
    host: String,
    port: u16,
    username: Option<String>,
    password: Option<String>,
}

fn select_working_api_proxy(raw: &str, log_path: &Path) -> Option<String> {
    let candidates = collect_api_proxy_candidates(raw, log_path);
    if candidates.is_empty() {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            append_log_line(log_path, "api_proxy is empty -> start without -api-proxy");
        } else {
            append_log_line(log_path, "no valid api_proxy candidates -> start without -api-proxy");
        }
        return None;
    }

    append_log_line(
        log_path,
        &format!(
            "checking {} api_proxy candidate(s), parallel={}",
            candidates.len(),
            API_PROXY_CHECK_PARALLEL
        ),
    );

    for chunk in candidates.chunks(API_PROXY_CHECK_PARALLEL.max(1)) {
        let (tx, rx) = mpsc::channel();
        let mut handles = Vec::new();
        for (idx, cand) in chunk.iter().cloned().enumerate() {
            let tx = tx.clone();
            handles.push(std::thread::spawn(move || {
                let ok = check_api_proxy_candidate(&cand, API_PROXY_CHECK_TIMEOUT);
                let _ = tx.send((idx, ok, cand));
            }));
        }
        drop(tx);

        let deadline = Instant::now() + API_PROXY_CHECK_TIMEOUT + Duration::from_millis(750);
        let mut results: Vec<(usize, bool, ApiProxyCandidate)> = Vec::new();
        while results.len() < chunk.len() {
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            let timeout = deadline.saturating_duration_since(now);
            match rx.recv_timeout(timeout) {
                Ok(item) => results.push(item),
                Err(_) => break,
            }
        }

        for handle in handles {
            let _ = handle.join();
        }

        results.sort_by_key(|(idx, _, _)| *idx);
        for (_, ok, cand) in &results {
            append_log_line(
                log_path,
                &format!(
                    "{} {}",
                    if *ok { "OK" } else { "FAIL" },
                    mask_proxy_for_log(&cand.original)
                ),
            );
        }

        if let Some((_, _, cand)) = results.into_iter().find(|(_, ok, _)| *ok) {
            append_log_line(
                log_path,
                &format!("selected {}", mask_proxy_for_log(&cand.original)),
            );
            info!("operaproxy: selected api_proxy {}", mask_proxy_for_log(&cand.original));
            return Some(cand.original);
        }
    }

    append_log_line(log_path, "no working api_proxy found -> start without -api-proxy");
    warn!("operaproxy: no working api_proxy found -> start without -api-proxy");
    None
}

fn collect_api_proxy_candidates(raw: &str, log_path: &Path) -> Vec<ApiProxyCandidate> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    if is_api_proxy_list_file_path(trimmed) {
        append_log_line(log_path, "api_proxy list file mode: 1 file");
        return collect_api_proxy_candidates_from_files(&[trimmed.to_string()], log_path);
    }

    let raw_tokens = split_api_proxy_tokens(trimmed);
    let list_files: Vec<String> = raw_tokens
        .iter()
        .filter(|token| is_api_proxy_list_file_path(token))
        .cloned()
        .collect();
    let list_urls: Vec<String> = raw_tokens
        .iter()
        .filter(|token| is_api_proxy_list_url(token))
        .cloned()
        .collect();

    if !list_files.is_empty() {
        append_log_line(
            log_path,
            &format!("api_proxy list file mode: {} file(s)", list_files.len()),
        );
        return collect_api_proxy_candidates_from_files(&list_files, log_path);
    }

    if !list_urls.is_empty() {
        append_log_line(
            log_path,
            &format!("api_proxy list url mode: {} URL(s)", list_urls.len()),
        );
        return collect_api_proxy_candidates_from_urls(&list_urls, log_path);
    }

    parse_api_proxy_candidates_from_text(trimmed, log_path)
}

fn collect_api_proxy_candidates_from_urls(urls: &[String], log_path: &Path) -> Vec<ApiProxyCandidate> {
    let list_path = Path::new(API_PROXY_LIST_TXT);
    // Always reset the cached list before downloading so every start uses a fresh file.
    // If downloading fails, the stale previous list is not reused.
    if let Err(e) = write_api_proxy_list_file(list_path, "") {
        append_log_line(
            log_path,
            &format!("failed to reset api_proxy list {}: {}", list_path.display(), e),
        );
    }
    let mut combined = String::new();

    for url in urls {
        append_log_line(
            log_path,
            &format!("downloading api_proxy list: {}", mask_proxy_for_log(url)),
        );
        match download_api_proxy_list(url) {
            Ok(text) => {
                append_log_line(
                    log_path,
                    &format!(
                        "downloaded {} byte(s) from {}",
                        text.as_bytes().len(),
                        mask_proxy_for_log(url)
                    ),
                );
                if !combined.is_empty() {
                    combined.push('\n');
                }
                combined.push_str(&text);
            }
            Err(e) => {
                append_log_line(
                    log_path,
                    &format!(
                        "failed to download api_proxy list from {}: {}",
                        mask_proxy_for_log(url),
                        e
                    ),
                );
            }
        }
    }

    if combined.trim().is_empty() {
        append_log_line(log_path, "api_proxy list download failed or empty -> start without -api-proxy");
        warn!("operaproxy: api_proxy list download failed or empty -> start without -api-proxy");
        return Vec::new();
    }

    if let Err(e) = write_api_proxy_list_file(list_path, &combined) {
        append_log_line(
            log_path,
            &format!("failed to save api_proxy list {}: {}", list_path.display(), e),
        );
        warn!("operaproxy: failed to save api_proxy list {}: {}", list_path.display(), e);
    } else {
        append_log_line(log_path, &format!("saved api_proxy list: {}", list_path.display()));
    }

    let candidates = parse_api_proxy_candidates_from_text(&combined, log_path);
    append_log_line(
        log_path,
        &format!("parsed {} valid candidate(s) from downloaded api_proxy list", candidates.len()),
    );
    candidates
}

fn collect_api_proxy_candidates_from_files(paths: &[String], log_path: &Path) -> Vec<ApiProxyCandidate> {
    let list_path = Path::new(API_PROXY_LIST_TXT);
    // Always reset the cached list before reading so every start uses current file contents.
    if let Err(e) = write_api_proxy_list_file(list_path, "") {
        append_log_line(
            log_path,
            &format!("failed to reset api_proxy list {}: {}", list_path.display(), e),
        );
    }
    let mut combined = String::new();

    for path in paths {
        append_log_line(log_path, &format!("reading api_proxy list file: {}", path));
        match read_api_proxy_list_file(path) {
            Ok(text) => {
                append_log_line(
                    log_path,
                    &format!("read {} byte(s) from {}", text.as_bytes().len(), path),
                );
                if !combined.is_empty() {
                    combined.push('\n');
                }
                combined.push_str(&text);
            }
            Err(e) => {
                append_log_line(
                    log_path,
                    &format!("failed to read api_proxy list file {}: {}", path, e),
                );
            }
        }
    }

    if combined.trim().is_empty() {
        append_log_line(log_path, "api_proxy list file read failed or empty -> start without -api-proxy");
        warn!("operaproxy: api_proxy list file read failed or empty -> start without -api-proxy");
        return Vec::new();
    }

    if let Err(e) = write_api_proxy_list_file(list_path, &combined) {
        append_log_line(
            log_path,
            &format!("failed to save api_proxy list {}: {}", list_path.display(), e),
        );
        warn!("operaproxy: failed to save api_proxy list {}: {}", list_path.display(), e);
    } else {
        append_log_line(log_path, &format!("saved api_proxy list: {}", list_path.display()));
    }

    let candidates = parse_api_proxy_candidates_from_text(&combined, log_path);
    append_log_line(
        log_path,
        &format!("parsed {} valid candidate(s) from local api_proxy list", candidates.len()),
    );
    candidates
}

fn download_api_proxy_list(url: &str) -> Result<String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(API_PROXY_LIST_DOWNLOAD_TIMEOUT)
        .user_agent("ZDT-D/1")
        .redirect(reqwest::redirect::Policy::limited(5))
        .build()
        .with_context(|| "build api_proxy list HTTP client")?;

    let mut response = client
        .get(url)
        .send()
        .with_context(|| format!("GET {}", mask_proxy_for_log(url)))?;

    let status = response.status();
    if !status.is_success() {
        anyhow::bail!("HTTP status {}", status);
    }

    let mut body = Vec::new();
    let mut limited = response.take((API_PROXY_LIST_MAX_BYTES + 1) as u64);
    limited
        .read_to_end(&mut body)
        .with_context(|| "read api_proxy list response")?;
    if body.len() > API_PROXY_LIST_MAX_BYTES {
        anyhow::bail!("api_proxy list is larger than {} byte(s)", API_PROXY_LIST_MAX_BYTES);
    }

    Ok(String::from_utf8_lossy(&body).into_owned())
}

fn read_api_proxy_list_file(path: &str) -> Result<String> {
    let path = Path::new(path.trim());
    if !path.is_absolute() {
        anyhow::bail!("api_proxy list file path must be absolute");
    }
    let file = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut body = Vec::new();
    let mut limited = file.take((API_PROXY_LIST_MAX_BYTES + 1) as u64);
    limited
        .read_to_end(&mut body)
        .with_context(|| format!("read {}", path.display()))?;
    if body.len() > API_PROXY_LIST_MAX_BYTES {
        anyhow::bail!("api_proxy list is larger than {} byte(s)", API_PROXY_LIST_MAX_BYTES);
    }

    Ok(String::from_utf8_lossy(&body).into_owned())
}

fn write_api_proxy_list_file(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    fs::write(path, text.as_bytes()).with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

fn parse_api_proxy_candidates_from_text(raw: &str, log_path: &Path) -> Vec<ApiProxyCandidate> {
    let mut out = Vec::new();
    let mut seen = BTreeSet::new();

    for token in split_api_proxy_tokens(raw) {
        if is_api_proxy_list_url(&token) {
            append_log_line(
                log_path,
                &format!(
                    "skip nested api_proxy list URL inside downloaded/list text: {}",
                    mask_proxy_for_log(&token)
                ),
            );
            continue;
        }
        match parse_api_proxy_candidate(&token) {
            Some(candidate) => {
                if seen.insert(candidate.original.clone()) {
                    out.push(candidate);
                }
            }
            None => append_log_line(
                log_path,
                &format!(
                    "skip invalid api_proxy candidate: {}",
                    mask_proxy_for_log(&token)
                ),
            ),
        }
    }

    out
}

fn split_api_proxy_tokens(raw: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    for line in raw.lines() {
        let without_comment = line.split_once('#').map(|(head, _)| head).unwrap_or(line);
        for part in without_comment.split(|c: char| c == ',' || c.is_whitespace()) {
            let token = part.trim();
            if !token.is_empty() {
                tokens.push(token.to_string());
            }
        }
    }
    tokens
}

fn is_api_proxy_list_url(token: &str) -> bool {
    let Some((scheme_raw, rest_raw)) = token.trim().split_once("://") else {
        return false;
    };
    let scheme = scheme_raw.trim().to_ascii_lowercase();
    if !matches!(scheme.as_str(), "http" | "https") {
        return false;
    }
    let rest = rest_raw.trim();
    if rest.is_empty() {
        return false;
    }

    let authority_end = rest
        .find(|c| matches!(c, '/' | '?' | '#'))
        .unwrap_or(rest.len());
    let authority = &rest[..authority_end];
    let tail = &rest[authority_end..];
    if authority.is_empty() || tail.is_empty() {
        return false;
    }

    let path_or_query_is_not_root = !matches!(tail, "/" | "" | "?" | "#");
    path_or_query_is_not_root
}

fn is_api_proxy_list_file_path(token: &str) -> bool {
    let token = token.trim();
    !token.is_empty() && Path::new(token).is_absolute()
}

fn parse_api_proxy_candidate(token: &str) -> Option<ApiProxyCandidate> {
    let (scheme_raw, rest_raw) = token.split_once("://")?;
    let scheme = scheme_raw.trim().to_ascii_lowercase();
    if !matches!(scheme.as_str(), "http" | "https" | "socks5" | "socks5h") {
        return None;
    }

    let rest = rest_raw.trim();
    if rest.is_empty() || rest.contains('/') || rest.contains('?') || rest.contains('#') {
        return None;
    }

    let (userinfo, hostport) = match rest.rsplit_once('@') {
        Some((u, hp)) => (Some(u), hp),
        None => (None, rest),
    };

    let (username, password) = match userinfo {
        Some(u) => {
            if u.is_empty() {
                return None;
            }
            let (name, pass) = u.split_once(':').unwrap_or((u, ""));
            if name.is_empty() {
                return None;
            }
            (Some(name.to_string()), Some(pass.to_string()))
        }
        None => (None, None),
    };

    let (host, port) = parse_host_port(hostport)?;
    Some(ApiProxyCandidate {
        original: token.to_string(),
        scheme,
        host,
        port,
        username,
        password,
    })
}

fn parse_host_port(hostport: &str) -> Option<(String, u16)> {
    let hostport = hostport.trim();
    if hostport.is_empty() {
        return None;
    }

    if let Some(rest) = hostport.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        let port_raw = tail.strip_prefix(':')?;
        let port = port_raw.parse::<u16>().ok()?;
        if host.trim().is_empty() || port == 0 {
            return None;
        }
        return Some((host.trim().to_string(), port));
    }

    let (host, port_raw) = hostport.rsplit_once(':')?;
    let port = port_raw.parse::<u16>().ok()?;
    if host.trim().is_empty() || port == 0 {
        return None;
    }
    Some((host.trim().to_string(), port))
}

fn check_api_proxy_candidate(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    match candidate.scheme.as_str() {
        "socks5" | "socks5h" => check_socks5_proxy(candidate, timeout),
        "http" => check_http_proxy(candidate, timeout),
        // std does not provide TLS, so HTTPS proxy validation is limited to TCP reachability.
        // The candidate is still passed to opera-proxy only if its proxy endpoint accepts TCP.
        "https" => connect_proxy_tcp(candidate, timeout).is_ok(),
        _ => false,
    }
}

fn connect_proxy_tcp(candidate: &ApiProxyCandidate, timeout: Duration) -> std::io::Result<TcpStream> {
    let addrs = (candidate.host.as_str(), candidate.port).to_socket_addrs()?;
    let mut last_err = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(stream) => {
                let _ = stream.set_read_timeout(Some(timeout));
                let _ = stream.set_write_timeout(Some(timeout));
                return Ok(stream);
            }
            Err(e) => last_err = Some(e),
        }
    }
    Err(last_err.unwrap_or_else(|| std::io::Error::new(std::io::ErrorKind::Other, "no socket address")))
}

fn check_socks5_proxy(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    let mut stream = match connect_proxy_tcp(candidate, timeout) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let use_auth = candidate.username.as_deref().map(|s| !s.is_empty()).unwrap_or(false);
    let greeting: &[u8] = if use_auth { &[0x05, 0x02, 0x00, 0x02] } else { &[0x05, 0x01, 0x00] };
    if stream.write_all(greeting).is_err() {
        return false;
    }
    let mut method = [0u8; 2];
    if stream.read_exact(&mut method).is_err() || method[0] != 0x05 || method[1] == 0xff {
        return false;
    }

    if method[1] == 0x02 {
        let user = candidate.username.as_deref().unwrap_or("").as_bytes();
        let pass = candidate.password.as_deref().unwrap_or("").as_bytes();
        if user.is_empty() || user.len() > 255 || pass.len() > 255 {
            return false;
        }
        let mut auth = Vec::with_capacity(3 + user.len() + pass.len());
        auth.push(0x01);
        auth.push(user.len() as u8);
        auth.extend_from_slice(user);
        auth.push(pass.len() as u8);
        auth.extend_from_slice(pass);
        if stream.write_all(&auth).is_err() {
            return false;
        }
        let mut auth_reply = [0u8; 2];
        if stream.read_exact(&mut auth_reply).is_err() || auth_reply[1] != 0x00 {
            return false;
        }
    } else if method[1] != 0x00 {
        return false;
    }

    let target_host = API_PROXY_CHECK_TARGET_HOST.as_bytes();
    if target_host.len() > 255 {
        return false;
    }
    let mut req = Vec::with_capacity(7 + target_host.len());
    req.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, target_host.len() as u8]);
    req.extend_from_slice(target_host);
    req.extend_from_slice(&API_PROXY_CHECK_TARGET_PORT.to_be_bytes());
    if stream.write_all(&req).is_err() {
        return false;
    }

    let mut head = [0u8; 4];
    if stream.read_exact(&mut head).is_err() || head[0] != 0x05 || head[1] != 0x00 {
        return false;
    }

    match head[3] {
        0x01 => read_and_discard(&mut stream, 4 + 2),
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).is_ok() && read_and_discard(&mut stream, len[0] as usize + 2)
        }
        0x04 => read_and_discard(&mut stream, 16 + 2),
        _ => false,
    }
}

fn check_http_proxy(candidate: &ApiProxyCandidate, timeout: Duration) -> bool {
    let mut stream = match connect_proxy_tcp(candidate, timeout) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let target = format!("{}:{}", API_PROXY_CHECK_TARGET_HOST, API_PROXY_CHECK_TARGET_PORT);
    let mut req = format!(
        "CONNECT {target} HTTP/1.1\r\nHost: {target}\r\nProxy-Connection: close\r\nUser-Agent: ZDT-D/1\r\n"
    );
    if let Some(user) = candidate.username.as_deref().filter(|s| !s.is_empty()) {
        let pass = candidate.password.as_deref().unwrap_or("");
        let encoded = base64_basic_auth(&format!("{}:{}", user, pass));
        req.push_str(&format!("Proxy-Authorization: Basic {}\r\n", encoded));
    }
    req.push_str("\r\n");

    if stream.write_all(req.as_bytes()).is_err() {
        return false;
    }
    let mut buf = [0u8; 192];
    let n = match stream.read(&mut buf) {
        Ok(n) => n,
        Err(_) => return false,
    };
    if n == 0 {
        return false;
    }
    let status = String::from_utf8_lossy(&buf[..n]);
    status.starts_with("HTTP/1.1 200") || status.starts_with("HTTP/1.0 200")
}

fn read_and_discard(stream: &mut TcpStream, len: usize) -> bool {
    let mut buf = vec![0u8; len];
    stream.read_exact(&mut buf).is_ok()
}

fn base64_basic_auth(input: &str) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(((bytes.len() + 2) / 3) * 4);
    let mut i = 0;
    while i < bytes.len() {
        let b0 = bytes[i];
        let b1 = if i + 1 < bytes.len() { bytes[i + 1] } else { 0 };
        let b2 = if i + 2 < bytes.len() { bytes[i + 2] } else { 0 };
        out.push(TABLE[(b0 >> 2) as usize] as char);
        out.push(TABLE[(((b0 & 0x03) << 4) | (b1 >> 4)) as usize] as char);
        if i + 1 < bytes.len() {
            out.push(TABLE[(((b1 & 0x0f) << 2) | (b2 >> 6)) as usize] as char);
        } else {
            out.push('=');
        }
        if i + 2 < bytes.len() {
            out.push(TABLE[(b2 & 0x3f) as usize] as char);
        } else {
            out.push('=');
        }
        i += 3;
    }
    out
}

fn mask_proxy_for_log(value: &str) -> String {
    let Some((scheme, rest)) = value.split_once("://") else {
        return value.to_string();
    };
    let safe_rest = match rest.rsplit_once('@') {
        Some((_userinfo, hostport)) => format!("***@{}", hostport),
        None => rest.to_string(),
    };
    format!("{}://{}", scheme, safe_rest)
}

fn append_log_line(path: &Path, line: &str) {
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{}", line);
    }
}

fn spawn_byedpi(bin: &Path, port: u16, extra_args: &[String], log_path: &Path) -> Result<u32> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("-i")
        .arg("127.0.0.1")
        .arg("-p")
        .arg(port.to_string())
        .arg("-x")
        .arg("0")
        .args(extra_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    info!("spawned operaproxy-byedpi pid={} port={} log={}", pid, port, log_path.display());
    Ok(pid)
}

fn spawn_opera_proxy(
    bin: &Path,
    bind_port: u16,
    fake_sni: &str,
    upstream_byedpi_port: Option<u16>,
    override_proxy_address: Option<&str>,
    country: &str,
    bootstrap_dns: &str,
    selected_api_proxy: Option<&str>,
    opera_args: &OperaArgs,
    log_path: &Path,
) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let upstream = upstream_byedpi_port.map(|p| format!("socks5://127.0.0.1:{}", p));
    let bind = format!("127.0.0.1:{}", bind_port);

    let mut cmd = Command::new(bin);
    cmd.arg("-bind-address")
        .arg(bind)
        .arg("-socks-mode")
        .arg("-cafile")
        .arg(OPERA_CAFILE)
        .arg("-fake-SNI")
        .arg(fake_sni)
        .arg("-bootstrap-dns")
        .arg(bootstrap_dns)
        .arg("-api-user-agent")
        .arg(&opera_args.api_user_agent)
        .arg("-country")
        .arg(country)
        .arg("-init-retry-interval")
        .arg(&opera_args.init_retry_interval)
        .arg("-server-selection")
        .arg(&opera_args.server_selection)
        .arg("-server-selection-dl-limit")
        .arg(opera_args.server_selection_dl_limit.to_string())
        .arg("-verbosity")
        .arg(opera_args.verbosity.to_string());
    if let Some(api_proxy) = selected_api_proxy.map(str::trim).filter(|s| !s.is_empty()) {
        cmd.arg("-api-proxy").arg(api_proxy);
    }
    cmd.arg("-server-selection-test-url")
        .arg(&opera_args.server_selection_test_url);
    if let Some(ref upstream) = upstream {
        cmd.arg("-proxy").arg(upstream);
    }
    if let Some(override_proxy_address) = override_proxy_address {
        cmd.arg("-override-proxy-address").arg(override_proxy_address);
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned opera-proxy pid={} bind_port={} sni='{}' log={}",
        child.id(),
        bind_port,
        fake_sni,
        log_path.display()
    );
    Ok(())
}

fn wait_for_socks(
    min_ok: usize,
    ports: &[u16],
    max_wait: Duration,
    interval: Duration,
    check_timeout: Duration,
    log_path: &Path,
) -> Result<bool> {
    let start = Instant::now();
    let mut f = OpenOptions::new()
        .create(true)
        .write(true)
        .append(true)
        .open(log_path)
        .with_context(|| format!("open {}", log_path.display()))?;
    writeln!(
        f,
        "Starting SOCKS check: ports={:?} min_ok={} max_wait={:?} interval={:?} check_timeout={:?}",
        ports,
        min_ok,
        max_wait,
        interval,
        check_timeout
    )
    .ok();

    while start.elapsed() < max_wait {
        let mut working = 0usize;
        // Run checks in parallel so that total check time is closer to the per-check timeout,
        // not multiplied by number of ports.
        let host: &'static str = "127.0.0.1";
        let mut handles = Vec::with_capacity(ports.len());
        for p in ports {
            let port = *p;
            handles.push(std::thread::spawn(move || {
                let (ok, err) = check_socks5_health(host, port, check_timeout);
                (port, ok, err)
            }));
        }

        for h in handles {
            match h.join() {
                Ok((port, ok, err)) => {
                    if ok {
                        working += 1;
                    }
                    writeln!(
                        f,
                        "  {}: {}{}",
                        port,
                        if ok { "OK" } else { "FAIL" },
                        err.as_deref()
                            .map(|e| format!(" ({})", e))
                            .unwrap_or_default()
                    )
                    .ok();
                }
                Err(_) => {
                    writeln!(f, "  <thread>: FAIL (panic)").ok();
                }
            }
        }
        writeln!(f, "CHECK RESULT: working={} total={} min_ok={}", working, ports.len(), min_ok).ok();
        f.flush().ok();

        if working >= min_ok {
            writeln!(f, "Reached required working servers: {}", working).ok();
            f.flush().ok();
            return Ok(true);
        }

        std::thread::sleep(interval);
    }
    writeln!(f, "Timeout waiting for required working servers").ok();
    Ok(false)
}

fn check_socks5_health(host: &str, port: u16, timeout: Duration) -> (bool, Option<String>) {
    // Minimal SOCKS5 handshake: NO AUTH, CONNECT 8.8.8.8:53 (IPv4)
    let addr = format!("{}:{}", host, port);
    let mut s = match TcpStream::connect_timeout(&addr.parse().unwrap(), timeout) {
        Ok(x) => x,
        Err(e) => return (false, Some(format!("connect: {}", e))),
    };
    let _ = s.set_read_timeout(Some(timeout));
    let _ = s.set_write_timeout(Some(timeout));

    // METHODS: VER=5, NMETHODS=1, METHOD=0x00
    if let Err(e) = s.write_all(&[0x05, 0x01, 0x00]) {
        return (false, Some(format!("write methods: {}", e)));
    }
    let mut resp = [0u8; 2];
    if let Err(e) = s.read_exact(&mut resp) {
        return (false, Some(format!("read methods: {}", e)));
    }
    if resp[0] != 0x05 || resp[1] == 0xFF {
        return (false, Some(format!("bad method reply: {:?}", resp)));
    }

    // CONNECT request: 8.8.8.8:53
    let req = [
        0x05, 0x01, 0x00, 0x01, // VER, CMD=CONNECT, RSV, ATYP=IPv4
        8, 8, 8, 8, // IP
        0, 53, // PORT big-endian
    ];
    if let Err(e) = s.write_all(&req) {
        return (false, Some(format!("write connect: {}", e)));
    }

    // Reply: VER, REP, RSV, ATYP
    let mut head = [0u8; 4];
    if let Err(e) = s.read_exact(&mut head) {
        return (false, Some(format!("read reply head: {}", e)));
    }
    if head[0] != 0x05 {
        return (false, Some(format!("bad reply ver: {}", head[0])));
    }
    if head[1] != 0x00 {
        return (false, Some(format!("rep=0x{:02x}", head[1])));
    }

    // Skip BND.ADDR depending on ATYP, then BND.PORT(2)
    match head[3] {
        0x01 => {
            let mut buf = [0u8; 4];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short ipv4".into()));
            }
        }
        0x03 => {
            let mut ln = [0u8; 1];
            if s.read_exact(&mut ln).is_err() {
                return (false, Some("short domain len".into()));
            }
            let mut buf = vec![0u8; ln[0] as usize];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short domain".into()));
            }
        }
        0x04 => {
            let mut buf = [0u8; 16];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short ipv6".into()));
            }
        }
        x => return (false, Some(format!("unknown atyp {}", x))),
    }
    let mut portbuf = [0u8; 2];
    if s.read_exact(&mut portbuf).is_err() {
        return (false, Some("short port".into()));
    }

    (true, None)
}

fn find_pid_by_listen_port(port: u16, log_path: &Path) -> Result<Option<String>> {
    let out = shell::run("ss", &["-ltnp"], Capture::Stdout).unwrap_or_default();

    let needle = format!(":{}", port);
    // shell::run returns (exit_code, stdout). Parse only stdout.
    let pid_opt = out
        .1
        .lines()
        .find(|l| l.contains(&needle))
        .and_then(extract_pid);

    let mut f = OpenOptions::new().create(true).append(true).open(log_path)?;
    if let Some(ref pid) = pid_opt {
        writeln!(f, "Found listener for port {} pid={}", port, pid)?;
    } else {
        writeln!(f, "No listener for port {}", port)?;
    }

    Ok(pid_opt)
}


fn extract_pid(line: &str) -> Option<String> {
    // ss output often: users:(("proc",pid=1234,fd=...))
    let idx = line.find("pid=")?;
    let tail = &line[idx + 4..];
    let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

fn sanitize_for_filename(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else {
            out.push('_');
        }
    }
    // limit length
    if out.len() > 64 {
        out.truncate(64);
    }
    out
}


fn truncate_file(p: &Path) -> Result<()> {
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)
        .with_context(|| format!("truncate {}", p.display()))?;
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new(BIN_DIR).join(name);
    if p.is_file() {
        return Ok(p);
    }
    // some builds may ship with .bin suffix
    let p2 = Path::new(BIN_DIR).join(format!("{}.bin", name));
    if p2.is_file() {
        return Ok(p2);
    }
    anyhow::bail!("binary not found: {}", p.display())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse {}", path.display()))?;
    Ok(v)
}

fn ensure_dir<P: AsRef<Path>>(p: P) -> Result<()> {
    let p = p.as_ref();
    fs::create_dir_all(p).with_context(|| format!("mkdir {}", p.display()))?;
    Ok(())
}

fn ensure_file_empty(path: &Path) -> Result<()> {
    if path.is_file() {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    fs::write(path, b"")
        .with_context(|| format!("create empty file {}", path.display()))?;
    Ok(())
}
