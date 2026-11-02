use anyhow::{bail, Context, Result};
use crate::common::network::{configure_tun_addr, wait_tun_link, wait_tcp_port, wait_tun_ipv4_ready};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self}, path::{Path}};

use crate::{
    network::vpn::netd::VpnNetdProfile,
    network::vpn::tether::VpnTetherProfile,
};

use super::model::*;
use super::profile::*;
use super::config::*;
use super::process::*;

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
    if !Path::new(MIHOMO_BIN).is_file() { errors.push(format!("binary missing: {MIHOMO_BIN}")); }
    let tun2socks_available = Path::new(TUN2SOCKS_BIN).is_file();

    let mut seen_tuns = BTreeMap::<String, String>::new();
    let mut seen_ports = BTreeMap::<u16, String>::new();
    let used_ports = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();

    for name in enabled_names {
        let profile_res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name).with_context(|| format!("read setting for profile {name}"))?;
            validate_setting(&setting).with_context(|| format!("validate setting for profile {name}"))?;

            let app_in = profile_dir.join("app/uid/user_program");
            let selection = crate::common::apps::app_selection(&app_in)?;
            let selected_for_hotspot = api_settings
                .hotspot_vpn_profile_for("mihomo") == Some(name.as_str());
            if selection == crate::common::apps::AppSelection::Empty && !selected_for_hotspot {
                bail!("app list is empty: {}", app_in.display());
            }
            let requires_tun = selection == crate::common::apps::AppSelection::RealApps || selected_for_hotspot;
            let _requires_netd = selection == crate::common::apps::AppSelection::RealApps;
            if requires_tun && !tun2socks_available {
                bail!("binary missing: {TUN2SOCKS_BIN}");
            }
            if requires_tun {
                if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                    bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
                }
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

            Ok(())
        })();

        if let Err(e) = profile_res { errors.push(format!("{name}: {e:#}")); }
    }

    if errors.is_empty() { Ok(()) } else { bail!("mihomo start plan has issue(s): {}", errors.join("; ")) }
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

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::network::vpn::netd::start_profiles(profiles)?;
    Ok(())
}

pub fn start_construction_profile(profile: &str) -> Result<()> {
	ensure_root_layout()?;
	ensure_valid_profile_name(profile)?;
	let active = read_active().unwrap_or_default();
	if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
		info!("mihomo: construction profile '{}' is disabled, skip targeted start", profile);
		return Ok(());
	}

	if !Path::new(MIHOMO_BIN).is_file() {
		warn!("mihomo: binary not found: {MIHOMO_BIN} -> skip targeted start");
		crate::programs::components::user_warn_missing_component("mihomo");
		return Ok(());
	}
	let tun2socks_available = Path::new(TUN2SOCKS_BIN).is_file();
	let hotspot_vpn_profile = crate::settings::load_api_settings()
		.ok()
		.and_then(|st| st.hotspot_vpn_profile_for("mihomo").map(|name| name.to_string()));

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
				"mihomo: ignoring unrelated enabled profile '{}' during targeted construction start of '{}': {e:#}",
				name,
				profile
			),
		}
	}

	let force_tun = hotspot_vpn_profile.as_deref() == Some(profile);
	let plan = build_profile_plan(profile, &used_netids, force_tun)
		.with_context(|| format!("mihomo targeted construction plan profile={profile}"))?;
	plans.push(plan.clone());
	validate_plan_conflicts(&plans)?;

	prepare_runtime_config(&plan)?;
	spawn_mihomo(&plan)?;
	wait_tcp_port("127.0.0.1", plan.setting.mixed_port, PORT_WAIT)
		.with_context(|| format!("mihomo targeted profile={} wait mixed_port={}", plan.name, plan.setting.mixed_port))?;
	if !plan.requires_tun {
		info!("mihomo: targeted profile={} uses launch marker only; skipping tun2socks/vpn_netd", plan.name);
		return Ok(());
	}
	if !tun2socks_available {
		bail!("tun2socks binary not found: {TUN2SOCKS_BIN}");
	}
	spawn_tun2socks(&plan)?;
	wait_tun_link(&plan.setting.tun, TUN_WAIT)
		.with_context(|| format!("mihomo targeted profile={} wait tun={}", plan.name, plan.setting.tun))?;
	configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
		.with_context(|| format!("mihomo targeted profile={} configure tun={}", plan.name, plan.setting.tun))?;
	wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
		.with_context(|| format!("mihomo targeted profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
	info!(
		"mihomo: targeted tun ready profile={} tun={} cidr={} gateway=none",
		plan.name,
		plan.setting.tun,
		plan.cidr
	);
	if plan.requires_netd {
		crate::network::vpn::netd::start_profiles(vec![netd_profile_for_plan(&plan)])?;
	}
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
        info!("mihomo: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("mihomo: запуск");

    if !Path::new(MIHOMO_BIN).is_file() {
        warn!("mihomo: binary not found: {MIHOMO_BIN} -> skip");
        crate::programs::components::user_warn_missing_component("mihomo");
        return Ok(Vec::new());
    }
    let tun2socks_available = Path::new(TUN2SOCKS_BIN).is_file();

    info!("mihomo: start requested enabled_profiles={}", enabled_names.join(","));

    let hotspot_vpn_profile = api_settings
        .hotspot_vpn_profile_for("mihomo")
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
        let res: Result<Option<VpnNetdProfile>> = (|| {
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
            wait_tcp_port("127.0.0.1", plan.setting.mixed_port, PORT_WAIT)
                .with_context(|| format!("mihomo profile={} wait mixed_port={}", plan.name, plan.setting.mixed_port))?;
            if !plan.requires_tun {
                info!("mihomo: profile={} uses launch marker only; skipping tun2socks/vpn_netd", plan.name);
                return Ok(None);
            }
            if !tun2socks_available {
                bail!("tun2socks binary not found: {TUN2SOCKS_BIN}");
            }
            spawn_tun2socks(plan)?;
            wait_tun_link(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("mihomo profile={} wait tun={}", plan.name, plan.setting.tun))?;
            configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
                .with_context(|| format!("mihomo profile={} configure tun={}", plan.name, plan.setting.tun))?;
            wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("mihomo profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
            info!(
                "mihomo: tun ready profile={} tun={} cidr={} gateway=none",
                plan.name,
                plan.setting.tun,
                plan.cidr
            );
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
                warn!("mihomo: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error { crate::logging::user_warn("mihomo: часть профилей не запущена, запуск продолжен"); }
    info!("mihomo: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

pub fn start_profile_for_hotspot_vpn(profile: &str) -> Result<Option<VpnTetherProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        warn!("mihomo: hotspot VPN profile '{}' is not enabled", profile);
        return Ok(None);
    }
    if !Path::new(MIHOMO_BIN).is_file() {
        warn!("mihomo: binary not found: {MIHOMO_BIN} -> skip hotspot VPN");
        crate::programs::components::user_warn_missing_component("mihomo");
        return Ok(None);
    }
    if !Path::new(TUN2SOCKS_BIN).is_file() {
        warn!("mihomo: tun2socks binary not found: {TUN2SOCKS_BIN} -> skip hotspot VPN");
        crate::programs::components::user_warn_missing_component("tun2socks");
        return Ok(None);
    }
    let plan = build_profile_plan_for_hotspot(profile)?;
    info!("mihomo: hotspot VPN start profile={} tun={}", plan.name, plan.setting.tun);
    if wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT).is_ok() {
        info!("mihomo: hotspot VPN reusing ready tun={}", plan.setting.tun);
    } else {
        prepare_runtime_config(&plan)?;
        spawn_mihomo(&plan)?;
        wait_tcp_port("127.0.0.1", plan.setting.mixed_port, PORT_WAIT)
            .with_context(|| format!("mihomo hotspot profile={} wait mixed_port={}", plan.name, plan.setting.mixed_port))?;
        spawn_tun2socks(&plan)?;
        wait_tun_link(&plan.setting.tun, TUN_WAIT)
            .with_context(|| format!("mihomo hotspot profile={} wait tun={}", plan.name, plan.setting.tun))?;
        configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
            .with_context(|| format!("mihomo hotspot profile={} configure tun={}", plan.name, plan.setting.tun))?;
        wait_tun_ipv4_ready(&plan.setting.tun, TUN_WAIT)
            .with_context(|| format!("mihomo hotspot profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
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

pub(super) fn build_profile_plan_for_hotspot(profile: &str) -> Result<ProfilePlan> {
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

pub(super) fn build_profile_plan(profile: &str, used_netids: &BTreeSet<u32>, force_tun: bool) -> Result<ProfilePlan> {
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

    let config_path = profile_dir.join("config.yaml");
    if !config_path.is_file() {
        bail!("config.yaml missing: {}", config_path.display());
    }
    let raw_config = fs::read_to_string(&config_path).with_context(|| format!("read {}", config_path.display()))?;
    if raw_config.trim().is_empty() { bail!("config.yaml is empty"); }
    let controller_port = parse_external_controller_port(&raw_config);

    let netid = if requires_tun {
        let id = stable_netid_for(profile)?;
        if used_netids.contains(&id) {
            bail!("netid {id} is already used by another mihomo profile");
        }
        id
    } else { 0 };
    let (tun_addr, cidr) = if requires_tun {
        VPN_CORE.generated_tun_addr_and_cidr(MIHOMO_NET_BASE, netid)?
    } else {
        (String::new(), String::new())
    };

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
        requires_netd,
        requires_tun,
        controller_port,
        netid,
        tun_addr,
        cidr,
    })
}

pub(super) fn validate_plan_conflicts(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen_tun: BTreeMap<String, String> = BTreeMap::new();
    let mut seen_port: BTreeMap<u16, String> = BTreeMap::new();
    let used_ports = crate::programs::ports::collect_used_ports_for_conflict_check_excluding_mihomo().unwrap_or_default();
    for plan in plans {
        if plan.requires_tun {
            if let Some(other) = seen_tun.insert(plan.setting.tun.clone(), plan.name.clone()) {
                bail!("mihomo tun conflict: tun {} is used by enabled profiles {} and {}", plan.setting.tun, other, plan.name);
            }
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

