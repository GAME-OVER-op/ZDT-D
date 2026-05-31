use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Deserializer, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::{Read, Write},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicBool, Ordering},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::{
    android::pkg_uid,
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
    vpn_tether::VpnTetherProfile,
};

const AWG_GO_BIN: &str = "/data/adb/modules/ZDT-D/bin/amneziawg-go";
const AWG_BIN: &str = "/data/adb/modules/ZDT-D/bin/awg";
const AMNEZIAWG_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg";
const AMNEZIAWG_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/amneziawg/active.json";
const SOCK_DIR: &str = "/var/run/amneziawg";
const NETID_BASE: u32 = 25200;
const NETID_MAX: u32 = 25999;
const LINK_WAIT: Duration = Duration::from_secs(15);
const TUN_WAIT: Duration = Duration::from_secs(25);
const IP_TIMEOUT: Duration = Duration::from_secs(3);
const AWG_TIMEOUT: Duration = Duration::from_secs(10);
const DNS_TIMEOUT: Duration = Duration::from_secs(4);
const HEALTH_IDLE_SLEEP: Duration = Duration::from_secs(2);
const HEALTH_INTERVAL_SEC: u64 = 30;
const HEALTH_LIFETIME_SEC: u64 = 180;
const HEALTH_GRACE_SEC: u64 = 120;
const HEALTH_STALE_LOG_COOLDOWN_SEC: u64 = 60;

static HEALTH_SUPERVISOR_RUNNING: AtomicBool = AtomicBool::new(false);

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
    #[serde(default, deserialize_with = "deserialize_address")]
    pub address: Vec<String>,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default = "default_mtu")]
    pub mtu: u32,
    #[serde(default = "default_endpoint_resolve")]
    pub endpoint_resolve: bool,
    #[serde(default)]
    pub strip_fwmark: bool,
}

fn default_mtu() -> u32 { 1280 }
fn default_endpoint_resolve() -> bool { true }
impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "awg0".to_string(),
            address: Vec::new(),
            dns: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            mtu: default_mtu(),
            endpoint_resolve: default_endpoint_resolve(),
            strip_fwmark: false,
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    config_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    go_log_path: PathBuf,
    awg_log_path: PathBuf,
    start_log_path: PathBuf,
    health_log_path: PathBuf,
    health_state_path: PathBuf,
}

#[derive(Debug, Clone)]
struct TunInfo {
    cidr: String,
    gateway: Option<String>,
}

#[derive(Debug, Clone)]
struct ImportedConfig {
    config: String,
    setting: ProfileSetting,
}

fn deserialize_address<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    out.extend(split_address_text(s));
                }
            }
        }
        serde_json::Value::String(s) => out.extend(split_address_text(&s)),
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("address must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    for part in split_dns_text(s) {
                        out.push(part);
                    }
                }
            }
        }
        serde_json::Value::String(s) => {
            for part in split_dns_text(&s) {
                out.push(part);
            }
        }
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn split_address_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .filter_map(|x| normalize_ipv4_cidr_token(x.trim()))
        .collect()
}

fn split_dns_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .map(str::trim)
        .filter(|x| !x.is_empty() && is_ipv4(x))
        .map(ToOwned::to_owned)
        .collect()
}

pub fn root_path() -> PathBuf { PathBuf::from(AMNEZIAWG_ROOT) }
pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(AMNEZIAWG_PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("amneziawg profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("tmp"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    ensure_file_empty(&root.join("app/out/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    let config_path = root.join("client.conf");
    if !config_path.exists() {
        fs::write(&config_path, "")?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(AMNEZIAWG_PROFILE_ROOT)?;
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

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    if setting.address.is_empty() || setting.address.len() > 4 {
        bail!("address must contain 1..4 IPv4 CIDR addresses");
    }
    let mut address_cidrs = Vec::<String>::new();
    for addr in &setting.address {
        if !is_cidr(addr) {
            bail!("invalid IPv4 CIDR address: {addr}");
        }
        let cidr = normalize_cidr_network(addr)?;
        for other in &address_cidrs {
            if cidrs_overlap(&cidr, other)? {
                bail!("address CIDR {cidr} overlaps with another address CIDR {other} in the same profile");
            }
        }
        address_cidrs.push(cidr);
    }
    if setting.dns.is_empty() || setting.dns.len() > 8 {
        bail!("dns must contain 1..8 IPv4 addresses");
    }
    for dns in &setting.dns {
        if !is_ipv4(dns) {
            bail!("invalid IPv4 DNS: {dns}");
        }
    }
    if setting.mtu < 576 || setting.mtu > 9000 {
        bail!("mtu must be in range 576..9000");
    }
    Ok(())
}

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let mut setting: ProfileSetting = serde_json::from_value(value).context("bad amneziawg setting.json")?;
    setting.address.sort();
    setting.address.dedup();
    setting.dns.sort();
    setting.dns.dedup();
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn import_config(profile: &str, raw: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let base = read_setting(profile).unwrap_or_default();
    let imported = normalize_config(raw, base)
        .with_context(|| format!("normalize amneziawg config for profile {profile}"))?;
    let root = profile_root(profile);
    validate_enabled_address_uniqueness_with_override(
        Some(profile),
        Some(&imported.setting),
        None,
    )?;
    write_text_atomic(&root.join("client.conf"), &imported.config)?;
    write_json_pretty(&root.join("setting.json"), &imported.setting)?;
    Ok(())
}

pub fn normalize_config_in_place(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let root = profile_root(profile);
    let config_path = root.join("client.conf");
    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    if raw.trim().is_empty() {
        bail!("client.conf is empty: {}", config_path.display());
    }
    let base = read_setting(profile).unwrap_or_default();
    let imported = normalize_config(&raw, base)
        .with_context(|| format!("normalize {}", config_path.display()))?;
    validate_enabled_address_uniqueness_with_override(
        Some(profile),
        Some(&imported.setting),
        None,
    )?;
    if imported.config != raw {
        write_text_atomic(&config_path, &imported.config)?;
    }
    write_json_pretty(&root.join("setting.json"), &imported.setting)?;
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
        validate_setting(&setting).with_context(|| format!("amneziawg profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("amneziawg tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
        }
    }
    Ok(())
}

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            for cidr in cidr_claims_for_setting(&setting) {
                out.push((format!("amneziawg/{name}"), cidr));
            }
        }
    }
    out
}

pub fn validate_enabled_address_uniqueness_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_enabled: Option<bool>,
) -> Result<()> {
    let local = enabled_cidr_claims_with_override(override_profile, override_setting, override_enabled)?;
    validate_cidr_claims_unique(&local)?;

    // Early protection against CIDRs that are known before runtime.
    // OpenVPN and myvpn cidr_mode=auto learn their CIDR only after the TUN
    // exists; vpn_netd remains the final runtime guard for those cases.
    let mut known_other = Vec::<(String, String)>::new();
    known_other.extend(crate::programs::tun2socks::enabled_cidr_claims());
    known_other.extend(crate::programs::myvpn::enabled_cidr_claims());
    known_other.extend(crate::programs::mihomo::enabled_cidr_claims());

    validate_cidr_claims_against(&local, &known_other)
}

fn enabled_cidr_claims_with_override(
    override_profile: Option<&str>,
    override_setting: Option<&ProfileSetting>,
    override_enabled: Option<bool>,
) -> Result<Vec<(String, String)>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let mut out = Vec::<(String, String)>::new();

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
        validate_setting(&setting).with_context(|| format!("amneziawg profile={name} setting validation"))?;
        for cidr in cidr_claims_for_setting(&setting) {
            out.push((format!("amneziawg/{name}"), cidr));
        }
    }

    Ok(out)
}

fn cidr_claims_for_setting(setting: &ProfileSetting) -> Vec<String> {
    let mut out = Vec::new();
    for addr in &setting.address {
        if let Ok(cidr) = normalize_cidr_network(addr) {
            out.push(cidr);
        }
    }
    out.sort();
    out.dedup();
    out
}

fn validate_cidr_claims_unique(claims: &[(String, String)]) -> Result<()> {
    for i in 0..claims.len() {
        for j in (i + 1)..claims.len() {
            if cidrs_overlap(&claims[i].1, &claims[j].1)? {
                bail!(
                    "amneziawg IPv4 CIDR conflict: {} uses {} which overlaps with {} using {}",
                    claims[i].0,
                    claims[i].1,
                    claims[j].0,
                    claims[j].1
                );
            }
        }
    }
    Ok(())
}

fn validate_cidr_claims_against(local: &[(String, String)], other: &[(String, String)]) -> Result<()> {
    for (label, cidr) in local {
        for (other_label, other_cidr) in other {
            if cidrs_overlap(cidr, other_cidr)? {
                bail!(
                    "VPN IPv4 CIDR conflict: {label} uses {cidr} which overlaps with {other_label} using {other_cidr}"
                );
            }
        }
    }
    Ok(())
}

fn validate_plan_address_cidrs(plans: &[ProfilePlan]) -> Result<()> {
    let mut local = Vec::<(String, String)>::new();
    for plan in plans {
        for cidr in cidr_claims_for_setting(&plan.setting) {
            local.push((format!("amneziawg/{}", plan.name), cidr));
        }
    }
    validate_cidr_claims_unique(&local)?;

    let mut known_other = Vec::<(String, String)>::new();
    known_other.extend(crate::programs::tun2socks::enabled_cidr_claims());
    known_other.extend(crate::programs::myvpn::enabled_cidr_claims());
    known_other.extend(crate::programs::mihomo::enabled_cidr_claims());
    validate_cidr_claims_against(&local, &known_other)
}

fn app_list_has_real_apps(raw: &str) -> bool {
    raw.lines().map(str::trim).any(|s| {
        !s.is_empty() && !s.starts_with('#') && !pkg_uid::is_launch_marker_package(s)
    })
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen: BTreeMap<String, String> = BTreeMap::new();
    for plan in plans {
        if let Some(other) = seen.insert(plan.setting.tun.clone(), plan.name.clone()) {
            bail!("amneziawg tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
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

    if enabled_names.is_empty() { return Ok(()); }

    let mut errors = Vec::<String>::new();
    if !Path::new(AWG_GO_BIN).is_file() {
        errors.push(format!("binary missing: {AWG_GO_BIN}"));
    }
    if !Path::new(AWG_BIN).is_file() {
        errors.push(format!("binary missing: {AWG_BIN}"));
    }

    let mut seen_tuns = BTreeMap::<String, String>::new();
    let mut seen_cidrs = Vec::<(String, String)>::new();
    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;
            if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
            }
            for cidr in cidr_claims_for_setting(&setting) {
                for (other_name, other_cidr) in &seen_cidrs {
                    if cidrs_overlap(&cidr, other_cidr)? {
                        bail!("address CIDR {cidr} overlaps with profile={other_name} CIDR {other_cidr}");
                    }
                }
                seen_cidrs.push((name.clone(), cidr));
            }
            let config_path = profile_dir.join("client.conf");
            let cfg = fs::read_to_string(&config_path)
                .with_context(|| format!("read {}", config_path.display()))?;
            if cfg.trim().is_empty() {
                bail!("client.conf is empty: {}", config_path.display());
            }
            validate_config_minimum(&cfg)?;
            let app_in = profile_dir.join("app/uid/user_program");
            let apps_raw = fs::read_to_string(&app_in)
                .with_context(|| format!("read {}", app_in.display()))?;
            let selected_for_hotspot = crate::settings::load_api_settings()
                .map(|st| st.hotspot_vpn_profile_for("amneziawg") == Some(name.as_str()))
                .unwrap_or(false);
            if !selected_for_hotspot && !app_list_has_real_apps(&apps_raw) {
                bail!("app list has no real apps: {}", app_in.display());
            }
            Ok(())
        })();

        if let Err(e) = profile_res {
            errors.push(format!("{name}: {e:#}"));
        }
    }

    if errors.is_empty() { Ok(()) } else { bail!("amneziawg start plan has issue(s): {}", errors.join("; ")) }
}

pub fn has_enabled_profiles() -> bool {
    read_active()
        .map(|a| a.profiles.values().any(|st| st.enabled))
        .unwrap_or(false)
}

pub fn has_profiles_requiring_netd() -> bool {
    let Ok(active) = read_active() else { return false; };
    active.profiles.iter().any(|(name, st)| {
        if !st.enabled { return false; }
        let app_in = profile_root(name).join("app/uid/user_program");
        fs::read_to_string(&app_in)
            .map(|raw| app_list_has_real_apps(&raw))
            .unwrap_or(false)
    })
}

pub fn is_running() -> bool { !main_pids_exact().is_empty() }

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        if let Ok(setting) = read_setting(&name) {
            out.push((format!("amneziawg/{name}"), setting.tun));
        }
    }
    out
}

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::vpn_netd::start_profiles(profiles)?;
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;

    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("amneziawg: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("AmneziaWG: запуск");

    if !Path::new(AWG_GO_BIN).is_file() || !Path::new(AWG_BIN).is_file() {
        warn!("amneziawg: binary not found: {AWG_GO_BIN} or {AWG_BIN} -> skip");
        crate::logging::user_warn("AmneziaWG: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    info!("amneziawg: start requested enabled_profiles={}", enabled_names.join(","));

    let hotspot_vpn_profile = crate::settings::load_api_settings()
        .ok()
        .and_then(|st| st.hotspot_vpn_profile_for("amneziawg").map(|name| name.to_string()));
    let mut plans = Vec::new();
    let mut had_error = false;
    for name in enabled_names {
        let selected_for_hotspot = hotspot_vpn_profile.as_deref() == Some(name.as_str());
        match build_profile_plan(&name, selected_for_hotspot) {
            Ok(plan) => {
                let apps_raw = fs::read_to_string(&plan.app_in).unwrap_or_default();
                if selected_for_hotspot && !app_list_has_real_apps(&apps_raw) {
                    info!("amneziawg: profile '{}' is selected for hotspot VPN only; skipping vpn_netd", plan.name);
                    continue;
                }
                plans.push(plan);
            }
            Err(e) => {
                if selected_for_hotspot {
                    info!("amneziawg: profile '{name}' skipped in vpn_netd phase; hotspot VPN will handle it separately: {e:#}");
                } else {
                    had_error = true;
                    warn!("amneziawg: profile '{name}' skip: {e:#}");
                }
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("AmneziaWG: ошибка запуска, запуск продолжен");
        }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_plan_tuns_unique(&plans) {
        warn!("amneziawg: enabled profiles have tun conflict: {e:#}");
        crate::logging::user_warn("AmneziaWG: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }
    if let Err(e) = validate_plan_address_cidrs(&plans) {
        warn!("amneziawg: enabled profiles have IPv4 CIDR conflict: {e:#}");
        crate::logging::user_warn("AmneziaWG: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    let mut used_cidrs = Vec::<(String, String)>::new();
    let mut used_netids = BTreeSet::<u32>::new();

    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            append_start_log(plan, &format!("start profile={} tun={}", plan.name, plan.setting.tun));
            info!(
                "amneziawg: starting profile={} tun={} address={} dns={} mtu={} config={}",
                plan.name,
                plan.setting.tun,
                plan.setting.address.join(","),
                plan.setting.dns.join(","),
                plan.setting.mtu,
                plan.config_path.display()
            );
            spawn_amneziawg(plan)?;
            wait_link_ready(&plan.setting.tun)
                .with_context(|| format!("amneziawg profile={} wait link tun={}", plan.name, plan.setting.tun))?;
            apply_awg_config(plan)?;
            apply_interface_settings(plan)?;
            wait_tun_ready(&plan.setting.tun)
                .with_context(|| format!("amneziawg profile={} wait tun={}", plan.name, plan.setting.tun))?;
            let tun = inspect_tun(&plan.setting.tun)
                .with_context(|| format!("amneziawg profile={} inspect tun={}", plan.name, plan.setting.tun))?;
            info!(
                "amneziawg: tun ready profile={} tun={} cidr={} gateway={}",
                plan.name,
                plan.setting.tun,
                tun.cidr,
                tun.gateway.as_deref().unwrap_or("none")
            );

            for (other_name, other_cidr) in &used_cidrs {
                if cidrs_overlap(&tun.cidr, other_cidr).unwrap_or(false) {
                    bail!(
                        "amneziawg profile={} cidr {} overlaps with profile={} cidr {}",
                        plan.name,
                        tun.cidr,
                        other_name,
                        other_cidr
                    );
                }
            }
            let netid = generate_netid(&used_netids)?;
            Ok(VpnNetdProfile {
                owner_program: "amneziawg".to_string(),
                profile: plan.name.clone(),
                netid,
                tun: plan.setting.tun.clone(),
                cidr: tun.cidr,
                gateway: tun.gateway,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                endpoint_escape_ips: collect_endpoint_escape_ips(plan),
            })
        })();

        match res {
            Ok(profile) => {
                used_netids.insert(profile.netid);
                used_cidrs.push((profile.profile.clone(), profile.cidr.clone()));
                profiles.push(profile);
            }
            Err(e) => {
                had_error = true;
                warn!("amneziawg: profile '{}' failed, startup continues: {e:#}", plan.name);
                append_start_log(plan, &format!("failed: {e:#}; cleanup interface"));
                cleanup_interface(&plan.setting.tun);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("AmneziaWG: часть профилей не запущена, запуск продолжен");
    }

    info!("amneziawg: prepared vpn_netd profiles count={}", profiles.len());
    start_health_supervisor_once();
    Ok(profiles)
}

pub fn start_profile_for_hotspot_vpn(profile: &str) -> Result<Option<VpnTetherProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        warn!("amneziawg: hotspot VPN profile '{}' is not enabled", profile);
        return Ok(None);
    }
    if !Path::new(AWG_GO_BIN).is_file() || !Path::new(AWG_BIN).is_file() {
        warn!("amneziawg: binary not found: {AWG_GO_BIN} or {AWG_BIN} -> skip hotspot VPN");
        return Ok(None);
    }
    let plan = build_profile_plan(profile, true)?;
    append_start_log(&plan, &format!("hotspot start profile={} tun={}", plan.name, plan.setting.tun));
    let tun = if wait_tun_ready(&plan.setting.tun).is_ok() {
        info!("amneziawg: hotspot VPN reusing ready tun={}", plan.setting.tun);
        inspect_tun(&plan.setting.tun)
            .with_context(|| format!("amneziawg hotspot profile={} inspect existing tun={}", plan.name, plan.setting.tun))?
    } else {
        spawn_amneziawg(&plan)?;
        wait_link_ready(&plan.setting.tun)
            .with_context(|| format!("amneziawg hotspot profile={} wait link tun={}", plan.name, plan.setting.tun))?;
        apply_awg_config(&plan)?;
        apply_interface_settings(&plan)?;
        wait_tun_ready(&plan.setting.tun)
            .with_context(|| format!("amneziawg hotspot profile={} wait tun={}", plan.name, plan.setting.tun))?;
        inspect_tun(&plan.setting.tun)
            .with_context(|| format!("amneziawg hotspot profile={} inspect tun={}", plan.name, plan.setting.tun))?
    };
    start_health_supervisor_once();
    Ok(Some(VpnTetherProfile {
        owner_program: "amneziawg".to_string(),
        profile: plan.name.clone(),
        tun: plan.setting.tun.clone(),
        cidr: tun.cidr,
        gateway: tun.gateway,
        dns: plan.setting.dns.clone(),
    }))
}

fn build_profile_plan(profile: &str, allow_empty_apps: bool) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    normalize_config_in_place(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let config_path = profile_dir.join("client.conf");
    if !config_path.is_file() {
        bail!("client.conf missing: {}", config_path.display());
    }
    let cfg = fs::read_to_string(&config_path).unwrap_or_default();
    if cfg.trim().is_empty() {
        bail!("client.conf is empty: {}", config_path.display());
    }
    validate_config_minimum(&cfg)?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    ensure_file_empty(&app_out)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if !allow_empty_apps && !app_list_has_real_apps(&apps_raw) {
        bail!("app list has no real apps: {}", app_in.display());
    }

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        config_path,
        app_in,
        app_out,
        go_log_path: profile_dir.join("log/amneziawg-go.log"),
        awg_log_path: profile_dir.join("log/awg.log"),
        start_log_path: profile_dir.join("log/start.log"),
        health_log_path: profile_dir.join("log/health.log"),
        health_state_path: profile_dir.join("log/health_state.txt"),
    })
}

fn normalize_config(raw: &str, mut setting: ProfileSetting) -> Result<ImportedConfig> {
    let mut kept = Vec::<String>::new();
    let mut in_interface = false;
    let mut saw_interface = false;
    let mut saw_peer = false;
    let mut extracted_address = Vec::<String>::new();
    let mut extracted_dns = Vec::<String>::new();
    let mut extracted_mtu = None::<u32>;

    let normalized_raw = raw.replace('\r', "");
    for line in normalized_raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            let section = trimmed.trim_start_matches('[').trim_end_matches(']').trim().to_ascii_lowercase();
            in_interface = section == "interface";
            if in_interface { saw_interface = true; }
            if section == "peer" { saw_peer = true; }
            kept.push(trimmed.to_string());
            continue;
        }

        if in_interface {
            if let Some((key_raw, val_raw)) = trimmed.split_once('=') {
                let key = key_raw.trim().to_ascii_lowercase();
                let val = strip_inline_comment(val_raw.trim());
                match key.as_str() {
                    "address" => {
                        extracted_address.extend(split_address_text(&val));
                        continue;
                    }
                    "dns" => {
                        extracted_dns.extend(split_dns_text(&val));
                        continue;
                    }
                    "mtu" => {
                        let mtu = val.trim().parse::<u32>()
                            .with_context(|| format!("bad MTU value: {val}"))?;
                        extracted_mtu = Some(mtu);
                        continue;
                    }
                    "table" | "preup" | "postup" | "predown" | "postdown" | "saveconfig" => {
                        continue;
                    }
                    "fwmark" if setting.strip_fwmark => {
                        continue;
                    }
                    _ => {}
                }
            }
        }

        kept.push(line.to_string());
    }

    while kept.last().map(|s| s.trim().is_empty()).unwrap_or(false) {
        kept.pop();
    }

    if !extracted_address.is_empty() {
        extracted_address.sort();
        extracted_address.dedup();
        setting.address = extracted_address;
    }
    if !extracted_dns.is_empty() {
        extracted_dns.sort();
        extracted_dns.dedup();
        setting.dns = extracted_dns;
    }
    if let Some(mtu) = extracted_mtu {
        setting.mtu = mtu;
    }

    validate_setting(&setting)?;
    let normalized = format!("{}\n", kept.join("\n"));
    if !saw_interface || !saw_peer {
        bail!("config must contain [Interface] and [Peer] sections");
    }
    validate_config_minimum(&normalized)?;
    Ok(ImportedConfig { config: normalized, setting })
}

fn validate_config_minimum(raw: &str) -> Result<()> {
    let mut section = String::new();
    let mut has_interface = false;
    let mut has_peer = false;
    let mut has_private_key = false;
    let mut has_public_key = false;
    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('#') || trimmed.starts_with(';') || trimmed.is_empty() {
            continue;
        }
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            section = trimmed.trim_start_matches('[').trim_end_matches(']').trim().to_ascii_lowercase();
            if section == "interface" { has_interface = true; }
            if section == "peer" { has_peer = true; }
            continue;
        }
        let Some((key_raw, _)) = trimmed.split_once('=') else { continue; };
        let key = key_raw.trim().to_ascii_lowercase();
        if section == "interface" && key == "privatekey" {
            has_private_key = true;
        }
        if section == "peer" && key == "publickey" {
            has_public_key = true;
        }
        if section == "interface" && matches!(key.as_str(), "address" | "dns" | "mtu" | "table" | "preup" | "postup" | "predown" | "postdown" | "saveconfig") {
            bail!("client.conf must be normalized before awg setconf; unsupported [Interface] key: {key}");
        }
    }
    if !has_interface || !has_peer || !has_private_key || !has_public_key {
        bail!("client.conf must contain [Interface] PrivateKey and [Peer] PublicKey");
    }
    Ok(())
}

fn strip_inline_comment(s: &str) -> String {
    let mut out = s.trim().to_string();
    for marker in [" #", " ;"] {
        if let Some(pos) = out.find(marker) {
            out.truncate(pos);
        }
    }
    out.trim().trim_matches('"').trim_matches('\'').to_string()
}

fn spawn_amneziawg(plan: &ProfilePlan) -> Result<()> {
    if amneziawg_process_running(&plan.setting.tun) {
        info!("amneziawg: profile={} tun={} already running, skip spawn", plan.name, plan.setting.tun);
        append_start_log(plan, "amneziawg-go already running; skip spawn");
        return Ok(());
    }

    cleanup_interface(&plan.setting.tun);
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    fs::create_dir_all(Path::new(SOCK_DIR)).ok();

    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.go_log_path)
        .with_context(|| format!("open log {}", plan.go_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone amneziawg-go log")?;

    let mut cmd = Command::new(AWG_GO_BIN);
    cmd.arg("-f")
        .arg(&plan.setting.tun)
        .current_dir(&plan.profile_dir)
        .env("WG_PROCESS_FOREGROUND", "1")
        .env("LOG_LEVEL", "error")
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {AWG_GO_BIN}"))?;
    info!("amneziawg: spawned profile={} pid={} tun={} log={}", plan.name, child.id(), plan.setting.tun, plan.go_log_path.display());
    append_start_log(plan, &format!("spawned amneziawg-go pid={}", child.id()));

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("amneziawg: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.go_log_path.display());
    }
    Ok(())
}

fn amneziawg_process_running(tun: &str) -> bool {
    let pattern = format!("{} -f {}", AWG_GO_BIN, tun);
    let cmd = format!(
        "ps -ef 2>/dev/null | grep -F {} | grep -v grep >/dev/null 2>&1",
        shell_quote_for_sh(&pattern)
    );
    shell::ok_sh(&cmd).is_ok()
}

fn apply_awg_config(plan: &ProfilePlan) -> Result<()> {
    let setconf_path = match prepare_setconf_config(plan) {
        Ok(path) => path,
        Err(e) => {
            append_start_log(plan, &format!("prepare awg setconf failed: {e:#}"));
            cleanup_interface(&plan.setting.tun);
            return Err(e);
        }
    };

    append_start_log(plan, &format!("awg setconf config={}", setconf_path.display()));
    let setconf_s = setconf_path.to_string_lossy().to_string();
    let args = ["setconf", plan.setting.tun.as_str(), setconf_s.as_str()];
    let setconf_res = run_command_timeout_with_env(
        AWG_BIN,
        &args,
        &[("WG_ENDPOINT_RESOLUTION_RETRIES", "1")],
        AWG_TIMEOUT,
    );

    let (code, out) = match setconf_res {
        Ok(v) => v,
        Err(e) => {
            append_awg_log(plan, &format!("$ WG_ENDPOINT_RESOLUTION_RETRIES=1 {} setconf {} {}\nERROR: {e:#}\n", AWG_BIN, plan.setting.tun, setconf_path.display()));
            cleanup_interface(&plan.setting.tun);
            bail!("awg setconf failed: {e:#}");
        }
    };

    append_awg_log(plan, &format!("$ WG_ENDPOINT_RESOLUTION_RETRIES=1 {} setconf {} {}\nrc={}\n{}\n", AWG_BIN, plan.setting.tun, setconf_path.display(), code, out));
    if code != 0 {
        cleanup_interface(&plan.setting.tun);
        bail!("awg setconf failed rc={code}: {}", out.trim());
    }
    let (show_code, show_out) = shell::run_timeout(AWG_BIN, &["show", &plan.setting.tun], Capture::Both, AWG_TIMEOUT)
        .unwrap_or((1, String::new()));
    append_awg_log(plan, &format!("$ {} show {}\nrc={}\n{}\n", AWG_BIN, plan.setting.tun, show_code, show_out));
    Ok(())
}

fn prepare_setconf_config(plan: &ProfilePlan) -> Result<PathBuf> {
    if !plan.setting.endpoint_resolve {
        return Ok(plan.config_path.clone());
    }
    let raw = fs::read_to_string(&plan.config_path)
        .with_context(|| format!("read {}", plan.config_path.display()))?;
    let resolved = resolve_endpoint_lines(&raw)
        .with_context(|| format!("resolve Endpoint for {}", plan.config_path.display()))?;
    let tmp = plan.profile_dir.join("tmp/client.resolved.conf");
    write_text_atomic(&tmp, &resolved)?;
    append_start_log(plan, &format!("endpoint_resolve enabled; using {}", tmp.display()));
    Ok(tmp)
}

fn resolve_endpoint_lines(raw: &str) -> Result<String> {
    let mut out = Vec::new();
    let mut endpoint_count = 0usize;
    for line in raw.lines() {
        let trimmed = line.trim();
        if let Some((key_raw, val_raw)) = trimmed.split_once('=') {
            if key_raw.trim().eq_ignore_ascii_case("Endpoint") {
                endpoint_count += 1;
                let val = strip_inline_comment(val_raw.trim());
                let Some((host, port)) = parse_host_port(&val) else {
                    bail!("unsupported Endpoint format for userspace IPv4 mode: {val}");
                };
                if is_ipv4(&host) {
                    out.push(line.to_string());
                    continue;
                }
                let ip = resolve_host_ipv4(&host)
                    .ok_or_else(|| anyhow::anyhow!("cannot resolve Endpoint host {host} to IPv4"))?;
                out.push(format!("Endpoint = {ip}:{port}"));
                continue;
            }
        }
        out.push(line.to_string());
    }
    if endpoint_count == 0 {
        return Ok(format!("{}\n", out.join("\n")));
    }
    Ok(format!("{}\n", out.join("\n")))
}

fn collect_endpoint_escape_ips(plan: &ProfilePlan) -> Vec<String> {
    let raw = match fs::read_to_string(&plan.config_path) {
        Ok(raw) => raw,
        Err(e) => {
            warn!(
                "amneziawg: profile={} cannot read config for endpoint escape: {e:#}",
                plan.name
            );
            return Vec::new();
        }
    };
    let mut ips = Vec::new();
    for line in raw.lines() {
        let trimmed = line.trim();
        let Some((key_raw, val_raw)) = trimmed.split_once('=') else { continue; };
        if !key_raw.trim().eq_ignore_ascii_case("Endpoint") {
            continue;
        }
        let val = strip_inline_comment(val_raw.trim());
        let Some((host, _port)) = parse_host_port(&val) else {
            warn!(
                "amneziawg: profile={} skip endpoint escape for unsupported Endpoint format: {}",
                plan.name,
                val
            );
            continue;
        };
        if is_ipv4(&host) {
            ips.push(host);
        } else if plan.setting.endpoint_resolve {
            match resolve_host_ipv4(&host) {
                Some(ip) => ips.push(ip),
                None => warn!(
                    "amneziawg: profile={} cannot resolve Endpoint host for endpoint escape: {}",
                    plan.name,
                    host
                ),
            }
        } else {
            warn!(
                "amneziawg: profile={} Endpoint host is not IPv4 and endpoint_resolve=false; endpoint escape route skipped for {}",
                plan.name,
                host
            );
        }
    }
    ips.sort();
    ips.dedup();
    if !ips.is_empty() {
        info!(
            "amneziawg: profile={} endpoint escape ips={}",
            plan.name,
            ips.join(",")
        );
        append_start_log(plan, &format!("endpoint escape ips={}", ips.join(",")));
    }
    ips
}

fn parse_host_port(s: &str) -> Option<(String, String)> {
    if s.contains('[') || s.matches(':').count() != 1 {
        return None;
    }
    let (host, port) = s.rsplit_once(':')?;
    let host = host.trim().trim_end_matches('.');
    let port = port.trim();
    if host.is_empty() || port.is_empty() || !port.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    if !host.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-') {
        return None;
    }
    Some((host.to_string(), port.to_string()))
}

fn resolve_host_ipv4(host: &str) -> Option<String> {
    let attempts: [(&str, Vec<&str>); 4] = [
        ("toybox", vec!["nslookup", host, "1.1.1.1"]),
        ("nslookup", vec![host, "1.1.1.1"]),
        ("toybox", vec!["nslookup", host]),
        ("nslookup", vec![host]),
    ];
    for (cmd, args) in attempts {
        let Ok((code, out)) = shell::run_timeout(cmd, &args, Capture::Both, DNS_TIMEOUT) else { continue; };
        if code != 0 { continue; }
        if let Some(ip) = first_resolved_ipv4_from_text(&out) {
            return Some(ip);
        }
    }

    if let Ok((code, out)) = shell::run_timeout("ping", &["-c", "1", "-W", "2", host], Capture::Both, DNS_TIMEOUT) {
        if code == 0 {
            return first_ipv4_from_text(&out);
        }
    }
    None
}

fn first_ipv4_from_text(text: &str) -> Option<String> {
    for raw in text.split(|c: char| c.is_ascii_whitespace() || matches!(c, ',' | ';' | '(' | ')' | '[' | ']' | '#')) {
        let token = raw.trim();
        if is_ipv4(token) {
            return Some(token.to_string());
        }
    }
    None
}

fn first_resolved_ipv4_from_text(text: &str) -> Option<String> {
    for raw in text.split(|c: char| c.is_ascii_whitespace() || matches!(c, ',' | ';' | '(' | ')' | '[' | ']' | '#')) {
        let token = raw.trim();
        if is_ipv4(token) && !matches!(token, "0.0.0.0" | "1.1.1.1" | "8.8.8.8" | "127.0.0.1") {
            return Some(token.to_string());
        }
    }
    None
}

fn run_command_timeout_with_env(
    cmd: &str,
    args: &[&str],
    envs: &[(&str, &str)],
    timeout: Duration,
) -> Result<(i32, String)> {
    info!("exec(timeout={:?}): {} {}", timeout, cmd, args.join(" "));

    let tmp_dir = if Path::new("/data/local/tmp").is_dir() {
        PathBuf::from("/data/local/tmp")
    } else {
        std::env::temp_dir()
    };
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let stamp = format!("zdtd_amneziawg_{}_{}", std::process::id(), nanos);
    let out_path = tmp_dir.join(format!("{stamp}.out"));
    let err_path = tmp_dir.join(format!("{stamp}.err"));

    let out_file = fs::File::create(&out_path)
        .with_context(|| format!("create stdout temp {}", out_path.display()))?;
    let err_file = fs::File::create(&err_path)
        .with_context(|| format!("create stderr temp {}", err_path.display()))?;

    let mut command = Command::new(cmd);
    command.args(args);
    for (k, v) in envs {
        command.env(k, v);
    }
    command.stdout(Stdio::from(out_file));
    command.stderr(Stdio::from(err_file));

    let mut child = command.spawn().with_context(|| format!("spawn {cmd}"))?;
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait().with_context(|| format!("wait {cmd}"))? {
            let code = status.code().unwrap_or(-1);
            let mut out = read_and_remove(&out_path);
            out.push_str(&read_and_remove(&err_path));
            return Ok((code, out));
        }
        if start.elapsed() >= timeout {
            let _ = child.kill();
            let _ = child.wait();
            let mut out = read_and_remove(&out_path);
            out.push_str(&read_and_remove(&err_path));
            bail!("command timeout after {:?}: {} {}\n{}", timeout, cmd, args.join(" "), out);
        }
        thread::sleep(Duration::from_millis(50));
    }
}

fn read_and_remove(path: &Path) -> String {
    let mut s = String::new();
    if let Ok(mut f) = fs::File::open(path) {
        let _ = f.read_to_string(&mut s);
    }
    let _ = fs::remove_file(path);
    s
}

fn apply_interface_settings(plan: &ProfilePlan) -> Result<()> {
    for addr in &plan.setting.address {
        let res = shell::run_timeout("ip", &["addr", "add", addr, "dev", &plan.setting.tun], Capture::Both, IP_TIMEOUT);
        let (code, out) = match res {
            Ok(v) => v,
            Err(e) => {
                cleanup_interface(&plan.setting.tun);
                bail!("ip addr add failed for {addr} dev {}: {e:#}", plan.setting.tun);
            }
        };
        if code != 0 && !out.to_ascii_lowercase().contains("file exists") {
            cleanup_interface(&plan.setting.tun);
            bail!("ip addr add failed for {addr} dev {} rc={} out={}", plan.setting.tun, code, out.trim());
        }
    }

    let mtu = plan.setting.mtu.to_string();
    let link_res = shell::run_timeout("ip", &["link", "set", "dev", &plan.setting.tun, "mtu", &mtu, "up"], Capture::Both, IP_TIMEOUT);
    let (code, out) = match link_res {
        Ok(v) => v,
        Err(e) => {
            cleanup_interface(&plan.setting.tun);
            bail!("ip link set dev {} mtu {} up failed: {e:#}", plan.setting.tun, mtu);
        }
    };
    if code != 0 {
        cleanup_interface(&plan.setting.tun);
        bail!("ip link set up failed for {} rc={} out={}", plan.setting.tun, code, out.trim());
    }
    append_start_log(plan, &format!("interface up tun={} mtu={} address={}", plan.setting.tun, plan.setting.mtu, plan.setting.address.join(",")));
    Ok(())
}

fn wait_link_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= LINK_WAIT {
            bail!("tun link {tun} was not created after {:?}", LINK_WAIT);
        }
        if link_exists(tun) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn link_exists(tun: &str) -> bool {
    shell::run_timeout("ip", &["link", "show", "dev", tun], Capture::None, IP_TIMEOUT)
        .map(|(code, _)| code == 0)
        .unwrap_or(false)
}

fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        if inspect_tun(tun).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(400));
    }
}

fn inspect_tun(tun: &str) -> Result<TunInfo> {
    let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
        .with_context(|| format!("ip addr show {tun}"))?;
    if code != 0 {
        bail!("ip addr show failed for {tun}");
    }

    let mut cidr = None::<String>;
    let mut gateway = None::<String>;
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "inet" {
                if let Some(ip_cidr) = tokens.get(i + 1) {
                    cidr = derive_cidr(ip_cidr);
                }
            }
            if tokens[i] == "peer" {
                if let Some(peer) = tokens.get(i + 1) {
                    let peer_ip = peer.split('/').next().unwrap_or(peer).to_string();
                    if is_ipv4(&peer_ip) {
                        gateway = Some(peer_ip);
                    }
                }
            }
        }
    }

    let cidr = cidr.ok_or_else(|| anyhow::anyhow!("tun {tun} has no IPv4 CIDR"))?;
    if gateway.is_none() {
        gateway = route_gateway_for_tun(tun).ok();
    }
    if gateway.is_none() {
        gateway = first_host_for_cidr(&cidr);
    }
    Ok(TunInfo { cidr, gateway })
}

fn derive_cidr(ip_cidr: &str) -> Option<String> {
    let (ip, prefix_s) = ip_cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if !is_ipv4(ip) || prefix > 32 { return None; }
    let addr = ipv4_to_u32(ip)?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    let net = addr & mask;
    Some(format!("{}/{}", u32_to_ipv4(net), prefix))
}

fn route_gateway_for_tun(tun: &str) -> Result<String> {
    let (code, out) = shell::run_timeout("ip", &["-4", "route", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)?;
    if code != 0 {
        bail!("ip route show dev {tun} failed");
    }
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "via" {
                if let Some(via) = tokens.get(i + 1) {
                    if is_ipv4(via) {
                        return Ok((*via).to_string());
                    }
                }
            }
        }
    }
    bail!("no gateway via route for {tun}")
}

fn first_host_for_cidr(cidr: &str) -> Option<String> {
    let (ip, prefix_s) = cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if prefix > 30 { return None; }
    let net = ipv4_to_u32(ip)?;
    Some(u32_to_ipv4(net.saturating_add(1)))
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) {
            return Ok(id);
        }
    }
    bail!("no free netid in range {NETID_BASE}..={NETID_MAX}")
}

fn normalize_cidr_network(cidr: &str) -> Result<String> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok(format!("{}/{}", u32_to_ipv4(addr & mask), prefix))
}

fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, am) = cidr_network_mask(a)?;
    let (bn, bm) = cidr_network_mask(b)?;
    let a_start = an;
    let a_end = an | !am;
    let b_start = bn;
    let b_end = bn | !bm;
    Ok(a_start <= b_end && b_start <= a_end)
}

fn cidr_network_mask(cidr: &str) -> Result<(u32, u32)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
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

fn is_ipv4(s: &str) -> bool {
    let parts = s.split('.').collect::<Vec<_>>();
    if parts.len() != 4 { return false; }
    parts.iter().all(|p| !p.is_empty() && p.parse::<u8>().is_ok())
}

fn is_cidr(s: &str) -> bool {
    let Some((ip, prefix)) = s.split_once('/') else { return false; };
    let Ok(prefix) = prefix.parse::<u8>() else { return false; };
    is_ipv4(ip) && prefix <= 32
}

fn normalize_ipv4_cidr_token(s: &str) -> Option<String> {
    if s.is_empty() || s.contains(':') { return None; }
    if let Some((ip, prefix)) = s.split_once('/') {
        let prefix = prefix.parse::<u8>().ok()?;
        if is_ipv4(ip) && prefix <= 32 {
            return Some(format!("{ip}/{prefix}"));
        }
        return None;
    }
    if is_ipv4(s) {
        return Some(format!("{s}/32"));
    }
    None
}

fn ipv4_to_u32(s: &str) -> Option<u32> {
    let mut out = 0u32;
    let mut count = 0usize;
    for part in s.split('.') {
        let n = part.parse::<u8>().ok()? as u32;
        out = (out << 8) | n;
        count += 1;
    }
    if count == 4 { Some(out) } else { None }
}

fn u32_to_ipv4(v: u32) -> String {
    format!("{}.{}.{}.{}", (v >> 24) & 0xff, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

fn ensure_file_empty(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if !path.exists() {
        fs::write(path, "")?;
    }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let txt = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_str(&txt).with_context(|| format!("parse {}", path.display()))
}

fn write_json_pretty<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(path, &txt)
}

fn write_text_atomic(path: &Path, content: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content)?;
    fs::rename(&tmp, path)?;
    Ok(())
}


#[derive(Debug, Clone, Default)]
struct HealthRuntimeState {
    first_seen: u64,
    last_check: u64,
    last_stale_log: u64,
    stale_count: u64,
}

fn start_health_supervisor_once() {
    if HEALTH_SUPERVISOR_RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }
    thread::spawn(|| {
        info!("amneziawg health: supervisor started");
        let res = std::panic::catch_unwind(health_supervisor_loop);
        if res.is_err() {
            warn!("amneziawg health: supervisor panicked");
        }
        HEALTH_SUPERVISOR_RUNNING.store(false, Ordering::SeqCst);
        info!("amneziawg health: supervisor stopped");
    });
}

fn health_supervisor_loop() {
    let mut states = BTreeMap::<String, HealthRuntimeState>::new();
    loop {
        let plans = health_enabled_plans();
        if plans.is_empty() {
            info!("amneziawg health: no enabled health profiles -> exit supervisor");
            return;
        }

        let mut known = BTreeSet::<String>::new();
        let mut checked_any = false;
        for plan in plans {
            let key = health_key(&plan);
            known.insert(key.clone());
            let now = now_epoch_secs();
            let state = states.entry(key.clone()).or_insert_with(|| HealthRuntimeState {
                first_seen: now,
                ..HealthRuntimeState::default()
            });
            let interval = HEALTH_INTERVAL_SEC.max(5);
            if state.last_check != 0 && now.saturating_sub(state.last_check) < interval {
                continue;
            }
            state.last_check = now;
            checked_any = true;
            if let Err(e) = check_health_profile(&plan, state) {
                warn!("amneziawg health: profile={} check failed: {e:#}", plan.name);
                append_health_log(&plan, &format!("check_error error={}", one_line(&format!("{e:#}"))));
                write_health_state(
                    &plan,
                    "check_error",
                    None,
                    state.stale_count,
                    Some(&format!("{e:#}")),
                );
            }
            thread::sleep(Duration::from_millis(250));
        }
        states.retain(|key, _| known.contains(key));
        if !checked_any {
            thread::sleep(HEALTH_IDLE_SLEEP);
        }
    }
}

fn health_enabled_plans() -> Vec<ProfilePlan> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(&name) else { continue; };
        if !link_exists(&setting.tun) { continue; }
        match build_profile_plan(&name, true) {
            Ok(plan) => out.push(plan),
            Err(e) => warn!("amneziawg health: profile={name} plan failed: {e:#}"),
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

fn check_health_profile(plan: &ProfilePlan, state: &mut HealthRuntimeState) -> Result<()> {
    let latest = latest_handshake_ts(&plan.setting.tun)?;
    let now = now_epoch_secs();
    let age = latest.map(|ts| now.saturating_sub(ts));
    let lifetime = HEALTH_LIFETIME_SEC.max(30);
    let grace = HEALTH_GRACE_SEC.max(10);
    let cooldown = HEALTH_STALE_LOG_COOLDOWN_SEC.max(10);

    if latest.is_none() && now.saturating_sub(state.first_seen) < grace {
        append_health_log(
            plan,
            &format!(
                "warming_up tun={} grace_left={}s",
                plan.setting.tun,
                grace.saturating_sub(now.saturating_sub(state.first_seen))
            ),
        );
        write_health_state(plan, "warming_up", age, state.stale_count, None);
        return Ok(());
    }

    if let Some(age) = age {
        if age <= lifetime {
            write_health_state(plan, "healthy", Some(age), state.stale_count, None);
            return Ok(());
        }
    }

    let stale_reason = if let Some(age) = age {
        format!("stale_handshake age={}s lifetime={}s", age, lifetime)
    } else {
        format!("no_handshake lifetime={}s grace={}s", lifetime, grace)
    };

    if state.last_stale_log != 0 && now.saturating_sub(state.last_stale_log) < cooldown {
        append_health_log(
            plan,
            &format!(
                "stale cooldown tun={} reason={} cooldown_left={}s",
                plan.setting.tun,
                stale_reason,
                cooldown.saturating_sub(now.saturating_sub(state.last_stale_log))
            ),
        );
        write_health_state(plan, "stale_cooldown", age, state.stale_count, None);
        return Ok(());
    }

    state.last_stale_log = now;
    state.stale_count = state.stale_count.saturating_add(1);
    append_health_log(
        plan,
        &format!(
            "stale_detected tun={} reason={} action=none",
            plan.setting.tun,
            stale_reason
        ),
    );
    write_health_state(plan, "stale", age, state.stale_count, None);
    Ok(())
}

fn latest_handshake_ts(tun: &str) -> Result<Option<u64>> {
    let (code, out) = shell::run_timeout(AWG_BIN, &["show", tun, "latest-handshakes"], Capture::Both, AWG_TIMEOUT)
        .with_context(|| format!("awg show {tun} latest-handshakes"))?;
    if code != 0 {
        bail!("awg show {tun} latest-handshakes failed rc={code}: {}", out.trim());
    }
    let latest = out
        .lines()
        .filter_map(|line| line.split_whitespace().nth(1))
        .filter_map(|ts| ts.parse::<u64>().ok())
        .filter(|ts| *ts > 0)
        .max();
    Ok(latest)
}

fn health_key(plan: &ProfilePlan) -> String {
    format!("{}|{}", plan.name, plan.setting.tun)
}

fn now_epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs()
}

fn append_health_log(plan: &ProfilePlan, line: &str) {
    if let Some(parent) = plan.health_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.health_log_path) {
        let _ = writeln!(f, "epoch={} profile={} {}", now_epoch_secs(), plan.name, line);
    }
}

fn write_health_state(
    plan: &ProfilePlan,
    status: &str,
    handshake_age: Option<u64>,
    stale_count: u64,
    last_error: Option<&str>,
) {
    if let Some(parent) = plan.health_state_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let latest = handshake_age
        .map(|age| age.to_string())
        .unwrap_or_else(|| "none".to_string());
    let content = format!(
        "status={}\nprofile={}\ntun={}\nlast_check_epoch={}\nlast_handshake_age={}\nstale_count={}\nlast_error={}\n",
        status,
        plan.name,
        plan.setting.tun,
        now_epoch_secs(),
        latest,
        stale_count,
        last_error.map(one_line).unwrap_or_default(),
    );
    let _ = write_text_atomic(&plan.health_state_path, &content);
}

fn one_line(s: &str) -> String {
    s.replace('\n', " ").replace('\r', " ").trim().to_string()
}

fn append_start_log(plan: &ProfilePlan, line: &str) {
    if let Some(parent) = plan.start_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.start_log_path) {
        let _ = writeln!(f, "{}", line);
    }
}

fn append_awg_log(plan: &ProfilePlan, text: &str) {
    if let Some(parent) = plan.awg_log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&plan.awg_log_path) {
        let _ = writeln!(f, "{}", text);
    }
}

fn shell_quote_for_sh(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

fn socket_path(tun: &str) -> PathBuf {
    Path::new(SOCK_DIR).join(format!("{tun}.sock"))
}

fn cleanup_interface(tun: &str) {
    let _ = shell::run_timeout("ip", &["link", "del", tun], Capture::None, IP_TIMEOUT);
    let _ = fs::remove_file(socket_path(tun));
    let pattern = format!("{} -f {}", AWG_GO_BIN, tun);
    let _ = shell::ok_sh(&format!(
        "pkill -f {} 2>/dev/null || true",
        shell_quote_for_sh(&pattern)
    ));
}

pub fn cleanup_all_interfaces() {
    let mut tuns = BTreeSet::<String>::new();
    if let Ok(rd) = fs::read_dir(profiles_root()) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            if let Ok(setting) = read_setting(profile) {
                tuns.insert(setting.tun);
            }
        }
    }
    for tun in tuns {
        cleanup_interface(&tun);
    }
}

fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = r#"sh -c "pgrep -f '^/data/adb/modules/ZDT-D/bin/amneziawg-go -f [A-Za-z0-9_.-]+$' 2>/dev/null || true""#;
    if let Ok(out) = shell::capture_quiet(cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = r#"sh -c "ps -ef 2>/dev/null | grep -F '/data/adb/modules/ZDT-D/bin/amneziawg-go -f ' | grep -v grep || true""#;
        if let Ok(out) = shell::capture_quiet(ps_cmd) {
            for line in out.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 1 {
                    if let Ok(pid) = cols[1].parse::<i32>() {
                        if pid > 1 {
                            pids.push(pid);
                        }
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}
