use anyhow::{bail, Context, Result};
use crate::common::network::{is_ipv4, is_valid_ifname, is_forbidden_tun_name};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use std::{collections::{BTreeMap}, fs::{self}, path::{Path, PathBuf}};


use super::model::*;

pub fn root_path() -> PathBuf {
    PathBuf::from(OPENVPN_ROOT)
}

pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(OPENVPN_ROOT))
}

pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(OPENVPN_ROOT))
}

pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("openvpn profile name must be 1..10 chars and contain only English letters/digits/_/-");
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
    crate::common::profile::ensure_profile_json(
        &crate::common::profile::setting_path(&root),
        &ProfileSetting::default(),
    )?;
    let config_path = root.join("client.ovpn");
    if !config_path.exists() {
        fs::write(&config_path, "")?;
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
    ensure_profile_layout(profile)?;
    let path = crate::common::profile::setting_path(&profile_root(profile));
    let raw: serde_json::Value = crate::common::profile::read_profile_json(&path)?;
    let setting = normalize_setting_value(raw.clone())?;
    let normalized = serde_json::to_value(&setting).context("serialize normalized openvpn setting.json")?;
    if normalized != raw {
        crate::common::profile::write_profile_json(&path, &setting)?;
    }
    Ok(setting)
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let normalized = normalize_setting(setting.clone())?;
    crate::common::profile::write_profile_json(
        &crate::common::profile::setting_path(&profile_root(profile)),
        &normalized,
    )
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) {
        bail!("tun must be 1..15 chars and contain only letters/digits/_/./-; physical interfaces are not allowed");
    }
    if is_forbidden_tun_name(&setting.tun) {
        bail!("tun must not be a physical/system interface: {}", setting.tun);
    }
    if setting.dns.is_empty() || setting.dns.len() > 8 {
        bail!("dns must contain 1..8 IPv4 addresses");
    }
    for dns in &setting.dns {
        if !is_ipv4(dns) {
            bail!("invalid IPv4 DNS: {dns}");
        }
    }
    Ok(())
}

pub(super) fn normalize_setting(mut setting: ProfileSetting) -> Result<ProfileSetting> {
    setting.tun = setting.tun.trim().to_string();
    setting.dns = setting
        .dns
        .iter()
        .flat_map(|value| crate::common::config::split_comma_whitespace(value))
        .collect();
    setting.dns.sort();
    setting.dns.dedup();
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let setting: ProfileSetting = serde_json::from_value(value).context("bad openvpn setting.json")?;
    normalize_setting(setting)
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
        if !enabled {
            continue;
        }

        let setting = if override_profile == Some(name.as_str()) {
            match override_setting {
                Some(s) => s.clone(),
                None => read_setting(&name).unwrap_or_default(),
            }
        } else {
            read_setting(&name).unwrap_or_default()
        };
        validate_setting(&setting)
            .with_context(|| format!("openvpn profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!(
                "openvpn tun conflict: tun {} is used by enabled profiles {} and {}",
                setting.tun,
                other,
                name
            );
        }
    }
    Ok(())
}

pub(super) fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    VPN_CORE.validate_unique_tuns(
        plans.iter().map(|plan| (plan.name.as_str(), plan.setting.tun.as_str())),
    )
}

