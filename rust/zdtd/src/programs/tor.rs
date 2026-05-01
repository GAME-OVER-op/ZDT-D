use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
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

fn deserialize_boolish<'de, D>(deserializer: D) -> std::result::Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{Error as _, Unexpected};
    let v = serde_json::Value::deserialize(deserializer)?;
    match v {
        serde_json::Value::Bool(b) => Ok(b),
        serde_json::Value::Number(n) => match n.as_i64() {
            Some(0) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(D::Error::invalid_value(Unexpected::Other("number"), &"bool or 0/1")),
        },
        serde_json::Value::String(s) => match s.trim().to_ascii_lowercase().as_str() {
            "true" | "1" => Ok(true),
            "false" | "0" => Ok(false),
            _ => Err(D::Error::invalid_value(Unexpected::Str(&s), &"bool or 0/1")),
        },
        _ => Err(D::Error::invalid_value(Unexpected::Other("non-bool"), &"bool or 0/1")),
    }
}

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

fn write_text_atomic(p: &Path, content: &str) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, content).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, p).with_context(|| format!("rename {} -> {}", tmp.display(), p.display()))?;
    Ok(())
}

fn write_json_atomic<T: Serialize>(p: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(p, &txt)
}

fn ensure_empty_file(p: &Path) -> Result<()> {
    if !p.exists() {
        write_text_atomic(p, "")?;
    }
    Ok(())
}

pub fn ensure_layout() -> Result<()> {
    let root = root_dir();
    fs::create_dir_all(root.join("app/uid")).with_context(|| format!("mkdir {}", root.join("app/uid").display()))?;
    fs::create_dir_all(root.join("app/out")).with_context(|| format!("mkdir {}", root.join("app/out").display()))?;
    fs::create_dir_all(root.join("log")).with_context(|| format!("mkdir {}", root.join("log").display()))?;

    let enabled_path = enabled_path();
    if !enabled_path.exists() {
        write_json_atomic(&enabled_path, &EnabledJson::default())?;
    } else {
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        write_json_atomic(&enabled_path, &current)?;
    }

    let setting_path = setting_path();
    if !setting_path.exists() {
        write_json_atomic(&setting_path, &Setting::default())?;
    }

    let torrc = torrc_path();
    if !torrc.exists() {
        write_text_atomic(&torrc, DEFAULT_TORRC)?;
    }

    ensure_empty_file(&uid_program_path())?;
    ensure_empty_file(&out_program_path())?;
    ensure_empty_file(&tor_log_path())?;
    ensure_empty_file(&t2s_log_path())?;
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
    write_json_atomic(&enabled_path(), &v)?;
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
    write_json_atomic(&setting_path(), setting)
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
    write_text_atomic(&torrc_path(), &normalized)
}

pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    match fs::read_to_string(uid_program_path()) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read uid_program failed: {e}")),
    }
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    write_text_atomic(&uid_program_path(), content)
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
    let raw = match fs::read_to_string(out_program_path()) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };
    let mut out = std::collections::BTreeSet::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() { continue; }
        let Some((_, rhs)) = s.rsplit_once('=') else { continue; };
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.insert(uid);
            }
        }
    }
    Ok(out.into_iter().collect())
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
    ensure_file(TOR_BIN)?;
    ensure_file(LYREBIRD_BIN)?;
    let setting = load_setting()?;
    let torrc = normalize_torrc_content(&read_torrc_text()?);
    let socks_port = validate_torrc_ready(&torrc)?;
    validate_setting(&setting, socks_port)?;
    Ok(())
}

pub fn start_if_enabled() -> Result<()> {
    ensure_layout()?;
    let enabled = load_enabled_json()?.is_enabled();
    if !enabled {
        info!("tor disabled (enabled.json enabled=0) -> skip");
        return Ok(());
    }

    validate_enable_requirements()?;
    let setting = load_setting()?;

    let torrc_raw = read_torrc_text()?;
    let normalized_torrc = normalize_torrc_content(&torrc_raw);
    if normalized_torrc != torrc_raw {
        write_torrc_text(&torrc_raw)?;
    }
    let socks_port = validate_torrc_ready(&normalized_torrc)?;
    validate_setting(&setting, socks_port)?;

    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding_programs(false, false, true, false)
        .unwrap_or_default();
    for port in [socks_port, setting.t2s_port, setting.t2s_web_port] {
        if external_used.contains(&port) {
            anyhow::bail!("port conflict detected: {}", port);
        }
    }

    let _ = rebuild_out_program()?;
    let resolved = count_valid_uid_pairs(&out_program_path()).unwrap_or(0);
    if resolved == 0 {
        warn!("tor: no resolved apps -> skip start/iptables");
        return Ok(());
    }

    crate::logging::user_info("Tor: запуск");

    truncate_file(&tor_log_path())?;
    truncate_file(&t2s_log_path())?;

    spawn_tor(&torrc_path(), &tor_log_path())?;
    let t2s_bin = find_bin("t2s")?;
    spawn_t2s(
        &t2s_bin,
        "127.0.0.1",
        setting.t2s_port,
        setting.t2s_web_port,
        &socks_port.to_string(),
        &t2s_log_path(),
    )?;

    iptables_port::apply(
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
    Ok(())
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
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(TOR_BIN);
    cmd.arg("-f")
        .arg(torrc)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", TOR_BIN))?;
    info!("spawned tor pid={} cfg={} log={}", child.id(), torrc.display(), log_path.display());

    std::thread::sleep(std::time::Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("tor pid={} exited quickly; check log {}", child.id(), log_path.display());
    }
    Ok(())
}

fn spawn_t2s(
    bin: &Path,
    listen_addr: &str,
    listen_port: u16,
    web_port: u16,
    socks_ports_csv: &str,
    log_path: &Path,
) -> Result<()> {
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("--listen-addr")
        .arg(listen_addr)
        .arg("--listen-port")
        .arg(listen_port.to_string())
        .arg("--socks-host")
        .arg("127.0.0.1")
        .arg("--socks-port")
        .arg(socks_ports_csv)
        .arg("--max-conns")
        .arg("1200")
        .arg("--idle-timeout")
        .arg("400")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket")
        .arg("--web-port")
        .arg(web_port.to_string())
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
    info!(
        "spawned t2s pid={} listen_addr={} listen_port={} socks_ports={} web_port={} log={}",
        child.id(), listen_addr, listen_port, socks_ports_csv, web_port, log_path.display()
    );
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() {
        Ok(p)
    } else {
        anyhow::bail!("binary not found: {}", p.display())
    }
}

fn truncate_file(p: &Path) -> Result<()> {
    ensure_parent_dir(p)?;
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)?;
    Ok(())
}

fn count_valid_uid_pairs(path: &Path) -> Result<usize> {
    if !path.is_file() {
        return Ok(0);
    }
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut n = 0usize;
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}

fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn ensure_file(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_file() {
        anyhow::bail!("file missing: {}", path.display());
    }
    Ok(())
}
