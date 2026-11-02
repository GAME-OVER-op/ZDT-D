use anyhow::{bail, Context, Result};
use crate::common::config::{validate_loglevel};
use crate::common::network::{is_valid_ifname, is_forbidden_tun_name};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self}, path::{Path, PathBuf}};


use super::model::*;
use super::config::*;

pub fn root_path() -> PathBuf { PathBuf::from(MIHOMO_ROOT) }
pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(MIHOMO_ROOT))
}
pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(MIHOMO_ROOT))
}
pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn profiles_using_subscription(subscription_id: &str) -> Vec<String> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for name in active.profiles.keys() {
        if let Ok(setting) = read_setting(name) {
            if setting.subscriptions.iter().any(|id| id == subscription_id) {
                out.push(name.clone());
            }
        }
    }
    out.sort();
    out
}

pub fn remove_subscription_from_all_profiles(subscription_id: &str) -> Result<()> {
    let active = read_active().unwrap_or_default();
    for name in active.profiles.keys() {
        let Ok(mut setting) = read_setting(name) else { continue; };
        let before = setting.subscriptions.len();
        setting.subscriptions.retain(|id| id != subscription_id);
        if setting.subscriptions.len() != before {
            write_setting(name, &setting)?;
        }
    }
    Ok(())
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
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
    crate::common::profile::ensure_relative_dirs(
        &root,
        &[
            crate::common::profile::APP_UID_DIR,
            crate::common::profile::APP_OUT_DIR,
            crate::common::profile::LOG_DIR,
            crate::common::profile::WORK_DIR,
        ],
    )?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_uid_program_path(&root))?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_out_program_path(&root))?;
    crate::common::profile::ensure_profile_json(
        &crate::common::profile::setting_path(&root),
        &ProfileSetting::default(),
    )?;
    let config_path = root.join("config.yaml");
    if !config_path.exists() {
        fs::write(&config_path, default_config_yaml())?;
    }
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
    for id in &setting.subscriptions {
        if id.len() < 5 || id.len() > 64 || !id.starts_with("sub_") || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            bail!("invalid mihomo subscription id: {id}");
        }
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
        validate_setting(&setting).with_context(|| format!("mihomo profile={name} setting validation"))?;
        if !crate::common::apps::app_list_requires_netd(&profile_root(&name).join("app/uid/user_program")) {
            continue;
        }

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("mihomo tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
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
        if let Ok((_, cidr)) = VPN_CORE.generated_tun_addr_and_cidr(MIHOMO_NET_BASE, netid) {
            out.push(VPN_CORE.claim(name, cidr));
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
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
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
    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
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

    let used_other = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    for (port, label) in collect_all_defined_mihomo_ports(override_profile, override_setting, override_config)? {
        if used_other.contains(&port) {
            bail!("mihomo port conflict: port {} used by {} conflicts with another ZDT-D local port", port, label);
        }
    }
    Ok(())
}

pub(super) fn collect_all_defined_mihomo_ports(
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

