use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream},
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

const MIHOMO_BIN: &str = "/data/adb/modules/ZDT-D/bin/mihomo";
const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const MIHOMO_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/mihomo";
const MIHOMO_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/mihomo/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/mihomo/active.json";
const NETID_BASE: u32 = 24200;
const NETID_MAX: u32 = 24999;
const MIHOMO_NET_BASE: u32 = 0xC612_8C00; // 198.18.140.0
const TUN_WAIT: Duration = Duration::from_secs(18);
const PORT_WAIT: Duration = Duration::from_secs(20);
const IP_TIMEOUT: Duration = Duration::from_secs(3);

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
    #[serde(default = "default_mixed_port")]
    pub mixed_port: u16,
    #[serde(default = "default_loglevel")]
    pub log_level: String,
    #[serde(default = "default_tun2socks_loglevel")]
    pub tun2socks_loglevel: String,
}

fn default_mixed_port() -> u16 { 17890 }
fn default_loglevel() -> String { "info".to_string() }
fn default_tun2socks_loglevel() -> String { "info".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun20".to_string(),
            mixed_port: default_mixed_port(),
            log_level: default_loglevel(),
            tun2socks_loglevel: default_tun2socks_loglevel(),
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    work_dir: PathBuf,
    config_path: PathBuf,
    runtime_config_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    mihomo_log_path: PathBuf,
    tun2socks_log_path: PathBuf,
    controller_port: Option<u16>,
    netid: u32,
    tun_addr: String,
    cidr: String,
}

pub fn root_path() -> PathBuf { PathBuf::from(MIHOMO_ROOT) }
pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(MIHOMO_PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("mihomo profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("work"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    ensure_file_empty(&root.join("app/out/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    let config_path = root.join("config.yaml");
    if !config_path.exists() {
        fs::write(&config_path, default_config_yaml())?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(MIHOMO_PROFILE_ROOT)?;
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
    let setting: ProfileSetting = serde_json::from_value(value).context("bad mihomo setting.json")?;
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    if setting.mixed_port == 0 {
        bail!("mixed_port must be 1..65535");
    }
    validate_loglevel(&setting.log_level, "log_level")?;
    validate_loglevel(&setting.tun2socks_loglevel, "tun2socks_loglevel")?;
    Ok(())
}

fn validate_loglevel(v: &str, field: &str) -> Result<()> {
    match v {
        "debug" | "info" | "warn" | "error" | "silent" => Ok(()),
        _ => bail!("{field} must be debug/info/warn/error/silent"),
    }
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
        validate_setting(&setting).with_context(|| format!("mihomo profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("mihomo tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
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
            out.push((format!("mihomo/{name}"), setting.tun));
        }
    }
    out
}

pub fn enabled_mixed_ports() -> Vec<u16> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            if setting.mixed_port != 0 { out.push(setting.mixed_port); }
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}


pub fn suggest_free_mixed_port() -> u16 {
    let used_other = crate::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    let used_mihomo = collect_all_defined_mihomo_ports(None, None, None).unwrap_or_default()
        .into_iter()
        .map(|(port, _)| port)
        .collect::<BTreeSet<u16>>();
    for port in 17890u16..=18999u16 {
        if !used_other.contains(&port) && !used_mihomo.contains(&port) {
            return port;
        }
    }
    default_mixed_port()
}

pub fn assign_free_ports_for_profile(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let mut setting = read_setting(profile).unwrap_or_default();
    let used_other = crate::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    let own_prefix = format!("mihomo/{profile}/");
    let used = collect_all_defined_mihomo_ports(None, None, None)?
        .into_iter()
        .filter(|(_, label)| !label.starts_with(&own_prefix))
        .map(|(port, _)| port)
        .collect::<BTreeSet<u16>>();
    if setting.mixed_port == 0 || used.contains(&setting.mixed_port) || used_other.contains(&setting.mixed_port) {
        for port in 17890u16..=18999u16 {
            if !used.contains(&port) && !used_other.contains(&port) {
                setting.mixed_port = port;
                break;
            }
        }
        write_setting(profile, &setting)?;
    }
    Ok(())
}

pub fn validate_port_uniqueness_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_config: Option<&str>,
) -> Result<()> {
    let mut seen = BTreeMap::<u16, String>::new();
    for (port, label) in collect_all_defined_mihomo_ports(override_profile, override_setting, override_config)? {
        if let Some(other) = seen.insert(port, label.clone()) {
            bail!("mihomo port conflict: port {} is used by {} and {}", port, other, label);
        }
    }

    let used_other = crate::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    for (port, label) in collect_all_defined_mihomo_ports(override_profile, override_setting, override_config)? {
        if used_other.contains(&port) {
            bail!("mihomo port conflict: port {} used by {} conflicts with another ZDT-D local port", port, label);
        }
    }
    Ok(())
}

fn collect_all_defined_mihomo_ports(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_config: Option<&str>,
) -> Result<Vec<(u16, String)>> {
    ensure_root_layout()?;
    let mut out = Vec::<(u16, String)>::new();
    let root = profiles_root();
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            let Some(name) = profile_dir.file_name().and_then(|s| s.to_str()) else { continue; };
            if name.starts_with('.') { continue; }
            ensure_valid_profile_name(name)?;
            let setting = if override_profile == Some(name) {
                override_setting.cloned().unwrap_or_else(|| read_setting(name).unwrap_or_default())
            } else {
                read_setting(name).unwrap_or_default()
            };
            if setting.mixed_port != 0 {
                out.push((setting.mixed_port, format!("mihomo/{name}/mixed_port")));
            }

            let config_raw = if override_profile == Some(name) {
                match override_config {
                    Some(raw) => Some(raw.to_string()),
                    None => fs::read_to_string(profile_dir.join("config.yaml")).ok(),
                }
            } else {
                fs::read_to_string(profile_dir.join("config.yaml")).ok()
            };
            if let Some(raw) = config_raw.as_deref() {
                if let Some(port) = parse_external_controller_port(raw) {
                    out.push((port, format!("mihomo/{name}/external-controller")));
                }
            }
        }
    }

    if let Some(name) = override_profile {
        let profile_dir = profile_root(name);
        if !profile_dir.exists() {
            let setting = override_setting.cloned().unwrap_or_default();
            if setting.mixed_port != 0 {
                out.push((setting.mixed_port, format!("mihomo/{name}/mixed_port")));
            }
            if let Some(raw) = override_config {
                if let Some(port) = parse_external_controller_port(raw) {
                    out.push((port, format!("mihomo/{name}/external-controller")));
                }
            }
        }
    }
    Ok(out)
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

    if enabled_names.is_empty() { return Ok(()); }

    let mut errors = Vec::<String>::new();
    if !Path::new(MIHOMO_BIN).is_file() { errors.push(format!("binary missing: {MIHOMO_BIN}")); }
    if !Path::new(TUN2SOCKS_BIN).is_file() { errors.push(format!("binary missing: {TUN2SOCKS_BIN}")); }

    let mut seen_tuns = BTreeMap::<String, String>::new();
    let mut seen_ports = BTreeMap::<u16, String>::new();
    let used_ports = crate::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();

    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;

            if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
            }
            if let Some(other) = seen_ports.insert(setting.mixed_port, name.clone()) {
                bail!("mixed_port {} is used by enabled profiles {} and {}", setting.mixed_port, other, name);
            }
            if used_ports.contains(&setting.mixed_port) {
                bail!("mixed_port {} conflicts with another ZDT-D local port", setting.mixed_port);
            }

            let config_path = profile_dir.join("config.yaml");
            if !config_path.is_file() {
                bail!("config.yaml is missing: {}", config_path.display());
            }
            let raw = fs::read_to_string(&config_path).with_context(|| format!("read {}", config_path.display()))?;
            if raw.trim().is_empty() { bail!("config.yaml is empty"); }
            if let Some(controller_port) = parse_external_controller_port(&raw) {
                if let Some(other) = seen_ports.insert(controller_port, format!("{name}/external-controller")) {
                    bail!("external-controller port {} is used by {}", controller_port, other);
                }
                if used_ports.contains(&controller_port) {
                    bail!("external-controller port {} conflicts with another ZDT-D local port", controller_port);
                }
            }

            let app_in = profile_dir.join("app/uid/user_program");
            let apps_raw = fs::read_to_string(&app_in).with_context(|| format!("read {}", app_in.display()))?;
            if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
                bail!("app list is empty: {}", app_in.display());
            }
            Ok(())
        })();

        if let Err(e) = profile_res { errors.push(format!("{name}: {e:#}")); }
    }

    if errors.is_empty() { Ok(()) } else { bail!("mihomo start plan has issue(s): {}", errors.join("; ")) }
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
        info!("mihomo: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("mihomo: запуск");

    if !Path::new(MIHOMO_BIN).is_file() {
        warn!("mihomo: binary not found: {MIHOMO_BIN} -> skip");
        crate::logging::user_warn("mihomo: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }
    if !Path::new(TUN2SOCKS_BIN).is_file() {
        warn!("mihomo: tun2socks binary not found: {TUN2SOCKS_BIN} -> skip");
        crate::logging::user_warn("mihomo: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    info!("mihomo: start requested enabled_profiles={}", enabled_names.join(","));

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
                warn!("mihomo: profile '{name}' skip: {e:#}");
            }
        }
    }

    if plans.is_empty() {
        if had_error { crate::logging::user_warn("mihomo: ошибка запуска, запуск продолжен"); }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_plan_conflicts(&plans) {
        warn!("mihomo: profile conflict: {e:#}");
        crate::logging::user_warn("mihomo: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "mihomo: starting profile={} tun={} mixed_port={} work={} config={} runtime_config={}",
                plan.name,
                plan.setting.tun,
                plan.setting.mixed_port,
                plan.work_dir.display(),
                plan.config_path.display(),
                plan.runtime_config_path.display()
            );
            prepare_runtime_config(plan)?;
            spawn_mihomo(plan)?;
            wait_tcp_port("127.0.0.1", plan.setting.mixed_port)
                .with_context(|| format!("mihomo profile={} wait mixed_port={}", plan.name, plan.setting.mixed_port))?;
            spawn_tun2socks(plan)?;
            wait_tun_link(&plan.setting.tun)
                .with_context(|| format!("mihomo profile={} wait tun={}", plan.name, plan.setting.tun))?;
            configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
                .with_context(|| format!("mihomo profile={} configure tun={}", plan.name, plan.setting.tun))?;
            wait_tun_ready(&plan.setting.tun)
                .with_context(|| format!("mihomo profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
            info!(
                "mihomo: tun ready profile={} tun={} cidr={} gateway=none",
                plan.name,
                plan.setting.tun,
                plan.cidr
            );

            Ok(VpnNetdProfile {
                owner_program: "mihomo".to_string(),
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
                warn!("mihomo: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error { crate::logging::user_warn("mihomo: часть профилей не запущена, запуск продолжен"); }
    info!("mihomo: prepared vpn_netd profiles count={}", profiles.len());
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

    let config_path = profile_dir.join("config.yaml");
    if !config_path.is_file() {
        bail!("config.yaml missing: {}", config_path.display());
    }
    let raw_config = fs::read_to_string(&config_path).with_context(|| format!("read {}", config_path.display()))?;
    if raw_config.trim().is_empty() { bail!("config.yaml is empty"); }
    let controller_port = parse_external_controller_port(&raw_config);

    let netid = generate_netid(used_netids)?;
    let (tun_addr, cidr) = generated_tun_addr_and_cidr(netid)?;

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        work_dir: profile_dir.join("work"),
        config_path: config_path.clone(),
        runtime_config_path: profile_dir.join("config.runtime.yaml"),
        app_in,
        app_out,
        mihomo_log_path: profile_dir.join("log/mihomo.log"),
        tun2socks_log_path: profile_dir.join("log/tun2socks.log"),
        controller_port,
        netid,
        tun_addr,
        cidr,
    })
}

fn validate_plan_conflicts(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen_tun: BTreeMap<String, String> = BTreeMap::new();
    let mut seen_port: BTreeMap<u16, String> = BTreeMap::new();
    let used_ports = crate::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    for plan in plans {
        if let Some(other) = seen_tun.insert(plan.setting.tun.clone(), plan.name.clone()) {
            bail!("mihomo tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
        }
        if let Some(other) = seen_port.insert(plan.setting.mixed_port, plan.name.clone()) {
            bail!("mihomo port conflict: mixed_port {} is used by enabled profiles {} and {}", plan.setting.mixed_port, other, plan.name);
        }
        if used_ports.contains(&plan.setting.mixed_port) {
            bail!("mihomo port conflict: mixed_port {} conflicts with another ZDT-D local port", plan.setting.mixed_port);
        }
        if let Some(port) = plan.controller_port {
            let label = format!("{}/external-controller", plan.name);
            if let Some(other) = seen_port.insert(port, label.clone()) {
                bail!("mihomo port conflict: external-controller port {} is used by {} and {}", port, other, label);
            }
            if used_ports.contains(&port) {
                bail!("mihomo port conflict: external-controller port {} conflicts with another ZDT-D local port", port);
            }
        }
    }
    Ok(())
}

fn prepare_runtime_config(plan: &ProfilePlan) -> Result<()> {
    fs::create_dir_all(&plan.work_dir)?;
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let raw = fs::read_to_string(&plan.config_path)
        .with_context(|| format!("read {}", plan.config_path.display()))?;
    let sanitized = sanitize_mihomo_yaml(&raw);
    let mut runtime = String::new();
    runtime.push_str("# ZDT-D managed runtime config. Do not edit this file; edit config.yaml instead.\n");
    runtime.push_str(&format!("mixed-port: {}\n", plan.setting.mixed_port));
    runtime.push_str("allow-lan: false\n");
    runtime.push_str("bind-address: 127.0.0.1\n");
    runtime.push_str(&format!("log-level: {}\n", plan.setting.log_level));
    if let Some(port) = plan.controller_port {
        runtime.push_str(&format!("external-controller: 127.0.0.1:{}\n", port));
    }
    runtime.push('\n');
    runtime.push_str(&sanitized);
    if !runtime.ends_with('\n') { runtime.push('\n'); }
    write_text_atomic(&plan.runtime_config_path, &runtime)
}

fn sanitize_mihomo_yaml(raw: &str) -> String {
    let mut out = String::new();
    let lines: Vec<&str> = raw.lines().collect();
    let mut i = 0usize;
    while i < lines.len() {
        let line = lines[i];
        let trimmed = line.trim_start();
        let indent = line.len().saturating_sub(trimmed.len());
        if indent == 0 {
            let key = top_level_key(trimmed);
            if let Some(k) = key {
                if is_forbidden_block(k) {
                    i += 1;
                    while i < lines.len() {
                        let next = lines[i];
                        let nt = next.trim_start();
                        if nt.is_empty() || nt.starts_with('#') {
                            i += 1;
                            continue;
                        }
                        let nindent = next.len().saturating_sub(nt.len());
                        if nindent == 0 { break; }
                        i += 1;
                    }
                    continue;
                }
                if is_forbidden_scalar(k) {
                    i += 1;
                    continue;
                }
            }
        }
        out.push_str(line);
        out.push('\n');
        i += 1;
    }
    out
}

fn top_level_key(trimmed: &str) -> Option<&str> {
    if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('-') { return None; }
    let (key, _) = trimmed.split_once(':')?;
    let key = key.trim();
    if key.is_empty() { None } else { Some(key) }
}


fn parse_external_controller_port(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let trimmed = line.trim_start();
        let indent = line.len().saturating_sub(trimmed.len());
        if indent != 0 { continue; }
        let Some(key) = top_level_key(trimmed) else { continue; };
        if key != "external-controller" { continue; }
        let (_, value) = trimmed.split_once(':')?;
        return parse_port_from_yaml_scalar(value);
    }
    None
}

fn parse_port_from_yaml_scalar(value: &str) -> Option<u16> {
    let mut v = value.trim();
    if v.is_empty() { return None; }
    if let Some(idx) = v.find(" #") { v = &v[..idx]; }
    v = v.trim().trim_matches('"').trim_matches('\'').trim();
    if v.is_empty() { return None; }
    if let Ok(port) = v.parse::<u16>() { return if port != 0 { Some(port) } else { None }; }
    let idx = v.rfind(':')?;
    let port_s = v[idx + 1..].trim().trim_matches(']').trim_matches('"').trim_matches('\'');
    port_s.parse::<u16>().ok().filter(|p| *p != 0)
}

fn is_forbidden_block(key: &str) -> bool {
    matches!(key, "tun" | "iptables")
}

fn is_forbidden_scalar(key: &str) -> bool {
    matches!(
        key,
        "mixed-port" | "allow-lan" | "bind-address" | "log-level" |
        "redir-port" | "tproxy-port" | "port" | "socks-port" | "external-controller"
    )
}

fn spawn_mihomo(plan: &ProfilePlan) -> Result<()> {
    if mihomo_profile_process_running(&plan.work_dir, &plan.runtime_config_path) {
        info!(
            "mihomo: profile={} already running for runtime_config={}, skip spawn",
            plan.name,
            plan.runtime_config_path.display()
        );
        return Ok(());
    }

    fs::create_dir_all(&plan.work_dir)?;
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.mihomo_log_path)
        .with_context(|| format!("open log {}", plan.mihomo_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone mihomo log")?;

    let mut cmd = Command::new(MIHOMO_BIN);
    cmd.arg("-d")
        .arg(&plan.work_dir)
        .arg("-f")
        .arg(&plan.runtime_config_path)
        .current_dir(&plan.work_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {MIHOMO_BIN}"))?;
    info!(
        "mihomo: spawned profile={} pid={} mixed_port={} log={}",
        plan.name,
        child.id(),
        plan.setting.mixed_port,
        plan.mihomo_log_path.display()
    );

    thread::sleep(Duration::from_millis(350));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("mihomo: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.mihomo_log_path.display());
    }
    Ok(())
}

fn spawn_tun2socks(plan: &ProfilePlan) -> Result<()> {
    let proxy = format!("socks5://127.0.0.1:{}", plan.setting.mixed_port);
    if tun2socks_profile_process_running(&plan.setting.tun, &proxy) {
        info!(
            "mihomo: tun2socks profile={} already running for tun={} proxy={}, skip spawn",
            plan.name,
            plan.setting.tun,
            proxy
        );
        return Ok(());
    }

    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.tun2socks_log_path)
        .with_context(|| format!("open log {}", plan.tun2socks_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone mihomo tun2socks log")?;

    let mut cmd = Command::new(TUN2SOCKS_BIN);
    cmd.arg("-device")
        .arg(format!("tun://{}", plan.setting.tun))
        .arg("-proxy")
        .arg(&proxy)
        .arg("-loglevel")
        .arg(&plan.setting.tun2socks_loglevel)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {TUN2SOCKS_BIN} for mihomo"))?;
    info!(
        "mihomo: spawned tun2socks profile={} pid={} tun={} proxy={} log={}",
        plan.name,
        child.id(),
        plan.setting.tun,
        proxy,
        plan.tun2socks_log_path.display()
    );
    Ok(())
}

fn mihomo_profile_process_running(work_dir: &Path, runtime_config: &Path) -> bool {
    let pattern = format!(
        "{} -d {} -f {}",
        MIHOMO_BIN,
        work_dir.display(),
        runtime_config.display()
    );
    let cmd = format!(
        "ps -ef 2>/dev/null | grep -F {} | grep -v grep >/dev/null 2>&1",
        shell_quote_for_sh(&pattern)
    );
    shell::ok_sh(&cmd).is_ok()
}

fn tun2socks_profile_process_running(tun: &str, proxy: &str) -> bool {
    let pattern = format!(
        "{} -device tun://{} -proxy {}",
        TUN2SOCKS_BIN,
        tun,
        proxy
    );
    let cmd = format!(
        "ps -ef 2>/dev/null | grep -F {} | grep -v grep >/dev/null 2>&1",
        shell_quote_for_sh(&pattern)
    );
    shell::ok_sh(&cmd).is_ok()
}

fn shell_quote_for_sh(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

fn wait_tcp_port(host: &str, port: u16) -> Result<()> {
    let ip: IpAddr = host.parse().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let addr = SocketAddr::new(ip, port);
    let start = Instant::now();
    loop {
        if start.elapsed() >= PORT_WAIT {
            bail!("127.0.0.1:{port} is not listening after {:?}", PORT_WAIT);
        }
        if TcpStream::connect_timeout(&addr, Duration::from_millis(250)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

fn wait_tun_link(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT { bail!("tun {tun} was not created after {:?}", TUN_WAIT); }
        let (code, _) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 { return Ok(()); }
        thread::sleep(Duration::from_millis(300));
    }
}

fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT { bail!("tun {tun} is not ready after {:?}", TUN_WAIT); }
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
    if code != 0 { bail!("ip addr replace {tun_addr} dev {tun} failed: {}", out.trim()); }
    let (code, out) = shell::run_timeout("ip", &["link", "set", tun, "up"], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("ip link set {tun} up"))?;
    if code != 0 { bail!("ip link set {tun} up failed: {}", out.trim()); }
    Ok(())
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) { return Ok(id); }
    }
    bail!("no free mihomo netid in range {NETID_BASE}..={NETID_MAX}")
}

fn generated_tun_addr_and_cidr(netid: u32) -> Result<(String, String)> {
    if !(NETID_BASE..=NETID_MAX).contains(&netid) { bail!("mihomo netid out of generated range: {netid}"); }
    let offset = (netid - NETID_BASE) * 4;
    let network = MIHOMO_NET_BASE.checked_add(offset).ok_or_else(|| anyhow::anyhow!("mihomo cidr overflow"))?;
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
    write_text_atomic(path, &txt)
}

fn write_text_atomic(path: &Path, txt: &str) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
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
    let cmd = r#"sh -c "pgrep -f '^/data/adb/modules/ZDT-D/bin/mihomo -d /data/adb/modules/ZDT-D/working_folder/mihomo/profile/.*/work -f /data/adb/modules/ZDT-D/working_folder/mihomo/profile/.*/config.runtime.yaml$' 2>/dev/null || true""#;
    if let Ok(out) = shell::capture_quiet(cmd) { pids.extend(parse_pid_lines(&out)); }
    if pids.is_empty() {
        let ps_cmd = r#"sh -c "ps -ef 2>/dev/null | grep -F '/data/adb/modules/ZDT-D/bin/mihomo' | grep -F '/data/adb/modules/ZDT-D/working_folder/mihomo/profile/' | grep -F 'config.runtime.yaml' | grep -v grep || true""#;
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

pub fn tun2socks_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = r#"sh -c "pgrep -f '^/data/adb/modules/ZDT-D/bin/tun2socks -device tun://.* -proxy socks5://127\.0\.0\.1:[0-9]+.*$' 2>/dev/null || true""#;
    if let Ok(out) = shell::capture_quiet(cmd) { pids.extend(parse_pid_lines(&out)); }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn default_config_yaml() -> &'static str {
    r#"# Mihomo profile config. The Android app may replace this file completely.
# ZDT-D generates config.runtime.yaml from this file before launch.

mode: rule
ipv6: false

proxies:
  - name: DIRECT-OUT
    type: direct
    udp: true
    ip-version: ipv4

proxy-groups:
  - name: Proxy
    type: select
    proxies:
      - DIRECT-OUT

rules:
  - MATCH,Proxy
"#
}
