use anyhow::{bail, Context, Result};
use crate::common::network::{is_ipv4, normalize_cidr_network, cidrs_overlap, is_valid_ifname, is_forbidden_tun_name};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use std::{collections::{BTreeMap}, fs::{self}, path::{Path, PathBuf}};


use super::model::*;
use super::config::*;
use super::network::*;

pub fn root_path() -> PathBuf { PathBuf::from(AMNEZIAWG_ROOT) }
pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(AMNEZIAWG_ROOT))
}
pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(AMNEZIAWG_ROOT))
}
pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
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
    crate::common::profile::ensure_relative_dirs(
        &root,
        &[
            crate::common::profile::APP_UID_DIR,
            crate::common::profile::APP_OUT_DIR,
            crate::common::profile::LOG_DIR,
            crate::common::profile::TMP_DIR,
        ],
    )?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_uid_program_path(&root))?;
    crate::common::fs::ensure_empty_file(&crate::common::profile::app_out_program_path(&root))?;
    crate::common::profile::ensure_profile_json(
        &crate::common::profile::setting_path(&root),
        &ProfileSetting::default(),
    )?;
    let config_path = root.join("client.conf");
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

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    if setting.address.is_empty() || setting.address.len() > 4 {
        bail!("address must contain 1..4 IPv4 CIDR addresses");
    }
    let mut address_cidrs = Vec::<String>::new();
    for addr in &setting.address {
        if !crate::common::network::is_ipv4_cidr(addr) {
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
    write_import_pair(&root, &imported.config, &imported.setting)?;
    Ok(())
}

pub fn normalize_config_in_place(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let root = profile_root(profile);
    let config_path = root.join("client.conf");
    let raw = read_amneziawg_config_text(&config_path)?;
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
        crate::common::fs::write_text_tmp_rename(&config_path, &imported.config)?;
    }
    crate::common::fs::json::write_json_pretty_tmp_rename(&root.join("setting.json"), &imported.setting)?;
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
                out.push(VPN_CORE.claim(&name, cidr));
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

pub(super) fn enabled_cidr_claims_with_override(
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
            out.push(VPN_CORE.claim(&name, cidr));
        }
    }

    Ok(out)
}

pub(super) fn cidr_claims_for_setting(setting: &ProfileSetting) -> Vec<String> {
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

pub(super) fn validate_cidr_claims_unique(claims: &[(String, String)]) -> Result<()> {
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

pub(super) fn validate_cidr_claims_against(local: &[(String, String)], other: &[(String, String)]) -> Result<()> {
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

pub(super) fn validate_plan_address_cidrs(plans: &[ProfilePlan]) -> Result<()> {
    let mut local = Vec::<(String, String)>::new();
    for plan in plans {
        for cidr in cidr_claims_for_setting(&plan.setting) {
            local.push(VPN_CORE.claim(&plan.name, cidr));
        }
    }
    validate_cidr_claims_unique(&local)?;

    let mut known_other = Vec::<(String, String)>::new();
    known_other.extend(crate::programs::tun2socks::enabled_cidr_claims());
    known_other.extend(crate::programs::myvpn::enabled_cidr_claims());
    known_other.extend(crate::programs::mihomo::enabled_cidr_claims());
    validate_cidr_claims_against(&local, &known_other)
}

pub(super) fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    VPN_CORE.validate_unique_tuns(
        plans
            .iter()
            .map(|plan| (plan.name.as_str(), plan.setting.tun.as_str())),
    )
}

