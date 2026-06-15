use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::{hotspot, iptables_port::{self, DpiTunnelOptions, ProtoChoice}};
use crate::{
    settings,
    shell::{self, Capture},
};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";

const WIREPROXY_BIN: &str = "/data/adb/modules/ZDT-D/bin/wireproxy";
const WIREPROXY_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/wireproxy";
const WIREPROXY_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/wireproxy/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/wireproxy/active.json";
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

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
    #[serde(default)]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            t2s_port: 12345,
            t2s_web_port: default_t2s_web_port(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ServerSetting {
    #[serde(default)]
    pub enabled: bool,
}

impl Default for ServerSetting {
    fn default() -> Self {
        Self { enabled: false }
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
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    uid_out: PathBuf,
    uid_count: usize,
    needs_t2s: bool,
    t2s_log: PathBuf,
    servers: Vec<ServerPlan>,
}

fn default_t2s_web_port() -> u16 {
    8001
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    let setting_path = profile_root(profile).join("setting.json");
    read_json(&setting_path)
        .with_context(|| format!("read {}", setting_path.display()))
}

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    fs::create_dir_all(WIREPROXY_ROOT).ok();
    fs::create_dir_all(WIREPROXY_PROFILE_ROOT).ok();
    ensure_file(WIREPROXY_BIN)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveProfiles = match read_json(active_path) {
        Ok(v) => v,
        Err(e) => {
            warn!("wireproxy: failed to read active.json (skip): {e:#}");
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
        info!("wireproxy: no enabled profiles in active.json");
        return Ok(());
    }

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings
        .hotspot_t2s_wireproxy_profile()
        .map(|s| s.to_string());

    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding(false, true)
        .unwrap_or_else(|_| BTreeSet::new());
    let mut own_used = BTreeSet::<u16>::new();
    let mut plans = Vec::<ProfilePlan>::new();

    for name in enabled_names {
        match build_profile_plan(&name, &external_used, &own_used, hotspot_profile.as_deref()) {
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
            Err(e) => warn!("wireproxy: profile '{}' skip: {e:#}", name),
        }
    }

    if plans.is_empty() {
        warn!("wireproxy: no runnable profiles after validation");
        return Ok(());
    }

    let t2s_bin = if plans.iter().any(|plan| plan.needs_t2s) {
        Some(find_bin("t2s")?)
    } else {
        None
    };
    if api_settings.hotspot_t2s_for_wireproxy() && hotspot_profile.is_none() {
        warn!("wireproxy: hotspot target is wireproxy, but no hotspot profile is selected; using 127.0.0.1 for all profiles");
    }
    if let Some(ref wanted_profile) = hotspot_profile {
        if !plans.iter().any(|plan| plan.name == *wanted_profile) {
            warn!(
                "wireproxy: hotspot profile '{}' is not active/runnable; skipping hotspot redirect",
                wanted_profile
            );
        }
    }

    crate::logging::user_info("wireproxy: запуск");

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
            spawn_wireproxy(&server.config_path, &server.log_path)
                .with_context(|| format!("spawn wireproxy profile={} server={}", plan.name, server.name))?;
        }

        let hotspot_for_plan = hotspot_profile.as_deref() == Some(plan.name.as_str());
        let t2s_listen_addr = if hotspot_for_plan { "0.0.0.0" } else { "127.0.0.1" };

        if plan.needs_t2s {
            let t2s_bin = t2s_bin.as_ref().context("t2s binary path missing for wireproxy profile")?;
            truncate_file(&plan.t2s_log)?;
            spawn_t2s(
                t2s_bin,
                t2s_listen_addr,
                plan.setting.t2s_port,
                plan.setting.t2s_web_port,
                &ports_csv,
                &plan.t2s_log,
                &plan.name,
            )
            .with_context(|| format!("spawn t2s profile={}", plan.name))?;

            if hotspot_for_plan {
                hotspot::ensure_prerouting_redirect(hotspot::HotspotRedirectConfig {
                    owner: "wireproxy",
                    listen_port: plan.setting.t2s_port,
                    capture_all: api_settings.hotspot_t2s_capture_all,
                    bypass_ports: hotspot::DEFAULT_HOTSPOT_BYPASS_PORTS,
                })?;
            }

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
            if plan.uid_count == 0 {
                info!("wireproxy: profile={} has no routed app UIDs yet; registered runtime routing for hot app refresh", plan.name);
            }

            info!(
                "wireproxy: profile={} apps={} servers={} t2s_port={} t2s_web_port={} socks_ports={} listen_addr={}",
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
                "wireproxy: profile={} apps={} servers={} socks_ports={} mode=server-only",
                plan.name,
                plan.uid_count,
                plan.servers.len(),
                ports_csv,
            );
        }
    }

    Ok(())
}

fn collect_enabled_local_ports(enabled_names: &[String]) -> BTreeSet<u16> {
    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings.hotspot_t2s_wireproxy_profile().map(|s| s.to_string());
    let mut ports = BTreeSet::new();

    for name in enabled_names {
        let Ok(setting) = read_setting(name) else { continue };
        let app_in = profile_root(name).join("app/uid/user_program");
        let has_real_apps = app_list_has_real_apps(&app_in).unwrap_or(false);
        let has_launch_marker = pkg_uid::file_has_launch_marker(&app_in).unwrap_or(false);
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

        let server_root = profile_root(name).join("server");
        let Ok(entries) = read_sorted_dirs(&server_root) else { continue };
        for (_server_name, dir) in entries {
            let setting: ServerSetting = match read_json(&dir.join("setting.json")) {
                Ok(v) => v,
                Err(_) => continue,
            };
            if !setting.enabled {
                continue;
            }
            let cfg = dir.join("config.conf");
            let bind = match parse_socks5_bind_address(&cfg) {
                Ok(v) => v,
                Err(_) => continue,
            };
            if bind.port != 0 {
                ports.insert(bind.port);
            }
        }
    }

    ports
}

pub fn start_construction_profile(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    fs::create_dir_all(WIREPROXY_ROOT).ok();
    fs::create_dir_all(WIREPROXY_PROFILE_ROOT).ok();
    ensure_file(WIREPROXY_BIN)?;

    let active: ActiveProfiles = read_json(Path::new(ACTIVE_JSON)).unwrap_or_default();
    if !active.profiles.get(profile).map(|st| st.enabled).unwrap_or(false) {
        info!("wireproxy: construction profile '{}' is disabled, skip targeted start", profile);
        return Ok(());
    }

    let api_settings = settings::load_api_settings().unwrap_or_default();
    let hotspot_profile = api_settings
        .hotspot_t2s_wireproxy_profile()
        .map(|s| s.to_string());
    let external_used = crate::ports::collect_used_ports_for_conflict_check_excluding(false, true)
        .unwrap_or_else(|_| BTreeSet::new());
    let other_enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(name, st)| st.enabled && name.as_str() != profile)
        .map(|(name, _)| name.clone())
        .collect();
    let own_used = collect_enabled_local_ports(&other_enabled_names);

    let Some(plan) = build_profile_plan(profile, &external_used, &own_used, hotspot_profile.as_deref())? else {
        info!("wireproxy: construction profile '{}' has no runnable plan", profile);
        return Ok(());
    };

    let t2s_bin = if plan.needs_t2s {
        Some(find_bin("t2s")?)
    } else {
        None
    };
    let ports_csv = plan
        .servers
        .iter()
        .map(|srv| srv.port.to_string())
        .collect::<Vec<_>>()
        .join(",");

    for server in &plan.servers {
        ensure_parent_dir(&server.log_path)?;
        truncate_file(&server.log_path)?;
        spawn_wireproxy(&server.config_path, &server.log_path)
            .with_context(|| format!("spawn wireproxy profile={} server={}", plan.name, server.name))?;
    }

    let hotspot_for_plan = hotspot_profile.as_deref() == Some(plan.name.as_str());
    let t2s_listen_addr = if hotspot_for_plan { "0.0.0.0" } else { "127.0.0.1" };

    if plan.needs_t2s {
        let t2s_bin = t2s_bin.as_ref().context("t2s binary path missing for wireproxy profile")?;
        truncate_file(&plan.t2s_log)?;
        spawn_t2s(
            t2s_bin,
            t2s_listen_addr,
            plan.setting.t2s_port,
            plan.setting.t2s_web_port,
            &ports_csv,
            &plan.t2s_log,
            &plan.name,
        )
        .with_context(|| format!("spawn t2s profile={}", plan.name))?;

        if hotspot_for_plan {
            hotspot::ensure_prerouting_redirect(hotspot::HotspotRedirectConfig {
                owner: "wireproxy",
                listen_port: plan.setting.t2s_port,
                capture_all: api_settings.hotspot_t2s_capture_all,
                bypass_ports: hotspot::DEFAULT_HOTSPOT_BYPASS_PORTS,
            })?;
        }

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
        if plan.uid_count == 0 {
            info!("wireproxy: profile={} has no routed app UIDs yet; registered runtime routing for hot app refresh", plan.name);
        }

        info!(
            "wireproxy: targeted construction start profile={} apps={} servers={} t2s_port={} t2s_web_port={} socks_ports={} listen_addr={}",
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
            "wireproxy: targeted construction start profile={} apps={} servers={} socks_ports={} mode=server-only",
            plan.name,
            plan.uid_count,
            plan.servers.len(),
            ports_csv,
        );
    }

    Ok(())
}

fn build_profile_plan(
    profile: &str,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
    hotspot_profile: Option<&str>,
) -> Result<Option<ProfilePlan>> {
    ensure_valid_profile_name(profile)?;

    let profile_dir = profile_root(profile);
    if !profile_dir.is_dir() {
        anyhow::bail!("profile directory missing: {}", profile_dir.display());
    }

    let setting_path = profile_dir.join("setting.json");
    let setting: ProfileSetting = read_json(&setting_path)
        .with_context(|| format!("read {}", setting_path.display()))?;

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
        warn!("wireproxy: profile '{}' has no resolved apps", profile);
        return Ok(None);
    }
    if resolved == 0 && has_launch_marker {
        info!("wireproxy: profile '{}' uses launch marker; starting without routing app UIDs", profile);
    }

    let needs_t2s = resolved > 0 || hotspot_profile == Some(profile);
    if needs_t2s {
        validate_profile_setting(profile, &setting, external_used, own_used)?;
    }

    let mut reserved = BTreeSet::new();
    if needs_t2s {
        reserved.insert(setting.t2s_port);
        reserved.insert(setting.t2s_web_port);
    }
    let servers = collect_profile_servers(profile, external_used, own_used, &reserved)?;
    if servers.is_empty() {
        warn!("wireproxy: profile '{}' has no runnable servers", profile);
        return Ok(None);
    }

    Ok(Some(ProfilePlan {
        name: profile.to_string(),
        setting,
        uid_out,
        uid_count: resolved,
        needs_t2s,
        t2s_log: profile_t2s_log_path(profile),
        servers,
    }))
}

fn validate_profile_setting(
    profile: &str,
    setting: &ProfileSetting,
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
) -> Result<()> {
    for (label, port) in [
        ("t2s_port", setting.t2s_port),
        ("t2s_web_port", setting.t2s_web_port),
    ] {
        if port == 0 {
            anyhow::bail!("{} is required for profile '{}'", label, profile);
        }
    }
    if setting.t2s_port == setting.t2s_web_port {
        anyhow::bail!("profile '{}' uses duplicate t2s ports", profile);
    }
    for port in [setting.t2s_port, setting.t2s_web_port] {
        if external_used.contains(&port) {
            anyhow::bail!("port conflict detected: {}", port);
        }
        if own_used.contains(&port) {
            anyhow::bail!("port conflict with another wireproxy profile: {}", port);
        }
    }
    Ok(())
}

fn collect_profile_servers(
    profile: &str,
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
    let mut entries = fs::read_dir(&server_root)
        .with_context(|| format!("readdir {}", server_root.display()))?
        .filter_map(|e| e.ok())
        .collect::<Vec<_>>();
    entries.sort_by_key(|e| e.file_name());

    for ent in entries {
        let dir = ent.path();
        if !dir.is_dir() {
            continue;
        }
        let Some(name) = dir.file_name().and_then(|s| s.to_str()) else { continue };
        if name.starts_with('.') {
            continue;
        }
        if let Err(e) = ensure_valid_profile_name(name) {
            warn!(
                "wireproxy: skip profile='{}' server-dir='{}' (invalid directory name): {e:#}",
                profile,
                name
            );
            continue;
        }

        let setting_path = dir.join("setting.json");
        let setting: ServerSetting = match read_json(&setting_path) {
            Ok(v) => v,
            Err(e) => {
                warn!(
                    "wireproxy: skip profile='{}' server='{}' (bad setting.json): {e:#}",
                    profile,
                    name
                );
                continue;
            }
        };
        if !setting.enabled {
            continue;
        }

        let cfg = dir.join("config.conf");
        if !cfg.is_file() {
            warn!(
                "wireproxy: skip profile='{}' server='{}' (config.conf missing)",
                profile,
                name
            );
            continue;
        }
        if !is_nonempty_file(&cfg).unwrap_or(false) {
            warn!(
                "wireproxy: skip profile='{}' server='{}' (config.conf empty)",
                profile,
                name
            );
            continue;
        }
        let bind = match parse_socks5_bind_address(&cfg) {
            Ok(v) => v,
            Err(e) => {
                warn!(
                    "wireproxy: skip profile='{}' server='{}' (invalid Socks5 BindAddress): {e:#}",
                    profile,
                    name
                );
                continue;
            }
        };
        if bind.host != "127.0.0.1" {
            warn!(
                "wireproxy: skip profile='{}' server='{}' (BindAddress host must be 127.0.0.1, got '{}')",
                profile,
                name,
                bind.host
            );
            continue;
        }
        let port = bind.port;
        if port == 0 {
            warn!(
                "wireproxy: skip profile='{}' server='{}' (missing/invalid port)",
                profile,
                name
            );
            continue;
        }
        if external_used.contains(&port)
            || own_used.contains(&port)
            || reserved.contains(&port)
            || !seen_ports.insert(port)
        {
            warn!(
                "wireproxy: skip profile='{}' server='{}' (port conflict {})",
                profile,
                name,
                port
            );
            continue;
        }

        out.push(ServerPlan {
            name: name.to_string(),
            port,
            config_path: cfg,
            log_path: dir.join("log/wireproxy.log"),
        });
    }

    Ok(out)
}

fn is_nonempty_file(p: &Path) -> Result<bool> {
    let md = fs::metadata(p).with_context(|| format!("stat {}", p.display()))?;
    Ok(md.len() > 0)
}

fn profile_root(profile: &str) -> PathBuf {
    Path::new(WIREPROXY_PROFILE_ROOT).join(profile)
}

fn profile_t2s_log_path(profile: &str) -> PathBuf {
    profile_root(profile).join("log/t2s.log")
}

fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if name.is_empty() {
        anyhow::bail!("profile name is empty");
    }
    if name.len() > 64 {
        anyhow::bail!("profile name too long");
    }
    if !name
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        anyhow::bail!("profile/server name must contain only English letters/digits/_/-");
    }
    Ok(())
}

fn spawn_wireproxy(config_path: &Path, log_path: &Path) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(WIREPROXY_BIN);
    cmd.arg("-c")
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
        .with_context(|| format!("spawn {}", WIREPROXY_BIN))?;
    info!(
        "spawned wireproxy pid={} cfg={} log={}",
        child.id(),
        config_path.display(),
        log_path.display()
    );

    std::thread::sleep(std::time::Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("wireproxy pid={} exited quickly; check log {}", child.id(), log_path.display());
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
    profile: &str,
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
        .arg("--program")
        .arg("wireproxy")
        .arg("--profile")
        .arg(profile)
        .arg("--scope")
        .arg(format!("profile/wireproxy/{}", profile))
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

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() {
        Ok(p)
    } else {
        anyhow::bail!("binary not found: {}", p.display())
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

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse json {}", path.display()))?;
    Ok(v)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}

fn ensure_file(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_file() {
        anyhow::bail!("file missing: {}", path.display());
    }
    Ok(())
}

#[derive(Debug, Clone)]
pub struct BindAddress {
    pub host: String,
    pub port: u16,
}

pub fn parse_socks5_bind_address(config_path: &Path) -> Result<BindAddress> {
    let raw = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    parse_socks5_bind_address_str(&raw)
}

pub fn parse_socks5_bind_address_str(raw: &str) -> Result<BindAddress> {
    let mut in_socks5 = false;
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with(';') {
            continue;
        }
        if line.starts_with('[') && line.ends_with(']') {
            let section = line.trim_start_matches('[').trim_end_matches(']').trim();
            in_socks5 = section.eq_ignore_ascii_case("Socks5");
            continue;
        }
        if !in_socks5 {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else { continue };
        if !key.trim().eq_ignore_ascii_case("BindAddress") {
            continue;
        }
        let value = value.trim();
        let (host, port_s) = value
            .rsplit_once(':')
            .ok_or_else(|| anyhow::anyhow!("BindAddress must be host:port"))?;
        let host = host.trim();
        if host.is_empty() {
            anyhow::bail!("BindAddress host is empty");
        }
        let port: u16 = port_s.trim().parse().map_err(|_| anyhow::anyhow!("BindAddress port is invalid"))?;
        if port == 0 {
            anyhow::bail!("BindAddress port must be > 0");
        }
        return Ok(BindAddress {
            host: host.to_string(),
            port,
        });
    }
    anyhow::bail!("[Socks5] BindAddress not found")
}
