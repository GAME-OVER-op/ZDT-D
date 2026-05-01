use anyhow::{Context, Result};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};

use crate::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker};
use crate::iptables::iptables_port::{self, DpiTunnelOptions, ProtoChoice};
use crate::settings;

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const MYPROGRAM_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myprogram";
const MYPROGRAM_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/myprogram/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/myprogram/active.json";
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;
const FIXED_SOCKS_HOST: &str = "127.0.0.1";

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
    pub apps_mode: bool,
    #[serde(default = "default_route_mode")]
    pub route_mode: String,
    #[serde(default = "default_proto_mode")]
    pub proto_mode: String,
    #[serde(default)]
    pub transparent_port: u16,
    #[serde(default = "default_t2s_port")]
    pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")]
    pub t2s_web_port: u16,
    #[serde(default)]
    pub socks_user: String,
    #[serde(default)]
    pub socks_pass: String,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            apps_mode: false,
            route_mode: default_route_mode(),
            proto_mode: default_proto_mode(),
            transparent_port: 0,
            t2s_port: default_t2s_port(),
            t2s_web_port: default_t2s_web_port(),
            socks_user: String::new(),
            socks_pass: String::new(),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct RuntimeState {
    #[serde(default)]
    pub running: bool,
    #[serde(default)]
    pub pid: u32,
    #[serde(default)]
    pub pgid: i32,
    #[serde(default)]
    pub started_at: u64,
    #[serde(default)]
    pub command: String,
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    command: String,
    uid_out: PathBuf,
    uid_count: usize,
    t2s_ports: Vec<u16>,
    protect_ports: Vec<u16>,
    t2s_log: PathBuf,
    program_log: PathBuf,
    runtime_path: PathBuf,
    bin_dir: PathBuf,
}

fn default_route_mode() -> String { "t2s".to_string() }
fn default_proto_mode() -> String { "tcp".to_string() }
fn default_t2s_port() -> u16 { 12350 }
fn default_t2s_web_port() -> u16 { 8006 }

fn route_mode_is_t2s(setting: &ProfileSetting) -> bool { setting.route_mode == "t2s" }
fn proto_choice_for_setting(setting: &ProfileSetting) -> ProtoChoice {
    match setting.proto_mode.as_str() {
        "tcp_udp" => ProtoChoice::TcpUdp,
        _ => ProtoChoice::Tcp,
    }
}

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    fs::create_dir_all(MYPROGRAM_ROOT).ok();
    fs::create_dir_all(MYPROGRAM_PROFILE_ROOT).ok();

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveProfiles = match read_json(active_path) {
        Ok(v) => v,
        Err(e) => {
            warn!("myprogram: failed to read active.json (skip): {e:#}");
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
        info!("myprogram: no enabled profiles in active.json");
        return Ok(());
    }

    let mut external_used = crate::ports::collect_used_ports_for_conflict_check().unwrap_or_else(|_| BTreeSet::new());
    for p in collect_defined_myprogram_ports_local() {
        external_used.remove(&p);
    }

    let mut own_used = BTreeSet::<u16>::new();
    let mut plans = Vec::<ProfilePlan>::new();

    for name in enabled_names {
        match build_profile_plan(&name, &external_used, &own_used) {
            Ok(Some(plan)) => {
                own_used.extend(plan_ports(&plan));
                plans.push(plan);
            }
            Ok(None) => {}
            Err(e) => warn!("myprogram: profile '{}' skip: {e:#}", name),
        }
    }

    if plans.is_empty() {
        warn!("myprogram: no runnable profiles after validation");
        return Ok(());
    }

    let t2s_bin = if plans.iter().any(|p| p.setting.apps_mode && route_mode_is_t2s(&p.setting)) { Some(find_bin("t2s")?) } else { None };
    crate::logging::user_info("myprogram: custom profiles");

    for plan in &plans {
        truncate_file(&plan.program_log)?;
        truncate_file(&plan.t2s_log)?;
        let runtime = spawn_program(&plan.command, &plan.bin_dir, &plan.program_log)
            .with_context(|| format!("spawn program profile={}", plan.name))?;
        write_json_atomic(&plan.runtime_path, &runtime)?;

        if plan.setting.apps_mode {
            if route_mode_is_t2s(&plan.setting) {
                spawn_t2s(t2s_bin.as_ref().expect("t2s checked"), &plan.setting, &plan.t2s_ports, &plan.t2s_log)
                    .with_context(|| format!("spawn t2s profile={}", plan.name))?;
                iptables_port::apply(
                    &plan.uid_out,
                    plan.setting.t2s_port,
                    ProtoChoice::Tcp,
                    None,
                    DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
                )
                .with_context(|| format!("iptables profile={}", plan.name))?;
            } else {
                iptables_port::apply(
                    &plan.uid_out,
                    plan.setting.transparent_port,
                    proto_choice_for_setting(&plan.setting),
                    None,
                    DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() },
                )
                .with_context(|| format!("iptables profile={}", plan.name))?;
            }
        }

        info!(
            "myprogram: profile={} apps_mode={} route_mode={} proto_mode={} transparent_port={} apps={} t2s_port={} t2s_web_port={} t2s_ports={} protect_ports={} log={}",
            plan.name,
            plan.setting.apps_mode,
            plan.setting.route_mode,
            plan.setting.proto_mode,
            plan.setting.transparent_port,
            plan.uid_count,
            plan.setting.t2s_port,
            plan.setting.t2s_web_port,
            plan.t2s_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","),
            plan.protect_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","),
            plan.program_log.display()
        );
    }

    Ok(())
}

fn plan_ports(plan: &ProfilePlan) -> BTreeSet<u16> {
    let mut set = BTreeSet::new();
    if plan.setting.apps_mode {
        if route_mode_is_t2s(&plan.setting) {
            if plan.setting.t2s_port > 0 { set.insert(plan.setting.t2s_port); }
            if plan.setting.t2s_web_port > 0 { set.insert(plan.setting.t2s_web_port); }
            set.extend(plan.t2s_ports.iter().copied().filter(|p| *p > 0));
        } else if plan.setting.transparent_port > 0 {
            set.insert(plan.setting.transparent_port);
        }
    }
    set.extend(plan.protect_ports.iter().copied().filter(|p| *p > 0));
    set
}

fn build_profile_plan(profile: &str, external_used: &BTreeSet<u16>, own_used: &BTreeSet<u16>) -> Result<Option<ProfilePlan>> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);

    let setting: ProfileSetting = read_json(&setting_path(profile))
        .with_context(|| format!("read {}", setting_path(profile).display()))?;
    validate_setting(&setting)?;

    let command = read_text_or_empty(&command_path(profile))?.trim().to_string();
    if command.is_empty() {
        warn!("myprogram: profile '{}' has empty command", profile);
        return Ok(None);
    }

    let t2s_ports = read_port_list(&t2s_ports_path(profile))
        .with_context(|| format!("read {}", t2s_ports_path(profile).display()))?;
    let protect_ports = read_port_list(&protect_ports_path(profile))
        .with_context(|| format!("read {}", protect_ports_path(profile).display()))?;

    let mut uid_out = out_program_path(profile);
    let mut uid_count = 0usize;
    if setting.apps_mode {
        let tracker = Sha256Tracker::new(SHA_FLAG_FILE);
        let uid_in = uid_program_path(profile);
        ensure_file_empty(&uid_in)?;
        ensure_parent_dir(&uid_out)?;
        let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &uid_out, &uid_in)
            .with_context(|| format!("pkg_uid processing profile={profile}"))?;
        uid_count = count_valid_uid_pairs(&uid_out).unwrap_or(0);
        if uid_count == 0 {
            warn!("myprogram: profile '{}' has no resolved apps", profile);
            return Ok(None);
        }
    } else {
        ensure_parent_dir(&uid_out)?;
        if !uid_out.exists() {
            write_text_atomic(&uid_out, "")?;
        }
    }

    validate_profile_ports(profile, &setting, &t2s_ports, &protect_ports, external_used, own_used)?;

    let t2s_log = profile_dir.join("log/t2s.log");
    let program_log = profile_dir.join("log/program.log");
    ensure_parent_dir(&t2s_log)?;
    ensure_parent_dir(&program_log)?;

    Ok(Some(ProfilePlan {
        name: profile.to_string(),
        setting,
        command,
        uid_out,
        uid_count,
        t2s_ports,
        protect_ports,
        t2s_log,
        program_log,
        runtime_path: runtime_path(profile),
        bin_dir: profile_dir.join("bin"),
    }))
}

fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    let user_empty = setting.socks_user.trim().is_empty();
    let pass_empty = setting.socks_pass.trim().is_empty();
    if user_empty ^ pass_empty {
        anyhow::bail!("socks_user and socks_pass must both be set or both be empty");
    }
    match setting.route_mode.as_str() {
        "t2s" | "transparent" => {}
        other => anyhow::bail!("invalid route_mode: {other}"),
    }
    match setting.proto_mode.as_str() {
        "tcp" | "tcp_udp" => {}
        other => anyhow::bail!("invalid proto_mode: {other}"),
    }
    if setting.apps_mode {
        if route_mode_is_t2s(setting) {
            if setting.t2s_port == 0 || setting.t2s_web_port == 0 || setting.t2s_port == setting.t2s_web_port {
                anyhow::bail!("invalid t2s ports");
            }
        } else {
            if setting.transparent_port == 0 {
                anyhow::bail!("transparent_port is required in transparent mode");
            }
        }
    }
    Ok(())
}

fn validate_profile_ports(
    _profile: &str,
    setting: &ProfileSetting,
    t2s_ports: &[u16],
    protect_ports: &[u16],
    external_used: &BTreeSet<u16>,
    own_used: &BTreeSet<u16>,
) -> Result<()> {
    let mut ports = BTreeSet::new();
    if setting.apps_mode {
        if route_mode_is_t2s(setting) {
            if t2s_ports.is_empty() {
                anyhow::bail!("t2s_ports list is empty");
            }
            ports.insert(setting.t2s_port);
            ports.insert(setting.t2s_web_port);
            for &p in t2s_ports {
                if p == 0 { anyhow::bail!("invalid t2s upstream port"); }
                if p == setting.t2s_port || p == setting.t2s_web_port {
                    anyhow::bail!("t2s upstream ports must not match t2s listen/web ports");
                }
                ports.insert(p);
            }
        } else {
            if setting.transparent_port == 0 { anyhow::bail!("invalid transparent port"); }
            ports.insert(setting.transparent_port);
        }
    }
    for &p in protect_ports {
        if p == 0 { anyhow::bail!("invalid protect port"); }
        ports.insert(p);
    }
    for port in ports {
        if external_used.contains(&port) || own_used.contains(&port) {
            anyhow::bail!("port conflict detected: {}", port);
        }
    }
    Ok(())
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if name.is_empty() { anyhow::bail!("profile name is empty"); }
    if name.len() > 64 { anyhow::bail!("profile name too long"); }
    if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') {
        anyhow::bail!("profile name must contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    fs::create_dir_all(root.join("bin"))?;
    ensure_file_empty(&uid_program_path(profile))?;
    ensure_file_empty(&out_program_path(profile))?;
    ensure_file_empty(&command_path(profile))?;
    ensure_file_empty(&t2s_ports_path(profile))?;
    ensure_file_empty(&protect_ports_path(profile))?;
    ensure_file_empty(&root.join("log/program.log"))?;
    ensure_file_empty(&root.join("log/t2s.log"))?;
    if !setting_path(profile).exists() {
        write_json_atomic(&setting_path(profile), &ProfileSetting::default())?;
    }
    if !runtime_path(profile).exists() {
        write_json_atomic(&runtime_path(profile), &RuntimeState::default())?;
    }
    Ok(())
}

pub fn read_command_text(profile: &str) -> Result<String> { read_text_or_empty(&command_path(profile)) }
pub fn write_command_text(profile: &str, content: &str) -> Result<()> { ensure_profile_layout(profile)?; write_text_atomic(&command_path(profile), content) }
pub fn read_t2s_ports_text(profile: &str) -> Result<String> { read_text_or_empty(&t2s_ports_path(profile)) }
pub fn write_t2s_ports_text(profile: &str, content: &str) -> Result<()> { ensure_profile_layout(profile)?; validate_port_list_str(content)?; write_text_atomic(&t2s_ports_path(profile), content) }
pub fn read_protect_ports_text(profile: &str) -> Result<String> { read_text_or_empty(&protect_ports_path(profile)) }
pub fn write_protect_ports_text(profile: &str, content: &str) -> Result<()> { ensure_profile_layout(profile)?; validate_port_list_str(content)?; write_text_atomic(&protect_ports_path(profile), content) }
pub fn load_setting(profile: &str) -> Result<ProfileSetting> { ensure_profile_layout(profile)?; read_json(&setting_path(profile)) }
pub fn save_setting(profile: &str, setting: &ProfileSetting) -> Result<()> { ensure_profile_layout(profile)?; validate_setting(setting)?; write_json_atomic(&setting_path(profile), setting) }
pub fn load_runtime(profile: &str) -> Result<RuntimeState> { ensure_profile_layout(profile)?; read_json(&runtime_path(profile)) }
pub fn save_runtime(profile: &str, runtime: &RuntimeState) -> Result<()> { ensure_profile_layout(profile)?; write_json_atomic(&runtime_path(profile), runtime) }
pub fn list_bin_files(profile: &str) -> Result<Vec<(String,u64)>> {
    ensure_profile_layout(profile)?;
    let root = profile_root(profile).join("bin");
    let mut out = Vec::new();
    if let Ok(rd) = fs::read_dir(&root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_file() { continue; }
            let Some(name) = ent.file_name().to_str().map(|s| s.to_string()) else { continue; };
            let size = ent.metadata().map(|m| m.len()).unwrap_or(0);
            out.push((name, size));
        }
    }
    out.sort_by(|a,b| a.0.cmp(&b.0));
    Ok(out)
}
pub fn save_bin_file(profile: &str, filename: &str, data: &[u8]) -> Result<()> {
    ensure_profile_layout(profile)?;
    let path = profile_root(profile).join("bin").join(filename);
    write_bytes_atomic(&path, data)?;
    chmod_best_effort(&path, 0o755);
    Ok(())
}
pub fn delete_bin_file(profile: &str, filename: &str) -> Result<()> {
    ensure_profile_layout(profile)?;
    let path = profile_root(profile).join("bin").join(filename);
    if path.exists() { fs::remove_file(&path).with_context(|| format!("remove {}", path.display()))?; }
    Ok(())
}

pub fn collect_defined_ports_for_conflict_check() -> BTreeSet<u16> {
    collect_defined_myprogram_ports_local()
}

pub fn stop_all() -> Result<()> {
    let root = Path::new(MYPROGRAM_PROFILE_ROOT);
    if let Ok(rd) = fs::read_dir(root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() { continue; }
            let Some(profile) = path.file_name().and_then(|s| s.to_str()) else { continue; };
            let runtime_path = runtime_path(profile);
            let runtime: RuntimeState = read_json(&runtime_path).unwrap_or_default();
            if runtime.pgid > 1 {
                let _ = crate::shell::ok_sh(&format!("kill -15 -- -{}", runtime.pgid));
            } else if runtime.pid > 1 {
                let _ = crate::shell::ok_sh(&format!("kill -15 {}", runtime.pid));
            }
            if runtime.pgid > 1 || runtime.pid > 1 {
                std::thread::sleep(std::time::Duration::from_millis(300));
                if runtime.pgid > 1 {
                    let _ = crate::shell::ok_sh(&format!("kill -9 -- -{}", runtime.pgid));
                } else if runtime.pid > 1 {
                    let _ = crate::shell::ok_sh(&format!("kill -9 {}", runtime.pid));
                }
            }
            let _ = write_json_atomic(&runtime_path, &RuntimeState::default());
        }
    }
    Ok(())
}

fn collect_defined_myprogram_ports_local() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();
    let root = Path::new(MYPROGRAM_PROFILE_ROOT);
    if let Ok(rd) = fs::read_dir(root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
            if let Ok(setting) = read_json::<ProfileSetting>(&profile_dir.join("setting.json")) {
                if setting.apps_mode {
                    if route_mode_is_t2s(&setting) {
                        if setting.t2s_port > 0 { used.insert(setting.t2s_port); }
                        if setting.t2s_web_port > 0 { used.insert(setting.t2s_web_port); }
                        if let Ok(ports) = read_port_list(&profile_dir.join("t2s_ports.txt")) { used.extend(ports); }
                    } else if setting.transparent_port > 0 {
                        used.insert(setting.transparent_port);
                    }
                }
            }
            if let Ok(ports) = read_port_list(&profile_dir.join("protect_ports.txt")) { used.extend(ports); }
        }
    }
    used
}

fn profile_root(profile: &str) -> PathBuf { Path::new(MYPROGRAM_PROFILE_ROOT).join(profile) }
fn setting_path(profile: &str) -> PathBuf { profile_root(profile).join("setting.json") }
fn uid_program_path(profile: &str) -> PathBuf { profile_root(profile).join("app/uid/user_program") }
fn out_program_path(profile: &str) -> PathBuf { profile_root(profile).join("app/out/user_program") }
fn command_path(profile: &str) -> PathBuf { profile_root(profile).join("command.txt") }
fn t2s_ports_path(profile: &str) -> PathBuf { profile_root(profile).join("t2s_ports.txt") }
fn protect_ports_path(profile: &str) -> PathBuf { profile_root(profile).join("protect_ports.txt") }
fn runtime_path(profile: &str) -> PathBuf { profile_root(profile).join("runtime.json") }

fn read_port_list(path: &Path) -> Result<Vec<u16>> {
    let raw = read_text_or_empty(path)?;
    parse_port_list_str(&raw)
}

pub fn parse_port_list_str(raw: &str) -> Result<Vec<u16>> {
    let mut out = Vec::<u16>::new();
    let mut seen = BTreeSet::<u16>::new();
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') { s = left.trim(); }
        if s.is_empty() { continue; }
        let port: u16 = s.parse().map_err(|_| anyhow::anyhow!("invalid port: {s}"))?;
        if port == 0 { anyhow::bail!("invalid port: 0"); }
        if seen.insert(port) { out.push(port); }
    }
    Ok(out)
}

pub fn validate_port_list_str(raw: &str) -> Result<()> { let _ = parse_port_list_str(raw)?; Ok(()) }

fn spawn_program(command: &str, bin_dir: &Path, log_path: &Path) -> Result<RuntimeState> {
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;
    let mut cmd = Command::new("sh");
    cmd.arg("-c")
        .arg(command)
        .current_dir(bin_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd.spawn().with_context(|| "spawn custom program")?;
    let pid = child.id();
    let pgid = pid as i32;
    let runtime = RuntimeState {
        running: true,
        pid,
        pgid,
        started_at: SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs(),
        command: command.to_string(),
    };
    info!("spawned myprogram pid={} cwd={} log={}", pid, bin_dir.display(), log_path.display());
    Ok(runtime)
}

fn spawn_t2s(bin: &Path, setting: &ProfileSetting, t2s_ports: &[u16], log_path: &Path) -> Result<()> {
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;
    let mut cmd = Command::new(bin);
    cmd.arg("--listen-addr")
        .arg("127.0.0.1")
        .arg("--listen-port")
        .arg(setting.t2s_port.to_string())
        .arg("--socks-host")
        .arg(FIXED_SOCKS_HOST)
        .arg("--socks-port")
        .arg(t2s_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","))
        .arg("--max-conns")
        .arg("1200")
        .arg("--idle-timeout")
        .arg("400")
        .arg("--connect-timeout")
        .arg("30")
        .arg("--enable-http2")
        .arg("--web-socket")
        .arg("--web-port")
        .arg(setting.t2s_web_port.to_string())
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    if !setting.socks_user.trim().is_empty() || !setting.socks_pass.trim().is_empty() {
        cmd.arg("--socks-user").arg(setting.socks_user.trim())
            .arg("--socks-pass").arg(setting.socks_pass.trim());
    }
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned t2s pid={} listen_addr=127.0.0.1 listen_port={} socks_host={} socks_port={} web_port={} log={}",
        child.id(), setting.t2s_port, FIXED_SOCKS_HOST, t2s_ports.iter().map(|p| p.to_string()).collect::<Vec<_>>().join(","), setting.t2s_web_port, log_path.display()
    );
    Ok(())
}

fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new("/data/adb/modules/ZDT-D/bin").join(name);
    if p.is_file() { Ok(p) } else { anyhow::bail!("binary not found: {}", p.display()) }
}

fn truncate_file(p: &Path) -> Result<()> {
    ensure_parent_dir(p)?;
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)?;
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(p: &Path) -> Result<T> {
    let raw = fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?;
    let v: T = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(v)
}

fn write_json_atomic<T: Serialize>(p: &Path, v: &T) -> Result<()> {
    let txt = serde_json::to_string_pretty(v)?;
    write_text_atomic(p, &txt)
}

fn read_text_or_empty(p: &Path) -> Result<String> {
    match fs::read_to_string(p) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read failed {}: {e}", p.display())),
    }
}

fn write_text_atomic(p: &Path, content: &str) -> Result<()> {
    ensure_parent_dir(p)?;
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, content.as_bytes()).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, p).with_context(|| format!("rename {} -> {}", tmp.display(), p.display()))?;
    Ok(())
}

fn write_bytes_atomic(p: &Path, data: &[u8]) -> Result<()> {
    ensure_parent_dir(p)?;
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, data).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, p).with_context(|| format!("rename {} -> {}", tmp.display(), p.display()))?;
    Ok(())
}

fn chmod_best_effort(path: &Path, mode: u32) {
    let _ = Command::new("chmod").arg(format!("{:o}", mode)).arg(path).status();
}

fn ensure_dir(p: &str) -> Result<()> {
    fs::create_dir_all(p).with_context(|| format!("mkdir {p}"))?;
    Ok(())
}

fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

fn ensure_file_empty(p: &Path) -> Result<()> {
    if !p.exists() {
        ensure_parent_dir(p)?;
        fs::write(p, "").with_context(|| format!("write {}", p.display()))?;
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
        if line.is_empty() || line.starts_with('#') { continue; }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}
