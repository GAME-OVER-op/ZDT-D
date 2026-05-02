use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Deserializer, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
    thread,
    time::{Duration, Instant},
};

use crate::{
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
};

const MYVPN_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myvpn";
const MYVPN_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myvpn/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/myvpn/active.json";
const NETID_BASE: u32 = 23200;
const NETID_MAX: u32 = 23999;
const TUN_WAIT: Duration = Duration::from_secs(20);
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
                    out.extend(split_dns_text(s));
                }
            }
        }
        serde_json::Value::String(s) => out.extend(split_dns_text(&s)),
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
    }
    out.sort();
    out.dedup();
    Ok(out)
}

fn split_dns_text(s: &str) -> Vec<String> {
    s.split(|c: char| c == ',' || c.is_ascii_whitespace())
        .map(str::trim)
        .filter(|x| !x.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

pub fn root_path() -> PathBuf { PathBuf::from(MYVPN_ROOT) }
pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(MYVPN_PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
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
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    ensure_file_empty(&root.join("app/out/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(MYVPN_PROFILE_ROOT)?;
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
            if cidr.is_empty() || !is_cidr(cidr) {
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
            out.push((format!("myvpn/{name}"), setting.tun));
        }
    }
    out
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen: BTreeMap<String, String> = BTreeMap::new();
    for plan in plans {
        if let Some(other) = seen.insert(plan.setting.tun.clone(), plan.name.clone()) {
            bail!("myvpn tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
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
            let app_in = profile_dir.join("app/uid/user_program");
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
    let enabled_names: Vec<String> = active.profiles.iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

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
            wait_tun_link(&plan.setting.tun)
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
            Ok(VpnNetdProfile {
                owner_program: "myvpn".to_string(),
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.setting.tun.clone(),
                cidr,
                gateway: None,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
            })
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

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }
    let netid = generate_netid(used_netids)?;
    Ok(ProfilePlan { name: profile.to_string(), setting, profile_dir, app_in, app_out, netid })
}

fn wait_tun_link(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} was not found after {:?}", TUN_WAIT);
        }
        let (code, _) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 { return Ok(()); }
        thread::sleep(Duration::from_millis(300));
    }
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
                    if let Some(cidr) = derive_cidr(ip_cidr) {
                        return Ok(TunInfo { cidr });
                    }
                }
            }
        }
    }
    bail!("tun {tun} has no IPv4 CIDR")
}

fn derive_cidr(ip_cidr: &str) -> Option<String> {
    normalize_cidr_network(ip_cidr).ok()
}

fn normalize_cidr_network(cidr: &str) -> Result<String> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok(format!("{}/{}", u32_to_ipv4(addr & mask), prefix))
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) { return Ok(id); }
    }
    bail!("no free myvpn netid in range {NETID_BASE}..={NETID_MAX}")
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
