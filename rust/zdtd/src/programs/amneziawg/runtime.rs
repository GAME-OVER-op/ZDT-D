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
use super::network::*;
use super::health::*;

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
            let cfg = read_amneziawg_config_text(&config_path)?;
            if cfg.trim().is_empty() {
                bail!("client.conf is empty: {}", config_path.display());
            }
            validate_config_minimum(&cfg)?;
            let app_in = profile_dir.join("app/uid/user_program");
            let apps_raw = fs::read_to_string(&app_in)
                .with_context(|| format!("read {}", app_in.display()))?;
            let selected_for_hotspot = api_settings
                .hotspot_vpn_profile_for(VPN_CORE.owner_program()) == Some(name.as_str());
            if !selected_for_hotspot && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
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
            .map(|raw| crate::common::apps::app_list_has_real_apps(&raw))
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
        info!("amneziawg: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("AmneziaWG: запуск");

    let awg_go_missing = !Path::new(AWG_GO_BIN).is_file();
    let awg_missing = !Path::new(AWG_BIN).is_file();
    if awg_go_missing || awg_missing {
        warn!("amneziawg: binary not found: {AWG_GO_BIN} or {AWG_BIN} -> skip");
        if awg_go_missing { crate::programs::components::user_warn_missing_component("amneziawg-go"); }
        if awg_missing { crate::programs::components::user_warn_missing_component("awg"); }
        return Ok(Vec::new());
    }

    info!("amneziawg: start requested enabled_profiles={}", enabled_names.join(","));

    let hotspot_vpn_profile = api_settings
        .hotspot_vpn_profile_for(VPN_CORE.owner_program())
        .map(|name| name.to_string());
    let mut plans = Vec::new();
    let mut had_error = false;
    for name in enabled_names {
        let selected_for_hotspot = hotspot_vpn_profile.as_deref() == Some(name.as_str());
        match build_profile_plan(&name, selected_for_hotspot) {
            Ok(plan) => {
                let apps_raw = fs::read_to_string(&plan.app_in).unwrap_or_default();
                if selected_for_hotspot && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
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
            let netid = stable_netid_for(&plan.name)?;
            if used_netids.contains(&netid) {
                bail!("netid {netid} is already used by another amneziawg profile");
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
                endpoint_escape_ips: collect_endpoint_escape_ips(plan),
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
    let awg_go_missing = !Path::new(AWG_GO_BIN).is_file();
    let awg_missing = !Path::new(AWG_BIN).is_file();
    if awg_go_missing || awg_missing {
        warn!("amneziawg: binary not found: {AWG_GO_BIN} or {AWG_BIN} -> skip hotspot VPN");
        if awg_go_missing { crate::programs::components::user_warn_missing_component("amneziawg-go"); }
        if awg_missing { crate::programs::components::user_warn_missing_component("awg"); }
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
    normalize_config_in_place(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let config_path = profile_dir.join("client.conf");
    if !config_path.is_file() {
        bail!("client.conf missing: {}", config_path.display());
    }
    let cfg = read_amneziawg_config_text(&config_path)?;
    if cfg.trim().is_empty() {
        bail!("client.conf is empty: {}", config_path.display());
    }
    validate_config_minimum(&cfg)?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    crate::common::fs::ensure_empty_file(&app_in)?;
    crate::common::fs::ensure_empty_file(&app_out)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if !allow_empty_apps && !crate::common::apps::app_list_has_real_apps(&apps_raw) {
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

