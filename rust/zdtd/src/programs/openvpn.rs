use anyhow::{bail, Context, Result};
use log::{info, warn};
use serde::{Deserialize, Deserializer, Serialize};
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
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
};

const OPENVPN_BIN: &str = "/data/adb/modules/ZDT-D/bin/openvpn";
const OPENVPN_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/openvpn";
const OPENVPN_PROFILE_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/openvpn/profile";
const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/openvpn/active.json";
const NETID_BASE: u32 = 20200;
const NETID_MAX: u32 = 29999;
const TUN_WAIT: Duration = Duration::from_secs(25);
const IP_TIMEOUT: Duration = Duration::from_secs(3);

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
    pub tun: String,
    #[serde(default, deserialize_with = "deserialize_dns")]
    pub dns: Vec<String>,
}

impl Default for ProfileSetting {
    fn default() -> Self {
        Self {
            tun: "tun1".to_string(),
            dns: vec!["94.140.14.14".to_string(), "94.140.15.15".to_string()],
        }
    }
}

#[derive(Debug, Clone)]
struct ProfilePlan {
    name: String,
    setting: ProfileSetting,
    profile_dir: PathBuf,
    config_path: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
    log_path: PathBuf,
}

#[derive(Debug, Clone)]
struct TunInfo {
    cidr: String,
    gateway: Option<String>,
}

fn deserialize_dns<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let v = serde_json::Value::deserialize(deserializer)?;
    let mut out = Vec::new();
    match v {
        serde_json::Value::Array(items) => {
            for item in items {
                if let Some(s) = item.as_str() {
                    for part in split_dns_text(s) {
                        out.push(part);
                    }
                }
            }
        }
        serde_json::Value::String(s) => {
            for part in split_dns_text(&s) {
                out.push(part);
            }
        }
        serde_json::Value::Null => {}
        _ => return Err(serde::de::Error::custom("dns must be array or string")),
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

pub fn root_path() -> PathBuf {
    PathBuf::from(OPENVPN_ROOT)
}

pub fn active_path() -> PathBuf {
    PathBuf::from(ACTIVE_JSON)
}

pub fn profiles_root() -> PathBuf {
    PathBuf::from(OPENVPN_PROFILE_ROOT)
}

pub fn profile_root(profile: &str) -> PathBuf {
    profiles_root().join(profile)
}

pub fn is_valid_profile_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 10
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("openvpn profile name must be 1..10 chars and contain only English letters/digits/_/-");
    }
    Ok(())
}

pub fn ensure_profile_layout(profile: &str) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let root = profile_root(profile);
    fs::create_dir_all(root.join("app/uid"))?;
    fs::create_dir_all(root.join("app/out"))?;
    fs::create_dir_all(root.join("log"))?;
    ensure_file_empty(&root.join("app/uid/user_program"))?;
    let setting_path = root.join("setting.json");
    if !setting_path.exists() {
        write_json_pretty(&setting_path, &ProfileSetting::default())?;
    }
    let config_path = root.join("client.ovpn");
    if !config_path.exists() {
        fs::write(&config_path, "")?;
    }
    Ok(())
}

pub fn ensure_root_layout() -> Result<()> {
    fs::create_dir_all(OPENVPN_PROFILE_ROOT)?;
    let active_path = active_path();
    if !active_path.exists() {
        write_json_pretty(&active_path, &ActiveProfiles::default())?;
    }
    Ok(())
}

pub fn read_active() -> Result<ActiveProfiles> {
    ensure_root_layout()?;
    read_json(&active_path())
}

pub fn write_active(active: &ActiveProfiles) -> Result<()> {
    ensure_root_layout()?;
    write_json_pretty(&active_path(), active)
}

pub fn read_setting(profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    read_json(&profile_root(profile).join("setting.json"))
}

pub fn write_setting(profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    validate_setting(setting)?;
    ensure_profile_layout(profile)?;
    write_json_pretty(&profile_root(profile).join("setting.json"), setting)
}

pub fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    if !is_valid_ifname(&setting.tun) {
        bail!("tun must be 1..15 chars and contain only letters/digits/_/./-; physical interfaces are not allowed");
    }
    if is_forbidden_tun_name(&setting.tun) {
        bail!("tun must not be a physical/system interface: {}", setting.tun);
    }
    if setting.dns.is_empty() || setting.dns.len() > 8 {
        bail!("dns must contain 1..8 IPv4 addresses");
    }
    for dns in &setting.dns {
        if !is_ipv4(dns) {
            bail!("invalid IPv4 DNS: {dns}");
        }
    }
    Ok(())
}

pub fn normalize_setting_value(value: serde_json::Value) -> Result<ProfileSetting> {
    let setting: ProfileSetting = serde_json::from_value(value).context("bad openvpn setting.json")?;
    validate_setting(&setting)?;
    Ok(setting)
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
        if !enabled {
            continue;
        }

        let setting = if override_profile == Some(name.as_str()) {
            match override_setting {
                Some(s) => s.clone(),
                None => read_setting(&name).unwrap_or_default(),
            }
        } else {
            read_setting(&name).unwrap_or_default()
        };
        validate_setting(&setting)
            .with_context(|| format!("openvpn profile={name} setting validation"))?;

        if let Some(other) = seen.insert(setting.tun.clone(), name.clone()) {
            bail!(
                "openvpn tun conflict: tun {} is used by enabled profiles {} and {}",
                setting.tun,
                other,
                name
            );
        }
    }
    Ok(())
}

fn validate_plan_tuns_unique(plans: &[ProfilePlan]) -> Result<()> {
    let mut seen: BTreeMap<String, String> = BTreeMap::new();
    for plan in plans {
        if let Some(other) = seen.insert(plan.setting.tun.clone(), plan.name.clone()) {
            bail!(
                "openvpn tun conflict: tun {} is used by enabled profiles {} and {}",
                plan.setting.tun,
                other,
                plan.name
            );
        }
    }
    Ok(())
}

pub fn validate_start_plan() -> Result<()> {
    ensure_root_layout()?;
    let active = read_active().unwrap_or_default();
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
            let cfg = fs::read_to_string(&config_path)
                .with_context(|| format!("read {}", config_path.display()))?;
            if cfg.trim().is_empty() {
                bail!("client.ovpn is empty: {}", config_path.display());
            }

            let app_in = profile_dir.join("app/uid/user_program");
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
        bail!("openvpn start plan has issue(s): {}", errors.join("; "))
    }
}

pub fn has_enabled_profiles() -> bool {
    read_active()
        .map(|a| a.profiles.values().any(|st| st.enabled))
        .unwrap_or(false)
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
            out.push((format!("openvpn/{name}"), setting.tun));
        }
    }
    out
}

pub fn start_if_enabled() -> Result<()> {
    let profiles = start_profiles_for_netd()?;
    crate::vpn_netd::start_profiles(profiles)?;
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    ensure_root_layout()?;

    let active = read_active().unwrap_or_default();
    let enabled_names: Vec<String> = active
        .profiles
        .iter()
        .filter(|(_, st)| st.enabled)
        .map(|(name, _)| name.clone())
        .collect();

    if enabled_names.is_empty() {
        info!("openvpn: no enabled profiles");
        return Ok(Vec::new());
    }

    crate::logging::user_info("OpenVPN: запуск");

    if !Path::new(OPENVPN_BIN).is_file() {
        warn!("openvpn: binary not found: {OPENVPN_BIN} -> skip");
        crate::logging::user_warn("OpenVPN: ошибка запуска, запуск продолжен");
        return Ok(Vec::new());
    }

    info!(
        "openvpn: start requested enabled_profiles={}",
        enabled_names.join(",")
    );

    let mut plans = Vec::new();
    let mut had_error = false;
    for name in enabled_names {
        match build_profile_plan(&name) {
            Ok(plan) => plans.push(plan),
            Err(e) => {
                had_error = true;
                warn!("openvpn: profile '{name}' skip: {e:#}");
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
            let netid = generate_netid(&used_netids)?;
            Ok(VpnNetdProfile {
                owner_program: "openvpn".to_string(),
                profile: plan.name.clone(),
                netid,
                tun: plan.setting.tun.clone(),
                cidr: tun.cidr,
                gateway: tun.gateway,
                dns: plan.setting.dns.clone(),
                app_list_path: plan.app_in.clone(),
                app_out_path: plan.app_out.clone(),
            })
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

fn build_profile_plan(profile: &str) -> Result<ProfilePlan> {
    ensure_valid_profile_name(profile)?;
    ensure_profile_layout(profile)?;
    let profile_dir = profile_root(profile);
    let setting = read_setting(profile)?;
    validate_setting(&setting)?;

    let config_path = profile_dir.join("client.ovpn");
    if !config_path.is_file() {
        bail!("client.ovpn missing: {}", config_path.display());
    }
    let cfg = fs::read_to_string(&config_path).unwrap_or_default();
    if cfg.trim().is_empty() {
        bail!("client.ovpn is empty: {}", config_path.display());
    }
    let tmp_dir = profile_dir.join("tmp");
    fs::create_dir_all(&tmp_dir)
        .with_context(|| format!("create openvpn tmp dir {}", tmp_dir.display()))?;
    normalize_client_config_in_place(&config_path, &setting.tun, &tmp_dir)
        .with_context(|| format!("normalize {}", config_path.display()))?;

    let app_in = profile_dir.join("app/uid/user_program");
    let app_out = profile_dir.join("app/out/user_program");
    ensure_file_empty(&app_in)?;
    let apps_raw = fs::read_to_string(&app_in).unwrap_or_default();
    if apps_raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')) {
        bail!("app list is empty: {}", app_in.display());
    }

    Ok(ProfilePlan {
        name: profile.to_string(),
        setting,
        profile_dir: profile_dir.clone(),
        config_path,
        app_in,
        app_out,
        log_path: profile_dir.join("log/openvpn.log"),
    })
}


const ZDTD_OPENVPN_BLOCK_BEGIN: &str = "# ZDT-D Android CLI UID-only mode BEGIN";
const ZDTD_OPENVPN_BLOCK_END: &str = "# ZDT-D Android CLI UID-only mode END";

fn normalize_client_config_in_place(config_path: &Path, tun: &str, tmp_dir: &Path) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let original_lines: Vec<&str> = original.lines().collect();
    let mut kept = Vec::<String>::new();
    let mut in_inline_block = false;
    let mut i = 0usize;

    while i < original_lines.len() {
        let raw = original_lines[i];
        let trimmed = raw.trim();

        if trimmed == ZDTD_OPENVPN_BLOCK_BEGIN {
            // Remove only a complete old managed block. If END is missing because a previous
            // write was interrupted, do not drop the rest of the user's config/certificates.
            if let Some(rel_end) = original_lines[i + 1..]
                .iter()
                .position(|line| line.trim() == ZDTD_OPENVPN_BLOCK_END)
            {
                i += rel_end + 2;
                continue;
            }
            i += 1;
            continue;
        }

        if starts_openvpn_inline_block(trimmed) {
            in_inline_block = true;
            kept.push(raw.to_string());
            i += 1;
            continue;
        }
        if in_inline_block {
            kept.push(raw.to_string());
            if ends_openvpn_inline_block(trimmed) {
                in_inline_block = false;
            }
            i += 1;
            continue;
        }

        if is_openvpn_line_managed_by_zdtd(trimmed) {
            i += 1;
            continue;
        }
        kept.push(raw.to_string());
        i += 1;
    }

    while kept.last().map(|s| s.trim().is_empty()).unwrap_or(false) {
        kept.pop();
    }

    kept.push(String::new());
    kept.push(ZDTD_OPENVPN_BLOCK_BEGIN.to_string());
    kept.push("# OpenVPN only raises the TUN interface; ZDT-D routes selected UIDs via Android netd.".to_string());
    kept.push("route-noexec".to_string());
    kept.push("pull-filter ignore \"redirect-gateway\"".to_string());
    kept.push("pull-filter ignore \"dhcp-option DNS\"".to_string());
    kept.push("pull-filter ignore \"tun-ipv6\"".to_string());
    kept.push("pull-filter ignore \"ifconfig-ipv6\"".to_string());
    kept.push("pull-filter ignore \"route-ipv6\"".to_string());
    kept.push(format!("tmp-dir {}", tmp_dir.display()));
    kept.push(format!("dev {tun}"));
    kept.push("dev-type tun".to_string());
    kept.push(ZDTD_OPENVPN_BLOCK_END.to_string());

    let normalized = format!("{}\n", kept.join("\n"));
    let effective_dev = effective_openvpn_dev(&normalized).unwrap_or_default();
    if effective_dev != tun {
        bail!(
            "openvpn config normalization failed: expected dev {}, got {}",
            tun,
            if effective_dev.is_empty() { "<none>" } else { effective_dev.as_str() }
        );
    }

    if normalized != original {
        info!(
            "openvpn: normalized config {} for tun={} tmp_dir={}",
            config_path.display(),
            tun,
            tmp_dir.display()
        );
        let tmp = config_path.with_file_name("client.ovpn.zdtd.tmp");
        fs::write(&tmp, normalized)
            .with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, config_path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), config_path.display()))?;
    }
    Ok(())
}


fn starts_openvpn_inline_block(trimmed: &str) -> bool {
    trimmed.starts_with('<') && !trimmed.starts_with("</") && trimmed.ends_with('>')
}

fn ends_openvpn_inline_block(trimmed: &str) -> bool {
    trimmed.starts_with("</") && trimmed.ends_with('>')
}

fn effective_openvpn_dev(config: &str) -> Option<String> {
    let mut in_inline_block = false;
    let mut dev = None;
    for raw in config.lines() {
        let trimmed = raw.trim();
        if starts_openvpn_inline_block(trimmed) {
            in_inline_block = true;
            continue;
        }
        if in_inline_block {
            if ends_openvpn_inline_block(trimmed) {
                in_inline_block = false;
            }
            continue;
        }
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        if matches!(parts.next(), Some(d) if d.eq_ignore_ascii_case("dev")) {
            if let Some(value) = parts.next() {
                dev = Some(value.to_string());
            }
        }
    }
    dev
}

fn is_openvpn_line_managed_by_zdtd(trimmed: &str) -> bool {
    if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
        return false;
    }

    let lower = trimmed.to_ascii_lowercase();
    let mut parts = lower.split_whitespace();
    let directive = parts.next().unwrap_or("");
    match directive {
        "block-outside-dns" | "route-noexec" | "redirect-gateway" | "tmp-dir" | "dev" | "dev-type" | "tun-ipv6" => true,
        "ifconfig-ipv6" | "route-ipv6" => true,
        "dhcp-option" => parts.next() == Some("dns"),
        "pull-filter" => {
            let rest = parts.collect::<Vec<_>>().join(" ");
            rest.contains("ignore")
                && (rest.contains("redirect-gateway")
                    || rest.contains("dhcp-option dns")
                    || rest.contains("tun-ipv6")
                    || rest.contains("ifconfig-ipv6")
                    || rest.contains("route-ipv6"))
        }
        _ => false,
    }
}

fn spawn_openvpn(plan: &ProfilePlan) -> Result<()> {
    if openvpn_profile_process_running(&plan.config_path) {
        info!(
            "openvpn: profile={} already running for config={}, skip spawn",
            plan.name,
            plan.config_path.display()
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
    let logf_err = logf.try_clone().context("clone openvpn log")?;

    let mut cmd = Command::new(OPENVPN_BIN);
    cmd.arg("--config")
        .arg(&plan.config_path)
        .current_dir(&plan.profile_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {OPENVPN_BIN}"))?;
    info!(
        "openvpn: spawned profile={} pid={} config={} log={}",
        plan.name,
        child.id(),
        plan.config_path.display(),
        plan.log_path.display()
    );

    thread::sleep(Duration::from_millis(250));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("openvpn: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.log_path.display());
    }
    Ok(())
}

fn openvpn_profile_process_running(config_path: &Path) -> bool {
    let pattern = format!("{} --config {}", OPENVPN_BIN, config_path.display());
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
                    cidr = derive_cidr(ip_cidr);
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
    if gateway.is_none() {
        gateway = first_host_for_cidr(&cidr);
    }

    Ok(TunInfo { cidr, gateway })
}

fn derive_cidr(ip_cidr: &str) -> Option<String> {
    let (ip, prefix_s) = ip_cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if !is_ipv4(ip) || prefix > 32 {
        return None;
    }
    let addr = ipv4_to_u32(ip)?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    let net = addr & mask;
    Some(format!("{}/{}", u32_to_ipv4(net), prefix))
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
                if let Some(via) = tokens.get(i + 1) {
                    if is_ipv4(via) {
                        return Ok((*via).to_string());
                    }
                }
            }
        }
    }
    bail!("no gateway via route for {tun}")
}

fn first_host_for_cidr(cidr: &str) -> Option<String> {
    let (ip, prefix_s) = cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if prefix > 30 {
        return None;
    }
    let net = ipv4_to_u32(ip)?;
    Some(u32_to_ipv4(net.saturating_add(1)))
}

fn generate_netid(used: &BTreeSet<u32>) -> Result<u32> {
    for id in NETID_BASE..=NETID_MAX {
        if !used.contains(&id) {
            return Ok(id);
        }
    }
    bail!("no free netid in range {NETID_BASE}..={NETID_MAX}")
}

fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, am) = cidr_network_mask(a)?;
    let (bn, bm) = cidr_network_mask(b)?;
    let a_start = an;
    let a_end = an | !am;
    let b_start = bn;
    let b_end = bn | !bm;
    Ok(a_start <= b_end && b_start <= a_end)
}

fn cidr_network_mask(cidr: &str) -> Result<(u32, u32)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 {
        bail!("bad cidr prefix {cidr}");
    }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
}

fn is_valid_ifname(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 15
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

fn is_forbidden_tun_name(s: &str) -> bool {
    s == "lo"
        || s == "dummy0"
        || s.starts_with("wlan")
        || s.starts_with("rmnet")
        || s.starts_with("ccmni")
        || s.starts_with("eth")
        || s.starts_with("ap")
        || s.starts_with("rndis")
}

fn is_ipv4(s: &str) -> bool {
    let parts = s.split('.').collect::<Vec<_>>();
    if parts.len() != 4 {
        return false;
    }
    parts.iter().all(|p| !p.is_empty() && p.parse::<u8>().is_ok())
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
    format!(
        "{}.{}.{}.{}",
        (v >> 24) & 0xff,
        (v >> 16) & 0xff,
        (v >> 8) & 0xff,
        v & 0xff
    )
}

fn ensure_file_empty(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if !path.exists() {
        fs::write(path, "")?;
    }
    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let txt = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_str(&txt).with_context(|| format!("parse {}", path.display()))
}

fn write_json_pretty<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let txt = serde_json::to_string_pretty(v)?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, txt)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = r#"sh -c "pgrep -f '^/data/adb/modules/ZDT-D/bin/openvpn --config /data/adb/modules/ZDT-D/working_folder/openvpn/profile/.*/client\.ovpn$' 2>/dev/null || true""#;
    if let Ok(out) = shell::capture_quiet(cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = r#"sh -c "ps -ef 2>/dev/null | grep -F '/data/adb/modules/ZDT-D/bin/openvpn --config /data/adb/modules/ZDT-D/working_folder/openvpn/profile/' | grep -F '/client.ovpn' | grep -v grep || true""#;
        if let Ok(out) = shell::capture_quiet(ps_cmd) {
            for line in out.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 1 {
                    if let Ok(pid) = cols[1].parse::<i32>() {
                        if pid > 1 {
                            pids.push(pid);
                        }
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}
