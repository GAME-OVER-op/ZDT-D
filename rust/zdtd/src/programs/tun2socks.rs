use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

use crate::{
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
};

const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const TUN2SOCKS_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/tun2socks";
const TUN2SOCKS_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/tun2socks/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/tun2socks/active.json";
const NETID_BASE: u32 = 21200;
const NETID_MAX: u32 = 21999;
const TUN_WAIT: Duration = Duration::from_secs(15);
const IP_TIMEOUT: Duration = Duration::from_secs(3);
const TUN2SOCKS_NET_BASE: u32 = 0xC612_6400; // 198.18.100.0

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
    pub tun: String,
    pub proxy: String,
    #[serde(default = "default_loglevel")]
    pub loglevel: String,
    #[serde(default)]
    pub udp_timeout: Option<String>,
    #[serde(default)]
    pub fwmark: Option<u32>,
    #[serde(default)]
    pub restapi: Option<String>,
}

fn default_loglevel() -> String { "info".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun9".to_string(),
            proxy: "socks5://127.0.0.1:1080".to_string(),
            loglevel: default_loglevel(),
            udp_timeout: None,
            fwmark: None,
            restapi: None,
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    log_path: PathBuf,
    netid: u32,
    tun_addr: String,
    cidr: String,
}

pub fn root_path() -> PathBuf { PathBuf::from(TUN2SOCKS_ROOT) }
pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(TUN2SOCKS_PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("tun2socks profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(TUN2SOCKS_PROFILE_ROOT)?;
    let active_path = active_path();
    if !active_path.exists() {
        write_json_pretty(&active_path, &ActiveProfiles::default())?;
    }
    Ok(())
}

pub fn read_active() -> Result<ActiveProfiles> {
    ensure_root_layout()?;
    read_json(&active_path())
}

pub fn write_active(active: &ActiveProfiles) -> Result<()> {
    ensure_root_layout()?;
    write_json_pretty(&active_path(), active)
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    read_json(&profile_root(profile).join("setting.json"))
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    validate_setting(setting)?;
    ensure_profile_layout(profile)?;
    write_json_pretty(&profile_root(profile).join("setting.json"), setting)
}

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let setting: ProfileSetting = serde_json::from_value(value).context("bad tun2socks setting.json")?;
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    validate_proxy_url(&setting.proxy)?;
    match setting.loglevel.as_str() {
        "debug" | "info" | "warn" | "error" | "silent" => {}
        _ => bail!("loglevel must be debug/info/warn/error/silent"),
    }
    if let Some(v) = &setting.udp_timeout {
        if v.trim().is_empty() || v.chars().any(|c| c.is_ascii_whitespace()) {
            bail!("udp_timeout must be a duration without spaces, for example 30s");
        }
    }
    if let Some(v) = &setting.restapi {
        if !v.trim().is_empty() && (v.contains(' ') || !v.contains(':')) {
            bail!("restapi must be empty or host:port/listen address");
        }
    }
    Ok(())
}

fn validate_proxy_url(proxy: &str) -> Result<()> {
    let p = proxy.trim();
    if p.is_empty() || p.chars().any(|c| c.is_ascii_whitespace()) {
        bail!("proxy must not be empty or contain spaces");
    }
    let lower = p.to_ascii_lowercase();
    if !(lower.starts_with("socks5://") || lower.starts_with("socks4://") || lower.starts_with("http://")) {
        bail!("proxy must start with socks5://, socks4:// or http://");
    }
    let after = p.split_once("://").map(|(_, rest)| rest).unwrap_or("");
    if after.is_empty() || !after.rsplit_once(':').map(|(_, port)| port.parse::<u16>().is_ok()).unwrap_or(false) {
        bail!("proxy must include host:port");
    }
    Ok(())
}

pub fn validate_enabled_tun_uniqueness_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_enabled: Option<bool>,
) -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let mut seen: BTreeMap<String, String> = BTreeMap::new();

    for (name, st) in active.profiles {
        let enabled = if override_profile == Some(name.as_str()) {
            override_enabled.unwrap_or(st.enabled)
        } else {
            st.enabled
        };
        if !enabled { continue; }

        let setting = if override_profile == Some(name.as_str()) {
            override_setting.cloned().unwrap_or_else(|| read_setting(&name).unwrap_or_default())
        } else {
            read_setting(&name).unwrap_or_default()
        };
        validate_setting(&setting).with_context(|| format!("tun2socks profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("tun2socks tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
        }
    }
    Ok(())
}

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            out.push((format!("tun2socks/{name}"), setting.tun));
        }
    }
    out
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen: BTreeMap<String, String> = BTreeMap::new();
    for plan in plans {
        if let Some(other) = seen.insert(plan.setting.tun.clone(), plan.name.clone()) {
            bail!("tun2socks tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
        }
    }
    Ok(())
}

pub fn validate_start_plan() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        return Ok(());
    }

    let mut errors = Vec::<String>::new();
    if !Path::new(TUN2SOCKS_BIN).is_file() {
        errors.push(format!("binary missing: {TUN2SOCKS_BIN}"));
    }

    let mut seen_tuns = BTreeMap::<String, String>::new();
    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name)
                .with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting)
                .with_context(|| format!("validate setting for profile {name}"))?;

            if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
            }

            let app_in = profile_dir.join("app/uid/user_program");
            let apps_raw = fs::read_to_string(&app_in)
                .with_context(|| format!("read {}", app_in.display()))?;
            if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
                bail!("app list is empty: {}", app_in.display());
            }
            Ok(())
        })();

        if let Err(e) = profile_res {
            errors.push(format!("{name}: {e:#}"));
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        bail!("tun2socks start plan has issue(s): {}", errors.join("; "))
    }
}

pub fn has_enabled_profiles() -> bool {
    read_active().map(|a| a.profiles.values().any(|st| st.enabled)).unwrap_or(false)
}

pub fn is_running() -> bool { !main_pids_exact().is_empty() }

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::vpn_netd::start_profiles(profiles)?;
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;

    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active.profiles.iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("tun2socks: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("tun2socks: запуск");

    if !Path::new(TUN2SOCKS_BIN).is_file() {
        warn!("tun2socks: binary not found: {TUN2SOCKS_BIN} -> skip");
        crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    info!("tun2socks: start requested enabled_profiles={}", enabled_names.join(","));

    let mut plans = Vec::new();
    let mut used_netids = BTreeSet::<u32>::new();
    let mut had_error = false;
    for name in enabled_names {
        match build_profile_plan(&name, &used_netids) {
            Ok(plan) => {
                used_netids.insert(plan.netid);
                plans.push(plan);
            }
            Err(e) => {
                had_error = true;
                warn!("tun2socks: profile '{name}' skip: {e:#}");
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
        }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_plan_tuns_unique(&plans) {
        warn!("tun2socks: enabled profiles have tun conflict: {e:#}");
        crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "tun2socks: starting profile={} tun={} proxy={} addr={} log={}",
                plan.name,
                plan.setting.tun,
                plan.setting.proxy,
                plan.tun_addr,
                plan.log_path.display()
            );
            spawn_tun2socks(plan)?;
            wait_tun_link(&plan.setting.tun)
                .with_context(|| format!("tun2socks profile={} wait tun={}", plan.name, plan.setting.tun))?;
            configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
                .with_context(|| format!("tun2socks profile={} configure tun={}", plan.name, plan.setting.tun))?;
            wait_tun_ready(&plan.setting.tun)
                .with_context(|| format!("tun2socks profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
            info!(
                "tun2socks: tun ready profile={} tun={} cidr={} gateway=none",
                plan.name,
                plan.setting.tun,
                plan.cidr
            );

            Ok(VpnNetdProfile {
                owner_program: "tun2socks".to_string(),
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.setting.tun.clone(),
                cidr: plan.cidr.clone(),
                gateway: None,
                dns: vec!["8.8.8.8".to_string()],
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
            })
        })();

        match res {
            Ok(profile) => profiles.push(profile),
            Err(e) => {
                had_error = true;
                warn!("tun2socks: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("tun2socks: часть профилей не запущена, запуск продолжен");
    }

    info!("tun2socks: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

fn build_profile_plan(profile: &str, used_netids: &BTreeSet<u32>) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }

    let netid = generate_netid(used_netids)?;
    let (tun_addr, cidr) = generated_tun_addr_and_cidr(netid)?;

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        app_in,
        app_out,
        log_path: profile_dir.join("log/tun2socks.log"),
        netid,
        tun_addr,
        cidr,
    })
}

fn spawn_tun2socks(plan: &ProfilePlan) -> Result<()> {
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.log_path)
        .with_context(|| format!("open log {}", plan.log_path.display()))?;
    let logf_err = logf.try_clone().context("clone tun2socks log")?;

    let mut cmd = Command::new(TUN2SOCKS_BIN);
    cmd.arg("-device")
        .arg(format!("tun://{}", plan.setting.tun))
        .arg("-proxy")
        .arg(&plan.setting.proxy)
        .arg("-loglevel")
        .arg(&plan.setting.loglevel)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    if let Some(v) = &plan.setting.udp_timeout {
        if !v.trim().is_empty() {
            cmd.arg("-udp-timeout").arg(v);
        }
    }
    if let Some(v) = plan.setting.fwmark {
        cmd.arg("-fwmark").arg(v.to_string());
    }
    if let Some(v) = &plan.setting.restapi {
        if !v.trim().is_empty() {
            cmd.arg("-restapi").arg(v);
        }
    }

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {TUN2SOCKS_BIN}"))?;
    info!(
        "tun2socks: spawned profile={} pid={} tun={} proxy={} log={}",
        plan.name,
        child.id(),
        plan.setting.tun,
        plan.setting.proxy,
        plan.log_path.display()
    );

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("tun2socks: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.log_path.display());
    }
    Ok(())
}

fn wait_tun_link(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} was not created after {:?}", TUN_WAIT);
        }
        let (code, _) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 { return Ok(()); }
        thread::sleep(Duration::from_millis(300));
    }
}

fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 && out.lines().any(|l| l.contains(" inet ") || l.split_whitespace().any(|t| t == "inet")) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

fn configure_tun_addr(tun: &str, tun_addr: &str) -> Result<()> {
    let (code, out) = shell::run_timeout("ip", &["addr", "replace", tun_addr, "dev", tun], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("ip addr replace {tun_addr} dev {tun}"))?;
    if code != 0 {
        bail!("ip addr replace {tun_addr} dev {tun} failed: {}", out.trim());
    }
    let (code, out) = shell::run_timeout("ip", &["link", "set", tun, "up"], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("ip link set {tun} up"))?;
    if code != 0 {
        bail!("ip link set {tun} up failed: {}", out.trim());
    }
    Ok(())
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) { return Ok(id); }
    }
    bail!("no free tun2socks netid in range {NETID_BASE}..={NETID_MAX}")
}

fn generated_tun_addr_and_cidr(netid: u32) -> Result<(String, String)> {
    if !(NETID_BASE..=NETID_MAX).contains(&netid) {
        bail!("tun2socks netid out of generated range: {netid}");
    }
    let offset = (netid - NETID_BASE) * 4;
    let network = TUN2SOCKS_NET_BASE
        .checked_add(offset)
        .ok_or_else(|| anyhow::anyhow!("tun2socks cidr overflow"))?;
    let addr = network + 1;
    Ok((format!("{}/30", u32_to_ipv4(addr)), format!("{}/30", u32_to_ipv4(network))))
}

fn is_valid_ifname(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 15
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

fn is_forbidden_tun_name(s: &str) -> bool {
    s == "lo"
        || s == "dummy0"
        || s.starts_with("wlan")
        || s.starts_with("rmnet")
        || s.starts_with("ccmni")
        || s.starts_with("eth")
        || s.starts_with("ap")
        || s.starts_with("rndis")
}

fn ensure_file_empty(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    if !path.exists() { fs::write(path, "")?; }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let txt = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_str(&txt).with_context(|| format!("parse {}", path.display()))
}

fn write_json_pretty<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let txt = serde_json::to_string_pretty(v)?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, txt)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn u32_to_ipv4(v: u32) -> String {
    format!("{}.{}.{}.{}", (v >> 24) & 0xff, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = r#"sh -c "pgrep -f '^/data/adb/modules/ZDT-D/bin/tun2socks -device tun://.* -proxy .*$' 2>/dev/null || true""#;
    if let Ok(out) = shell::capture_quiet(cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = r#"sh -c "ps -ef 2>/dev/null | grep -F '/data/adb/modules/ZDT-D/bin/tun2socks' | grep -F -- '-device tun://' | grep -F ' -proxy ' | grep -v grep || true""#;
        if let Ok(out) = shell::capture_quiet(ps_cmd) {
            for line in out.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 1 {
                    if let Ok(pid) = cols[1].parse::<i32>() {
                        if pid > 1 { pids.push(pid); }
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}
