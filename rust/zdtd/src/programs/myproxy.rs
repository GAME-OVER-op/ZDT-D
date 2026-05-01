use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
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
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub user: String,
    #[serde(default)]
    pub pass: String,
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self { host: "127.0.0.1".to_string(), port: 1080, user: String::new(), pass: String::new() }
    }
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

    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding_programs(false, false, false, true)
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
            plan.proxy.port,
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
    if resolved == 0 {
        warn!("myproxy: profile '{}' has no resolved apps", profile);
        return Ok(None);
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
    if setting.t2s_port == proxy.port || setting.t2s_web_port == proxy.port {
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
    if proxy.port == 0 {
        anyhow::bail!("proxy port must be > 0");
    }
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
        .arg(proxy.port.to_string())
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
        child.id(), setting.t2s_port, proxy.host, proxy.port, setting.t2s_web_port, log_path.display()
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
