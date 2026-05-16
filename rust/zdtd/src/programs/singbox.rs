use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{de, Deserialize, Deserializer, Serialize, Serializer};
use serde_json::{json, Map, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::Write,
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
use crate::{
    settings,
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
    xtables_lock,
};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";

const SINGBOX_BIN: &str = "/data/adb/modules/ZDT-D/bin/sing-box";
const SINGBOX_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/singbox";
const SINGBOX_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/singbox/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/singbox/active.json";
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

const NETID_BASE: u32 = 22200;
const NETID_MAX: u32 = 22999;
const SINGBOX_NET_BASE: u32 = 0xAC1F_F000; // 172.31.240.0 (outside sing-box fakeip 198.18.0.0/15)
const TUN_WAIT: Duration = Duration::from_secs(25);
const IP_TIMEOUT: Duration = Duration::from_secs(3);
const SINGBOX_CHECK_TIMEOUT: Duration = Duration::from_secs(10);

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SingboxMode {
    T2s,
    Vpn,
}

impl Default for SingboxMode {
    fn default() -> Self { Self::T2s }
}

impl SingboxMode {
    pub fn as_str(&self) -> &'static str { "t2s" }

    pub fn is_t2s(&self) -> bool { true }
    pub fn is_vpn(&self) -> bool { false }
}

impl Serialize for SingboxMode {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for SingboxMode {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = Option::<String>::deserialize(deserializer)?.unwrap_or_else(|| "t2s".to_string());
        let _ = s;
        Ok(Self::T2s)
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    #[serde(default, skip_serializing)]
    pub mode: SingboxMode,
    #[serde(default)]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
    #[serde(default = "default_tun_name", skip_serializing)]
    pub tun: String,
    #[serde(default = "default_dns", deserialize_with = "deserialize_dns", skip_serializing)]
    pub dns: Vec<String>,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            mode: SingboxMode::T2s,
            t2s_port: 12345,
            t2s_web_port: default_t2s_web_port(),
            tun: default_tun_name(),
            dns: default_dns(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ServerSetting {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub port: u16,
}

impl Default for ServerSetting {
    fn default() -> Self {
        Self {
            enabled: false,
            port: 1080,
        }
    }
}

#[derive(Debug, Clone)]
struct ServerPlan {
    name: String,
    port: u16,
    config_path: PathBuf,
    log_path: PathBuf,
}

#[derive(Debug, Clone)]
struct T2sProfilePlan {
    name: String,
    setting: ProfileSetting,
    uid_out: PathBuf,
    uid_count: usize,
    needs_t2s: bool,
    t2s_log: PathBuf,
    servers: Vec<ServerPlan>,
}

#[derive(Debug, Clone)]
struct VpnProfilePlan {
    name: String,
    setting: ProfileSetting,
    config_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    log_path: PathBuf,
    netid: u32,
    tun: String,
    tun_address: String,
}

#[derive(Debug, Clone)]
struct TunInfo {
    cidr: String,
    gateway: Option<String>,
}

fn default_t2s_web_port() -> u16 { 8001 }
fn default_tun_name() -> String { "sbtun0".to_string() }
fn default_dns() -> Vec<String> { vec!["8.8.8.8".to_string()] }

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    out.extend(split_dns_text(s));
                }
            }
        }
        Value::String(s) => out.extend(split_dns_text(&s)),
        Value::Null => {}
        _ => return Err(de::Error::custom("dns must be array or string")),
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

pub fn normalize_setting_value(value: Value) -> Result<ProfileSetting> {
    let dns_missing = !value.as_object().map(|o| o.contains_key("dns")).unwrap_or(false);
    let mut setting: ProfileSetting = serde_json::from_value(value).context("bad sing-box profile setting.json")?;
    if dns_missing {
        setting.dns.clear();
    }
    normalize_setting_defaults(&mut setting)?;
    validate_setting(&setting)?;
    Ok(setting)
}

fn normalize_setting_defaults(setting: &mut ProfileSetting) -> Result<()> {
    setting.mode = SingboxMode::T2s;
    if setting.t2s_port == 0 {
        setting.t2s_port = 12345;
    }
    if setting.t2s_web_port == 0 {
        setting.t2s_web_port = default_t2s_web_port();
    }
    setting.tun = setting.tun.trim().to_string();
    if setting.tun.is_empty() {
        setting.tun = default_tun_name();
    }
    if setting.dns.is_empty() {
        setting.dns = default_dns();
    }
    setting.dns.sort();
    setting.dns.dedup();
    Ok(())
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    for (label, port) in [
        ("t2s_port", setting.t2s_port),
        ("t2s_web_port", setting.t2s_web_port),
    ] {
        if port == 0 {
            bail!("{} is required", label);
        }
    }
    if setting.t2s_port == setting.t2s_web_port {
        bail!("duplicate t2s ports");
    }
    Ok(())
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    let setting_path = profile_root(profile).join("setting.json");
    let value: Value = read_json(&setting_path)
        .with_context(|| format!("read {}", setting_path.display()))?;
    normalize_setting_value(value)
        .with_context(|| format!("validate sing-box profile={profile} setting"))
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let mut setting = setting.clone();
    normalize_setting_defaults(&mut setting)?;
    validate_setting(&setting)?;
    write_json_atomic(&profile_root(profile).join("setting.json"), &setting)
}

pub fn start_if_enabled() -> Result<()> {
    start_t2s_if_enabled()
}

pub fn start_t2s_if_enabled() -> Result<()> {
    ensure_base_layout()?;
    ensure_file(SINGBOX_BIN)?;

    let active = match read_active_profiles() {
        Ok(v) => v,
        Err(e) => {
            warn!("sing-box: failed to read active.json (skip): {e:#}");
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
        info!("sing-box: no enabled profiles in active.json");
        return Ok(());
    }

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings
        .hotspot_t2s_singbox_profile()
        .map(|s| s.to_string());

    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding(true, false)
        .unwrap_or_else(|_| BTreeSet::new());
    let mut own_used = BTreeSet::<u16>::new();
    let mut plans = Vec::<T2sProfilePlan>::new();
    let mut had_error = false;

    for name in enabled_names {
        match build_t2s_profile_plan(&name, &external_used, &own_used, hotspot_profile.as_deref()) {
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
                warn!("sing-box: profile '{}' skip: {e:#}", name);
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("sing-box: ошибка запуска, запуск продолжен");
        }
        warn!("sing-box: no runnable t2s profiles after validation");
        return Ok(());
    }

    let t2s_bin = if plans.iter().any(|plan| plan.needs_t2s) {
        Some(find_bin("t2s")?)
    } else {
        None
    };
    if api_settings.hotspot_t2s_for_singbox() && hotspot_profile.is_none() {
        warn!("sing-box: hotspot target is sing-box, but no hotspot profile is selected; using 127.0.0.1 for all profiles");
    }
    if let Some(ref wanted_profile) = hotspot_profile {
        if !plans.iter().any(|plan| plan.name == *wanted_profile) {
            warn!(
                "sing-box: hotspot profile '{}' is not active/runnable; skipping hotspot redirect",
                wanted_profile
            );
        }
    }

    crate::logging::user_info("sing-box: запуск");

    for plan in &plans {
        let ports_csv = plan
            .servers
            .iter()
            .map(|s| s.port.to_string())
            .collect::<Vec<_>>()
            .join(",");

        for server in &plan.servers {
            ensure_parent_dir(&server.log_path)?;
            truncate_file(&server.log_path)?;
            spawn_singbox(&server.config_path, &server.log_path)
                .with_context(|| format!("spawn sing-box profile={} server={}", plan.name, server.name))?;
        }

        let hotspot_for_plan = hotspot_profile.as_deref() == Some(plan.name.as_str());
        let t2s_listen_addr = if hotspot_for_plan { "0.0.0.0" } else { "127.0.0.1" };

        if plan.needs_t2s {
            let t2s_bin = t2s_bin.as_ref().context("t2s binary path missing for sing-box profile")?;
            truncate_file(&plan.t2s_log)?;
            spawn_t2s(
                t2s_bin,
                t2s_listen_addr,
                plan.setting.t2s_port,
                plan.setting.t2s_web_port,
                &ports_csv,
                &plan.t2s_log,
            )
            .with_context(|| format!("spawn t2s profile={}", plan.name))?;

            if hotspot_for_plan {
                apply_hotspot_prerouting_redirect(plan.setting.t2s_port)?;
            }

            if plan.uid_count > 0 {
                iptables_port::apply(
                    &plan.uid_out,
                    plan.setting.t2s_port,
                    ProtoChoice::Tcp,
                    None,
                    DpiTunnelOptions {
                        port_preference: 1,
                        ..DpiTunnelOptions::default()
                    },
                )
                .with_context(|| format!("iptables profile={}", plan.name))?;
            } else {
                info!("sing-box: profile={} has no routed app UIDs; only hotspot routing uses t2s", plan.name);
            }

            info!(
                "sing-box: t2s profile={} apps={} servers={} t2s_port={} t2s_web_port={} socks_ports={} listen_addr={}",
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
                "sing-box: profile={} apps={} servers={} socks_ports={} mode=server-only",
                plan.name,
                plan.uid_count,
                plan.servers.len(),
                ports_csv,
            );
        }
    }

    if had_error {
        crate::logging::user_warn("sing-box: часть профилей не запущена, запуск продолжен");
    }

    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    Ok(Vec::new())
}

fn build_t2s_profile_plan(
    profile: &str,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    hotspot_profile: Option<&str>,
) -> Result<Option<T2sProfilePlan>> {
    ensure_valid_profile_name(profile)?;

    let profile_dir = profile_root(profile);
    if !profile_dir.is_dir() {
        bail!("profile directory missing: {}", profile_dir.display());
    }

    let setting = read_setting(profile)?;
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
    let has_launch_marker = pkg_uid::file_has_launch_marker(&uid_in).unwrap_or(false);
    if resolved == 0 && !has_launch_marker {
        warn!("sing-box: profile '{}' has no resolved apps", profile);
        return Ok(None);
    }
    if resolved == 0 && has_launch_marker {
        info!("sing-box: profile '{}' uses launch marker; starting without routing app UIDs", profile);
    }

    let needs_t2s = resolved > 0 || hotspot_profile == Some(profile);
    if needs_t2s {
        validate_t2s_profile_ports(profile, &setting, external_used, own_used)?;
    }

    let mut reserved = BTreeSet::new();
    if needs_t2s {
        reserved.insert(setting.t2s_port);
        reserved.insert(setting.t2s_web_port);
    }
    let servers = collect_t2s_profile_servers(profile, &setting, external_used, own_used, &reserved)?;
    if servers.is_empty() {
        warn!("sing-box: profile '{}' has no runnable t2s servers", profile);
        return Ok(None);
    }

    Ok(Some(T2sProfilePlan {
        name: profile.to_string(),
        setting,
        uid_out,
        uid_count: resolved,
        needs_t2s,
        t2s_log: profile_t2s_log_path(profile),
        servers,
    }))
}

fn build_vpn_profile_plan(profile: &str, used_netids: &BTreeSet<u32>) -> Result<VpnProfilePlan> {
    ensure_valid_profile_name(profile)?;
    let profile_dir = profile_root(profile);
    if !profile_dir.is_dir() {
        bail!("profile directory missing: {}", profile_dir.display());
    }
    let mut setting = read_setting(profile)?;
    if !setting.mode.is_vpn() {
        bail!("profile mode is not vpn");
    }

    let server_dirs = list_server_dirs(profile)?;
    if server_dirs.len() != 1 {
        bail!(
            "vpn mode supports exactly one server, found {}",
            server_dirs.len()
        );
    }
    let server_name = &server_dirs[0];
    let server_dir = singbox_server_root(profile, server_name);
    let server_setting: ServerSetting = read_json(&server_dir.join("setting.json")).unwrap_or_default();
    if !server_setting.enabled {
        bail!("vpn mode server '{}' is disabled", server_name);
    }
    let config_path = server_dir.join("config.json");
    let log_path = server_dir.join("log/sing-box.log");
    if !config_path.is_file() {
        bail!("config.json missing: {}", config_path.display());
    }
    if !is_nonempty_file(&config_path).unwrap_or(false) {
        bail!("config.json is empty: {}", config_path.display());
    }
    let netid = generate_netid(used_netids)?;
    let (tun_address, _planned_cidr, _planned_dns) = generated_tun_address_for_index(netid - NETID_BASE)?;
    let effective_tun = normalize_singbox_config_for_vpn(&config_path, &setting, &tun_address)
        .with_context(|| format!("normalize vpn config {}", config_path.display()))?;
    if setting.tun != effective_tun {
        setting.tun = effective_tun.clone();
        write_setting(profile, &setting).with_context(|| format!("sync sing-box vpn tun profile={profile}"))?;
    }
    singbox_check_config_with_vpn_retry(&config_path, &log_path)
        .with_context(|| format!("sing-box check {}", config_path.display()))?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }

    Ok(VpnProfilePlan {
        name: profile.to_string(),
        setting,
        config_path,
        app_in,
        app_out,
        log_path,
        netid,
        tun: effective_tun,
        tun_address,
    })
}

fn validate_t2s_profile_ports(
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
            bail!("port conflict with another sing-box profile: {}", port);
        }
    }
    Ok(())
}

fn collect_t2s_profile_servers(
    profile: &str,
    profile_setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    reserved: &BTreeSet<u16>,
) -> Result<Vec<ServerPlan>> {
    let server_root = profile_root(profile).join("server");
    if !server_root.is_dir() {
        return Ok(Vec::new());
    }

    let mut out = Vec::<ServerPlan>::new();
    let mut seen_ports = BTreeSet::<u16>::new();
    let entries = read_sorted_dirs(&server_root)?;

    for (name, dir) in entries {
        let setting_path = dir.join("setting.json");
        let setting: ServerSetting = match read_json(&setting_path) {
            Ok(v) => v,
            Err(e) => {
                warn!(
                    "sing-box: skip profile='{}' server='{}' (bad setting.json): {e:#}",
                    profile,
                    name
                );
                continue;
            }
        };
        if !setting.enabled {
            continue;
        }
        if setting.port == 0 {
            warn!(
                "sing-box: skip profile='{}' server='{}' (missing/invalid port)",
                profile,
                name
            );
            continue;
        }
        if external_used.contains(&setting.port)
            || own_used.contains(&setting.port)
            || reserved.contains(&setting.port)
            || !seen_ports.insert(setting.port)
        {
            warn!(
                "sing-box: skip profile='{}' server='{}' (port conflict {})",
                profile,
                name,
                setting.port
            );
            continue;
        }

        let cfg = dir.join("config.json");
        if !cfg.is_file() {
            warn!(
                "sing-box: skip profile='{}' server='{}' (config.json missing)",
                profile,
                name
            );
            continue;
        }
        if !is_nonempty_file(&cfg).unwrap_or(false) {
            warn!(
                "sing-box: skip profile='{}' server='{}' (config.json empty)",
                profile,
                name
            );
            continue;
        }
        if let Err(e) = singbox_check_config_with_log(&cfg, &dir.join("log/sing-box.log"))
        {
            warn!(
                "sing-box: skip profile='{}' server='{}' (bad t2s config): {e:#}",
                profile,
                name
            );
            continue;
        }

        out.push(ServerPlan {
            name,
            port: setting.port,
            config_path: cfg,
            log_path: dir.join("log/sing-box.log"),
        });
    }

    Ok(out)
}

pub fn validate_start_plan() -> Result<()> {
    ensure_base_layout()?;
    let active = read_active_profiles().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        return Ok(());
    }

    let mut errors = Vec::<String>::new();
    if !Path::new(SINGBOX_BIN).is_file() {
        errors.push(format!("binary missing: {SINGBOX_BIN}"));
    }

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings
        .hotspot_t2s_singbox_profile()
        .map(|s| s.to_string());
    let mut seen_t2s_ports = BTreeMap::<u16, String>::new();
    let used_ports = crate::ports::collect_used_ports_for_conflict_check_excluding(true, false).unwrap_or_default();

    for name in enabled_names {
        let res: Result<()> = (|| {
            ensure_valid_profile_name(&name)?;
            let profile_dir = profile_root(&name);
            let setting = read_setting(&name)?;
            let servers = list_enabled_server_settings(&name)?;
            if servers.is_empty() {
                bail!("no enabled t2s servers");
            }
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
                if pkg_uid::is_launch_marker_package(s) {
                    has_marker = true;
                } else {
                    has_real_app = true;
                }
            }
            if !has_marker && !has_real_app {
                bail!("app list is empty: {}", app_in.display());
            }
            let needs_t2s = has_real_app || hotspot_profile.as_deref() == Some(name.as_str());
            if needs_t2s {
                validate_t2s_profile_ports(&name, &setting, &used_ports, &BTreeSet::new())?;
                for (port, label) in [
                    (setting.t2s_port, "t2s_port"),
                    (setting.t2s_web_port, "t2s_web_port"),
                ] {
                    if let Some(other) = seen_t2s_ports.insert(port, format!("{name}/{label}")) {
                        bail!("port {} is used by {} and {}", port, other, name);
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
        bail!("sing-box start plan has issue(s): {}", errors.join("; "))
    }
}

pub fn has_enabled_profiles() -> bool {
    read_active_profiles()
        .map(|a| a.profiles.values().any(|st| st.enabled))
        .unwrap_or(false)
}

pub fn has_enabled_vpn_profiles() -> bool { false }

pub fn enabled_tun_claims() -> Vec<(String, String)> { Vec::new() }

pub fn enabled_cidr_claims() -> Vec<(String, String)> { Vec::new() }


pub fn is_running() -> bool {
    !main_pids_exact().is_empty()
}

pub fn normalize_config_for_profile_server(profile: &str, server: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_valid_profile_name(server)?;
    Ok(())
}

fn validate_vpn_plan_conflicts(plans: &[VpnProfilePlan]) -> Result<()> {
    let mut seen_tun = BTreeMap::<String, String>::new();
    for plan in plans {
        if let Some(other) = seen_tun.insert(plan.tun.clone(), plan.name.clone()) {
            bail!("sing-box tun conflict: tun {} is used by enabled profiles {} and {}", plan.tun, other, plan.name);
        }
    }
    Ok(())
}

fn list_enabled_server_settings(profile: &str) -> Result<Vec<(String, ServerSetting)>> {
    let mut out = Vec::new();
    for (name, dir) in read_sorted_dirs(&profile_root(profile).join("server"))? {
        let setting: ServerSetting = read_json(&dir.join("setting.json")).unwrap_or_default();
        if setting.enabled {
            out.push((name, setting));
        }
    }
    Ok(out)
}

fn list_server_dirs(profile: &str) -> Result<Vec<String>> {
    Ok(read_sorted_dirs(&profile_root(profile).join("server"))?
        .into_iter()
        .map(|(name, _)| name)
        .collect())
}

fn read_sorted_dirs(root: &Path) -> Result<Vec<(String, PathBuf)>> {
    if !root.is_dir() {
        return Ok(Vec::new());
    }
    let mut entries = Vec::<(String, PathBuf)>::new();
    for ent in fs::read_dir(root).with_context(|| format!("readdir {}", root.display()))? {
        let ent = match ent {
            Ok(v) => v,
            Err(_) => continue,
        };
        let dir = ent.path();
        if !dir.is_dir() {
            continue;
        }
        let Some(name) = dir.file_name().and_then(|s| s.to_str()).map(ToOwned::to_owned) else { continue };
        if name.starts_with('.') {
            continue;
        }
        if let Err(e) = ensure_valid_profile_name(&name) {
            warn!(
                "sing-box: skip server-dir='{}' (invalid directory name): {e:#}",
                name
            );
            continue;
        }
        entries.push((name, dir));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(entries)
}

fn normalize_singbox_config_for_t2s(config_path: &Path, port: u16, dns_servers: &[String]) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    {
        let obj = value.as_object_mut().ok_or_else(|| anyhow::anyhow!("sing-box config root must be JSON object"))?;
        obj.insert(
            "inbounds".to_string(),
            Value::Array(vec![json!({
                "type": "mixed",
                "tag": "mixed-in",
                "listen": "127.0.0.1",
                "listen_port": port
            })]),
        );
        normalize_singbox_common(obj, dns_servers);
    }
    migrate_legacy_domain_strategy(&mut value);
    write_json_value_if_changed(config_path, &original, &value)
}

fn normalize_singbox_config_for_vpn(config_path: &Path, setting: &ProfileSetting, tun_address: &str) -> Result<String> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    let effective_tun = effective_vpn_tun_from_config_value(&value, &setting.tun)?;
    {
        let obj = value.as_object_mut().ok_or_else(|| anyhow::anyhow!("sing-box config root must be JSON object"))?;

        let mut inbound = Map::new();
        inbound.insert("type".to_string(), Value::String("tun".to_string()));
        inbound.insert("tag".to_string(), Value::String("tun-in".to_string()));
        inbound.insert("interface_name".to_string(), Value::String(effective_tun.clone()));
        inbound.insert(
            "address".to_string(),
            Value::Array(vec![Value::String(tun_address.to_string())]),
        );
        inbound.insert("auto_route".to_string(), Value::Bool(false));
        inbound.insert("auto_redirect".to_string(), Value::Bool(false));
        inbound.insert("strict_route".to_string(), Value::Bool(false));
        inbound.insert("stack".to_string(), Value::String("mixed".to_string()));

        obj.insert("inbounds".to_string(), Value::Array(vec![Value::Object(inbound)]));
        normalize_singbox_common(obj, &setting.dns);
    }
    migrate_legacy_domain_strategy(&mut value);

    write_json_value_if_changed(config_path, &original, &value)?;
    Ok(effective_tun)
}

fn normalize_singbox_common(obj: &mut Map<String, Value>, dns_servers: &[String]) {
    let primary_dns = dns_servers
        .iter()
        .map(|s| s.trim())
        .find(|s| is_ipv4(s))
        .unwrap_or("8.8.8.8")
        .to_string();

    let mut direct_domains = collect_singbox_direct_dns_domains(obj);
    direct_domains.insert("dns.google".to_string());
    for domain in collect_singbox_outbound_domains(obj) {
        direct_domains.insert(domain);
    }

    let mut dns_rules = Vec::new();
    if !direct_domains.is_empty() {
        dns_rules.push(json!({
            "domain": direct_domains.into_iter().collect::<Vec<_>>(),
            "server": "dns-direct"
        }));
    }

    let inbound_tags = collect_singbox_inbound_tags(obj);
    if !inbound_tags.is_empty() {
        dns_rules.push(json!({
            "inbound": inbound_tags.clone(),
            "query_type": ["A", "AAAA"],
            "server": "dns-fake",
            "disable_cache": true
        }));
    }

    obj.insert(
        "dns".to_string(),
        json!({
            "servers": [
                {
                    "type": "local",
                    "tag": "dns-local"
                },
                {
                    "type": "udp",
                    "tag": "dns-direct",
                    "server": primary_dns,
                    "server_port": 53
                },
                {
                    "type": "https",
                    "tag": "dns-remote",
                    "server": "dns.google",
                    "server_port": 443,
                    "path": "/dns-query",
                    "domain_resolver": {
                        "server": "dns-direct",
                        "strategy": "ipv4_only"
                    }
                },
                {
                    "type": "fakeip",
                    "tag": "dns-fake",
                    "inet4_range": "198.18.0.0/15",
                    "inet6_range": "fc00::/18"
                }
            ],
            "rules": dns_rules,
            "final": "dns-remote",
            "independent_cache": true,
            "strategy": "ipv4_only"
        }),
    );

    let mut route_obj = obj
        .remove("route")
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();

    route_obj.insert("auto_detect_interface".to_string(), Value::Bool(true));
    route_obj.insert(
        "default_domain_resolver".to_string(),
        json!({
            "server": "dns-direct",
            "strategy": "ipv4_only"
        }),
    );

    let mut route_rules = Vec::new();
    for tag in &inbound_tags {
        route_rules.push(json!({
            "inbound": [tag],
            "action": "sniff"
        }));
    }
    route_rules.push(json!({
        "action": "hijack-dns",
        "port": [53]
    }));
    route_rules.push(json!({
        "action": "hijack-dns",
        "protocol": ["dns"]
    }));
    route_rules.push(json!({
        "action": "reject",
        "ip_cidr": ["224.0.0.0/3", "ff00::/8"],
        "source_ip_cidr": ["224.0.0.0/3", "ff00::/8"]
    }));
    route_obj.insert("rules".to_string(), Value::Array(route_rules));
    route_obj.entry("rule_set".to_string()).or_insert_with(|| Value::Array(Vec::new()));

    if !route_obj.contains_key("final") && has_singbox_outbound_tag(obj, "proxy") {
        route_obj.insert("final".to_string(), Value::String("proxy".to_string()));
    }

    obj.insert("route".to_string(), Value::Object(route_obj));
}

fn collect_singbox_inbound_tags(obj: &Map<String, Value>) -> Vec<String> {
    let mut tags = obj
        .get("inbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|item| item.as_object())
                .filter_map(|o| o.get("tag").and_then(|v| v.as_str()))
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    tags.sort();
    tags.dedup();
    tags
}

fn collect_singbox_direct_dns_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Some(rules) = obj
        .get("dns")
        .and_then(|v| v.as_object())
        .and_then(|o| o.get("rules"))
        .and_then(|v| v.as_array())
    {
        for rule in rules {
            let Some(rule_obj) = rule.as_object() else { continue };
            if rule_obj
                .get("server")
                .and_then(|v| v.as_str())
                .map(|s| s == "dns-direct")
                .unwrap_or(false)
            {
                collect_domains_from_value(rule_obj.get("domain"), &mut out);
            }
        }
    }
    out
}

fn collect_singbox_outbound_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Some(outbounds) = obj.get("outbounds").and_then(|v| v.as_array()) {
        for outbound in outbounds {
            let Some(server) = outbound
                .as_object()
                .and_then(|o| o.get("server"))
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|s| !s.is_empty())
            else {
                continue;
            };
            if !is_ipv4(server) && !server.contains(':') {
                out.insert(server.to_string());
            }
        }
    }
    out
}

fn collect_domains_from_value(value: Option<&Value>, out: &mut BTreeSet<String>) {
    match value {
        Some(Value::String(s)) => {
            let s = s.trim();
            if !s.is_empty() {
                out.insert(s.to_string());
            }
        }
        Some(Value::Array(arr)) => {
            for item in arr {
                collect_domains_from_value(Some(item), out);
            }
        }
        _ => {}
    }
}

fn has_singbox_outbound_tag(obj: &Map<String, Value>, tag: &str) -> bool {
    obj.get("outbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter().any(|item| {
                item.as_object()
                    .and_then(|o| o.get("tag"))
                    .and_then(|v| v.as_str())
                    .map(|s| s == tag)
                    .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

fn is_vpn_removed_route_rule(rule: &Value) -> bool {
    let Some(obj) = rule.as_object() else { return false };
    if obj
        .get("action")
        .and_then(|v| v.as_str())
        .map(|s| s.eq_ignore_ascii_case("hijack-dns"))
        .unwrap_or(false)
    {
        return true;
    }
    false
}

fn migrate_legacy_domain_strategy(value: &mut Value) {
    match value {
        Value::Object(obj) => {
            let strategy = match obj.remove("domain_strategy") {
                Some(Value::String(s)) => s.trim().to_string(),
                Some(Value::Number(n)) => n.to_string(),
                Some(Value::Bool(b)) => b.to_string(),
                _ => String::new(),
            };
            if !strategy.is_empty() && looks_like_dial_object(obj) && !obj.contains_key("domain_resolver") {
                obj.insert(
                    "domain_resolver".to_string(),
                    json!({
                        "server": "dns-direct",
                        "strategy": normalize_domain_strategy(&strategy)
                    }),
                );
            }
            let keys = obj.keys().cloned().collect::<Vec<_>>();
            for key in keys {
                if let Some(child) = obj.get_mut(&key) {
                    migrate_legacy_domain_strategy(child);
                }
            }
        }
        Value::Array(arr) => {
            for child in arr {
                migrate_legacy_domain_strategy(child);
            }
        }
        _ => {}
    }
}

fn looks_like_dial_object(obj: &Map<String, Value>) -> bool {
    if obj.contains_key("detour") || obj.contains_key("bind_interface") || obj.contains_key("routing_mark") {
        return true;
    }
    let ty = obj
        .get("type")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_ascii_lowercase())
        .unwrap_or_default();
    matches!(
        ty.as_str(),
        "direct" | "socks" | "http" | "shadowsocks" | "vmess" | "vless" | "trojan" |
        "hysteria" | "hysteria2" | "tuic" | "wireguard" | "ssh" | "shadowtls" | "anytls" | "tor"
    ) || (obj.contains_key("server") && (obj.contains_key("server_port") || obj.contains_key("port")))
}

fn normalize_domain_strategy(strategy: &str) -> String {
    match strategy.trim().to_ascii_lowercase().as_str() {
        "prefer_ipv6" => "prefer_ipv6".to_string(),
        "ipv4_only" => "ipv4_only".to_string(),
        "ipv6_only" => "ipv6_only".to_string(),
        _ => "prefer_ipv4".to_string(),
    }
}

fn effective_vpn_tun_from_config_value(value: &Value, fallback: &str) -> Result<String> {
    let from_config = value
        .get("inbounds")
        .and_then(|v| v.as_array())
        .and_then(|arr| {
            arr.iter().find_map(|item| {
                let obj = item.as_object()?;
                let ty = obj.get("type")?.as_str()?.trim().to_ascii_lowercase();
                if ty != "tun" {
                    return None;
                }
                obj.get("interface_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(ToOwned::to_owned)
            })
        });
    let tun = from_config.unwrap_or_else(|| fallback.trim().to_string()).trim().to_string();
    let tun = if tun.is_empty() { default_tun_name() } else { tun };
    if !is_valid_ifname(&tun) || is_forbidden_tun_name(&tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    Ok(tun)
}

fn write_json_value_if_changed(config_path: &Path, original: &str, value: &Value) -> Result<()> {
    let normalized = serde_json::to_string_pretty(value)?;
    let normalized = format!("{}\n", normalized);
    if normalized != original {
        let tmp = unique_tmp_path(config_path);
        fs::write(&tmp, normalized).with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, config_path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), config_path.display()))?;
        info!("sing-box: normalized config {}", config_path.display());
    }
    Ok(())
}

fn singbox_check_config(config_path: &Path) -> Result<()> {
    let (code, out) = run_singbox_check(config_path)?;
    if code != 0 {
        bail!("sing-box check failed rc={code}: {}", out.trim());
    }
    Ok(())
}

fn singbox_check_config_with_vpn_retry(config_path: &Path, log_path: &Path) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(config_path)?;
    if code == 0 {
        return Ok(());
    }

    if output_mentions_dns_mode(&out) {
        append_check_log(
            log_path,
            &format!(
                "sing-box check failed with dns_mode, retrying without dns_mode\nconfig: {}\nrc: {}\n{}\n",
                config_path.display(),
                code,
                out.trim()
            ),
        )?;
        remove_tun_dns_mode(config_path)?;
        let (retry_code, retry_out) = run_singbox_check(config_path)?;
        if retry_code == 0 {
            append_check_log(
                log_path,
                "sing-box check succeeded after removing unsupported tun dns_mode\n",
            )?;
            return Ok(());
        }
        write_check_failure_log(log_path, config_path, retry_code, &retry_out)?;
        bail!("sing-box check failed rc={retry_code}: {}", retry_out.trim());
    }

    write_check_failure_log(log_path, config_path, code, &out)?;
    bail!("sing-box check failed rc={code}: {}", out.trim())
}

fn singbox_check_config_with_log(config_path: &Path, log_path: &Path) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(config_path)?;
    if code != 0 {
        write_check_failure_log(log_path, config_path, code, &out)?;
        bail!("sing-box check failed rc={code}: {}", out.trim());
    }
    Ok(())
}

fn run_singbox_check(config_path: &Path) -> Result<(i32, String)> {
    let cfg = config_path
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("non-utf8 config path: {}", config_path.display()))?;
    shell::run_timeout(SINGBOX_BIN, &["check", "-c", cfg], Capture::Both, SINGBOX_CHECK_TIMEOUT)
        .with_context(|| format!("run sing-box check -c {}", config_path.display()))
}

fn write_check_failure_log(log_path: &Path, config_path: &Path, code: i32, out: &str) -> Result<()> {
    let text = format!(
        "sing-box check failed\nconfig: {}\nrc: {}\n{}\n",
        config_path.display(),
        code,
        out.trim()
    );
    fs::write(log_path, text).with_context(|| format!("write {}", log_path.display()))
}

fn append_check_log(log_path: &Path, text: &str) -> Result<()> {
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .with_context(|| format!("open {}", log_path.display()))?;
    file.write_all(text.as_bytes())
        .with_context(|| format!("write {}", log_path.display()))
}

fn output_mentions_dns_mode(out: &str) -> bool {
    out.to_ascii_lowercase().contains("dns_mode")
}

fn remove_tun_dns_mode(config_path: &Path) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    if let Some(inbounds) = value.get_mut("inbounds").and_then(|v| v.as_array_mut()) {
        for inbound in inbounds {
            let Some(obj) = inbound.as_object_mut() else { continue };
            if obj
                .get("type")
                .and_then(|v| v.as_str())
                .map(|s| s.eq_ignore_ascii_case("tun"))
                .unwrap_or(false)
            {
                obj.remove("dns_mode");
                obj.remove("dns_address");
            }
        }
    }
    write_json_value_if_changed(config_path, &original, &value)
}

fn singbox_supports_tun_dns_mode() -> bool {
    let Ok((code, out)) = shell::run_timeout(SINGBOX_BIN, &["version"], Capture::Both, Duration::from_secs(3)) else {
        return false;
    };
    if code != 0 {
        return false;
    }
    let Some(ver) = first_version_token(&out) else { return false };
    version_supports_tun_dns_mode(&ver)
}

fn first_version_token(text: &str) -> Option<String> {
    text.split_whitespace()
        .map(|token| token.trim_matches(|c: char| !(c.is_ascii_alphanumeric() || c == '.' || c == '-')).trim_start_matches('v'))
        .find(|token| token.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) && token.contains('.'))
        .map(ToOwned::to_owned)
}

fn version_supports_tun_dns_mode(version: &str) -> bool {
    let mut split = version.splitn(2, '-');
    let base = split.next().unwrap_or("");
    let suffix = split.next();
    let nums = base
        .split('.')
        .filter_map(|p| p.parse::<u32>().ok())
        .collect::<Vec<_>>();
    let major = nums.get(0).copied().unwrap_or(0);
    let minor = nums.get(1).copied().unwrap_or(0);
    let patch = nums.get(2).copied().unwrap_or(0);
    if major > 1 || (major == 1 && minor > 14) || (major == 1 && minor == 14 && patch > 0) {
        return true;
    }
    if !(major == 1 && minor == 14 && patch == 0) {
        return false;
    }
    match suffix {
        None => true,
        Some(s) => {
            if let Some(n) = s.strip_prefix("alpha.").and_then(|n| n.parse::<u32>().ok()) {
                n >= 21
            } else {
                !s.starts_with("alpha")
            }
        }
    }
}

fn apply_hotspot_prerouting_redirect(listen_port: u16) -> Result<()> {
    const IPT_TIMEOUT: Duration = Duration::from_secs(5);
    let _guard = xtables_lock::lock();
    let listen_port_s = listen_port.to_string();
    let check_args = [
        "-w",
        "5",
        "-t",
        "nat",
        "-C",
        "PREROUTING",
        "-p",
        "tcp",
        "-j",
        "REDIRECT",
        "--to-ports",
        listen_port_s.as_str(),
    ];
    let rc = match xtables_lock::run_timeout_retry("iptables", &check_args, Capture::None, IPT_TIMEOUT) {
        Ok((rc, _)) => rc,
        Err(_) => 1,
    };
    if rc != 0 {
        let add_args = [
            "-w",
            "5",
            "-t",
            "nat",
            "-I",
            "PREROUTING",
            "-p",
            "tcp",
            "-j",
            "REDIRECT",
            "--to-ports",
            listen_port_s.as_str(),
        ];
        let (add_rc, out) = xtables_lock::run_timeout_retry("iptables", &add_args, Capture::Both, IPT_TIMEOUT)
            .with_context(|| format!("sing-box hotspot PREROUTING redirect to :{}", listen_port))?;
        if add_rc != 0 {
            bail!("sing-box hotspot PREROUTING redirect to :{} failed: {}", listen_port, out.trim());
        }
    }
    Ok(())
}

fn spawn_singbox(config_path: &Path, log_path: &Path) -> Result<()> {
    if singbox_profile_process_running(config_path) {
        info!(
            "sing-box: already running for config={}, skip spawn",
            config_path.display()
        );
        return Ok(());
    }

    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(SINGBOX_BIN);
    cmd.arg("run")
        .arg("-c")
        .arg(config_path)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd
        .spawn()
        .with_context(|| format!("spawn {}", SINGBOX_BIN))?;
    info!(
        "spawned sing-box pid={} cfg={} log={}",
        child.id(),
        config_path.display(),
        log_path.display()
    );

    thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("sing-box pid={} exited quickly; check log {}", child.id(), log_path.display());
    }
    Ok(())
}

fn spawn_t2s(
    bin: &Path,
    listen_addr: &str,
    listen_port: u16,
    web_port: u16,
    socks_ports_csv: &str,
    log_path: &Path,
) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("--listen-addr")
        .arg(listen_addr)
        .arg("--listen-port")
        .arg(listen_port.to_string())
        .arg("--socks-host")
        .arg("127.0.0.1")
        .arg("--socks-port")
        .arg(socks_ports_csv)
        .arg("--max-conns")
        .arg("1200")
        .arg("--idle-timeout")
        .arg("400")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket")
        .arg("--web-port")
        .arg(web_port.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned t2s pid={} listen_addr={} listen_port={} socks_ports={} web_port={} log={}",
        child.id(),
        listen_addr,
        listen_port,
        socks_ports_csv,
        web_port,
        log_path.display()
    );
    Ok(())
}

fn singbox_profile_process_running(config_path: &Path) -> bool {
    let pattern = format!("{} run -c {}", SINGBOX_BIN, config_path.display());
    let cmd = format!(
        "ps -ef 2>/dev/null | grep -F {} | grep -v grep >/dev/null 2>&1",
        shell_quote_for_sh(&pattern)
    );
    shell::ok_sh(&cmd).is_ok()
}

fn shell_quote_for_sh(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
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
                    cidr = normalize_cidr_network(ip_cidr).ok();
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
    Ok(TunInfo { cidr, gateway })
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
                if let Some(ip) = tokens.get(i + 1) {
                    if is_ipv4(ip) {
                        return Ok((*ip).to_string());
                    }
                }
            }
        }
    }
    bail!("gateway not found for {tun}")
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) {
            return Ok(id);
        }
    }
    bail!("no free sing-box VPN netid in {NETID_BASE}..={NETID_MAX}")
}

fn generated_tun_address_for_profile(profile: &str) -> Result<(String, String, String)> {
    let dirs = read_sorted_dirs(Path::new(SINGBOX_PROFILE_ROOT)).unwrap_or_default();
    let index = dirs.iter().position(|(name, _)| name == profile).unwrap_or(0) as u32;
    generated_tun_address_for_index(index)
}

pub fn generated_tun_address_for_index(index: u32) -> Result<(String, String, String)> {
    let offset = index.checked_mul(4).ok_or_else(|| anyhow::anyhow!("sing-box cidr overflow"))?;
    let network = SINGBOX_NET_BASE.checked_add(offset).ok_or_else(|| anyhow::anyhow!("sing-box cidr overflow"))?;
    let addr = network.checked_add(1).ok_or_else(|| anyhow::anyhow!("sing-box cidr overflow"))?;
    let dns = network.checked_add(2).ok_or_else(|| anyhow::anyhow!("sing-box cidr overflow"))?;
    Ok((format!("{}/30", u32_to_ipv4(addr)), format!("{}/30", u32_to_ipv4(network)), u32_to_ipv4(dns)))
}


fn normalize_cidr_network(cidr: &str) -> Result<String> {
    let (net, prefix) = cidr_network_prefix(cidr)?;
    Ok(format!("{}/{}", u32_to_ipv4(net), prefix))
}

fn cidr_network_prefix(cidr: &str) -> Result<(u32, u8)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 {
        bail!("bad cidr prefix {cidr}");
    }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, prefix))
}

fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, ap) = cidr_network_prefix(a)?;
    let (bn, bp) = cidr_network_prefix(b)?;
    let am = if ap == 0 { 0 } else { u32::MAX << (32 - ap) };
    let bm = if bp == 0 { 0 } else { u32::MAX << (32 - bp) };
    let a_start = an;
    let a_end = an | !am;
    let b_start = bn;
    let b_end = bn | !bm;
    Ok(a_start <= b_end && b_start <= a_end)
}

fn is_ipv4(s: &str) -> bool {
    ipv4_to_u32(s).is_some()
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

fn is_valid_ifname(s: &str) -> bool {
    !s.is_empty() && s.len() <= 15 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

fn is_forbidden_tun_name(s: &str) -> bool {
    let lower = s.to_ascii_lowercase();
    lower == "lo"
        || lower.starts_with("wlan")
        || lower.starts_with("eth")
        || lower.starts_with("rmnet")
        || lower.starts_with("ccmni")
        || lower.starts_with("radio")
        || lower.starts_with("dummy")
        || lower.starts_with("bridge")
        || lower.starts_with("br-")
}

fn main_pids_exact() -> Vec<u32> {
    let mut pids = Vec::new();
    for name in ["sing-box", "singbox"] {
        if let Ok((code, out)) = shell::run_timeout("pidof", &[name], Capture::Stdout, Duration::from_secs(2)) {
            if code == 0 {
                for part in out.split_whitespace() {
                    if let Ok(pid) = part.parse::<u32>() {
                        pids.push(pid);
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn read_active_profiles() -> Result<ActiveProfiles> {
    read_json(Path::new(ACTIVE_JSON))
}

fn ensure_base_layout() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    fs::create_dir_all(SINGBOX_ROOT).ok();
    fs::create_dir_all(SINGBOX_PROFILE_ROOT).ok();
    Ok(())
}

fn is_nonempty_file(p: &Path) -> Result<bool> {
    let md = fs::metadata(p).with_context(|| format!("stat {}", p.display()))?;
    Ok(md.len() > 0)
}

fn profile_root(profile: &str) -> PathBuf {
    Path::new(SINGBOX_PROFILE_ROOT).join(profile)
}

fn singbox_server_root(profile: &str, server: &str) -> PathBuf {
    profile_root(profile).join("server").join(server)
}

fn profile_t2s_log_path(profile: &str) -> PathBuf {
    profile_root(profile).join("log/t2s.log")
}

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("profile/server name must contain only English letters/digits/_/-");
    }
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() {
        Ok(p)
    } else {
        bail!("binary not found: {}", p.display())
    }
}

fn truncate_file(p: &Path) -> Result<()> {
    ensure_parent_dir(p)?;
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)?;
    Ok(())
}

fn ensure_file_empty(p: &Path) -> Result<()> {
    if !p.exists() {
        if let Some(parent) = p.parent() {
            fs::create_dir_all(parent).ok();
        }
        fs::write(p, b"").with_context(|| format!("create {}", p.display()))?;
    }
    Ok(())
}

fn count_valid_uid_pairs(path: &Path) -> Result<usize> {
    if !path.is_file() {
        return Ok(0);
    }
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut n = 0usize;
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}

fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn unique_tmp_path(target: &Path) -> PathBuf {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let pid = std::process::id();
    let name = target
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("tmp");
    target.with_file_name(format!(".{name}.{pid}.{ts}.tmp"))
}

fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = unique_tmp_path(path);
    let text = serde_json::to_string_pretty(value)?;
    fs::write(&tmp, text).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse json {}", path.display()))?;
    Ok(v)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        bail!("directory missing: {}", path.display());
    }
    Ok(())
}

fn ensure_file(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_file() {
        bail!("file missing: {}", path.display());
    }
    Ok(())
}
