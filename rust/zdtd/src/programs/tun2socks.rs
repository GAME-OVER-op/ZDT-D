use anyhow::{bail, Context, Result};
use crate::common::apps::{enabled_app_list_empty};
use crate::common::network::{is_ipv4, IP_TIMEOUT, is_valid_ifname, is_forbidden_tun_name, NETID_TUN2SOCKS, configure_tun_addr, wait_tun_link};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, any_live_ps_ef_where};
use crate::programs::registry::{ProgramId};
use crate::network::vpn::{VpnProfileCore, VpnNetdProfileSpec};
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    os::unix::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

use crate::{
    common::process::shell::{self, Capture},
    network::vpn::netd::VpnNetdProfile,
};

const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const TUN2SOCKS_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/tun2socks";
const VPN_CORE: VpnProfileCore = VpnProfileCore::new(ProgramId::Tun2Socks, NETID_TUN2SOCKS);

fn stable_netid_for(profile: &str) -> Result<u32> {
    VPN_CORE.stable_netid(&read_active().unwrap_or_default(), profile)
}
const TUN_WAIT: Duration = Duration::from_secs(15);
const TUN2SOCKS_NET_BASE: u32 = 0xC612_6400; // 198.18.100.0

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileSetting {
    pub tun: String,
    pub proxy: String,
    #[serde(default = "default_loglevel")]
    pub loglevel: String,
    #[serde(default)]
    pub udp_timeout: Option<String>,
    #[serde(default)]
    pub fwmark: Option<u32>,
    #[serde(default)]
    pub restapi: Option<String>,
}

fn default_loglevel() -> String { "info".to_string() }

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun9".to_string(),
            proxy: "socks5://127.0.0.1:1080".to_string(),
            loglevel: default_loglevel(),
            udp_timeout: None,
            fwmark: None,
            restapi: None,
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    log_path: PathBuf,
    netid: u32,
    tun_addr: String,
    cidr: String,
}

pub fn root_path() -> PathBuf { PathBuf::from(TUN2SOCKS_ROOT) }
pub fn active_path() -> PathBuf {
    crate::common::profile::active_json_path(Path::new(TUN2SOCKS_ROOT))
}
pub fn profiles_root() -> PathBuf {
    crate::common::profile::profiles_root_path(Path::new(TUN2SOCKS_ROOT))
}
pub fn profile_root(profile: &str) -> PathBuf {
    crate::common::profile::profile_dir(&profiles_root(), profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    crate::common::profile::is_valid_ascii_profile_name(name, 10)
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("tun2socks profile name must be 1..10 chars and contain only English letters/digits/_/-");
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

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let setting: ProfileSetting = serde_json::from_value(value).context("bad tun2socks setting.json")?;
    validate_setting(&setting)?;
    Ok(setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) || is_forbidden_tun_name(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    validate_proxy_url(&setting.proxy)?;
    match setting.loglevel.as_str() {
        "debug" | "info" | "warn" | "error" | "silent" => {}
        _ => bail!("loglevel must be debug/info/warn/error/silent"),
    }
    if let Some(v) = &setting.udp_timeout {
        if v.trim().is_empty() || v.chars().any(|c| c.is_ascii_whitespace()) {
            bail!("udp_timeout must be a duration without spaces, for example 30s");
        }
    }
    if let Some(v) = &setting.restapi {
        if !v.trim().is_empty() && (v.contains(' ') || !v.contains(':')) {
            bail!("restapi must be empty or host:port/listen address");
        }
    }
    Ok(())
}

fn validate_proxy_url(proxy: &str) -> Result<()> {
    let p = proxy.trim();
    if p.is_empty() || p.chars().any(|c| c.is_ascii_whitespace()) {
        bail!("proxy must not be empty or contain spaces");
    }
    let lower = p.to_ascii_lowercase();
    if !(lower.starts_with("socks5://") || lower.starts_with("socks4://") || lower.starts_with("http://")) {
        bail!("proxy must start with socks5://, socks4:// or http://");
    }
    let after = p.split_once("://").map(|(_, rest)| rest).unwrap_or("");
    if after.is_empty() || !after.rsplit_once(':').map(|(_, port)| port.parse::<u16>().is_ok()).unwrap_or(false) {
        bail!("proxy must include host:port");
    }
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
        validate_setting(&setting).with_context(|| format!("tun2socks profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!("tun2socks tun conflict: tun {} is used by enabled profiles {} and {}", setting.tun, other, name);
        }
    }
    Ok(())
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

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(active) = read_active() else { return out; };
    for (name, st) in &active.profiles {
        if !st.enabled { continue; }
        let Ok(setting) = read_setting(name) else { continue; };
        if validate_setting(&setting).is_err() || enabled_app_list_empty(&crate::common::profile::app_uid_program_path(&profile_root(name))) { continue; }
        let Ok(netid) = VPN_CORE.stable_netid(&active, name) else { continue; };
        if let Ok((_, cidr)) = VPN_CORE.generated_tun_addr_and_cidr(TUN2SOCKS_NET_BASE, netid) {
            out.push(VPN_CORE.claim(name, cidr));
        }
    }
    out
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    VPN_CORE.validate_unique_tuns(
        plans.iter().map(|plan| (plan.name.as_str(), plan.setting.tun.as_str())),
    )
}

pub fn validate_start_plan() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);

    if enabled_names.is_empty() {
        return Ok(());
    }

    let mut errors = Vec::<String>::new();
    if !Path::new(TUN2SOCKS_BIN).is_file() {
        errors.push(format!("binary missing: {TUN2SOCKS_BIN}"));
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

            let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
            let apps_raw = fs::read_to_string(&app_in)
                .with_context(|| format!("read {}", app_in.display()))?;
            if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
                bail!("app list is empty: {}", app_in.display());
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
        bail!("tun2socks start plan has issue(s): {}", errors.join("; "))
    }
}

pub fn has_enabled_profiles() -> bool {
    read_active().map(|a| a.profiles.values().any(|st| st.enabled)).unwrap_or(false)
}

pub fn is_running() -> bool { !main_pids_exact().is_empty() }

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::network::vpn::netd::start_profiles(profiles)?;
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;

    let active = read_active().unwrap_or_default();
    let enabled_names = VPN_CORE.enabled_profile_names(&active);

    if enabled_names.is_empty() {
        info!("tun2socks: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("tun2socks: запуск");

    if !Path::new(TUN2SOCKS_BIN).is_file() {
        warn!("tun2socks: binary not found: {TUN2SOCKS_BIN} -> skip");
        crate::programs::components::user_warn_missing_component("tun2socks");
        return Ok(Vec::new());
    }

    info!("tun2socks: start requested enabled_profiles={}", enabled_names.join(","));

    let mut plans = Vec::new();
    let mut used_netids = BTreeSet::<u32>::new();
    let mut had_error = false;
    for name in enabled_names {
        match build_profile_plan(&name, &used_netids) {
            Ok(plan) => {
                used_netids.insert(plan.netid);
                plans.push(plan);
            }
            Err(e) => {
                had_error = true;
                warn!("tun2socks: profile '{name}' skip: {e:#}");
            }
        }
    }

    if plans.is_empty() {
        if had_error {
            crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
        }
        return Ok(Vec::new());
    }

    if let Err(e) = validate_plan_tuns_unique(&plans) {
        warn!("tun2socks: enabled profiles have tun conflict: {e:#}");
        crate::logging::user_warn("tun2socks: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    let mut profiles = Vec::new();
    for plan in &plans {
        let res: Result<VpnNetdProfile> = (|| {
            info!(
                "tun2socks: starting profile={} tun={} proxy={} addr={} log={}",
                plan.name,
                plan.setting.tun,
                plan.setting.proxy,
                plan.tun_addr,
                plan.log_path.display()
            );
            spawn_tun2socks(plan)?;
            wait_tun_link(&plan.setting.tun, TUN_WAIT)
                .with_context(|| format!("tun2socks profile={} wait tun={}", plan.name, plan.setting.tun))?;
            configure_tun_addr(&plan.setting.tun, &plan.tun_addr)
                .with_context(|| format!("tun2socks profile={} configure tun={}", plan.name, plan.setting.tun))?;
            wait_tun_ready(&plan.setting.tun)
                .with_context(|| format!("tun2socks profile={} wait IPv4 tun={}", plan.name, plan.setting.tun))?;
            info!(
                "tun2socks: tun ready profile={} tun={} cidr={} gateway=none",
                plan.name,
                plan.setting.tun,
                plan.cidr
            );

            Ok(VPN_CORE.netd_profile(VpnNetdProfileSpec {
                profile: plan.name.clone(),
                netid: plan.netid,
                tun: plan.setting.tun.clone(),
                cidr: plan.cidr.clone(),
                gateway: None,
                dns: vec!["8.8.8.8".to_string()],
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
                endpoint_escape_ips: collect_proxy_escape_ips(&plan.setting.proxy),
            }))
        })();

        match res {
            Ok(profile) => profiles.push(profile),
            Err(e) => {
                had_error = true;
                warn!("tun2socks: profile '{}' failed, startup continues: {e:#}", plan.name);
            }
        }
    }

    if had_error {
        crate::logging::user_warn("tun2socks: часть профилей не запущена, запуск продолжен");
    }

    info!("tun2socks: prepared vpn_netd profiles count={}", profiles.len());
    Ok(profiles)
}

fn build_profile_plan(profile: &str, used_netids: &BTreeSet<u32>) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let app_in = crate::common::profile::app_uid_program_path(&profile_dir);
    let app_out = crate::common::profile::app_out_program_path(&profile_dir);
    crate::common::fs::ensure_empty_file(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }

    let netid = stable_netid_for(profile)?;
    if used_netids.contains(&netid) {
        bail!("netid {netid} is already used by another tun2socks profile");
    }
    let (tun_addr, cidr) = VPN_CORE.generated_tun_addr_and_cidr(TUN2SOCKS_NET_BASE, netid)?;

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        app_in,
        app_out,
        log_path: profile_dir.join("log/tun2socks.log"),
        netid,
        tun_addr,
        cidr,
    })
}

fn spawn_tun2socks(plan: &ProfilePlan) -> Result<()> {
    if tun2socks_profile_process_running(&plan.setting.tun, &plan.setting.proxy) {
        info!(
            "tun2socks: profile={} already running for tun={} proxy={}, skip spawn",
            plan.name,
            plan.setting.tun,
            plan.setting.proxy
        );
        return Ok(());
    }

    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.log_path)
        .with_context(|| format!("open log {}", plan.log_path.display()))?;
    let logf_err = logf.try_clone().context("clone tun2socks log")?;

    let mut cmd = Command::new(TUN2SOCKS_BIN);
    cmd.arg("--device")
        .arg(format!("tun://{}", plan.setting.tun))
        .arg("--proxy")
        .arg(&plan.setting.proxy)
        .arg("--loglevel")
        .arg(&plan.setting.loglevel)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    if let Some(v) = &plan.setting.udp_timeout {
        if !v.trim().is_empty() {
            cmd.arg("--udp-timeout").arg(v);
        }
    }
    if let Some(v) = plan.setting.fwmark {
        cmd.arg("--fwmark").arg(v.to_string());
    }
    if let Some(v) = &plan.setting.restapi {
        if !v.trim().is_empty() {
            cmd.arg("--restapi").arg(v);
        }
    }

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {TUN2SOCKS_BIN}"))?;
    info!(
        "tun2socks: spawned profile={} pid={} tun={} proxy={} log={}",
        plan.name,
        child.id(),
        plan.setting.tun,
        plan.setting.proxy,
        plan.log_path.display()
    );

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("tun2socks: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.log_path.display());
    }
    Ok(())
}

fn tun2socks_profile_process_running(tun: &str, proxy: &str) -> bool {
    let pattern = format!(
        "{} --device tun://{} --proxy {}",
        TUN2SOCKS_BIN,
        tun,
        proxy
    );
    any_live_ps_ef_where(|line| line.contains(&pattern))
}

fn wait_tun_ready(tun: &str) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        let (code, out) = shell::run_timeout("ip", &["-o", "-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 && out.lines().any(|l| l.contains(" inet ") || l.split_whitespace().any(|t| t == "inet")) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

fn collect_proxy_escape_ips(proxy: &str) -> Vec<String> {
    let mut value = proxy.trim();
    if let Some((_, rest)) = value.split_once("://") {
        value = rest;
    }
    if let Some((_, rest)) = value.rsplit_once('@') {
        value = rest;
    }
    value = value
        .split(|c| c == '/' || c == '?' || c == '#')
        .next()
        .unwrap_or(value)
        .trim();
    if value.starts_with('[') {
        return Vec::new();
    }
    let host = value
        .rsplit_once(':')
        .map(|(host, _)| host)
        .unwrap_or(value)
        .trim()
        .trim_end_matches('.');
    if is_ipv4(host) && !host.starts_with("127.") && host != "0.0.0.0" {
        vec![host.to_string()]
    } else {
        Vec::new()
    }
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(
        r#"^/data/adb/modules/ZDT-D/bin/tun2socks --device tun://.* --proxy .*$"#,
    );
    if pids.is_empty() {
        pids = ps_ef_pids_where(|line| {
            line.contains("/data/adb/modules/ZDT-D/bin/tun2socks")
                && line.contains("--device tun://")
                && line.contains(" --proxy ")
        });
    }
    filter_live_pids(&mut pids);
    pids
}
