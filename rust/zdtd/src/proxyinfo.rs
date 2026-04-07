use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
};

use crate::{
    android::pkg_uid::{self, Mode as UidMode, Sha256Tracker},
    logging,
    settings,
    shell::{self, Capture},
};

const PROXY_CHAIN: &str = "ZDT_PROXYINFO";
const IPT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const UID_TRACKER_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/flag.sha256";
const API_PORT: u16 = 1006;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnabledJson {
    #[serde(default)]
    pub enabled: u8,
}

impl Default for EnabledJson {
    fn default() -> Self {
        Self { enabled: 0 }
    }
}

impl EnabledJson {
    pub fn normalized(&self) -> Self {
        Self {
            enabled: if self.enabled == 0 { 0 } else { 1 },
        }
    }

    pub fn is_enabled(&self) -> bool {
        self.enabled != 0
    }
}

#[derive(Debug, Deserialize, Default)]
struct ProfileState {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilesActive {
    #[serde(default)]
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize, Default)]
struct EnabledActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct ProfilePortJson {
    #[serde(default)]
    port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct OperaActive {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Deserialize, Default)]
struct OperaPortJson {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default)]
    opera_start_port: u16,
    #[serde(default)]
    byedpi_port: u16,
}

fn default_operaproxy_t2s_web_port() -> u16 {
    8000
}

#[derive(Debug, Deserialize, Default)]
struct SingboxProfileSetting {
    #[serde(default)]
    t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    t2s_web_port: u16,
}

#[derive(Debug, Deserialize, Default)]
struct SingboxServerSetting {
    #[serde(default)]
    enabled: bool,
    #[serde(default)]
    port: u16,
}

fn default_t2s_web_port() -> u16 {
    8001
}

fn read_json_file<T: for<'de> Deserialize<'de> + Default>(p: &Path) -> Result<T> {
    let raw = fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?;
    let v: T = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(v)
}

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
    let root = settings::proxyinfo_root_path();
    fs::create_dir_all(&root).with_context(|| format!("mkdir {}", root.display()))?;

    let enabled_path = settings::proxyinfo_enabled_json_path();
    if !enabled_path.exists() {
        write_json_atomic(&enabled_path, &EnabledJson::default())?;
    } else {
        // Normalize existing file; invalid JSON falls back to default and gets rewritten.
        let current = match fs::read_to_string(&enabled_path) {
            Ok(raw) => serde_json::from_str::<EnabledJson>(&raw).unwrap_or_default().normalized(),
            Err(_) => EnabledJson::default(),
        };
        write_json_atomic(&enabled_path, &current)?;
    }

    ensure_empty_file(&settings::proxyinfo_uid_program_path())?;
    ensure_empty_file(&settings::proxyinfo_out_program_path())?;
    Ok(())
}

pub fn load_enabled_json() -> Result<EnabledJson> {
    ensure_layout()?;
    let path = settings::proxyinfo_enabled_json_path();
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    match serde_json::from_str::<EnabledJson>(&raw) {
        Ok(v) => Ok(v.normalized()),
        Err(_) => Ok(EnabledJson::default()),
    }
}

pub fn save_enabled_value(enabled: u8) -> Result<EnabledJson> {
    ensure_layout()?;
    let v = EnabledJson { enabled }.normalized();
    write_json_atomic(&settings::proxyinfo_enabled_json_path(), &v)?;
    Ok(v)
}

pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    match fs::read_to_string(settings::proxyinfo_uid_program_path()) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read uid_program failed: {e}")),
    }
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    write_text_atomic(&settings::proxyinfo_uid_program_path(), content)
}

pub fn read_proxy_packages() -> Result<BTreeSet<String>> {
    let raw = read_uid_program_text()?;
    Ok(parse_package_lines(&raw))
}

fn parse_package_lines(raw: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        out.insert(s.to_string());
    }
    out
}

pub fn rebuild_out_program() -> Result<Vec<u32>> {
    ensure_layout()?;
    let tracker = Sha256Tracker::new(UID_TRACKER_FILE);
    let input = settings::proxyinfo_uid_program_path();
    let output = settings::proxyinfo_out_program_path();
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &output, &input)
        .with_context(|| "proxyInfo uid parsing")?;
    read_out_uids()
}

pub fn read_out_uids() -> Result<Vec<u32>> {
    ensure_layout()?;
    let raw = match fs::read_to_string(settings::proxyinfo_out_program_path()) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };

    let mut out = BTreeSet::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() {
            continue;
        }
        let Some((_, rhs)) = s.rsplit_once('=') else {
            continue;
        };
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.insert(uid);
            }
        }
    }
    Ok(out.into_iter().collect())
}

pub fn is_active() -> bool {
    match shell::run_timeout(
        "iptables",
        &["-C", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN],
        Capture::None,
        IPT_TIMEOUT,
    ) {
        Ok((rc, _)) => rc == 0,
        Err(_) => false,
    }
}

fn table_cmd_ok(args: &[&str]) -> bool {
    match shell::run_timeout("iptables", args, Capture::None, IPT_TIMEOUT) {
        Ok((rc, _)) => rc == 0,
        Err(_) => false,
    }
}

pub fn clear_rules() -> Result<()> {
    // Remove hook(s) first, then flush and delete our chain. Best-effort and idempotent.
    while table_cmd_ok(&["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]) {}
    let _ = shell::run_timeout("iptables", &["-F", PROXY_CHAIN], Capture::None, IPT_TIMEOUT);
    let _ = shell::run_timeout("iptables", &["-X", PROXY_CHAIN], Capture::None, IPT_TIMEOUT);
    Ok(())
}

fn ensure_chain() -> Result<()> {
    if !table_cmd_ok(&["-L", PROXY_CHAIN]) {
        let (rc, out) = shell::run_timeout("iptables", &["-N", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("iptables -N {PROXY_CHAIN} failed: {out}");
        }
    }
    let (rc, out) = shell::run_timeout("iptables", &["-F", PROXY_CHAIN], Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("iptables -F {PROXY_CHAIN} failed: {out}");
    }
    while table_cmd_ok(&["-D", "OUTPUT", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN]) {}
    let (rc, out) = shell::run_timeout(
        "iptables",
        &["-I", "OUTPUT", "1", "-o", "lo", "-p", "tcp", "-d", "127.0.0.1", "-j", PROXY_CHAIN],
        Capture::Both,
        IPT_TIMEOUT,
    )?;
    if rc != 0 {
        anyhow::bail!("iptables -I OUTPUT -> {PROXY_CHAIN} failed: {out}");
    }
    Ok(())
}

fn add_rule_for_chunk(uid: u32, ports: &[u16]) -> Result<()> {
    let ports_csv = ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(",");
    let uid_s = uid.to_string();
    let args = [
        "-A",
        PROXY_CHAIN,
        "-p",
        "tcp",
        "-m",
        "owner",
        "--uid-owner",
        uid_s.as_str(),
        "-m",
        "multiport",
        "--dports",
        ports_csv.as_str(),
        "-j",
        "REJECT",
        "--reject-with",
        "tcp-reset",
    ];
    let (rc, out) = shell::run_timeout("iptables", &args, Capture::Both, IPT_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("iptables add proxyInfo rule failed uid={uid}: {out}");
    }
    Ok(())
}

fn install_rules(uids: &[u32], ports: &[u16]) -> Result<()> {
    clear_rules()?;
    if uids.is_empty() || ports.is_empty() {
        return Ok(());
    }
    ensure_chain()?;
    for &uid in uids {
        for chunk in ports.chunks(15) {
            add_rule_for_chunk(uid, chunk)?;
        }
    }
    Ok(())
}

pub fn refresh_runtime(services_running: bool) -> Result<bool> {
    ensure_layout()?;
    let enabled = load_enabled_json()?.is_enabled();
    if !enabled {
        clear_rules()?;
        return Ok(false);
    }

    let _uids = rebuild_out_program()?;
    if !services_running {
        clear_rules()?;
        return Ok(false);
    }

    let uids = read_out_uids()?;
    if uids.is_empty() {
        clear_rules()?;
        return Ok(false);
    }
    let ports = collect_protected_ports()?;
    if ports.is_empty() {
        clear_rules()?;
        return Ok(false);
    }
    let port_list: Vec<u16> = ports.into_iter().collect();
    install_rules(&uids, &port_list)?;
    logging::info(&format!(
        "proxyInfo active: {} uid(s), {} port(s)",
        uids.len(),
        port_list.len()
    ));
    Ok(true)
}

pub fn collect_protected_ports() -> Result<BTreeSet<u16>> {
    let mut out = BTreeSet::new();
    out.insert(API_PORT);
    collect_byedpi_ports(&mut out)?;
    collect_dpitunnel_ports(&mut out)?;
    collect_operaproxy_ports(&mut out)?;
    collect_singbox_ports(&mut out)?;
    Ok(out)
}

fn working_program_dir(program: &str) -> PathBuf {
    Path::new(settings::MODULE_DIR).join("working_folder").join(program)
}

fn collect_byedpi_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("byedpi").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("byedpi");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_dpitunnel_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("dpitunnel").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("dpitunnel");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let p = root.join(&name).join("port.json");
        if !p.is_file() {
            continue;
        }
        let port: ProfilePortJson = read_json_file(&p).unwrap_or_default();
        if port.port > 0 {
            out.insert(port.port);
        }
    }
    Ok(())
}

fn collect_operaproxy_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("operaproxy").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let active: OperaActive = read_json_file(&active_path).unwrap_or_default();
    if !active.enabled {
        return Ok(());
    }
    let port_path = working_program_dir("operaproxy").join("port.json");
    if !port_path.is_file() {
        return Ok(());
    }
    let ports: OperaPortJson = read_json_file(&port_path).unwrap_or_default();
    if ports.byedpi_port > 0 {
        out.insert(ports.byedpi_port);
    }
    if ports.t2s_port > 0 {
        out.insert(ports.t2s_port);
    }
    out.insert(default_operaproxy_t2s_web_port());

    let sni_path = working_program_dir("operaproxy").join("config/sni.json");
    let count = count_operaproxy_sni(&sni_path);
    if ports.opera_start_port > 0 && count > 0 {
        for i in 0..count {
            if let Some(p) = ports.opera_start_port.checked_add(i as u16) {
                out.insert(p);
            }
        }
    }
    Ok(())
}

fn count_operaproxy_sni(p: &Path) -> usize {
    let raw = match fs::read_to_string(p) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    let v: Value = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(_) => return 0,
    };
    match v {
        Value::Array(arr) => arr.len(),
        _ => 0,
    }
}

fn collect_singbox_ports(out: &mut BTreeSet<u16>) -> Result<()> {
    let active_path = working_program_dir("singbox").join("active.json");
    if !active_path.is_file() {
        return Ok(());
    }
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string());
    let active: ProfilesActive = read_json_file(&active_path).unwrap_or_default();
    let root = working_program_dir("singbox").join("profile");
    for (name, st) in active.profiles {
        if !st.enabled {
            continue;
        }
        let is_hotspot_profile = hotspot_profile.as_deref() == Some(name.as_str());
        let profile_dir = root.join(&name);
        if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
            continue;
        }
        let setting_path = profile_dir.join("setting.json");
        if setting_path.is_file() {
            let setting: SingboxProfileSetting = read_json_file(&setting_path).unwrap_or_default();
            if !is_hotspot_profile && setting.t2s_port > 0 {
                out.insert(setting.t2s_port);
            }
            if !is_hotspot_profile && setting.t2s_web_port > 0 {
                out.insert(setting.t2s_web_port);
            }
        }
        let server_root = profile_dir.join("server");
        if let Ok(rd) = fs::read_dir(&server_root) {
            for ent in rd.flatten() {
                let server_dir = ent.path();
                if !server_dir.is_dir() {
                    continue;
                }
                if server_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) {
                    continue;
                }
                let setting_path = server_dir.join("setting.json");
                if !setting_path.is_file() {
                    continue;
                }
                let setting: SingboxServerSetting = read_json_file(&setting_path).unwrap_or_default();
                if !is_hotspot_profile && setting.enabled && setting.port > 0 {
                    out.insert(setting.port);
                }
            }
        }
    }
    Ok(())
}
