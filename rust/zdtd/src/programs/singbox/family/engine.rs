use anyhow::{bail, Context, Result};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs, path::{Path, PathBuf}, time::Duration};

use crate::platform::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::common::apps::count_valid_uid_pairs;
use crate::common::components::find_module_binary;
use crate::common::fs::{ensure_parent_dir, is_nonempty_file, truncate_file};
use crate::common::network::{configure_tun_addr, stable_netid, wait_tcp_port, wait_tun_link};
use crate::network::t2s::{
    format_ports_csv, launch_profile_t2s, profile_scope, T2sHotspotRouting, T2sProfileRouting,
    T2sSpawnConfig,
};
use crate::settings;
use crate::network::vpn::netd::VpnNetdProfile;

use super::{
    app_list_has_real_apps, ensure_base_layout, ensure_file, ensure_file_empty,
    ensure_valid_profile_name, generated_tun_address_for_index,
    profile_root, profile_t2s_log_path, profile_tun2socks_log_path, read_active_profiles,
    read_json, read_setting, read_sorted_dirs, server_root, spawn_singbox,
    spawn_tun2socks_for_vpn, stable_netid_for, validate_setting,
    wait_tun_ready, ProfileSetting, ServerPlan, ServerSetting, SingboxVariant, T2sProfilePlan,
    VariantSpec, VpnProfilePlan,
};

const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;
const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const TUN_WAIT: Duration = Duration::from_secs(25);
const PORT_WAIT: Duration = Duration::from_secs(20);

#[derive(Clone, Copy)]
pub(crate) struct ConfigHooks {
    normalize_t2s: fn(&Path, u16, &[String]) -> Result<()>,
    prepare_runtime: fn(&Path, bool) -> Result<(PathBuf, Vec<String>)>,
    check_with_log: fn(&Path, &Path) -> Result<()>,
}

impl ConfigHooks {
    pub(crate) const fn new(
        normalize_t2s: fn(&Path, u16, &[String]) -> Result<()>,
        prepare_runtime: fn(&Path, bool) -> Result<(PathBuf, Vec<String>)>,
        check_with_log: fn(&Path, &Path) -> Result<()>,
    ) -> Self {
        Self { normalize_t2s, prepare_runtime, check_with_log }
    }
}

pub(crate) fn is_installed(spec: VariantSpec) -> bool {
    Path::new(spec.binary).is_file()
}

fn hotspot_profile_from_settings(spec: VariantSpec, api_settings: &settings::ApiSettings) -> Option<String> {
    if !spec.hotspot_supported() {
        return None;
    }
    api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string())
}

fn hotspot_profile(spec: VariantSpec) -> Option<String> {
    let api_settings = settings::load_api_settings().unwrap_or_default();
    hotspot_profile_from_settings(spec, &api_settings)
}

fn external_used_ports(spec: VariantSpec) -> BTreeSet<u16> {
    match spec.variant {
        SingboxVariant::Standard => {
            crate::programs::ports::collect_used_ports_for_conflict_check_excluding(true, false)
                .unwrap_or_else(|_| BTreeSet::new())
        }
        SingboxVariant::Lx => {
            crate::programs::ports::collect_used_ports_for_conflict_check_excluding_singbox_lx()
                .unwrap_or_else(|_| BTreeSet::new())
        }
    }
}


pub(crate) fn normalize_config_for_profile_server(
    spec: VariantSpec,
    hooks: ConfigHooks,
    profile: &str,
    server: &str,
) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_valid_profile_name(server)?;
    let setting = read_setting(spec, profile)?;
    let dir = server_root(spec, profile, server);
    let server_setting: ServerSetting = read_json(&dir.join("setting.json")).unwrap_or_default();
    if server_setting.port == 0 {
        bail!("server port is required");
    }
    let config_path = dir.join("config.json");
    if !config_path.is_file() || !is_nonempty_file(&config_path).unwrap_or(false) {
        return Ok(());
    }
    (hooks.normalize_t2s)(&config_path, server_setting.port, &setting.dns)
}

pub(crate) fn list_enabled_server_settings(
    spec: VariantSpec,
    profile: &str,
) -> Result<Vec<(String, ServerSetting)>> {
    let mut out = Vec::new();
    for (name, dir) in read_sorted_dirs(spec, &profile_root(spec, profile).join("server"))? {
        let setting: ServerSetting = read_json(&dir.join("setting.json")).unwrap_or_default();
        if setting.enabled {
            out.push((name, setting));
        }
    }
    Ok(out)
}

#[allow(dead_code)]
pub(crate) fn list_server_dirs(spec: VariantSpec, profile: &str) -> Result<Vec<String>> {
    Ok(read_sorted_dirs(spec, &profile_root(spec, profile).join("server"))?
        .into_iter()
        .map(|(name, _)| name)
        .collect())
}

pub(crate) fn collect_enabled_t2s_local_ports(
    spec: VariantSpec,
    enabled_names: &[String],
) -> BTreeSet<u16> {
    let hotspot_profile = hotspot_profile(spec);
    let mut ports = BTreeSet::new();

    for name in enabled_names {
        let Ok(setting) = read_setting(spec, name) else { continue };
        if !setting.mode.is_t2s() {
            continue;
        }

        let app_in = profile_root(spec, name).join("app/uid/user_program");
        let has_real_apps = app_list_has_real_apps(&app_in).unwrap_or(false);
        let has_launch_marker = crate::common::apps::file_has_launch_marker(&app_in).unwrap_or(false);
        let profile_can_start = has_real_apps || has_launch_marker;
        if !profile_can_start {
            continue;
        }

        if has_real_apps || hotspot_profile.as_deref() == Some(name.as_str()) {
            if setting.t2s_port != 0 {
                ports.insert(setting.t2s_port);
            }
            if setting.t2s_web_port != 0 {
                ports.insert(setting.t2s_web_port);
            }
        }

        if let Ok(servers) = list_enabled_server_settings(spec, name) {
            for (_, server_setting) in servers {
                if server_setting.port != 0 {
                    ports.insert(server_setting.port);
                }
            }
        }
    }

    ports
}

pub(crate) fn validate_t2s_profile_ports(
    spec: VariantSpec,
    profile: &str,
    setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
) -> Result<()> {
    validate_setting(setting).with_context(|| format!("profile '{profile}' setting"))?;
    for port in [setting.t2s_port, setting.t2s_web_port] {
        if external_used.contains(&port) {
            bail!("port conflict detected: {}", port);
        }
        if own_used.contains(&port) {
            bail!("port conflict with another {} profile: {}", spec.label, port);
        }
    }
    Ok(())
}

pub(crate) fn collect_t2s_profile_servers(
    spec: VariantSpec,
    hooks: ConfigHooks,
    profile: &str,
    profile_setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    reserved: &BTreeSet<u16>,
) -> Result<Vec<ServerPlan>> {
    let server_root = profile_root(spec, profile).join("server");
    if !server_root.is_dir() {
        return Ok(Vec::new());
    }

    let mut out = Vec::<ServerPlan>::new();
    let mut seen_ports = BTreeSet::<u16>::new();
    let mut validation_errors = Vec::<String>::new();
    let entries = read_sorted_dirs(spec, &server_root)?;

    for (name, dir) in entries {
        let setting_path = dir.join("setting.json");
        let setting: ServerSetting = match read_json(&setting_path) {
            Ok(v) => v,
            Err(e) => {
                warn!(
                    "{}: skip profile='{}' server='{}' (bad setting.json): {e:#}",
                    spec.label,
                    profile,
                    name
                );
                if spec.is_lx() {
                    validation_errors.push(format!("server '{name}': bad setting.json: {e:#}"));
                }
                continue;
            }
        };
        if !setting.enabled {
            continue;
        }
        if setting.port == 0 {
            warn!(
                "{}: skip profile='{}' server='{}' (missing/invalid port)",
                spec.label,
                profile,
                name
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': missing/invalid port"));
            }
            continue;
        }
        if external_used.contains(&setting.port)
            || own_used.contains(&setting.port)
            || reserved.contains(&setting.port)
            || !seen_ports.insert(setting.port)
        {
            warn!(
                "{}: skip profile='{}' server='{}' (port conflict {})",
                spec.label,
                profile,
                name,
                setting.port
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': port conflict {}", setting.port));
            }
            continue;
        }

        let cfg = dir.join("config.json");
        if !cfg.is_file() {
            warn!(
                "{}: skip profile='{}' server='{}' (config.json missing)",
                spec.label,
                profile,
                name
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': config.json missing"));
            }
            continue;
        }
        if !is_nonempty_file(&cfg).unwrap_or(false) {
            warn!(
                "{}: skip profile='{}' server='{}' (config.json empty)",
                spec.label,
                profile,
                name
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': config.json empty"));
            }
            continue;
        }
        if let Err(e) = (hooks.normalize_t2s)(&cfg, setting.port, &profile_setting.dns) {
            warn!(
                "{}: skip profile='{}' server='{}' (normalize t2s config failed): {e:#}",
                spec.label,
                profile,
                name
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': normalize failed: {e:#}"));
            }
            continue;
        }
        let runtime_cfg = match (hooks.prepare_runtime)(&cfg, profile_setting.endpoint_resolve) {
            Ok((path, _)) => path,
            Err(e) => {
                warn!(
                    "{}: skip profile='{}' server='{}' (endpoint resolve failed): {e:#}",
                    spec.label,
                    profile,
                    name
                );
                if spec.is_lx() {
                    validation_errors.push(format!("server '{name}': endpoint resolve failed: {e:#}"));
                }
                continue;
            }
        };
        if let Err(e) = (hooks.check_with_log)(&runtime_cfg, &dir.join("log/sing-box.log")) {
            warn!(
                "{}: skip profile='{}' server='{}' (bad t2s config): {e:#}",
                spec.label,
                profile,
                name
            );
            if spec.is_lx() {
                validation_errors.push(format!("server '{name}': config check failed: {e:#}"));
            }
            continue;
        }

        out.push(ServerPlan {
            name,
            port: setting.port,
            config_path: runtime_cfg,
            log_path: dir.join("log/sing-box.log"),
        });
    }

    if spec.is_lx() && out.is_empty() && !validation_errors.is_empty() {
        bail!(
            "profile '{profile}' has no runnable t2s servers: {}",
            validation_errors.join("; ")
        );
    }

    Ok(out)
}

pub(crate) fn build_t2s_profile_plan(
    spec: VariantSpec,
    hooks: ConfigHooks,
    profile: &str,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    hotspot_profile: Option<&str>,
) -> Result<Option<T2sProfilePlan>> {
    ensure_valid_profile_name(profile)?;

    let profile_dir = profile_root(spec, profile);
    if !profile_dir.is_dir() {
        bail!("profile directory missing: {}", profile_dir.display());
    }

    let setting = read_setting(spec, profile)?;
    if !setting.mode.is_t2s() {
        return Ok(None);
    }

    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);
    let uid_in = profile_dir.join("app/uid/user_program");
    let uid_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&uid_in)?;
    ensure_parent_dir(&uid_out)?;
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &uid_out, &uid_in)
        .with_context(|| format!("pkg_uid processing profile={profile}"))?;
    let resolved = count_valid_uid_pairs(&uid_out).unwrap_or(0);
    let has_launch_marker = crate::common::apps::file_has_launch_marker(&uid_in).unwrap_or(false);
    if resolved == 0 && !has_launch_marker {
        warn!("{}: profile '{}' has no resolved apps", spec.label, profile);
        return Ok(None);
    }
    if resolved == 0 && has_launch_marker {
        info!(
            "{}: profile '{}' uses launch marker; starting without routing app UIDs",
            spec.label,
            profile
        );
    }

    let needs_t2s = resolved > 0 || hotspot_profile == Some(profile);
    if needs_t2s {
        validate_t2s_profile_ports(spec, profile, &setting, external_used, own_used)?;
    }

    let mut reserved = BTreeSet::new();
    if needs_t2s {
        reserved.insert(setting.t2s_port);
        reserved.insert(setting.t2s_web_port);
    }
    let servers = collect_t2s_profile_servers(
        spec,
        hooks,
        profile,
        &setting,
        external_used,
        own_used,
        &reserved,
    )?;
    if servers.is_empty() {
        warn!("{}: profile '{}' has no runnable t2s servers", spec.label, profile);
        return Ok(None);
    }

    Ok(Some(T2sProfilePlan {
        name: profile.to_string(),
        setting,
        uid_out,
        uid_count: resolved,
        needs_t2s,
        t2s_log: profile_t2s_log_path(spec, profile),
        servers,
    }))
}

pub(crate) fn build_vpn_profile_plan(
    spec: VariantSpec,
    hooks: ConfigHooks,
    profile: &str,
    used_netids: &BTreeSet<u32>,
    external_used: &BTreeSet<u16>,
    own_used_ports: &BTreeSet<u16>,
) -> Result<Option<VpnProfilePlan>> {
    ensure_valid_profile_name(profile)?;
    let profile_dir = profile_root(spec, profile);
    if !profile_dir.is_dir() {
        bail!("profile directory missing: {}", profile_dir.display());
    }
    let setting = read_setting(spec, profile)?;
    if !setting.mode.is_vpn() {
        return Ok(None);
    }
    validate_setting(&setting).with_context(|| format!("profile '{profile}' setting"))?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    ensure_parent_dir(&app_out)?;
    if !app_list_has_real_apps(&app_in)? {
        warn!("{} vpn: profile '{}' has no real routed apps", spec.label, profile);
        return Ok(None);
    }

    let enabled_servers = list_enabled_server_settings(spec, profile)?;
    if enabled_servers.len() != 1 {
        bail!(
            "vpn mode supports exactly one enabled server, found {}",
            enabled_servers.len()
        );
    }
    let (server_name, server_setting) = &enabled_servers[0];
    if server_setting.port == 0 {
        bail!("vpn server '{}' has invalid port", server_name);
    }
    if external_used.contains(&server_setting.port) || own_used_ports.contains(&server_setting.port) {
        bail!("port conflict detected: {}", server_setting.port);
    }

    let server_dir = server_root(spec, profile, server_name);
    let config_path = server_dir.join("config.json");
    let log_path = server_dir.join("log/sing-box.log");
    if !config_path.is_file() {
        bail!("config.json missing: {}", config_path.display());
    }
    if !is_nonempty_file(&config_path).unwrap_or(false) {
        bail!("config.json is empty: {}", config_path.display());
    }

    (hooks.normalize_t2s)(&config_path, server_setting.port, &setting.dns)
        .with_context(|| format!("normalize vpn/tun2socks backend config {}", config_path.display()))?;
    let (runtime_config_path, endpoint_escape_ips) =
        (hooks.prepare_runtime)(&config_path, setting.endpoint_resolve)
            .with_context(|| format!("prepare resolved {} config {}", spec.label, config_path.display()))?;
    (hooks.check_with_log)(&runtime_config_path, &log_path)
        .with_context(|| format!("{} check {}", spec.label, runtime_config_path.display()))?;

    let netid = stable_netid_for(spec, profile)?;
    if used_netids.contains(&netid) {
        bail!("netid {netid} is already used by another {} profile", spec.label);
    }
    let (tun_address, cidr, _generated_dns) =
        generated_tun_address_for_index(spec, netid - spec.netid_base)?;

    Ok(Some(VpnProfilePlan {
        name: profile.to_string(),
        setting: setting.clone(),
        server_name: server_name.clone(),
        server_port: server_setting.port,
        config_path: runtime_config_path,
        app_in,
        app_out,
        log_path,
        tun2socks_log_path: profile_tun2socks_log_path(spec, profile),
        netid,
        tun: setting.tun.clone(),
        tun_address,
        cidr,
        endpoint_escape_ips,
    }))
}

pub(crate) fn validate_vpn_plan_conflicts(spec: VariantSpec, plans: &[VpnProfilePlan]) -> Result<()> {
    let mut seen_tun = BTreeMap::<String, String>::new();
    for plan in plans {
        if let Some(other) = seen_tun.insert(plan.tun.clone(), plan.name.clone()) {
            bail!(
                "{} tun conflict: tun {} is used by enabled profiles {} and {}",
                spec.label,
                plan.tun,
                other,
                plan.name
            );
        }
    }
    Ok(())
}

pub(crate) fn has_enabled_profiles(spec: VariantSpec) -> bool {
    if spec.optional_component && !is_installed(spec) {
        return false;
    }
    read_active_profiles(spec)
        .map(|a| a.profiles.values().any(|st| st.enabled))
        .unwrap_or(false)
}

pub(crate) fn has_enabled_vpn_profiles(spec: VariantSpec) -> bool {
    if spec.optional_component && !is_installed(spec) {
        return false;
    }
    read_active_profiles(spec)
        .map(|active| {
            active.profiles.into_iter().any(|(name, st)| {
                st.enabled
                    && read_setting(spec, &name)
                        .map(|setting| setting.mode.is_vpn())
                        .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

pub(crate) fn enabled_tun_claims(spec: VariantSpec) -> Vec<(String, String)> {
    let mut out = Vec::new();
    if spec.optional_component && !is_installed(spec) {
        return out;
    }
    let Ok(active) = read_active_profiles(spec) else { return out };
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(spec, &name) else { continue };
        if !setting.mode.is_vpn() { continue; }
        if !app_list_has_real_apps(&profile_root(spec, &name).join("app/uid/user_program")).unwrap_or(false) { continue; }
        out.push((format!("{}/{}", spec.program_id.canonical(), name), setting.tun));
    }
    out
}

pub(crate) fn enabled_cidr_claims(spec: VariantSpec) -> Vec<(String, String)> {
    let mut out = Vec::new();
    if spec.optional_component && !is_installed(spec) {
        return out;
    }
    let Ok(active) = read_active_profiles(spec) else { return out };
    let all_names: Vec<String> = active.profiles.keys().cloned().collect();
    for (name, st) in active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(spec, &name) else { continue; };
        if !setting.mode.is_vpn() { continue; }
        if !app_list_has_real_apps(&profile_root(spec, &name).join("app/uid/user_program")).unwrap_or(false) { continue; }
        let Ok(netid) = stable_netid(spec.netid_base, spec.netid_max, &all_names, &name) else { continue; };
        if let Ok((_, cidr, _)) = generated_tun_address_for_index(spec, netid - spec.netid_base) {
            out.push((format!("{}/{}", spec.program_id.canonical(), name), cidr));
        }
    }
    out
}

pub(crate) fn validate_start_plan(spec: VariantSpec) -> Result<()> {
    let api_settings = settings::load_api_settings().unwrap_or_default();
    validate_start_plan_with_settings(spec, &api_settings)
}

pub(crate) fn validate_start_plan_with_settings(
    spec: VariantSpec,
    api_settings: &settings::ApiSettings,
) -> Result<()> {
    ensure_base_layout(spec)?;
    let active = read_active_profiles(spec).unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        return Ok(());
    }

    if spec.optional_component && !is_installed(spec) {
        return Ok(());
    }

    let mut errors = Vec::<String>::new();
    if !spec.optional_component && !Path::new(spec.binary).is_file() {
        errors.push(format!("binary missing: {}", spec.binary));
    }

    let hotspot_profile = hotspot_profile_from_settings(spec, api_settings);
    let mut seen_ports = BTreeMap::<u16, String>::new();
    let mut seen_tuns = BTreeMap::<String, String>::new();
    let used_ports = external_used_ports(spec);

    for name in enabled_names {
        let res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(spec, &name);
            let setting = read_setting(spec, &name)?;
            validate_setting(&setting)?;
            let servers = list_enabled_server_settings(spec, &name)?;
            let app_in = profile_dir.join("app/uid/user_program");

            let apps_raw = fs::read_to_string(&app_in).with_context(|| format!("read {}", app_in.display()))?;
            let mut has_marker = false;
            let mut has_real_app = false;
            for line in apps_raw.lines() {
                let mut s = line.trim();
                if let Some((left, _)) = s.split_once('#') {
                    s = left.trim();
                }
                if s.is_empty() {
                    continue;
                }
                if crate::common::apps::is_launch_marker_package(s) {
                    has_marker = true;
                } else {
                    has_real_app = true;
                }
            }

            if setting.mode.is_vpn() {
                if !Path::new(TUN2SOCKS_BIN).is_file() {
                    bail!("binary missing: {TUN2SOCKS_BIN}");
                }
                if !has_real_app {
                    bail!("vpn mode requires real app list: {}", app_in.display());
                }
                if servers.len() != 1 {
                    bail!("vpn mode supports exactly one enabled server, found {}", servers.len());
                }
                if let Some(other) = seen_tuns.insert(setting.tun.clone(), name.clone()) {
                    bail!("tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
                }
                let (server_name, server_setting) = &servers[0];
                if server_setting.port == 0 {
                    bail!("server {} port is required", server_name);
                }
                if let Some(other) = seen_ports.insert(server_setting.port, format!("{name}/{server_name}/port")) {
                    bail!("port {} is used by {} and {}", server_setting.port, other, name);
                }
                if used_ports.contains(&server_setting.port) {
                    bail!("server port {} conflicts with another ZDT-D local port", server_setting.port);
                }
                let config_path = server_root(spec, &name, server_name).join("config.json");
                if !config_path.is_file() {
                    bail!("config.json is missing: {}", config_path.display());
                }
                if !is_nonempty_file(&config_path).unwrap_or(false) {
                    bail!("config.json is empty: {}", config_path.display());
                }
            } else {
                if servers.is_empty() {
                    bail!("no enabled t2s servers");
                }
                if !has_marker && !has_real_app {
                    bail!("app list is empty: {}", app_in.display());
                }
                let needs_t2s = has_real_app || hotspot_profile.as_deref() == Some(name.as_str());
                if needs_t2s {
                    validate_t2s_profile_ports(spec, &name, &setting, &used_ports, &BTreeSet::new())?;
                    for (port, label) in [(setting.t2s_port, "t2s_port"), (setting.t2s_web_port, "t2s_web_port")] {
                        if let Some(other) = seen_ports.insert(port, format!("{name}/{label}")) {
                            bail!("port {} is used by {} and {}", port, other, name);
                        }
                    }
                }
                for (server_name, server_setting) in servers {
                    if server_setting.port == 0 {
                        bail!("server {} port is required", server_name);
                    }
                    if let Some(other) = seen_ports.insert(server_setting.port, format!("{name}/{server_name}/port")) {
                        bail!("port {} is used by {} and {}", server_setting.port, other, name);
                    }
                    if used_ports.contains(&server_setting.port) {
                        bail!("server port {} conflicts with another ZDT-D local port", server_setting.port);
                    }
                }
            }
            Ok(())
        })();
        if let Err(e) = res {
            errors.push(format!("{name}: {e:#}"));
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        bail!("{} start plan has issue(s): {}", spec.label, errors.join("; "))
    }
}

pub(crate) fn start_construction_profile(
    spec: VariantSpec,
    hooks: ConfigHooks,
    profile: &str,
) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_base_layout(spec)?;

    let active = read_active_profiles(spec).unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        info!("{}: construction profile '{}' is disabled, skip targeted start", spec.label, profile);
        return Ok(());
    }
    ensure_file(spec.binary)?;

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = if spec.hotspot_supported() {
        api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string())
    } else {
        None
    };
    let external_used = external_used_ports(spec);
    let other_enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(name, st)| st.enabled && name.as_str() != profile)
        .map(|(name, _)| name.clone())
        .collect();
    let own_used = collect_enabled_t2s_local_ports(spec, &other_enabled_names);

    let Some(plan) = build_t2s_profile_plan(
        spec,
        hooks,
        profile,
        &external_used,
        &own_used,
        hotspot_profile.as_deref(),
    )? else {
        info!("{}: construction profile '{}' has no runnable plan", spec.label, profile);
        return Ok(());
    };

    let t2s_bin = if plan.needs_t2s { Some(find_module_binary("t2s")?) } else { None };
    let ports_csv = format_ports_csv(plan.servers.iter().map(|server| server.port));

    for server in &plan.servers {
        ensure_parent_dir(&server.log_path)?;
        truncate_file(&server.log_path)?;
        spawn_singbox(spec, &server.config_path, &server.log_path)
            .with_context(|| format!("spawn {} profile={} server={}", spec.label, plan.name, server.name))?;
    }

    let hotspot_for_plan = hotspot_profile.as_deref() == Some(plan.name.as_str());
    let t2s_listen_addr = if hotspot_for_plan { "0.0.0.0" } else { "127.0.0.1" };

    if plan.needs_t2s {
        let t2s_bin = t2s_bin.as_ref().with_context(|| format!("t2s binary path missing for {} profile", spec.label))?;
        truncate_file(&plan.t2s_log)?;
        let scope = profile_scope(spec.label, &plan.name);
        launch_profile_t2s(
            T2sSpawnConfig {
                bin: t2s_bin,
                listen_addr: t2s_listen_addr,
                listen_port: plan.setting.t2s_port,
                socks_host: "127.0.0.1",
                socks_ports_csv: &ports_csv,
                web_port: Some(plan.setting.t2s_web_port),
                program: spec.label,
                profile: &plan.name,
                scope: &scope,
                log_path: &plan.t2s_log,
                ..Default::default()
            },
            T2sProfileRouting::exact(&plan.uid_out, plan.setting.proto_choice()),
            if hotspot_for_plan {
                Some(T2sHotspotRouting {
                    owner: spec.label,
                    capture_all: api_settings.hotspot_t2s_capture_all,
                })
            } else {
                None
            },
        )?;
        if plan.uid_count == 0 {
            info!(
                "{}: profile={} has no routed app UIDs yet; registered runtime routing for hot app refresh",
                spec.label,
                plan.name
            );
        }

        info!(
            "{}: targeted construction start profile={} apps={} servers={} t2s_port={} t2s_web_port={} socks_ports={} listen_addr={}",
            spec.label,
            plan.name,
            plan.uid_count,
            plan.servers.len(),
            plan.setting.t2s_port,
            plan.setting.t2s_web_port,
            ports_csv,
            t2s_listen_addr,
        );
    } else {
        info!(
            "{}: targeted construction start profile={} apps={} servers={} socks_ports={} mode=server-only",
            spec.label,
            plan.name,
            plan.uid_count,
            plan.servers.len(),
            ports_csv,
        );
    }

    Ok(())
}

pub(crate) fn start_t2s_if_enabled(spec: VariantSpec, hooks: ConfigHooks) -> Result<()> {
    let api_settings = settings::load_api_settings().unwrap_or_default();
    start_t2s_if_enabled_with_settings(spec, hooks, &api_settings)
}

pub(crate) fn start_t2s_if_enabled_with_settings(
    spec: VariantSpec,
    hooks: ConfigHooks,
    api_settings: &settings::ApiSettings,
) -> Result<()> {
    ensure_base_layout(spec)?;

    let active = match read_active_profiles(spec) {
        Ok(v) => v,
        Err(e) => {
            warn!("{}: failed to read active.json (skip): {e:#}", spec.label);
            return Ok(());
        }
    };

    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("{}: no enabled profiles in active.json", spec.label);
        return Ok(());
    }
    if spec.optional_component {
        if !is_installed(spec) {
            info!("{}: enabled profile exists but component is not installed", spec.label);
            crate::programs::components::user_warn_missing_component(spec.label);
            return Ok(());
        }
    } else {
        ensure_file(spec.binary)?;
    }

    let hotspot_profile = if spec.hotspot_supported() {
        api_settings.hotspot_t2s_singbox_profile().map(|s| s.to_string())
    } else {
        None
    };

    let external_used = external_used_ports(spec);
    let mut own_used = BTreeSet::<u16>::new();
    let mut plans = Vec::<T2sProfilePlan>::new();
    let mut had_error = false;

    for name in enabled_names {
        match build_t2s_profile_plan(spec, hooks, &name, &external_used, &own_used, hotspot_profile.as_deref()) {
            Ok(Some(plan)) => {
                if plan.needs_t2s {
                    own_used.insert(plan.setting.t2s_port);
                    own_used.insert(plan.setting.t2s_web_port);
                }
                for srv in &plan.servers {
                    own_used.insert(srv.port);
                }
                plans.push(plan);
            }
            Ok(None) => {}
            Err(e) => {
                had_error = true;
                warn!("{}: profile '{}' skip: {e:#}", spec.label, name);
                if spec.is_lx() {
                    crate::logging::user_warn(&format!(
                        "sing-box-lx: профиль '{}' не запущен: {e:#}",
                        name
                    ));
                }
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn(&format!("{}: ошибка запуска, запуск продолжен", spec.label));
        }
        info!("{}: no runnable t2s profiles after validation", spec.label);
        return Ok(());
    }

    let t2s_bin = if plans.iter().any(|plan| plan.needs_t2s) { Some(find_module_binary("t2s")?) } else { None };
    if spec.hotspot_supported() && api_settings.hotspot_t2s_for_singbox() && hotspot_profile.is_none() {
        warn!("sing-box: hotspot target is sing-box, but no hotspot profile is selected; using 127.0.0.1 for all profiles");
    }
    if let Some(ref wanted_profile) = hotspot_profile {
        if !plans.iter().any(|plan| plan.name == *wanted_profile) {
            warn!(
                "{}: hotspot profile '{}' is not active/runnable; skipping hotspot redirect",
                spec.label,
                wanted_profile
            );
        }
    }

    crate::logging::user_info(&format!("{}: запуск", spec.label));

    for plan in &plans {
        let ports_csv = format_ports_csv(plan.servers.iter().map(|server| server.port));

        for server in &plan.servers {
            ensure_parent_dir(&server.log_path)?;
            truncate_file(&server.log_path)?;
            spawn_singbox(spec, &server.config_path, &server.log_path)
                .with_context(|| format!("spawn {} profile={} server={}", spec.label, plan.name, server.name))?;
        }

        let hotspot_for_plan = hotspot_profile.as_deref() == Some(plan.name.as_str());
        let t2s_listen_addr = if hotspot_for_plan { "0.0.0.0" } else { "127.0.0.1" };

        if plan.needs_t2s {
            let t2s_bin = t2s_bin.as_ref().with_context(|| format!("t2s binary path missing for {} profile", spec.label))?;
            truncate_file(&plan.t2s_log)?;
            let scope = profile_scope(spec.label, &plan.name);
            launch_profile_t2s(
                T2sSpawnConfig {
                    bin: t2s_bin,
                    listen_addr: t2s_listen_addr,
                    listen_port: plan.setting.t2s_port,
                    socks_host: "127.0.0.1",
                    socks_ports_csv: &ports_csv,
                    web_port: Some(plan.setting.t2s_web_port),
                    program: spec.label,
                    profile: &plan.name,
                    scope: &scope,
                    log_path: &plan.t2s_log,
                    ..Default::default()
                },
                T2sProfileRouting::exact(&plan.uid_out, plan.setting.proto_choice()),
                if hotspot_for_plan {
                    Some(T2sHotspotRouting {
                        owner: spec.label,
                        capture_all: api_settings.hotspot_t2s_capture_all,
                    })
                } else {
                    None
                },
            )?;
            if plan.uid_count == 0 {
                info!(
                    "{}: profile={} has no routed app UIDs yet; registered runtime routing for hot app refresh",
                    spec.label,
                    plan.name
                );
            }

            info!(
                "{}: t2s profile={} apps={} servers={} t2s_port={} t2s_web_port={} socks_ports={} listen_addr={}",
                spec.label,
                plan.name,
                plan.uid_count,
                plan.servers.len(),
                plan.setting.t2s_port,
                plan.setting.t2s_web_port,
                ports_csv,
                t2s_listen_addr,
            );
        } else {
            info!(
                "{}: profile={} apps={} servers={} socks_ports={} mode=server-only",
                spec.label,
                plan.name,
                plan.uid_count,
                plan.servers.len(),
                ports_csv,
            );
        }
    }

    if had_error {
        crate::logging::user_warn(&format!("{}: часть профилей не запущена, запуск продолжен", spec.label));
    }

    Ok(())
}

pub(crate) fn start_profiles_for_netd(spec: VariantSpec, hooks: ConfigHooks) -> Result<Vec<VpnNetdProfile>> {
    ensure_base_layout(spec)?;

    let active = match read_active_profiles(spec) {
        Ok(v) => v,
        Err(e) => {
            warn!("{} vpn: failed to read active.json (skip): {e:#}", spec.label);
            return Ok(Vec::new());
        }
    };

    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("{} vpn: no enabled profiles in active.json", spec.label);
        return Ok(Vec::new());
    }
    if spec.optional_component {
        if !is_installed(spec) {
            info!("{}: enabled VPN profile exists but component is not installed", spec.label);
            crate::programs::components::user_warn_missing_component(spec.label);
            return Ok(Vec::new());
        }
    } else {
        ensure_file(spec.binary)?;
    }

    let external_used = external_used_ports(spec);
    let mut used_netids = BTreeSet::<u32>::new();
    let mut own_used_ports = collect_enabled_t2s_local_ports(spec, &enabled_names);
    let mut plans = Vec::<VpnProfilePlan>::new();
    let mut had_error = false;

    for name in enabled_names {
        match build_vpn_profile_plan(spec, hooks, &name, &used_netids, &external_used, &own_used_ports) {
            Ok(Some(plan)) => {
                used_netids.insert(plan.netid);
                own_used_ports.insert(plan.server_port);
                plans.push(plan);
            }
            Ok(None) => {}
            Err(e) => {
                had_error = true;
                warn!("{} vpn: profile '{}' skip: {e:#}", spec.label, name);
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn(&format!("{}: ошибка запуска VPN, запуск продолжен", spec.label));
        }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_vpn_plan_conflicts(spec, &plans) {
        warn!("{} vpn: profile conflict: {e:#}", spec.label);
        crate::logging::user_warn(&format!("{}: конфликт VPN-профилей, запуск продолжен", spec.label));
        return Ok(Vec::new());
    }

    let tun2socks_bin = find_module_binary("tun2socks")?;
    crate::logging::user_info(&format!("{} VPN: запуск", spec.label));

    let mut profiles = Vec::<VpnNetdProfile>::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "{} vpn: starting profile={} server={} tun={} port={} config={}",
                spec.label,
                plan.name,
                plan.server_name,
                plan.tun,
                plan.server_port,
                plan.config_path.display()
            );

            ensure_parent_dir(&plan.log_path)?;
            truncate_file(&plan.log_path)?;
            spawn_singbox(spec, &plan.config_path, &plan.log_path)
                .with_context(|| format!("spawn {} vpn profile={} server={}", spec.label, plan.name, plan.server_name))?;
            wait_tcp_port("127.0.0.1", plan.server_port, PORT_WAIT)
                .with_context(|| format!("{} vpn profile={} wait server port={}", spec.label, plan.name, plan.server_port))?;

            truncate_file(&plan.tun2socks_log_path)?;
            spawn_tun2socks_for_vpn(spec, &tun2socks_bin, plan)
                .with_context(|| format!("spawn tun2socks for {} profile={}", spec.label, plan.name))?;
            wait_tun_link(&plan.tun, TUN_WAIT)
                .with_context(|| format!("{} vpn profile={} wait tun={}", spec.label, plan.name, plan.tun))?;
            configure_tun_addr(&plan.tun, &plan.tun_address)
                .with_context(|| format!("{} vpn profile={} configure tun={}", spec.label, plan.name, plan.tun))?;
            wait_tun_ready(&plan.tun)
                .with_context(|| format!("{} vpn profile={} wait IPv4 tun={}", spec.label, plan.name, plan.tun))?;

            info!(
                "{} vpn: tun ready profile={} tun={} cidr={} gateway=none",
                spec.label,
                plan.name,
                plan.tun,
                plan.cidr
            );

            Ok(VpnNetdProfile {
                owner_program: spec.program_id.canonical().to_string(),
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.tun.clone(),
                cidr: plan.cidr.clone(),
                gateway: None,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                endpoint_escape_ips: plan.endpoint_escape_ips.clone(),
            })
        })();

        match res {
            Ok(profile) => profiles.push(profile),
            Err(e) => {
                had_error = true;
                warn!("{} vpn: profile '{}' failed, startup continues: {e:#}", spec.label, plan.name);
            }
        }
    }

    if had_error {
        crate::logging::user_warn(&format!("{}: часть VPN-профилей не запущена, запуск продолжен", spec.label));
    }
    info!("{} vpn: prepared vpn_netd profiles count={}", spec.label, profiles.len());
    Ok(profiles)
}
