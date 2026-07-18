use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{de, Deserialize, Deserializer, Serialize, Serializer};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use super::common::*;
use crate::{
    android::pkg_uid::{self, Mode as UidMode, Sha256Tracker},
    iptables::iptables_port::{DpiTunnelOptions, ProtoChoice},
    settings,
    shell,
    vpn_netd::VpnNetdProfile,
};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const HYSTERIA2_BIN: &str = "/data/adb/modules/ZDT-D/bin/hysteria2";
const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/hysteria2";
const PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/hysteria2/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/hysteria2/active.json";
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;
const NETID_BASE: u32 = 26200;
const NETID_MAX: u32 = 26999;
const NET_BASE: u32 = 0xAC1F_E800; // 172.31.232.0/30 pool
const PORT_WAIT: Duration = Duration::from_secs(25);
const TUN_WAIT: Duration = Duration::from_secs(25);

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ProfileState { #[serde(default)] pub enabled: bool }

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ActiveProfiles { #[serde(default)] pub profiles: BTreeMap<String, ProfileState> }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Hysteria2Mode { T2s, Vpn }

impl Default for Hysteria2Mode { fn default() -> Self { Self::T2s } }
impl Hysteria2Mode {
    pub fn is_t2s(&self) -> bool { matches!(self, Self::T2s) }
    pub fn is_vpn(&self) -> bool { matches!(self, Self::Vpn) }
    pub fn as_str(&self) -> &'static str { if self.is_vpn() { "vpn" } else { "t2s" } }
}
impl Serialize for Hysteria2Mode {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error> where S: Serializer {
        serializer.serialize_str(self.as_str())
    }
}
impl<'de> Deserialize<'de> for Hysteria2Mode {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error> where D: Deserializer<'de> {
        let s = Option::<String>::deserialize(deserializer)?.unwrap_or_else(|| "t2s".to_string());
        match s.trim().to_ascii_lowercase().as_str() {
            "" | "t2s" | "proxy" => Ok(Self::T2s),
            "vpn" | "tun2proxy" | "tun2socks" => Ok(Self::Vpn),
            other => Err(de::Error::custom(format!("unknown hysteria2 mode: {other}"))),
        }
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    #[serde(default)] pub mode: Hysteria2Mode,
    #[serde(default)] pub t2s_port: u16,
    #[serde(default = "default_t2s_web_port")] pub t2s_web_port: u16,
    #[serde(default = "default_tun_name")] pub tun: String,
    #[serde(default = "default_dns", deserialize_with = "deserialize_dns")] pub dns: Vec<String>,
    #[serde(default = "default_tun2socks_loglevel")] pub tun2socks_loglevel: String,
    #[serde(default)] pub proto_mode: String,
}
impl Default for ProfileSetting {
    fn default() -> Self { Self { mode: Hysteria2Mode::T2s, t2s_port: 12590, t2s_web_port: 8059, tun: default_tun_name(), dns: default_dns(), tun2socks_loglevel: default_tun2socks_loglevel(), proto_mode: "tcp_udp".to_string() } }
}
impl ProfileSetting { pub fn proto_choice(&self) -> ProtoChoice { if self.proto_mode == "tcp" { ProtoChoice::Tcp } else { ProtoChoice::TcpUdp } } }

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ServerSetting {
    #[serde(default)] pub enabled: bool,
    #[serde(default)] pub socks5_port: u16,
    #[serde(default = "default_log_level")] pub log_level: String,
}
impl Default for ServerSetting { fn default() -> Self { Self { enabled: false, socks5_port: 11590, log_level: default_log_level() } } }

#[derive(Debug, Clone)]
struct ServerPlan { name: String, setting: ServerSetting, config_path: PathBuf, log_path: PathBuf }
#[derive(Debug, Clone)]
struct T2sPlan { name: String, setting: ProfileSetting, uid_out: PathBuf, uid_count: usize, needs_t2s: bool, t2s_log: PathBuf, servers: Vec<ServerPlan> }
#[derive(Debug, Clone)]
struct VpnPlan { name: String, setting: ProfileSetting, server: ServerPlan, app_in: PathBuf, app_out: PathBuf, netid: u32, tun_address: String, cidr: String, tun2socks_log: PathBuf }

fn default_t2s_web_port() -> u16 { 8059 }
fn default_tun_name() -> String { "hytun0".to_string() }
fn default_dns() -> Vec<String> { vec!["8.8.8.8".to_string()] }
fn default_tun2socks_loglevel() -> String { "info".to_string() }
fn default_log_level() -> String { "info".to_string() }

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error> where D: Deserializer<'de> {
    let v = Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        Value::Array(items) => for item in items { if let Some(s) = item.as_str() { out.extend(split_dns(s)); } },
        Value::String(s) => out.extend(split_dns(&s)),
        Value::Null => {}
        _ => return Err(de::Error::custom("dns must be array or string")),
    }
    out.sort(); out.dedup(); Ok(out)
}
fn split_dns(s: &str) -> Vec<String> { s.split(|c: char| c == ',' || c == ';' || c.is_ascii_whitespace()).map(str::trim).filter(|x| !x.is_empty()).map(ToOwned::to_owned).collect() }

pub fn normalize_setting_value(value: Value) -> Result<ProfileSetting> {
    let mut setting: ProfileSetting = serde_json::from_value(value).context("bad hysteria2 setting.json")?;
    normalize_setting_defaults(&mut setting)?;
    validate_setting(&setting)?;
    Ok(setting)
}
fn normalize_setting_defaults(setting: &mut ProfileSetting) -> Result<()> {
    if setting.t2s_port == 0 { setting.t2s_port = 12590; }
    if setting.t2s_web_port == 0 { setting.t2s_web_port = default_t2s_web_port(); }
    setting.tun = setting.tun.trim().to_string();
    if setting.tun.is_empty() { setting.tun = default_tun_name(); }
    if setting.dns.is_empty() { setting.dns = default_dns(); }
    setting.dns.sort(); setting.dns.dedup();
    setting.tun2socks_loglevel = normalize_log_level(&setting.tun2socks_loglevel, "info");
    setting.proto_mode = if setting.proto_mode.trim().eq_ignore_ascii_case("tcp") { "tcp".to_string() } else { "tcp_udp".to_string() };
    Ok(())
}
fn normalize_log_level(v: &str, default: &str) -> String { match v.trim().to_ascii_lowercase().as_str() { "trace"|"debug"|"info"|"warn"|"error"|"silent" => v.trim().to_ascii_lowercase(), _ => default.to_string() } }
fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if setting.t2s_port == 0 || setting.t2s_web_port == 0 { bail!("t2s ports must be 1..65535"); }
    if setting.t2s_port == setting.t2s_web_port { bail!("t2s_port and t2s_web_port must differ"); }
    if !is_valid_ifname(&setting.tun) { bail!("bad tun name"); }
    Ok(())
}

pub fn active_path() -> PathBuf { PathBuf::from(ACTIVE_JSON) }
pub fn profiles_root() -> PathBuf { PathBuf::from(PROFILE_ROOT) }
pub fn profile_root(profile: &str) -> PathBuf { profiles_root().join(profile) }
pub fn server_root(profile: &str, server: &str) -> PathBuf { profile_root(profile).join("server").join(server) }
pub fn ensure_valid_profile_name(name: &str) -> Result<()> { if !is_valid_profile_name(name) { bail!("profile/server name must contain only English letters/digits/_/-"); } Ok(()) }
pub fn is_valid_profile_name(name: &str) -> bool { !name.is_empty() && name.len() <= 64 && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') }

pub fn ensure_root_layout() -> Result<()> { fs::create_dir_all(ROOT)?; fs::create_dir_all(PROFILE_ROOT)?; if !active_path().exists() { write_json_atomic(&active_path(), &ActiveProfiles::default())?; } Ok(()) }
pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?; ensure_root_layout()?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?; fs::create_dir_all(root.join("app/out"))?; fs::create_dir_all(root.join("server"))?; fs::create_dir_all(root.join("log"))?;
    ensure_text_file(&root.join("app/uid/user_program"), "")?;
    ensure_text_file(&root.join("app/out/user_program"), "")?;
    if !root.join("setting.json").exists() { write_json_atomic(&root.join("setting.json"), &ProfileSetting::default())?; }
    Ok(())
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> { ensure_valid_profile_name(profile)?; let v: Value = read_json(&profile_root(profile).join("setting.json"))?; normalize_setting_value(v) }
pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> { ensure_valid_profile_name(profile)?; let mut s = setting.clone(); normalize_setting_defaults(&mut s)?; validate_setting(&s)?; write_json_atomic(&profile_root(profile).join("setting.json"), &s) }

pub fn start_if_enabled() -> Result<()> { start_t2s_if_enabled() }

pub fn start_t2s_if_enabled() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let names = enabled_names(&active);
    if names.is_empty() { return Ok(()); }
    ensure_file(HYSTERIA2_BIN)?;
    let external = crate::ports::collect_used_ports_for_conflict_check_excluding_hysteria2().unwrap_or_default();
    let mut own = BTreeSet::new();
    let mut plans = Vec::new();
    for name in names {
        match build_t2s_plan(&name, &external, &own) {
            Ok(Some(plan)) => { if plan.needs_t2s { own.insert(plan.setting.t2s_port); own.insert(plan.setting.t2s_web_port); } for s in &plan.servers { own.insert(s.setting.socks5_port); } plans.push(plan); }
            Ok(None) => {}
            Err(e) => warn!("hysteria2: profile {name} skipped: {e:#}"),
        }
    }
    if plans.is_empty() { return Ok(()); }
    let t2s_bin = if plans.iter().any(|p| p.needs_t2s) { Some(find_bin("t2s")?) } else { None };
    crate::logging::user_info("hysteria2: запуск");
    for plan in &plans {
        let ports_csv = plan.servers.iter().map(|s| s.setting.socks5_port.to_string()).collect::<Vec<_>>().join(",");
        for srv in &plan.servers {
            truncate_file(&srv.log_path)?;
            spawn_hysteria2(&srv.config_path, &srv.log_path, &srv.setting.log_level)?;
            wait_tcp_port("127.0.0.1", srv.setting.socks5_port, PORT_WAIT)?;
        }
        if plan.needs_t2s {
            let bin = t2s_bin.as_ref().context("t2s binary missing")?;
            truncate_file(&plan.t2s_log)?;
            spawn_t2s_proxy(T2sSpawnConfig { bin, listen_addr: "127.0.0.1", listen_port: plan.setting.t2s_port, socks_host: "127.0.0.1", socks_ports_csv: &ports_csv, web_port: Some(plan.setting.t2s_web_port), program: "hysteria2", profile: &plan.name, scope: &format!("profile/hysteria2/{}", plan.name), log_path: &plan.t2s_log, ..Default::default() })?;
            apply_t2s_routing_ext(&plan.uid_out, plan.setting.t2s_port, plan.setting.proto_choice(), plan.setting.proto_choice(), None, DpiTunnelOptions { port_preference: 1, ..DpiTunnelOptions::default() })?;
        }
        info!("hysteria2: t2s profile={} apps={} servers={} needs_t2s={}", plan.name, plan.uid_count, plan.servers.len(), plan.needs_t2s);
    }
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let names = enabled_names(&active);
    if names.is_empty() { return Ok(Vec::new()); }
    ensure_file(HYSTERIA2_BIN)?; ensure_file(TUN2SOCKS_BIN)?;
    let external = crate::ports::collect_used_ports_for_conflict_check_excluding_hysteria2().unwrap_or_default();
    let mut used_netids = BTreeSet::new();
    let mut own_ports = collect_enabled_t2s_ports(&names);
    let mut plans = Vec::new();
    for name in names { if let Some(plan) = build_vpn_plan(&name, &used_netids, &external, &own_ports)? { used_netids.insert(plan.netid); own_ports.insert(plan.server.setting.socks5_port); plans.push(plan); } }
    if plans.is_empty() { return Ok(Vec::new()); }
    let tun2socks_bin = find_bin("tun2socks")?;
    let mut out = Vec::new();
    crate::logging::user_info("hysteria2 VPN: запуск");
    for plan in &plans {
        truncate_file(&plan.server.log_path)?;
        spawn_hysteria2(&plan.server.config_path, &plan.server.log_path, &plan.server.setting.log_level)?;
        wait_tcp_port("127.0.0.1", plan.server.setting.socks5_port, PORT_WAIT)?;
        truncate_file(&plan.tun2socks_log)?;
        spawn_tun2socks_for_vpn(&tun2socks_bin, plan)?;
        wait_tun_link(&plan.setting.tun, TUN_WAIT)?;
        configure_tun_addr(&plan.setting.tun, &plan.tun_address)?;
        out.push(VpnNetdProfile { owner_program: "hysteria2".to_string(), profile: plan.name.clone(), netid: plan.netid, tun: plan.setting.tun.clone(), cidr: plan.cidr.clone(), gateway: None, dns: plan.setting.dns.clone(), app_list_path: plan.app_in.clone(), app_out_path: plan.app_out.clone(), endpoint_escape_ips: endpoint_escape_ips_from_config(&plan.server.config_path), });
    }
    Ok(out)
}

fn build_t2s_plan(profile: &str, external: &BTreeSet<u16>, own: &BTreeSet<u16>) -> Result<Option<T2sPlan>> {
    ensure_profile_layout(profile)?; let setting = read_setting(profile)?; if !setting.mode.is_t2s() { return Ok(None); }
    let root = profile_root(profile); let uid_in = root.join("app/uid/user_program"); let uid_out = root.join("app/out/user_program");
    let tracker = Sha256Tracker::new(SHA_FLAG_FILE); let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &uid_out, &uid_in)?;
    let uid_count = count_valid_uid_pairs(&uid_out).unwrap_or(0); let has_marker = pkg_uid::file_has_launch_marker(&uid_in).unwrap_or(false);
    if uid_count == 0 && !has_marker { return Ok(None); }
    let needs_t2s = uid_count > 0;
    if needs_t2s { check_port(setting.t2s_port, external, own)?; check_port(setting.t2s_web_port, external, own)?; }
    let mut reserved = BTreeSet::new(); if needs_t2s { reserved.insert(setting.t2s_port); reserved.insert(setting.t2s_web_port); }
    let servers = collect_servers(profile, &setting, external, own, &reserved)?;
    if servers.is_empty() { return Ok(None); }
    Ok(Some(T2sPlan { name: profile.to_string(), setting, uid_out, uid_count, needs_t2s, t2s_log: root.join("log/t2s.log"), servers }))
}

fn build_vpn_plan(profile: &str, used_netids: &BTreeSet<u32>, external: &BTreeSet<u16>, own_ports: &BTreeSet<u16>) -> Result<Option<VpnPlan>> {
    ensure_profile_layout(profile)?; let setting = read_setting(profile)?; if !setting.mode.is_vpn() { return Ok(None); }
    let root = profile_root(profile); let app_in = root.join("app/uid/user_program"); let app_out = root.join("app/out/user_program");
    if !app_list_has_real_apps(&app_in)? { return Ok(None); }
    let enabled = enabled_servers(profile)?; if enabled.len() != 1 { bail!("vpn mode supports exactly one enabled server, found {}", enabled.len()); }
    let server = enabled.into_iter().next().unwrap(); check_port(server.setting.socks5_port, external, own_ports)?;
    let netid = generate_netid(used_netids, NETID_BASE, NETID_MAX)?; let (tun_address, cidr, _) = generated_tun_address_for_index(netid - NETID_BASE)?;
    Ok(Some(VpnPlan { name: profile.to_string(), setting, server, app_in, app_out, netid, tun_address, cidr, tun2socks_log: root.join("log/tun2socks.log") }))
}

fn collect_servers(profile: &str, setting: &ProfileSetting, external: &BTreeSet<u16>, own: &BTreeSet<u16>, reserved: &BTreeSet<u16>) -> Result<Vec<ServerPlan>> {
    let mut out = Vec::new(); let mut seen = BTreeSet::new();
    for (name, dir) in read_sorted_dirs(&profile_root(profile).join("server"))? {
        let mut s: ServerSetting = read_json(&dir.join("setting.json")).unwrap_or_default();
        s.log_level = normalize_log_level(&s.log_level, "info");
        if !s.enabled { continue; }
        if s.socks5_port == 0 || external.contains(&s.socks5_port) || own.contains(&s.socks5_port) || reserved.contains(&s.socks5_port) || !seen.insert(s.socks5_port) { warn!("hysteria2: skip {profile}/{name}, port conflict"); continue; }
        let cfg = dir.join("config.json"); if !cfg.is_file() || !is_nonempty_file(&cfg).unwrap_or(false) { warn!("hysteria2: skip {profile}/{name}, config missing/empty"); continue; }
        normalize_hysteria2_config_for_socks5(&cfg, s.socks5_port).with_context(|| format!("normalize hysteria2 config {profile}/{name}"))?;
        validate_hysteria2_config_json(&cfg)?;
        out.push(ServerPlan { name, setting: s, config_path: cfg, log_path: dir.join("log/hysteria2.log") });
    }
    if setting.mode.is_vpn() && out.len() > 1 { bail!("vpn mode supports exactly one enabled server"); }
    Ok(out)
}
fn enabled_servers(profile: &str) -> Result<Vec<ServerPlan>> { collect_servers(profile, &read_setting(profile)?, &BTreeSet::new(), &BTreeSet::new(), &BTreeSet::new()) }
fn check_port(port: u16, external: &BTreeSet<u16>, own: &BTreeSet<u16>) -> Result<()> { if port == 0 { bail!("invalid port"); } if external.contains(&port) || own.contains(&port) { bail!("port conflict detected: {port}"); } Ok(()) }

pub fn normalize_config_for_profile_server(profile: &str, server: &str) -> Result<()> { ensure_valid_profile_name(profile)?; ensure_valid_profile_name(server)?; let s: ServerSetting = read_json(&server_root(profile, server).join("setting.json")).unwrap_or_default(); if s.socks5_port != 0 { normalize_hysteria2_config_for_socks5(&server_root(profile, server).join("config.json"), s.socks5_port)?; } Ok(()) }

fn normalize_hysteria2_config_for_socks5(path: &Path, port: u16) -> Result<()> {
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    if raw.trim().is_empty() { bail!("config is empty"); }
    let mut v: Value = serde_json::from_str(&raw).with_context(|| format!("parse json {}", path.display()))?;
    let obj = v.as_object_mut().ok_or_else(|| anyhow::anyhow!("config root must be JSON object"))?;
    if obj.get("server").and_then(|x| x.as_str()).map(str::trim).unwrap_or("").is_empty() { bail!("hysteria2 config requires server"); }
    for key in ["http", "tcpForwarding", "udpForwarding", "tcpTProxy", "udpTProxy", "tcpRedirect", "tun"] { obj.remove(key); }
    let mut socks5 = obj.get("socks5").and_then(|v| v.as_object()).cloned().unwrap_or_default();
    socks5.insert("listen".to_string(), Value::String(format!("127.0.0.1:{port}")));
    socks5.insert("disableUDP".to_string(), Value::Bool(false));
    obj.insert("socks5".to_string(), Value::Object(socks5));
    write_json_value_if_changed(path, &raw, &v)
}
fn validate_hysteria2_config_json(path: &Path) -> Result<()> { let raw = fs::read_to_string(path)?; let v: Value = serde_json::from_str(&raw)?; if v.get("server").and_then(|x| x.as_str()).map(str::trim).unwrap_or("").is_empty() { bail!("hysteria2 config requires server"); } Ok(()) }

fn spawn_hysteria2(config: &Path, log: &Path, log_level: &str) -> Result<()> {
    if process_running_with_config(HYSTERIA2_BIN, config) { return Ok(()); }
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(log)?; let logf_err = logf.try_clone()?;
    let mut cmd = Command::new(HYSTERIA2_BIN);
    cmd.arg("--disable-update-check").arg("-f").arg("console").arg("-l").arg(normalize_log_level(log_level, "info")).arg("-c").arg(config).arg("client").stdin(Stdio::null()).stdout(Stdio::from(logf)).stderr(Stdio::from(logf_err));
    unsafe { cmd.pre_exec(|| { let _ = libc::setsid(); Ok(()) }); }
    let child = cmd.spawn().with_context(|| format!("spawn {HYSTERIA2_BIN}"))?;
    info!("hysteria2: spawned pid={} config={}", child.id(), config.display());
    thread::sleep(Duration::from_millis(150)); Ok(())
}
fn spawn_tun2socks_for_vpn(bin: &Path, plan: &VpnPlan) -> Result<()> {
    let proxy = format!("socks5://127.0.0.1:{}", plan.server.setting.socks5_port);
    let logf = OpenOptions::new().create(true).write(true).truncate(true).open(&plan.tun2socks_log)?; let logf_err = logf.try_clone()?;
    let mut cmd = Command::new(bin);
    cmd.arg("-device").arg(format!("tun://{}", plan.setting.tun)).arg("-proxy").arg(proxy).arg("-loglevel").arg(&plan.setting.tun2socks_loglevel).current_dir(profile_root(&plan.name)).stdin(Stdio::null()).stdout(Stdio::from(logf)).stderr(Stdio::from(logf_err));
    unsafe { cmd.pre_exec(|| { let _ = libc::setsid(); Ok(()) }); }
    cmd.spawn().with_context(|| format!("spawn tun2socks for hysteria2 {}", plan.name))?; Ok(())
}

pub fn validate_start_plan() -> Result<()> { ensure_root_layout()?; Ok(()) }
pub fn has_enabled_profiles() -> bool { read_active().map(|a| a.profiles.values().any(|s| s.enabled)).unwrap_or(false) }
pub fn has_enabled_vpn_profiles() -> bool { enabled_vpn_profile_names().next().is_some() }
fn enabled_vpn_profile_names() -> impl Iterator<Item = String> { read_active().unwrap_or_default().profiles.into_iter().filter_map(|(n, st)| { if st.enabled && read_setting(&n).map(|s| s.mode.is_vpn()).unwrap_or(false) && app_list_has_real_apps(&profile_root(&n).join("app/uid/user_program")).unwrap_or(false) { Some(n) } else { None } }) }
pub fn enabled_tun_claims() -> Vec<(String, String)> { enabled_vpn_profile_names().filter_map(|n| read_setting(&n).ok().map(|s| (format!("hysteria2/{n}"), s.tun))).collect() }
pub fn enabled_cidr_claims() -> Vec<(String, String)> { let mut used = BTreeSet::new(); let mut out = Vec::new(); for n in enabled_vpn_profile_names() { if let Ok(id)=generate_netid(&used, NETID_BASE, NETID_MAX) { used.insert(id); if let Ok((_, cidr, _))=generated_tun_address_for_index(id-NETID_BASE) { out.push((format!("hysteria2/{n}"), cidr)); } } } out }

pub fn is_running() -> bool { !main_pids_exact().is_empty() }
pub fn main_pids_exact() -> Vec<i32> { pids_matching(&format!("{} --disable-update-check", HYSTERIA2_BIN)) }
pub fn tun2socks_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let Ok(out) = shell::capture_quiet("ps -ef 2>/dev/null | grep -F 'tun2socks -device tun://' | grep -v grep || true") else { return pids };
    let mut tun_names = BTreeSet::new();
    if let Ok(active) = read_active() {
        for name in enabled_names(&active) {
            if let Ok(setting) = read_setting(&name) {
                tun_names.insert(setting.tun);
            }
        }
    }
    tun_names.insert(default_tun_name());
    for line in out.lines() {
        if !tun_names.iter().any(|tun| line.contains(&format!("tun://{tun}"))) { continue; }
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() > 1 {
            if let Ok(pid) = cols[1].parse::<i32>() {
                if pid > 1 { pids.push(pid); }
            }
        }
    }
    pids.sort_unstable(); pids.dedup(); pids
}

fn app_list_has_real_apps(path: &Path) -> Result<bool> { let raw = fs::read_to_string(path).unwrap_or_default(); Ok(raw.lines().any(|line| { let s=line.split('#').next().unwrap_or("").trim(); !s.is_empty() && !pkg_uid::is_launch_marker_package(s) })) }
fn collect_enabled_t2s_ports(names: &[String]) -> BTreeSet<u16> { let mut out = BTreeSet::new(); for n in names { if let Ok(s)=read_setting(n) { if s.mode.is_t2s() { out.insert(s.t2s_port); out.insert(s.t2s_web_port); } } if let Ok(servers)=enabled_servers(n) { for srv in servers { out.insert(srv.setting.socks5_port); } } } out }
fn enabled_names(active: &ActiveProfiles) -> Vec<String> { active.profiles.iter().filter(|(_, st)| st.enabled).map(|(n, _)| n.clone()).collect() }

pub fn generated_tun_address_for_index(index: u32) -> Result<(String, String, String)> { let offset = index.checked_mul(4).ok_or_else(|| anyhow::anyhow!("hysteria2 cidr overflow"))?; let net = NET_BASE.checked_add(offset).ok_or_else(|| anyhow::anyhow!("hysteria2 cidr overflow"))?; Ok((format!("{}/30", u32_to_ipv4(net+1)), format!("{}/30", u32_to_ipv4(net)), u32_to_ipv4(net+2))) }
fn endpoint_escape_ips_from_config(path: &Path) -> Vec<String> { let Ok(raw)=fs::read_to_string(path) else { return Vec::new() }; let Ok(v)=serde_json::from_str::<Value>(&raw) else { return Vec::new() }; let Some(server)=v.get("server").and_then(|x| x.as_str()) else { return Vec::new() }; let host=server.rsplit_once(':').map(|(h,_)| h).unwrap_or(server).trim_matches(['[',']']); if host.parse::<std::net::Ipv4Addr>().is_ok() { vec![host.to_string()] } else { Vec::new() } }

fn process_running_with_config(bin: &str, config: &Path) -> bool { let pattern = format!("{} --disable-update-check", bin); let cfg = config.display().to_string(); pids_matching(&pattern).into_iter().any(|pid| read_cmdline_pid(pid).contains(&cfg)) }
fn pids_matching(pattern: &str) -> Vec<i32> { let cmd = format!("ps -ef 2>/dev/null | grep -F {} | grep -v grep || true", shell_quote_for_sh(pattern)); let Ok(out)=shell::capture_quiet(&cmd) else { return Vec::new() }; let mut pids = Vec::new(); for line in out.lines() { let cols: Vec<&str>=line.split_whitespace().collect(); if cols.len()>1 { if let Ok(pid)=cols[1].parse::<i32>() { if pid>1 { pids.push(pid); } } } } pids.sort_unstable(); pids.dedup(); pids }
fn read_cmdline_pid(pid: i32) -> String { fs::read(format!("/proc/{pid}/cmdline")).map(|b| String::from_utf8_lossy(&b).replace('\0', " ")).unwrap_or_default() }
fn find_bin(name: &str) -> Result<PathBuf> { let p=Path::new("/data/adb/modules/ZDT-D/bin").join(name); if p.is_file() { Ok(p) } else { bail!("binary not found: {}", p.display()) } }
fn ensure_file(path: &str) -> Result<()> { if Path::new(path).is_file() { Ok(()) } else { bail!("file missing: {path}") } }
fn ensure_text_file(path: &Path, text: &str) -> Result<()> { if !path.exists() { if let Some(p)=path.parent() { fs::create_dir_all(p)?; } fs::write(path, text)?; } Ok(()) }
fn truncate_file(path: &Path) -> Result<()> { if let Some(p)=path.parent() { fs::create_dir_all(p)?; } OpenOptions::new().create(true).write(true).truncate(true).open(path)?; Ok(()) }
fn unique_tmp_path(target: &Path) -> PathBuf { let ts=SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos(); target.with_file_name(format!(".{}.{}.tmp", target.file_name().and_then(|s|s.to_str()).unwrap_or("tmp"), ts)) }
fn write_json_atomic<T: Serialize>(path: &Path, value: &T) -> Result<()> { if let Some(p)=path.parent() { fs::create_dir_all(p)?; } let tmp=unique_tmp_path(path); fs::write(&tmp, serde_json::to_string_pretty(value)?)?; fs::rename(tmp, path)?; Ok(()) }
fn write_json_value_if_changed(path: &Path, old: &str, value: &Value) -> Result<()> { let new=serde_json::to_string_pretty(value)?; if old.trim()!=new.trim() { fs::write(path, new)?; } Ok(()) }
fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> { Ok(serde_json::from_str(&fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?)?) }
fn read_active() -> Result<ActiveProfiles> { read_json(&active_path()) }
fn read_sorted_dirs(root: &Path) -> Result<Vec<(String, PathBuf)>> { let mut out=Vec::new(); if !root.is_dir() { return Ok(out); } for ent in fs::read_dir(root)? { let ent=ent?; let p=ent.path(); if p.is_dir() { if let Some(n)=p.file_name().and_then(|s|s.to_str()) { if !n.starts_with('.') && is_valid_profile_name(n) { out.push((n.to_string(), p)); } } } } out.sort_by(|a,b| a.0.cmp(&b.0)); Ok(out) }
