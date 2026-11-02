use anyhow::{bail, Context, Result};
use crate::common::apps::{enabled_app_list_empty};
use crate::common::network::{is_ipv4, is_ipv4_cidr, normalize_cidr_network, cidrs_overlap, IP_TIMEOUT, is_valid_ifname, is_forbidden_tun_name, NETID_MYVPN, wait_tun_link};
use crate::programs::registry::{ProgramId};
use crate::network::vpn::{VpnProfileCore, VpnNetdProfileSpec};
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use serde::{Deserialize, Deserializer, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
    time::Duration,
};

use crate::{
    common::process::shell::{self, Capture},
    network::vpn::netd::VpnNetdProfile,
};

const MYVPN_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myvpn";
const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::MyVpn, NETID_MYVPN);

fn stable_netid_for(profile: &str) -> Result<u32> {
    VPN_CORE.stable_netid(&read_active().unwrap_or_default(), profile)
}
const TUN_WAIT: Duration = Duration::from_secs(20);

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
    #[serde(default = "default_cidr_mode")]
    pub cidr_mode: String,
    #[serde(default)]
    pub cidr: String,
}

fn default_cidr_mode() -> String { "auto".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun9".to_string(),
            dns: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            cidr_mode: default_cidr_mode(),
            cidr: String::new(),
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    #[allow(dead_code)]
    profile_dir: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    netid: u32,
}

#[derive(Debug, Clone)]
struct TunInfo {
    cidr: String,
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
                    out.extend(crate::common::config::split_comma_whitespace(s));
                }
            }
        }
        serde_json::Value::String(s) => out.extend(crate::common::config::split_comma_whitespace(&s)),
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}


pub fn root_path() -> PathBuf { PathBuf::from(MYVPN_ROOT) }
pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(MYVPN_ROOT))
}
pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(MYVPN_ROOT))
}
pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("myvpn profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    crate::common::profile::ensure_relative_dirs(
        &root,
        &[crate::common::profile::APP_UID_DIR, crate::common::profile::APP_OUT_DIR],
    )?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_uid_program_path(&root))?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_out_program_path(&root))?;
    crate::common::profile::ensure_profile_json(
        &crate::common::profile::setting_path(&root),
        &ProfileSetting::default(),
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

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let mut setting: ProfileSetting = serde_json::from_value(value).context("bad myvpn setting.json")?;
    setting.cidr_mode = setting.cidr_mode.trim().to_ascii_lowercase();
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    if setting.dns.is_empty() || setting.dns.len() > 8 || !setting.dns.iter().all(|d| is_ipv4(d)) {
        bail!("dns must contain 1..8 IPv4 addresses");
    }
    match setting.cidr_mode.as_str() {
        "auto" => {}
        "manual" => {
            let cidr = setting.cidr.trim();
            if cidr.is_empty() || !is_ipv4_cidr(cidr) {
                bail!("cidr must be IPv4 CIDR when cidr_mode=manual");
            }
        }
        _ => bail!("cidr_mode must be auto or manual"),
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
        validate_setting(&setting).with_context(|| format!("myvpn profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("myvpn tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
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
            out.push(VPN_CORE.claim(&name, setting.tun));
        }
    }
    out
}

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(&name) else { continue; };
        if validate_setting(&setting).is_err() || enabled_app_list_empty(&crate::common::profile::app_uid_program_path(&profile_root(&name))) { continue; }
        if setting.cidr_mode == "manual" {
            if let Ok(cidr) = normalize_cidr_network(setting.cidr.trim()) {
                out.push(VPN_CORE.claim(&name, cidr));
            }
        }
    }
    out
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    VPN_CORE.validate_unique_tuns(
        plans.iter().map(|plan| (plan.name.as_str(), plan.setting.tun.as_str())),
    )
}

pub fn validate_start_plan() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);
    if enabled_names.is_empty() { return Ok(()); }

    let mut errors = Vec::<String>::new();
    let mut seen_tuns = BTreeMap::<String, String>::new();
    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;
            if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
            }
            let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
            let apps_raw = fs::read_to_string(&app_in).with_context(|| format!("read {}", app_in.display()))?;
            if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
                bail!("app list is empty: {}", app_in.display());
            }
            Ok(())
        })();
        if let Err(e) = profile_res {
            errors.push(format!("{name}: {e:#}"));
        }
    }

    if errors.is_empty() { Ok(()) } else { bail!("myvpn start plan has issue(s): {}", errors.join("; ")) }
}

pub fn has_enabled_profiles() -> bool {
    read_active().map(|a| a.profiles.values().any(|st| st.enabled)).unwrap_or(false)
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);

    if enabled_names.is_empty() {
        info!("myvpn: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("myvpn: запуск");

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
                warn!("myvpn: profile '{name}' skip: {e:#}");
            }
        }
    }

    if plans.is_empty() {
        if had_error { crate::logging::user_warn("myvpn: ошибка запуска, запуск продолжен"); }
        return Ok(Vec::new());
    }
    if let Err(e) = validate_plan_tuns_unique(&plans) {
        warn!("myvpn: enabled profiles have tun conflict: {e:#}");
        crate::logging::user_warn("myvpn: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    let mut used_cidrs = Vec::<(String, String)>::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            wait_tun_link(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("myvpn profile={} wait tun={}", plan.name, plan.setting.tun))?;
            let cidr = match plan.setting.cidr_mode.as_str() {
                "manual" => normalize_cidr_network(plan.setting.cidr.trim())?,
                _ => inspect_tun(&plan.setting.tun)
                    .with_context(|| format!("myvpn profile={} inspect tun={}", plan.name, plan.setting.tun))?
                    .cidr,
            };
            for (other_name, other_cidr) in &used_cidrs {
                if cidrs_overlap(&cidr, other_cidr).unwrap_or(false) {
                    bail!("myvpn profile={} cidr {} overlaps with profile={} cidr {}", plan.name, cidr, other_name, other_cidr);
                }
            }
            info!("myvpn: tun ready profile={} tun={} cidr={}", plan.name, plan.setting.tun, cidr);
            Ok(VPN_CORE.netd_profile(VpnNetdProfileSpec {
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.setting.tun.clone(),
                cidr,
                gateway: None,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                endpoint_escape_ips: Vec::new(),
            }))
        })();
        match res {
            Ok(profile) => {
                used_cidrs.push((profile.profile.clone(), profile.cidr.clone()));
                profiles.push(profile);
            }
            Err(e) => {
                had_error = true;
                warn!("myvpn: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("myvpn: часть профилей не применена, запуск продолжен");
    }
    info!("myvpn: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

fn build_profile_plan(profile: &str, used_netids: &BTreeSet<u32>) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
    let app_out = crate::common::profile::app_out_program_path(&profile_dir);
    crate::common::fs::ensure_empty_file(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }
    let netid = stable_netid_for(profile)?;
    if used_netids.contains(&netid) {
        bail!("netid {netid} is already used by another myvpn profile");
    }
    Ok(ProfilePlan { name: profile.to_string(), setting, profile_dir, app_in, app_out, netid })
}

fn inspect_tun(tun: &str) -> Result<TunInfo> {
    let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
        .with_context(|| format!("ip -o -4 addr show dev {tun}"))?;
    if code != 0 {
        bail!("ip addr show failed for tun {tun}: {}", out.trim());
    }
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for (i, token) in tokens.iter().enumerate() {
            if *token == "inet" {
                if let Some(ip_cidr) = tokens.get(i + 1) {
                    if let Ok(cidr) = normalize_cidr_network(ip_cidr) {
                        return Ok(TunInfo { cidr });
                    }
                }
            }
        }
    }
    bail!("tun {tun} has no IPv4 CIDR")
}
