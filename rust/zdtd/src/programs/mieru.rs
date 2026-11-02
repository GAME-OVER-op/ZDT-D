use anyhow::{bail, Context, Result};
use crate::common::network::{is_valid_ifname, is_forbidden_tun_name, NETID_MIERU, configure_tun_addr, wait_tun_link, wait_tcp_port, wait_tun_ipv4_ready};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, any_live_ps_ef_where, LogMode, spawn_detached_logged_with_clone_context};
use crate::programs::registry::{ProgramId};
use crate::network::t2s::{build_tun2socks_command, tun2socks_process_running};
use crate::network::vpn::{VpnProfileCore, VpnNetdProfileSpec};
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self, OpenOptions}, os::unix::process::CommandExt, path::{Path, PathBuf}, process::{Command, Stdio}, thread, time::Duration};

use crate::{
    network::vpn::netd::VpnNetdProfile,
    network::vpn::tether::VpnTetherProfile,
};

const MIERU_BIN: &str = "/data/adb/modules/ZDT-D/bin/mieru";
const TUN2PROXY_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const MIERU_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/mieru";
const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::Mieru, NETID_MIERU);

fn stable_netid_for(profile: &str) -> Result<u32> {
    let active = read_active().unwrap_or_default();
    VPN_CORE.stable_netid(&active, profile)
}
const MIERU_NET_BASE: u32 = 0xAC1F_FC00; // 172.31.252.0
const TUN_WAIT: Duration = Duration::from_secs(18);
const PORT_WAIT: Duration = Duration::from_secs(25);

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    #[serde(default = "default_socks5_port")]
    pub socks5_port: u16,
    #[serde(default = "default_rpc_port")]
    pub rpc_port: u16,
    #[serde(default)]
    pub mtu: Option<u16>,
    #[serde(default = "default_tun2proxy_loglevel")]
    pub tun2proxy_loglevel: String,
    #[serde(default = "default_mieru_loglevel")]
    pub mieru_loglevel: String,
}

fn default_socks5_port() -> u16 { 2085 }
fn default_rpc_port() -> u16 { 8964 }
fn default_tun2proxy_loglevel() -> String { "info".to_string() }
fn default_mieru_loglevel() -> String { "INFO".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "mrtun0".to_string(),
            socks5_port: default_socks5_port(),
            rpc_port: default_rpc_port(),
            mtu: None,
            tun2proxy_loglevel: default_tun2proxy_loglevel(),
            mieru_loglevel: default_mieru_loglevel(),
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    config_path: PathBuf,
    runtime_config_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    mieru_log_path: PathBuf,
    tun2proxy_log_path: PathBuf,
    requires_netd: bool,
    requires_tun: bool,
    netid: u32,
    tun_addr: String,
    cidr: String,
}

fn netd_profile_for_plan(plan: &ProfilePlan) -> VpnNetdProfile {
    VPN_CORE.netd_profile(VpnNetdProfileSpec {
        profile: plan.name.clone(),
        netid: plan.netid,
        tun: plan.setting.tun.clone(),
        cidr: plan.cidr.clone(),
        gateway: None,
        dns: vec!["8.8.8.8".to_string()],
        app_list_path: plan.app_in.clone(),
        app_out_path: plan.app_out.clone(),
        endpoint_escape_ips: Vec::new(),
    })
}

pub fn root_path() -> PathBuf { PathBuf::from(MIERU_ROOT) }
pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(MIERU_ROOT))
}
pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(MIERU_ROOT))
}
pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("mieru profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    crate::common::profile::ensure_relative_dirs(
        &root,
        &[
            crate::common::profile::APP_UID_DIR,
            crate::common::profile::APP_OUT_DIR,
            crate::common::profile::LOG_DIR,
        ],
    )?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_uid_program_path(&root))?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_out_program_path(&root))?;
    crate::common::profile::ensure_profile_json(
        &crate::common::profile::setting_path(&root),
        &default_setting_for_profile(profile),
    )?;
    crate::common::profile::ensure_profile_json(
        &root.join("config.json"),
        &default_config_json(profile),
    )?;
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    crate::common::profile::ensure_active_profiles_file(&profiles_root(), &active_path())
}

pub fn read_active() -> Result<ActiveProfiles> {
    crate::common::profile::read_active_profiles(&profiles_root(), &active_path())
}

pub fn write_active(active: &ActiveProfiles) -> Result<()> {
    crate::common::profile::write_active_profiles(&profiles_root(), &active_path(), active)
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    crate::common::profile::read_profile_json(&crate::common::profile::setting_path(&profile_root(profile)))
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    validate_setting(setting)?;
    ensure_profile_layout(profile)?;
    crate::common::profile::write_profile_json(
        &crate::common::profile::setting_path(&profile_root(profile)),
        setting,
    )
}

pub fn normalize_setting_value(value: Value) -> Result<ProfileSetting> {
    let setting: ProfileSetting = serde_json::from_value(value).context("bad mieru setting.json")?;
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    validate_local_port(setting.socks5_port, "socks5_port")?;
    validate_local_port(setting.rpc_port, "rpc_port")?;
    if setting.socks5_port == setting.rpc_port {
        bail!("socks5_port must not equal rpc_port");
    }
    if let Some(mtu) = setting.mtu {
        if !(1280..=9000).contains(&mtu) { bail!("mtu must be empty or 1280..9000"); }
    }
    validate_tun2proxy_loglevel(&setting.tun2proxy_loglevel)?;
    validate_mieru_loglevel(&setting.mieru_loglevel)?;
    Ok(())
}

fn validate_local_port(port: u16, field: &str) -> Result<()> {
    if port < 1025 { bail!("{field} must be 1025..65535"); }
    Ok(())
}

fn validate_tun2proxy_loglevel(v: &str) -> Result<()> {
    match v {
        "debug" | "info" | "warn" | "error" | "silent" => Ok(()),
        _ => bail!("tun2proxy_loglevel must be debug/info/warn/error/silent"),
    }
}

fn validate_mieru_loglevel(v: &str) -> Result<()> {
    match v {
        "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL" | "trace" | "debug" | "info" | "warn" | "error" | "fatal" => Ok(()),
        _ => bail!("mieru_loglevel must be TRACE/DEBUG/INFO/WARN/ERROR/FATAL"),
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
        validate_setting(&setting).with_context(|| format!("mieru profile={name} setting validation"))?;
        if !crate::common::apps::app_list_requires_netd(&profile_root(&name).join("app/uid/user_program")) {
            continue;
        }

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("mieru tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
        }
    }
    Ok(())
}

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    let api_settings = crate::settings::load_api_settings().unwrap_or_default();
    enabled_tun_claims_with_settings(&api_settings)
}

pub(crate) fn enabled_tun_claims_with_settings(
    api_settings: &crate::settings::ApiSettings,
) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let selected_for_hotspot = api_settings
            .hotspot_vpn_profile_for(VPN_CORE.owner_program()) == Some(name.as_str());
        if !selected_for_hotspot && !crate::common::apps::app_list_requires_netd(&profile_root(&name).join("app/uid/user_program")) { continue; }
        if let Ok(setting) = read_setting(&name) {
            out.push(VPN_CORE.claim(&name, setting.tun));
        }
    }
    out
}
pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let api_settings = crate::settings::load_api_settings().unwrap_or_default();
    enabled_cidr_claims_with_settings(&api_settings)
}

pub(crate) fn enabled_cidr_claims_with_settings(
    api_settings: &crate::settings::ApiSettings,
) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    let hotspot_profile = api_settings
        .hotspot_vpn_profile_for(VPN_CORE.owner_program())
        .map(|s| s.to_string());
    for (name, st) in &active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(name) else { continue; };
        if validate_setting(&setting).is_err() { continue; }
        let selected_for_hotspot = hotspot_profile.as_deref() == Some(name.as_str());
        if !selected_for_hotspot && !crate::common::apps::app_list_requires_netd(&profile_root(name).join("app/uid/user_program")) { continue; }
        let Ok(netid) = VPN_CORE.stable_netid(&active, name) else { continue; };
        if let Ok((_, cidr)) = VPN_CORE.generated_tun_addr_and_cidr(MIERU_NET_BASE, netid) {
            out.push(VPN_CORE.claim(name, cidr));
        }
    }
    out
}
pub fn suggest_free_socks5_port() -> u16 {
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();
    let used_mieru = collect_all_defined_mieru_ports(None, None)
        .unwrap_or_default()
        .into_iter()
        .map(|(port, _)| port)
        .collect::<BTreeSet<u16>>();
    for port in 2085u16..=2199u16 {
        if !used_other.contains(&port) && !used_mieru.contains(&port) { return port; }
    }
    default_socks5_port()
}

pub fn suggest_free_rpc_port() -> u16 {
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();
    let used_mieru = collect_all_defined_mieru_ports(None, None)
        .unwrap_or_default()
        .into_iter()
        .map(|(port, _)| port)
        .collect::<BTreeSet<u16>>();
    for port in 8964u16..=9064u16 {
        if !used_other.contains(&port) && !used_mieru.contains(&port) { return port; }
    }
    default_rpc_port()
}

pub fn assign_free_ports_for_profile(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let mut setting = read_setting(profile).unwrap_or_else(|_| default_setting_for_profile(profile));
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();
    let own_prefix = format!("mieru/{profile}/");
    let used = collect_all_defined_mieru_ports(None, None)?
        .into_iter()
        .filter(|(_, label)| !label.starts_with(&own_prefix))
        .map(|(port, _)| port)
        .collect::<BTreeSet<u16>>();

    if setting.socks5_port < 1025 || used.contains(&setting.socks5_port) || used_other.contains(&setting.socks5_port) {
        for port in 2085u16..=2199u16 {
            if !used.contains(&port) && !used_other.contains(&port) {
                setting.socks5_port = port;
                break;
            }
        }
    }
    if setting.rpc_port < 1025 || setting.rpc_port == setting.socks5_port || used.contains(&setting.rpc_port) || used_other.contains(&setting.rpc_port) {
        for port in 8964u16..=9064u16 {
            if port != setting.socks5_port && !used.contains(&port) && !used_other.contains(&port) {
                setting.rpc_port = port;
                break;
            }
        }
    }
    write_setting(profile, &setting)?;
    sync_config_ports(profile, &setting)?;
    Ok(())
}

pub fn validate_port_uniqueness_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
) -> Result<()> {
    let mut seen = BTreeMap::<u16, String>::new();
    for (port, label) in collect_all_defined_mieru_ports(override_profile, override_setting)? {
        if let Some(other) = seen.insert(port, label.clone()) {
            bail!("mieru port conflict: port {} is used by {} and {}", port, other, label);
        }
    }
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();
    for (port, label) in collect_all_defined_mieru_ports(override_profile, override_setting)? {
        if used_other.contains(&port) {
            bail!("mieru port conflict: port {} used by {} conflicts with another ZDT-D local port", port, label);
        }
    }
    Ok(())
}

fn collect_all_defined_mieru_ports(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
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
            if setting.socks5_port != 0 { out.push((setting.socks5_port, format!("mieru/{name}/socks5_port"))); }
            if setting.rpc_port != 0 { out.push((setting.rpc_port, format!("mieru/{name}/rpc_port"))); }
        }
    }
    if let Some(name) = override_profile {
        let profile_dir = profile_root(name);
        if !profile_dir.exists() {
            let setting = override_setting.cloned().unwrap_or_else(|| default_setting_for_profile(name));
            if setting.socks5_port != 0 { out.push((setting.socks5_port, format!("mieru/{name}/socks5_port"))); }
            if setting.rpc_port != 0 { out.push((setting.rpc_port, format!("mieru/{name}/rpc_port"))); }
        }
    }
    Ok(out)
}

pub fn enabled_local_ports() -> Vec<u16> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            if setting.socks5_port != 0 { out.push(setting.socks5_port); }
            if setting.rpc_port != 0 { out.push(setting.rpc_port); }
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}

pub fn validate_start_plan() -> Result<()> {
    let api_settings = crate::settings::load_api_settings().unwrap_or_default();
    validate_start_plan_with_settings(&api_settings)
}

pub(crate) fn validate_start_plan_with_settings(api_settings: &crate::settings::ApiSettings) -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);
    if enabled_names.is_empty() { return Ok(()); }

    let mut errors = Vec::<String>::new();
    if !Path::new(MIERU_BIN).is_file() { errors.push(format!("binary missing: {MIERU_BIN}")); }
    let tun2proxy_available = Path::new(TUN2PROXY_BIN).is_file();

    let mut seen_tuns = BTreeMap::<String, String>::new();
    let mut seen_ports = BTreeMap::<u16, String>::new();
    let used_ports = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();

    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;
            let app_in = profile_dir.join("app/uid/user_program");
            let selection = crate::common::apps::app_selection(&app_in)?;
            let selected_for_hotspot = api_settings
                .hotspot_vpn_profile_for("mieru") == Some(name.as_str());
            if selection == crate::common::apps::AppSelection::Empty && !selected_for_hotspot {
                bail!("app list is empty: {}", app_in.display());
            }
            let requires_tun = selection == crate::common::apps::AppSelection::RealApps || selected_for_hotspot;
            let _requires_netd = selection == crate::common::apps::AppSelection::RealApps;
            if requires_tun && !tun2proxy_available {
                bail!("binary missing: {TUN2PROXY_BIN}");
            }
            if requires_tun {
                if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                    bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
                }
            }
            for (port, field) in [(setting.socks5_port, "socks5_port"), (setting.rpc_port, "rpc_port")] {
                let label = format!("{name}/{field}");
                if let Some(other) = seen_ports.insert(port, label.clone()) {
                    bail!("port {} is used by {} and {}", port, other, label);
                }
                if used_ports.contains(&port) { bail!("{field} {} conflicts with another ZDT-D local port", port); }
            }
            let config_path = profile_dir.join("config.json");
            if !config_path.is_file() { bail!("config.json is missing: {}", config_path.display()); }
            let raw = fs::read_to_string(&config_path).with_context(|| format!("read {}", config_path.display()))?;
            if raw.trim().is_empty() { bail!("config.json is empty"); }
            Ok(())
        })();
        if let Err(e) = profile_res { errors.push(format!("{name}: {e:#}")); }
    }
    if errors.is_empty() { Ok(()) } else { bail!("mieru start plan has issue(s): {}", errors.join("; ")) }
}

pub fn has_enabled_profiles() -> bool {
    read_active().map(|a| a.profiles.values().any(|st| st.enabled)).unwrap_or(false)
}

pub fn has_profiles_requiring_netd() -> bool {
    read_active()
        .map(|active| {
            active.profiles.into_iter().any(|(name, st)| {
                st.enabled && crate::common::apps::app_list_requires_netd(&profile_root(&name).join("app/uid/user_program"))
            })
        })
        .unwrap_or(false)
}

pub fn is_running() -> bool { !main_pids_exact().is_empty() }

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    let api_settings = crate::settings::load_api_settings().unwrap_or_default();
    start_profiles_for_netd_with_settings(&api_settings)
}

pub(crate) fn start_profiles_for_netd_with_settings(
    api_settings: &crate::settings::ApiSettings,
) -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);
    if enabled_names.is_empty() {
        info!("mieru: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("mieru: запуск");

    if !Path::new(MIERU_BIN).is_file() {
        warn!("mieru: binary not found: {MIERU_BIN} -> skip");
        crate::programs::components::user_warn_missing_component("mieru");
        return Ok(Vec::new());
    }
    let tun2proxy_available = Path::new(TUN2PROXY_BIN).is_file();

    let hotspot_vpn_profile = api_settings
        .hotspot_vpn_profile_for("mieru")
        .map(|name| name.to_string());
    let mut plans = Vec::new();
    let mut used_netids = BTreeSet::<u32>::new();
    let mut had_error = false;
    for name in enabled_names {
        let force_tun = hotspot_vpn_profile.as_deref() == Some(name.as_str());
        match build_profile_plan(&name, &used_netids, force_tun) {
            Ok(plan) => {
                if plan.requires_tun {
                    used_netids.insert(plan.netid);
                }
                plans.push(plan);
            }
            Err(e) => {
                if !crate::programs::components::report_missing_components(&e) {
                    had_error = true;
                }
                warn!("mieru: profile '{name}' skip: {e:#}");
            }
        }
    }
    if plans.is_empty() {
        if had_error { crate::logging::user_warn("mieru: ошибка запуска, запуск продолжен"); }
        return Ok(Vec::new());
    }
    if let Err(e) = validate_plan_conflicts(&plans) {
        warn!("mieru: profile conflict: {e:#}");
        crate::logging::user_warn("mieru: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    for plan in &plans {
        let res: Result<Option<VpnNetdProfile>> = (|| {
            prepare_runtime_config(plan)?;
            spawn_mieru(plan)?;
            wait_tcp_port("127.0.0.1", plan.setting.socks5_port, PORT_WAIT)
                .with_context(|| format!("mieru profile={} wait socks5_port={}", plan.name, plan.setting.socks5_port))?;
            if !plan.requires_tun {
                info!("mieru: profile={} uses launch marker only; skipping tun2proxy/vpn_netd", plan.name);
                return Ok(None);
            }
            if !tun2proxy_available {
                bail!("tun2proxy binary not found: {TUN2PROXY_BIN}");
            }
            spawn_tun2proxy(plan)?;
            wait_tun_link(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("mieru profile={} wait tun={}", plan.name, plan.setting.tun))?;
            configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
                .with_context(|| format!("mieru profile={} configure tun={}", plan.name, plan.setting.tun))?;
            wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("mieru profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
            info!("mieru: tun ready profile={} tun={} cidr={}", plan.name, plan.setting.tun, plan.cidr);
            if !plan.requires_netd {
                return Ok(None);
            }
            Ok(Some(netd_profile_for_plan(plan)))
        })();
        match res {
            Ok(Some(profile)) => profiles.push(profile),
            Ok(None) => {}
            Err(e) => {
                if !crate::programs::components::report_missing_components(&e) {
                    had_error = true;
                }
                warn!("mieru: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }
    if had_error { crate::logging::user_warn("mieru: часть профилей не запущена, запуск продолжен"); }
    info!("mieru: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

pub fn start_construction_profile(profile: &str) -> Result<()> {
	ensure_root_layout()?;
	ensure_valid_profile_name(profile)?;
	let active = read_active().unwrap_or_default();
	if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
		info!("mieru: construction profile '{}' is disabled, skip targeted start", profile);
		return Ok(());
	}

	if !Path::new(MIERU_BIN).is_file() {
		warn!("mieru: binary not found: {MIERU_BIN} -> skip targeted start");
		crate::programs::components::user_warn_missing_component("mieru");
		return Ok(());
	}
	let tun2proxy_available = Path::new(TUN2PROXY_BIN).is_file();
	let hotspot_vpn_profile = crate::settings::load_api_settings()
		.ok()
		.and_then(|st| st.hotspot_vpn_profile_for("mieru").map(|name| name.to_string()));

	let mut used_netids = BTreeSet::<u32>::new();
	let mut plans = Vec::<ProfilePlan>::new();
	for (name, st) in active.profiles.iter() {
		if !st.enabled || name == profile {
			continue;
		}
		let force_tun = hotspot_vpn_profile.as_deref() == Some(name.as_str());
		match build_profile_plan(name, &used_netids, force_tun) {
			Ok(plan) => {
				if plan.requires_tun {
					used_netids.insert(plan.netid);
				}
				plans.push(plan);
			}
			Err(e) => warn!(
				"mieru: ignoring unrelated enabled profile '{}' during targeted construction start of '{}': {e:#}",
				name,
				profile
			),
		}
	}

	let force_tun = hotspot_vpn_profile.as_deref() == Some(profile);
	let plan = build_profile_plan(profile, &used_netids, force_tun)
		.with_context(|| format!("mieru targeted construction plan profile={profile}"))?;
	plans.push(plan.clone());
	validate_plan_conflicts(&plans)?;

	prepare_runtime_config(&plan)?;
	spawn_mieru(&plan)?;
	wait_tcp_port("127.0.0.1", plan.setting.socks5_port, PORT_WAIT)
		.with_context(|| format!("mieru targeted profile={} wait socks5_port={}", plan.name, plan.setting.socks5_port))?;
	if !plan.requires_tun {
		info!("mieru: targeted profile={} uses launch marker only; skipping tun2proxy/vpn_netd", plan.name);
		return Ok(());
	}
	if !tun2proxy_available {
		bail!("tun2proxy binary not found: {TUN2PROXY_BIN}");
	}
	spawn_tun2proxy(&plan)?;
	wait_tun_link(&plan.setting.tun, TUN_WAIT)
		.with_context(|| format!("mieru targeted profile={} wait tun={}", plan.name, plan.setting.tun))?;
	configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
		.with_context(|| format!("mieru targeted profile={} configure tun={}", plan.name, plan.setting.tun))?;
	wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
		.with_context(|| format!("mieru targeted profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
	info!("mieru: targeted tun ready profile={} tun={} cidr={}", plan.name, plan.setting.tun, plan.cidr);
	if plan.requires_netd {
		crate::network::vpn::netd::start_profiles(vec![netd_profile_for_plan(&plan)])?;
	}
	Ok(())
}

pub fn start_profile_for_hotspot_vpn(profile: &str) -> Result<Option<VpnTetherProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        warn!("mieru: hotspot VPN profile '{}' is not enabled", profile);
        return Ok(None);
    }
    if !Path::new(MIERU_BIN).is_file() {
        warn!("mieru: binary not found: {MIERU_BIN} -> skip hotspot VPN");
        crate::programs::components::user_warn_missing_component("mieru");
        return Ok(None);
    }
    if !Path::new(TUN2PROXY_BIN).is_file() {
        warn!("mieru: tun2proxy binary not found: {TUN2PROXY_BIN} -> skip hotspot VPN");
        crate::programs::components::user_warn_missing_component("tun2socks");
        return Ok(None);
    }
    let plan = build_profile_plan_for_hotspot(profile)?;
    info!("mieru: hotspot VPN start profile={} tun={}", plan.name, plan.setting.tun);
    if wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT).is_ok() {
        info!("mieru: hotspot VPN reusing ready tun={}", plan.setting.tun);
    } else {
        prepare_runtime_config(&plan)?;
        spawn_mieru(&plan)?;
        wait_tcp_port("127.0.0.1", plan.setting.socks5_port, PORT_WAIT)
            .with_context(|| format!("mieru hotspot profile={} wait socks5_port={}", plan.name, plan.setting.socks5_port))?;
        spawn_tun2proxy(&plan)?;
        wait_tun_link(&plan.setting.tun, TUN_WAIT)
            .with_context(|| format!("mieru hotspot profile={} wait tun={}", plan.name, plan.setting.tun))?;
        configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
            .with_context(|| format!("mieru hotspot profile={} configure tun={}", plan.name, plan.setting.tun))?;
        wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
            .with_context(|| format!("mieru hotspot profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
    }
    Ok(Some(VpnTetherProfile {
        owner_program: VPN_CORE.owner_program().to_string(),
        profile: plan.name.clone(),
        tun: plan.setting.tun.clone(),
        cidr: plan.cidr.clone(),
        gateway: None,
        dns: vec!["8.8.8.8".to_string()],
    }))
}

fn build_profile_plan_for_hotspot(profile: &str) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    let active = read_active().unwrap_or_default();
    let mut used_netids = BTreeSet::<u32>::new();
    for (name, st) in active.profiles.iter() {
        if !st.enabled { continue; }
        let is_target = name == profile;
        match build_profile_plan(name, &used_netids, is_target) {
            Ok(plan) => {
                if plan.requires_tun {
                    used_netids.insert(plan.netid);
                }
                if is_target {
                    return Ok(plan);
                }
            }
            Err(e) if is_target => return Err(e),
            Err(_) => continue,
        }
    }
    bail!("hotspot VPN profile is not enabled: {profile}")
}

fn build_profile_plan(profile: &str, used_netids: &BTreeSet<u32>, force_tun: bool) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    crate::common::fs::ensure_empty_file(&app_in)?;
    let selection = crate::common::apps::app_selection(&app_in)?;
    if selection == crate::common::apps::AppSelection::Empty && !force_tun {
        bail!("app list is empty: {}", app_in.display());
    }
    let requires_netd = selection == crate::common::apps::AppSelection::RealApps;
    let requires_tun = requires_netd || force_tun;

    let config_path = profile_dir.join("config.json");
    if !config_path.is_file() { bail!("config.json missing: {}", config_path.display()); }
    let raw_config = fs::read_to_string(&config_path).with_context(|| format!("read {}", config_path.display()))?;
    if raw_config.trim().is_empty() { bail!("config.json is empty"); }

    let netid = if requires_tun {
        let id = stable_netid_for(profile)?;
        if used_netids.contains(&id) {
            bail!("netid {id} is already used by another mieru profile");
        }
        id
    } else { 0 };
    let (tun_addr, cidr) = if requires_tun {
        VPN_CORE.generated_tun_addr_and_cidr(MIERU_NET_BASE, netid)?
    } else {
        (String::new(), String::new())
    };
    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        config_path: config_path.clone(),
        runtime_config_path: profile_dir.join("config.runtime.json"),
        app_in,
        app_out,
        mieru_log_path: profile_dir.join("log/mieru.log"),
        tun2proxy_log_path: profile_dir.join("log/tun2proxy.log"),
        requires_netd,
        requires_tun,
        netid,
        tun_addr,
        cidr,
    })
}

fn validate_plan_conflicts(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen_tun: BTreeMap<String, String> = BTreeMap::new();
    let mut seen_port: BTreeMap<u16, String> = BTreeMap::new();
    let used_ports = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mieru().unwrap_or_default();
    for plan in plans {
        if plan.requires_tun {
            if let Some(other) = seen_tun.insert(plan.setting.tun.clone(), plan.name.clone()) {
                bail!("mieru tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
            }
        }
        for (port, field) in [(plan.setting.socks5_port, "socks5_port"), (plan.setting.rpc_port, "rpc_port")] {
            let label = format!("{}/{}", plan.name, field);
            if let Some(other) = seen_port.insert(port, label.clone()) {
                bail!("mieru port conflict: port {} is used by {} and {}", port, other, label);
            }
            if used_ports.contains(&port) { bail!("mieru port conflict: {} {} conflicts with another ZDT-D local port", field, port); }
        }
    }
    Ok(())
}

fn prepare_runtime_config(plan: &ProfilePlan) -> Result<()> {
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let raw = fs::read_to_string(&plan.config_path)
        .with_context(|| format!("read {}", plan.config_path.display()))?;
    let mut value: Value = serde_json::from_str(&raw)
        .with_context(|| format!("parse {}", plan.config_path.display()))?;
    sync_mieru_config_value(&mut value, &plan.name, &plan.setting)?;
    crate::common::fs::json::write_json_pretty_tmp_rename(&plan.runtime_config_path, &value)
}

fn sync_config_ports(profile: &str, setting: &ProfileSetting) -> Result<()> {
    let path = profile_root(profile).join("config.json");
    let mut value: Value = if path.exists() { crate::common::fs::json::read_json_short_ctx(&path).unwrap_or_else(|_| default_config_json(profile)) } else { default_config_json(profile) };
    sync_mieru_config_value(&mut value, profile, setting)?;
    crate::common::fs::json::write_json_pretty_tmp_rename(&path, &value)
}

fn sync_mieru_config_value(value: &mut Value, profile: &str, setting: &ProfileSetting) -> Result<()> {
    if !value.is_object() { *value = json!({}); }
    let obj = value.as_object_mut().unwrap();
    let profile_name = first_profile_name(obj.get("profiles")).unwrap_or_else(|| profile.to_string());
    obj.insert("activeProfile".to_string(), Value::String(profile_name.clone()));
    obj.insert("rpcPort".to_string(), Value::Number(serde_json::Number::from(setting.rpc_port as u64)));
    obj.insert("socks5Port".to_string(), Value::Number(serde_json::Number::from(setting.socks5_port as u64)));
    obj.insert("loggingLevel".to_string(), Value::String(setting.mieru_loglevel.to_uppercase()));
    obj.insert("socks5ListenLAN".to_string(), Value::Bool(false));
    obj.remove("httpProxyPort");
    obj.remove("httpProxyListenLAN");
    if !obj.contains_key("profiles") {
        obj.insert("profiles".to_string(), default_profiles_value(&profile_name));
    }
    Ok(())
}

fn first_profile_name(v: Option<&Value>) -> Option<String> {
    let arr = v?.as_array()?;
    for item in arr {
        let name = item.get("profileName").and_then(|x| x.as_str()).unwrap_or("").trim();
        if !name.is_empty() { return Some(name.to_string()); }
    }
    None
}

fn spawn_mieru(plan: &ProfilePlan) -> Result<()> {
    if mieru_profile_process_running(&plan.runtime_config_path) {
        info!("mieru: profile={} already running for runtime_config={}, skip spawn", plan.name, plan.runtime_config_path.display());
        return Ok(());
    }
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(&plan.mieru_log_path)
        .with_context(|| format!("open log {}", plan.mieru_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone mieru log")?;
    let mut cmd = Command::new(MIERU_BIN);
    cmd.arg("run")
        .env("MIERU_CONFIG_JSON_FILE", &plan.runtime_config_path)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    unsafe { cmd.pre_exec(|| { let _ = libc::setsid(); Ok(()) }); }
    let child = cmd.spawn().with_context(|| format!("spawn {MIERU_BIN}"))?;
    info!("mieru: spawned profile={} pid={} socks5_port={} log={}", plan.name, child.id(), plan.setting.socks5_port, plan.mieru_log_path.display());
    thread::sleep(Duration::from_millis(400));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("mieru: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.mieru_log_path.display());
    }
    Ok(())
}

fn spawn_tun2proxy(plan: &ProfilePlan) -> Result<()> {
    let proxy = format!("socks5://127.0.0.1:{}", plan.setting.socks5_port);
    if tun2socks_process_running(TUN2PROXY_BIN, &plan.setting.tun, &proxy) {
        info!("mieru: tun2proxy profile={} already running for tun={} proxy={}, skip spawn", plan.name, plan.setting.tun, proxy);
        return Ok(());
    }
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let mut cmd = build_tun2socks_command(
        Path::new(TUN2PROXY_BIN),
        &plan.setting.tun,
        &proxy,
        &plan.setting.tun2proxy_loglevel,
        plan.setting.mtu,
        &plan.profile_dir,
    );
    let child = spawn_detached_logged_with_clone_context(
        &mut cmd,
        &plan.tun2proxy_log_path,
        LogMode::Truncate,
        || "clone mieru tun2proxy log".to_string(),
        || format!("spawn {TUN2PROXY_BIN} for mieru"),
    )?;
    info!("mieru: spawned tun2proxy profile={} pid={} tun={} proxy={} log={}", plan.name, child.id(), plan.setting.tun, proxy, plan.tun2proxy_log_path.display());
    Ok(())
}

fn mieru_profile_process_running(runtime_config: &Path) -> bool {
    let config = runtime_config.display().to_string();
    any_live_ps_ef_where(|line| line.contains(&config) && line.contains("mieru run"))
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(r#"^/data/adb/modules/ZDT-D/bin/mieru run$"#);
    if pids.is_empty() {
        pids = ps_ef_pids_where(|line| {
            line.contains("/data/adb/modules/ZDT-D/bin/mieru") && line.contains(" run")
        });
    }
    filter_live_pids(&mut pids);
    pids
}

pub fn tun2proxy_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(
        r#"^/data/adb/modules/ZDT-D/bin/tun2socks --device tun://.* --proxy socks5://127\.0\.0\.1:[0-9]+.*$"#,
    );
    filter_live_pids(&mut pids);
    pids
}

fn default_setting_for_profile(profile: &str) -> ProfileSetting {
    let mut s = ProfileSetting::default();
    let idx = profile_index(profile).unwrap_or(0);
    s.tun = format!("mrtun{}", idx);
    s
}

fn profile_index(profile: &str) -> Option<u32> {
    let digits = profile.strip_prefix("profile")?;
    digits.parse::<u32>().ok().map(|n| n.saturating_sub(1))
}

fn default_profiles_value(profile_name: &str) -> Value {
    json!([
        {
            "profileName": profile_name,
            "user": { "name": "", "password": "" },
            "servers": [
                {
                    "domainName": "",
                    "portBindings": [ { "port": 443, "protocol": "TCP" } ]
                }
            ],
            "mtu": 1400,
            "multiplexing": { "level": "MULTIPLEXING_HIGH" },
            "handshakeMode": "HANDSHAKE_STANDARD"
        }
    ])
}

fn default_config_json(profile: &str) -> Value {
    let profile_name = if profile.trim().is_empty() { "default" } else { profile.trim() };
    json!({
        "profiles": default_profiles_value(profile_name),
        "activeProfile": profile_name,
        "rpcPort": default_rpc_port(),
        "socks5Port": default_socks5_port(),
        "loggingLevel": default_mieru_loglevel(),
        "socks5ListenLAN": false
    })
}
