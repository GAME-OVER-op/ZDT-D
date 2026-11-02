use anyhow::{Context, Result};
use crate::common::config::deserialize_boolish;
use crate::common::apps::{count_valid_uid_pairs};
use crate::common::components::{find_module_binary};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, LogMode, spawn_detached_logged};
use crate::network::t2s::{apply_t2s_routing, T2sSpawnConfig, spawn_t2s_proxy};
use crate::common::fs::truncate_file;
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
};

use crate::platform::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::iptables_port::{DpiTunnelOptions, ProtoChoice};
use crate::settings;

const TOR_BIN: &str = "/data/adb/modules/ZDT-D/bin/torproxy";
const LYREBIRD_BIN: &str = "/data/adb/modules/ZDT-D/bin/lyrebird";
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

const TOR_DATA_DIR_LINE: &str = "DataDirectory /data/adb/modules/ZDT-D/working_folder/tor/";
const DEFAULT_CLIENT_TRANSPORT_PLUGIN_LINE: &str = "ClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel exec /data/adb/modules/ZDT-D/bin/lyrebird";
const LEGACY_CLIENT_TRANSPORT_PLUGIN_LINE: &str = "ClientTransportPlugin obfs4 exec /data/adb/modules/ZDT-D/bin/obfs4proxy";
const SUPPORTED_BRIDGE_PROTOCOLS: &[&str] = &["obfs4", "webtunnel", "snowflake", "meek_lite"];
const DEFAULT_TORRC: &str = "DataDirectory /data/adb/modules/ZDT-D/working_folder/tor/\nSocksPort 127.0.0.1:9050\nLog notice stdout\n\nUseBridges 1\nClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel exec /data/adb/modules/ZDT-D/bin/lyrebird\n";



fn serialize_bool_as_bool<S>(value: &bool, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.serialize_bool(*value)
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnabledJson {
    #[serde(default, deserialize_with = "deserialize_boolish", serialize_with = "serialize_bool_as_bool")]
    pub enabled: bool,
}

impl Default for EnabledJson {
    fn default() -> Self {
        Self { enabled: false }
    }
}

impl EnabledJson {
    pub fn normalized(&self) -> Self {
        Self { enabled: self.enabled }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Setting {
    #[serde(default = "default_t2s_port")]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
}

impl Default for Setting {
    fn default() -> Self {
        Self {
            t2s_port: default_t2s_port(),
            t2s_web_port: default_t2s_web_port(),
        }
    }
}

fn default_t2s_port() -> u16 { 12347 }
fn default_t2s_web_port() -> u16 { 8002 }

fn root_dir() -> PathBuf { settings::working_program_root_path("tor") }
fn enabled_path() -> PathBuf { settings::tor_enabled_json_path() }
fn setting_path() -> PathBuf { settings::tor_setting_json_path() }
fn torrc_path() -> PathBuf { settings::tor_torrc_path() }
fn uid_program_path() -> PathBuf { settings::tor_uid_program_path() }
fn out_program_path() -> PathBuf { settings::tor_out_program_path() }
fn tor_log_path() -> PathBuf { root_dir().join("log/tor.log") }
fn t2s_log_path() -> PathBuf { root_dir().join("log/t2s.log") }






pub fn ensure_layout() -> Result<()> {
    let root = root_dir();
    fs::create_dir_all(root.join("app/uid")).with_context(|| format!("mkdir {}", root.join("app/uid").display()))?;
    fs::create_dir_all(root.join("app/out")).with_context(|| format!("mkdir {}", root.join("app/out").display()))?;
    fs::create_dir_all(root.join("log")).with_context(|| format!("mkdir {}", root.join("log").display()))?;

    let enabled_path = enabled_path();
    if !enabled_path.exists() {
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &EnabledJson::default())?;
    } else {
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        crate::common::fs::write_json_pretty_atomic_context(&enabled_path, &current)?;
    }

    let setting_path = setting_path();
    if !setting_path.exists() {
        crate::common::fs::write_json_pretty_atomic_context(&setting_path, &Setting::default())?;
    }

    let torrc = torrc_path();
    if !torrc.exists() {
        crate::common::fs::write_text_atomic_context(&torrc, DEFAULT_TORRC)?;
    }

    crate::common::fs::ensure_empty_file_atomic_context(&uid_program_path())?;
    crate::common::fs::ensure_empty_file_atomic_context(&out_program_path())?;
    crate::common::fs::ensure_empty_file_atomic_context(&tor_log_path())?;
    crate::common::fs::ensure_empty_file_atomic_context(&t2s_log_path())?;
    Ok(())
}

pub fn load_enabled_json() -> Result<EnabledJson> {
    ensure_layout()?;
    let raw = fs::read_to_string(enabled_path()).with_context(|| format!("read {}", enabled_path().display()))?;
    match serde_json::from_str::<EnabledJson>(&raw) {
        Ok(v) => Ok(v.normalized()),
        Err(_) => Ok(EnabledJson::default()),
    }
}

pub fn save_enabled_value(enabled: bool) -> Result<EnabledJson> {
    ensure_layout()?;
    let v = EnabledJson { enabled }.normalized();
    crate::common::fs::write_json_pretty_atomic_context(&enabled_path(), &v)?;
    Ok(v)
}

pub fn load_setting() -> Result<Setting> {
    ensure_layout()?;
    let raw = fs::read_to_string(setting_path()).with_context(|| format!("read {}", setting_path().display()))?;
    match serde_json::from_str::<Setting>(&raw) {
        Ok(v) => Ok(v),
        Err(_) => Ok(Setting::default()),
    }
}

pub fn save_setting(setting: &Setting) -> Result<()> {
    ensure_layout()?;
    crate::common::fs::write_json_pretty_atomic_context(&setting_path(), setting)
}

pub fn read_torrc_text() -> Result<String> {
    ensure_layout()?;
    match fs::read_to_string(torrc_path()) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(DEFAULT_TORRC.to_string()),
        Err(e) => Err(anyhow::anyhow!("read torrc failed: {e}")),
    }
}

pub fn write_torrc_text(content: &str) -> Result<()> {
    ensure_layout()?;
    let normalized = normalize_torrc_content(content);
    crate::common::fs::write_text_atomic_context(&torrc_path(), &normalized)
}

pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    crate::common::apps::read_optional_uid_program_text(&uid_program_path())
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    crate::common::fs::write_text_atomic_context(&uid_program_path(), content)
}

pub fn rebuild_out_program() -> Result<Vec<u32>> {
    ensure_layout()?;
    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &out_program_path(), &uid_program_path())
        .with_context(|| "tor uid parsing")?;
    read_out_uids()
}

pub fn read_out_uids() -> Result<Vec<u32>> {
    ensure_layout()?;
    crate::common::apps::read_out_uids(&out_program_path())
}

pub fn parse_socks_port_from_file(path: &Path) -> Result<u16> {
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    parse_socks_port_from_str(&raw)
}

pub fn parse_socks_port_from_str(raw: &str) -> Result<u16> {
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        if !lower.starts_with("socksport") {
            continue;
        }
        let mut parts = line.split_whitespace();
        let key = parts.next().unwrap_or("");
        if !key.eq_ignore_ascii_case("SocksPort") {
            continue;
        }
        let value = parts.next().ok_or_else(|| anyhow::anyhow!("SocksPort value is missing"))?;
        let addr = value.trim();
        let (host, port_s) = addr.rsplit_once(':').ok_or_else(|| anyhow::anyhow!("SocksPort must be 127.0.0.1:PORT"))?;
        if host.trim() != "127.0.0.1" {
            anyhow::bail!("SocksPort host must be 127.0.0.1");
        }
        let port = port_s.trim().parse::<u16>().map_err(|_| anyhow::anyhow!("invalid SocksPort port"))?;
        if port == 0 {
            anyhow::bail!("SocksPort port must be > 0");
        }
        return Ok(port);
    }
    anyhow::bail!("SocksPort 127.0.0.1:PORT is required")
}

fn supported_bridge_protocol_from_short_line(line: &str) -> Option<&'static str> {
    let first = line.split_whitespace().next()?;
    SUPPORTED_BRIDGE_PROTOCOLS
        .iter()
        .copied()
        .find(|proto| first.eq_ignore_ascii_case(proto))
}

fn supported_bridge_protocol_from_bridge_line(line: &str) -> Option<&'static str> {
    let mut parts = line.split_whitespace();
    let key = parts.next()?;
    if !key.eq_ignore_ascii_case("Bridge") {
        return None;
    }
    let proto = parts.next()?;
    SUPPORTED_BRIDGE_PROTOCOLS
        .iter()
        .copied()
        .find(|supported| proto.eq_ignore_ascii_case(supported))
}

pub fn bridge_count_from_str(raw: &str) -> usize {
    raw.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#') && !line.starts_with(';'))
        .filter(|line| {
            supported_bridge_protocol_from_bridge_line(line).is_some()
                || supported_bridge_protocol_from_short_line(line).is_some()
        })
        .count()
}

pub fn normalize_torrc_content(raw: &str) -> String {
    let had_trailing_newline = raw.ends_with('\n');
    let mut out = Vec::new();
    let mut saw_data_directory = false;

    for line in raw.lines() {
        let trimmed = line.trim();
        if !trimmed.is_empty() && !trimmed.starts_with('#') && !trimmed.starts_with(';') {
            if trimmed.starts_with("DataDirectory") {
                if !saw_data_directory {
                    out.push(TOR_DATA_DIR_LINE.to_string());
                    saw_data_directory = true;
                }
                continue;
            }
            if trimmed == LEGACY_CLIENT_TRANSPORT_PLUGIN_LINE {
                out.push(DEFAULT_CLIENT_TRANSPORT_PLUGIN_LINE.to_string());
                continue;
            }
            if supported_bridge_protocol_from_short_line(trimmed).is_some() {
                out.push(format!("Bridge {}", trimmed));
                continue;
            }
        }
        out.push(line.to_string());
    }

    if !saw_data_directory {
        out.insert(0, TOR_DATA_DIR_LINE.to_string());
    }

    let mut joined = out.join("\n");
    if had_trailing_newline || (!joined.is_empty() && !joined.ends_with('\n')) {
        joined.push('\n');
    }
    joined
}

fn use_bridges_enabled(raw: &str) -> bool {
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        let mut parts = line.split_whitespace();
        let key = parts.next().unwrap_or("");
        if !key.eq_ignore_ascii_case("UseBridges") {
            continue;
        }
        return parts.next().map(|v| v.trim() == "1").unwrap_or(false);
    }
    false
}

pub fn validate_torrc_ready(raw: &str) -> Result<u16> {
    let socks_port = parse_socks_port_from_str(raw)?;
    if !use_bridges_enabled(raw) {
        anyhow::bail!("UseBridges 1 is required");
    }
    if bridge_count_from_str(raw) < 1 {
        anyhow::bail!("at least one bridge is required");
    }
    Ok(socks_port)
}

pub fn validate_enable_toggle_requirements() -> Result<()> {
    ensure_layout()?;
    let torrc = normalize_torrc_content(&read_torrc_text()?);
    if bridge_count_from_str(&torrc) < 1 {
        anyhow::bail!("at least one bridge is required");
    }
    Ok(())
}

pub fn validate_enable_requirements() -> Result<()> {
    ensure_layout()?;
    crate::common::fs::require_file_str(TOR_BIN)?;
    crate::common::fs::require_file_str(LYREBIRD_BIN)?;
    let setting = load_setting()?;
    let torrc = normalize_torrc_content(&read_torrc_text()?);
    let socks_port = validate_torrc_ready(&torrc)?;
    let _ = rebuild_out_program()?;
    if has_routed_app_outputs() {
        validate_setting(&setting, socks_port)?;
    }
    Ok(())
}

pub fn start_if_enabled() -> Result<()> {
    ensure_layout()?;
    let enabled = load_enabled_json()?.is_enabled();
    if !enabled {
        info!("tor disabled (enabled.json enabled=0) -> skip");
        return Ok(());
    }

    crate::common::fs::require_file_str(TOR_BIN)?;
    crate::common::fs::require_file_str(LYREBIRD_BIN)?;
    let setting = load_setting()?;

    let torrc_raw = read_torrc_text()?;
    let normalized_torrc = normalize_torrc_content(&torrc_raw);
    if normalized_torrc != torrc_raw {
        write_torrc_text(&torrc_raw)?;
    }
    let socks_port = validate_torrc_ready(&normalized_torrc)?;

    let _ = rebuild_out_program()?;
    let resolved = count_valid_uid_pairs(&out_program_path()).unwrap_or(0);
    let has_launch_marker = crate::common::apps::file_has_launch_marker(&uid_program_path()).unwrap_or(false);
    if resolved == 0 && !has_launch_marker {
        warn!("tor: no resolved apps -> skip start/iptables");
        return Ok(());
    }
    let needs_t2s = resolved > 0 || has_launch_marker;
    if resolved == 0 && has_launch_marker {
        info!("tor: launch marker present, starting tor+t2s without routed app UIDs yet");
    }

    if needs_t2s {
        validate_setting(&setting, socks_port)?;
    }

    let external_used = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_programs(false, false, true, false, false, false, false, false)
        .unwrap_or_default();
    let mut ports_to_check = vec![socks_port];
    if needs_t2s {
        ports_to_check.push(setting.t2s_port);
        ports_to_check.push(setting.t2s_web_port);
    }
    for port in ports_to_check {
        if external_used.contains(&port) {
            anyhow::bail!("port conflict detected: {}", port);
        }
    }

    crate::logging::user_info("Tor: запуск");

    truncate_file(&tor_log_path())?;
    spawn_tor(&torrc_path(), &tor_log_path())?;

    if needs_t2s {
        truncate_file(&t2s_log_path())?;
        let t2s_bin = find_module_binary("t2s")?;
        let t2s_log = t2s_log_path();
        let t2s_socks_ports_csv = socks_port.to_string();
        spawn_t2s_proxy(T2sSpawnConfig {
            bin: &t2s_bin,
            listen_addr: "127.0.0.1",
            listen_port: setting.t2s_port,
            socks_host: "127.0.0.1",
            socks_ports_csv: &t2s_socks_ports_csv,
            web_port: Some(setting.t2s_web_port),
            program: "tor",
            profile: "main",
            scope: "program/tor",
            log_path: &t2s_log,
            ..Default::default()
        })?;

        apply_t2s_routing(
            &out_program_path(),
            setting.t2s_port,
            ProtoChoice::Tcp,
            None,
            DpiTunnelOptions {
                port_preference: 1,
                ..DpiTunnelOptions::default()
            },
        )
        .with_context(|| "iptables tor")?;

        info!(
            "tor: apps={} socks_port={} t2s_port={} t2s_web_port={}",
            resolved,
            socks_port,
            setting.t2s_port,
            setting.t2s_web_port,
        );
    } else {
        info!(
            "tor: apps=0 launch_marker={} socks_port={} t2s=disabled",
            has_launch_marker,
            socks_port,
        );
    }
    Ok(())
}

pub fn has_routed_app_outputs() -> bool {
    count_valid_uid_pairs(&out_program_path()).unwrap_or(0) > 0
}

fn validate_setting(setting: &Setting, socks_port: u16) -> Result<()> {
    if setting.t2s_port == 0 || setting.t2s_web_port == 0 || setting.t2s_port == setting.t2s_web_port {
        anyhow::bail!("invalid t2s ports");
    }
    if setting.t2s_port == socks_port || setting.t2s_web_port == socks_port {
        anyhow::bail!("t2s ports must not match SocksPort");
    }
    Ok(())
}

fn spawn_tor(torrc: &Path, log_path: &Path) -> Result<()> {
    let mut cmd = Command::new(TOR_BIN);
    cmd.arg("-f").arg(torrc);

    let child = spawn_detached_logged(
        &mut cmd,
        log_path,
        LogMode::Truncate,
        || format!("spawn {}", TOR_BIN),
    )?;
    info!("spawned tor pid={} cfg={} log={}", child.id(), torrc.display(), log_path.display());

    std::thread::sleep(std::time::Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("tor pid={} exited quickly; check log {}", child.id(), log_path.display());
    }
    Ok(())
}


pub fn main_pids_exact() -> Vec<i32> {
    let torrc = torrc_path().display().to_string();
    let pattern = format!("^torproxy -f {}$", torrc);
    let mut pids = pgrep_full_regex(&pattern);
    if pids.is_empty() {
        let expected = format!("torproxy -f {}", torrc);
        pids = ps_ef_pids_where(|line| line.contains(&expected));
    }
    filter_live_pids(&mut pids);
    pids
}

pub fn helper_pids_exact() -> Vec<i32> {
    let pattern = format!("^{}", LYREBIRD_BIN);
    let mut pids = pgrep_full_regex(&pattern);
    if pids.is_empty() {
        pids = ps_ef_pids_where(|line| line.contains(LYREBIRD_BIN));
    }
    filter_live_pids(&mut pids);
    pids
}
