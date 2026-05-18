use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
use crate::settings;

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const MYPROXY_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myproxy";
const MYPROXY_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myproxy/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/myproxy/active.json";
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ProfileState {
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ActiveProfiles {
    #[serde(default)]
    pub profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    #[serde(default = "default_t2s_port")]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self { t2s_port: default_t2s_port(), t2s_web_port: default_t2s_web_port() }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProxyConfig {
    #[serde(default)]
    pub host: String,
    /// Upstream SOCKS5 port definition.
    ///
    /// Backward compatible formats accepted by the API/runtime:
    /// - number: 1080
    /// - string: "1080" or "1080,1081,1082"
    /// - array: [1080, 1081, 1082]
    #[serde(default = "default_proxy_port_value")]
    pub port: Value,
    /// Optional explicit multi-port list. If non-empty, it has priority over `port`.
    #[serde(default)]
    pub ports: Vec<u16>,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub pass: String,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self {
            host: "127.0.0.1".to_string(),
            port: default_proxy_port_value(),
            ports: Vec::new(),
            user: String::new(),
            pass: String::new(),
        }
    }
}

fn default_proxy_port_value() -> Value { Value::from(1080u16) }

impl ProxyConfig {
    pub fn effective_ports(&self) -> Result<Vec<u16>> {
        let mut out = Vec::<u16>::new();
        if !self.ports.is_empty() {
            out.extend(self.ports.iter().copied());
        } else {
            collect_ports_from_value(&self.port, &mut out)?;
        }
        normalize_ports(out)
    }

    pub fn effective_ports_csv(&self) -> Result<String> {
        Ok(self.effective_ports()?.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","))
    }
}

fn collect_ports_from_value(v: &Value, out: &mut Vec<u16>) -> Result<()> {
    match v {
        Value::Number(n) => {
            let Some(raw) = n.as_u64() else { anyhow::bail!("proxy port must be a valid number"); };
            let port = u16::try_from(raw).map_err(|_| anyhow::anyhow!("proxy port out of range: {raw}"))?;
            out.push(port);
        }
        Value::String(s) => {
            for part in s.split(',') {
                let part = part.trim();
                if part.is_empty() { continue; }
                let raw: u16 = part.parse().map_err(|_| anyhow::anyhow!("invalid proxy port: {part}"))?;
                out.push(raw);
            }
        }
        Value::Array(items) => {
            for item in items {
                collect_ports_from_value(item, out)?;
            }
        }
        Value::Null => {}
        _ => anyhow::bail!("proxy port must be a number, comma-separated string, or array"),
    }
    Ok(())
}

fn normalize_ports(ports: Vec<u16>) -> Result<Vec<u16>> {
    let mut seen = BTreeSet::<u16>::new();
    let mut out = Vec::<u16>::new();
    for port in ports {
        if port == 0 { anyhow::bail!("proxy port must be > 0"); }
        if seen.insert(port) {
            out.push(port);
        }
    }
    if out.is_empty() { anyhow::bail!("proxy port is required"); }
    Ok(out)
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    proxy: ProxyConfig,
    uid_out: PathBuf,
    uid_count: usize,
    t2s_log: PathBuf,
}

fn default_t2s_port() -> u16 { 12348 }
fn default_t2s_web_port() -> u16 { 8004 }

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    fs::create_dir_all(MYPROXY_ROOT).ok();
    fs::create_dir_all(MYPROXY_PROFILE_ROOT).ok();

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveProfiles = match read_json(active_path) {
        Ok(v) => v,
        Err(e) => {
            warn!("myproxy: failed to read active.json (skip): {e:#}");
            return Ok(());
        }
    };

    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("myproxy: no enabled profiles in active.json");
        return Ok(());
    }

    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding_programs(false, false, false, true, false, false)
        .unwrap_or_else(|_| BTreeSet::new());
    let mut own_used = BTreeSet::<u16>::new();
    let mut plans = Vec::<ProfilePlan>::new();

    for name in enabled_names {
        match build_profile_plan(&name, &external_used, &own_used) {
            Ok(Some(plan)) => {
                own_used.insert(plan.setting.t2s_port);
                own_used.insert(plan.setting.t2s_web_port);
                plans.push(plan);
            }
            Ok(None) => {}
            Err(e) => warn!("myproxy: profile '{}' skip: {e:#}", name),
        }
    }

    if plans.is_empty() {
        warn!("myproxy: no runnable profiles after validation");
        return Ok(());
    }

    let t2s_bin = find_bin("t2s")?;
    crate::logging::user_info("myproxy: socks5 profiles");

    for plan in &plans {
        truncate_file(&plan.t2s_log)?;
        spawn_t2s(&t2s_bin, &plan.setting, &plan.proxy, &plan.t2s_log)
            .with_context(|| format!("spawn t2s profile={}", plan.name))?;

        iptables_port::apply(
            &plan.uid_out,
            plan.setting.t2s_port,
            ProtoChoice::Tcp,
            None,
            DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
        )
        .with_context(|| format!("iptables profile={}", plan.name))?;

        info!(
            "myproxy: profile={} apps={} t2s_port={} t2s_web_port={} upstream={}:{} auth={} ",
            plan.name,
            plan.uid_count,
            plan.setting.t2s_port,
            plan.setting.t2s_web_port,
            plan.proxy.host,
            plan.proxy.effective_ports_csv().unwrap_or_else(|_| "?".to_string()),
            (!plan.proxy.user.is_empty() || !plan.proxy.pass.is_empty())
        );
    }

    Ok(())
}

fn build_profile_plan(profile: &str, external_used: &BTreeSet<u16>, own_used: &BTreeSet<u16>) -> Result<Option<ProfilePlan>> {
    ensure_valid_profile_name(profile)?;
    let profile_dir = profile_root(profile);
    if !profile_dir.is_dir() {
        anyhow::bail!("profile directory missing: {}", profile_dir.display());
    }

    let setting_path = profile_dir.join("setting.json");
    let setting: ProfileSetting = read_json(&setting_path)
        .with_context(|| format!("read {}", setting_path.display()))?;

    let proxy_path = profile_dir.join("proxy.json");
    let proxy: ProxyConfig = read_json(&proxy_path)
        .with_context(|| format!("read {}", proxy_path.display()))?;

    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);
    let uid_in = profile_dir.join("app/uid/user_program");
    let uid_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&uid_in)?;
    ensure_parent_dir(&uid_out)?;
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &uid_out, &uid_in)
        .with_context(|| format!("pkg_uid processing profile={profile}"))?;
    let resolved = count_valid_uid_pairs(&uid_out).unwrap_or(0);
    let has_launch_marker = pkg_uid::file_has_launch_marker(&uid_in).unwrap_or(false);
    if resolved == 0 && !has_launch_marker {
        warn!("myproxy: profile '{}' has no resolved apps", profile);
        return Ok(None);
    }
    if resolved == 0 && has_launch_marker {
        info!("myproxy: profile '{}' uses launch marker; starting without routing app UIDs", profile);
    }

    validate_profile_setting(profile, &setting, &proxy, external_used, own_used)?;
    let t2s_log = profile_t2s_log_path(profile);
    ensure_parent_dir(&t2s_log)?;

    Ok(Some(ProfilePlan { name: profile.to_string(), setting, proxy, uid_out, uid_count: resolved, t2s_log }))
}

fn validate_profile_setting(
    profile: &str,
    setting: &ProfileSetting,
    proxy: &ProxyConfig,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
) -> Result<()> {
    if setting.t2s_port == 0 || setting.t2s_web_port == 0 || setting.t2s_port == setting.t2s_web_port {
        anyhow::bail!("invalid profile ports");
    }
    validate_proxy_config(proxy)?;
    let upstream_ports = proxy.effective_ports()?;
    if upstream_ports.iter().any(|p| *p == setting.t2s_port || *p == setting.t2s_web_port) {
        anyhow::bail!("t2s ports must not match upstream port");
    }
    for port in [setting.t2s_port, setting.t2s_web_port] {
        if external_used.contains(&port) || own_used.contains(&port) {
            anyhow::bail!("port conflict detected: {}", port);
        }
    }
    let uid_out = profile_root(profile).join("app/out/user_program");
    if !uid_out.is_file() {
        anyhow::bail!("uid out file missing: {}", uid_out.display());
    }
    Ok(())
}

pub fn validate_proxy_config(proxy: &ProxyConfig) -> Result<()> {
    if proxy.host.trim().is_empty() {
        anyhow::bail!("proxy host is required");
    }
    let _ = proxy.effective_ports()?;
    let user_empty = proxy.user.trim().is_empty();
    let pass_empty = proxy.pass.trim().is_empty();
    if user_empty ^ pass_empty {
        anyhow::bail!("proxy user and pass must both be set or both be empty");
    }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(p: &Path) -> Result<T> {
    let raw = fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?;
    let v: T = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(v)
}

fn ensure_dir(p: &str) -> Result<()> {
    fs::create_dir_all(p).with_context(|| format!("mkdir {p}"))?;
    Ok(())
}

fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn ensure_file_empty(p: &Path) -> Result<()> {
    if !p.exists() {
        ensure_parent_dir(p)?;
        fs::write(p, "").with_context(|| format!("write {}", p.display()))?;
    }
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
        if line.is_empty() || line.starts_with('#') { continue; }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}

fn profile_root(profile: &str) -> PathBuf {
    Path::new(MYPROXY_PROFILE_ROOT).join(profile)
}

fn profile_t2s_log_path(profile: &str) -> PathBuf {
    profile_root(profile).join("log/t2s.log")
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if name.is_empty() { anyhow::bail!("profile name is empty"); }
    if name.len() > 64 { anyhow::bail!("profile name too long"); }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') {
        anyhow::bail!("profile name must contain only English letters/digits/_/-");
    }
    Ok(())
}

fn spawn_t2s(bin: &Path, setting: &ProfileSetting, proxy: &ProxyConfig, log_path: &Path) -> Result<()> {
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("--listen-addr")
        .arg("127.0.0.1")
        .arg("--listen-port")
        .arg(setting.t2s_port.to_string())
        .arg("--socks-host")
        .arg(proxy.host.trim())
        .arg("--socks-port")
        .arg(proxy.effective_ports_csv()?)
        .arg("--max-conns")
        .arg("1200")
        .arg("--idle-timeout")
        .arg("400")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket")
        .arg("--web-port")
        .arg(setting.t2s_web_port.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    if !proxy.user.trim().is_empty() || !proxy.pass.trim().is_empty() {
        cmd.arg("--socks-user").arg(proxy.user.trim())
            .arg("--socks-pass").arg(proxy.pass.trim());
    }

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned t2s pid={} listen_addr=127.0.0.1 listen_port={} socks_host={} socks_port={} web_port={} log={}",
        child.id(), setting.t2s_port, proxy.host, proxy.effective_ports_csv().unwrap_or_else(|_| "?".to_string()), setting.t2s_web_port, log_path.display()
    );
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() { Ok(p) } else { anyhow::bail!("binary not found: {}", p.display()) }
}

fn truncate_file(p: &Path) -> Result<()> {
    ensure_parent_dir(p)?;
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)?;
    Ok(())
}
