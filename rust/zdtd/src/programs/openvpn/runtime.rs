use anyhow::{bail, Context, Result};
use crate::common::network::{cidrs_overlap};
use crate::network::vpn::{VpnNetdProfileSpec};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self}, path::{Path}};

use crate::{network::vpn::netd::VpnNetdProfile, network::vpn::tether::VpnTetherProfile};

use super::model::*;
use super::profile::*;
use super::config::*;
use super::process::*;
use super::network::*;

pub fn validate_start_plan() -> Result<()> {
    let api_settings = crate::settings::load_api_settings().unwrap_or_default();
    validate_start_plan_with_settings(&api_settings)
}

pub(crate) fn validate_start_plan_with_settings(api_settings: &crate::settings::ApiSettings) -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);

    if enabled_names.is_empty() {
        return Ok(());
    }

    let mut errors = Vec::<String>::new();
    if !Path::new(OPENVPN_BIN).is_file() {
        errors.push(format!("binary missing: {OPENVPN_BIN}"));
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

            let config_path = profile_dir.join("client.ovpn");
            let cfg = read_openvpn_config_text(&config_path)?;
            if cfg.trim().is_empty() {
                bail!("client.ovpn is empty: {}", config_path.display());
            }

            let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
            let apps_raw = fs::read_to_string(&app_in)
                .with_context(|| format!("read {}", app_in.display()))?;
            let selected_for_hotspot = api_settings
                .hotspot_vpn_profile_for("openvpn") == Some(name.as_str());
            if !selected_for_hotspot && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
                bail!("app list has no real apps: {}", app_in.display());
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
        bail!("openvpn start plan has issue(s): {}", errors.join("; "))
    }
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
        let app_in = crate::common::profile::app_uid_program_path(&profile_root(name));
        fs::read_to_string(&app_in)
            .map(|raw| crate::common::apps::app_list_has_real_apps(&raw))
            .unwrap_or(false)
    })
}

pub fn is_running() -> bool {
    !main_pids_exact().is_empty()
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

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::network::vpn::netd::start_profiles(profiles)?;
    Ok(())
}

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
        info!("openvpn: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("OpenVPN: запуск");

    if !Path::new(OPENVPN_BIN).is_file() {
        warn!("openvpn: binary not found: {OPENVPN_BIN} -> skip");
        crate::programs::components::user_warn_missing_component("openvpn");
        return Ok(Vec::new());
    }

    info!(
        "openvpn: start requested enabled_profiles={}",
        enabled_names.join(",")
    );

    let hotspot_vpn_profile = api_settings
        .hotspot_vpn_profile_for("openvpn")
        .map(|name| name.to_string());
    let mut plans = Vec::new();
    let mut had_error = false;
    for name in enabled_names {
        let selected_for_hotspot = hotspot_vpn_profile.as_deref() == Some(name.as_str());
        match build_profile_plan(&name, selected_for_hotspot) {
            Ok(plan) => {
                let apps_raw = fs::read_to_string(&plan.app_in).unwrap_or_default();
                if selected_for_hotspot && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
                    info!("openvpn: profile '{}' is selected for hotspot VPN only; skipping vpn_netd", plan.name);
                    continue;
                }
                plans.push(plan);
            }
            Err(e) => {
                if selected_for_hotspot {
                    info!("openvpn: profile '{name}' skipped in vpn_netd phase; hotspot VPN will handle it separately: {e:#}");
                } else {
                    had_error = true;
                    warn!("openvpn: profile '{name}' skip: {e:#}");
                }
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("OpenVPN: ошибка запуска, запуск продолжен");
        }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_plan_tuns_unique(&plans) {
        warn!("openvpn: enabled profiles have tun conflict: {e:#}");
        crate::logging::user_warn("OpenVPN: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    let mut used_cidrs = Vec::<(String, String)>::new();
    let mut used_netids = BTreeSet::<u32>::new();

    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "openvpn: starting profile={} tun={} dns={} config={}",
                plan.name,
                plan.setting.tun,
                plan.setting.dns.join(","),
                plan.config_path.display()
            );
            spawn_openvpn(plan)?;
            wait_tun_ready(&plan.setting.tun)
                .with_context(|| format!("openvpn profile={} wait tun={}", plan.name, plan.setting.tun))?;
            let tun = inspect_tun(&plan.setting.tun)
                .with_context(|| format!("openvpn profile={} inspect tun={}", plan.name, plan.setting.tun))?;
            info!(
                "openvpn: tun ready profile={} tun={} cidr={} gateway={}",
                plan.name,
                plan.setting.tun,
                tun.cidr,
                tun.gateway.as_deref().unwrap_or("none")
            );

            for (other_name, other_cidr) in &used_cidrs {
                if cidrs_overlap(&tun.cidr, other_cidr).unwrap_or(false) {
                    bail!(
                        "openvpn profile={} cidr {} overlaps with profile={} cidr {}",
                        plan.name,
                        tun.cidr,
                        other_name,
                        other_cidr
                    );
                }
            }
            let netid = stable_netid_for(&plan.name)?;
            if used_netids.contains(&netid) {
                bail!("netid {netid} is already used by another openvpn profile");
            }
            Ok(VPN_CORE.netd_profile(VpnNetdProfileSpec {
                profile: plan.name.clone(),
                netid,
                tun: plan.setting.tun.clone(),
                cidr: tun.cidr,
                gateway: tun.gateway,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                endpoint_escape_ips: plan.endpoint_escape_ips.clone(),
            }))
        })();

        match res {
            Ok(profile) => {
                used_netids.insert(profile.netid);
                used_cidrs.push((profile.profile.clone(), profile.cidr.clone()));
                profiles.push(profile);
            }
            Err(e) => {
                had_error = true;
                warn!("openvpn: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("OpenVPN: часть профилей не запущена, запуск продолжен");
    }

    info!("openvpn: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

pub fn start_profile_for_hotspot_vpn(profile: &str) -> Result<Option<VpnTetherProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        warn!("openvpn: hotspot VPN profile '{}' is not enabled", profile);
        return Ok(None);
    }
    if !Path::new(OPENVPN_BIN).is_file() {
        warn!("openvpn: binary not found: {OPENVPN_BIN} -> skip hotspot VPN");
        crate::programs::components::user_warn_missing_component("openvpn");
        return Ok(None);
    }
    let plan = build_profile_plan(profile, true)?;
    info!("openvpn: hotspot VPN start profile={} tun={}", plan.name, plan.setting.tun);
    let tun = if wait_tun_ready(&plan.setting.tun).is_ok() {
        info!("openvpn: hotspot VPN reusing ready tun={}", plan.setting.tun);
        inspect_tun(&plan.setting.tun)
            .with_context(|| format!("openvpn hotspot profile={} inspect existing tun={}", plan.name, plan.setting.tun))?
    } else {
        spawn_openvpn(&plan)?;
        wait_tun_ready(&plan.setting.tun)
            .with_context(|| format!("openvpn hotspot profile={} wait tun={}", plan.name, plan.setting.tun))?;
        inspect_tun(&plan.setting.tun)
            .with_context(|| format!("openvpn hotspot profile={} inspect tun={}", plan.name, plan.setting.tun))?
    };
    Ok(Some(VpnTetherProfile {
        owner_program: VPN_CORE.owner_program().to_string(),
        profile: plan.name.clone(),
        tun: plan.setting.tun.clone(),
        cidr: tun.cidr,
        gateway: tun.gateway,
        dns: plan.setting.dns.clone(),
    }))
}

pub(super) fn build_profile_plan(profile: &str, allow_empty_apps: bool) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let config_path = profile_dir.join("client.ovpn");
    if !config_path.is_file() {
        bail!("client.ovpn missing: {}", config_path.display());
    }
    let cfg = read_openvpn_config_text(&config_path)?;
    if cfg.trim().is_empty() {
        bail!("client.ovpn is empty: {}", config_path.display());
    }
    let tmp_dir = profile_dir.join("tmp");
    fs::create_dir_all(&tmp_dir)
        .with_context(|| format!("create openvpn tmp dir {}", tmp_dir.display()))?;
    normalize_client_config_in_place(&config_path, &setting.tun, &tmp_dir)
        .with_context(|| format!("normalize {}", config_path.display()))?;
    let (runtime_config_path, endpoint_escape_ips) = prepare_runtime_config(
        profile,
        &profile_dir,
        &config_path,
        setting.endpoint_resolve,
    )?;

    let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
    let app_out = crate::common::profile::app_out_program_path(&profile_dir);
    crate::common::fs::ensure_empty_file(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if !allow_empty_apps && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
        bail!("app list has no real apps: {}", app_in.display());
    }

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        config_path,
        runtime_config_path,
        endpoint_escape_ips,
        app_in,
        app_out,
        log_path: profile_dir.join("log/openvpn.log"),
    })
}


