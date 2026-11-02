#![allow(dead_code)]
use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::{fs, path::{Path, PathBuf}};

use crate::common::network::{
    cidrs_overlap, generated_ipv4_30, ipv4_to_u32, is_valid_ifname,
    normalize_cidr_network, stable_netid,
};
use crate::common::profile::{
    active_json_path, is_valid_ascii_profile_name, profiles_root_path, ActiveProfiles,
};
use crate::common::config::validate_loglevel;

use super::{ProfileSetting, VariantSpec};

pub(crate) fn normalize_setting_value(spec: VariantSpec, value: Value) -> Result<ProfileSetting> {
    if let Some(mode) = value.get("mode").and_then(Value::as_str) {
        let mode = mode.trim().to_ascii_lowercase();
        if !matches!(mode.as_str(), "" | "t2s" | "proxy" | "vpn" | "tun2socks" | "t2s-vpn" | "t2s_vpn") {
            bail!("unknown {} mode: {}", spec.label, mode);
        }
    }
    let mut setting: ProfileSetting = serde_json::from_value(value)
        .with_context(|| format!("bad {} profile setting.json", spec.label))?;
    normalize_setting_defaults(spec, &mut setting)?;
    validate_setting(&setting)?;
    Ok(setting)
}

pub(crate) fn normalize_setting_defaults(spec: VariantSpec, setting: &mut ProfileSetting) -> Result<()> {
    if setting.t2s_port == 0 {
        setting.t2s_port = spec.default_t2s_port;
    }
    if setting.t2s_web_port == 0 {
        setting.t2s_web_port = spec.default_t2s_web_port;
    }
    setting.tun = setting.tun.trim().to_string();
    if setting.tun.is_empty() {
        setting.tun = spec.default_tun.to_string();
    }
    if setting.dns.is_empty() {
        setting.dns = vec!["8.8.8.8".to_string()];
    }
    setting.dns.sort();
    setting.dns.dedup();
    setting.tun2socks_loglevel = setting.tun2socks_loglevel.trim().to_ascii_lowercase();
    if setting.tun2socks_loglevel.is_empty() {
        setting.tun2socks_loglevel = "info".to_string();
    }
    setting.proto_mode = match setting.proto_mode.trim().to_ascii_lowercase().as_str() {
        "" | "tcp_udp" => "tcp_udp".to_string(),
        "tcp" => "tcp".to_string(),
        other => bail!("invalid proto_mode: {other}; expected tcp or tcp_udp"),
    };
    Ok(())
}

pub(crate) fn validate_setting(setting: &ProfileSetting) -> Result<()> {
    for (label, port) in [("t2s_port", setting.t2s_port), ("t2s_web_port", setting.t2s_web_port)] {
        if port == 0 {
            bail!("{} is required", label);
        }
    }
    if setting.t2s_port == setting.t2s_web_port {
        bail!("duplicate t2s ports");
    }
    if !is_valid_ifname(&setting.tun) || forbidden_tun(&setting.tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    validate_loglevel(&setting.tun2socks_loglevel, "tun2socks_loglevel")?;
    for dns in &setting.dns {
        if !ipv4(dns) {
            bail!("dns must contain IPv4 addresses only");
        }
    }
    Ok(())
}

pub(crate) fn profile_root(spec: VariantSpec, profile: &str) -> PathBuf {
    Path::new(spec.profiles_root).join(profile)
}

pub(crate) fn server_root(spec: VariantSpec, profile: &str, server: &str) -> PathBuf {
    profile_root(spec, profile).join("server").join(server)
}

pub(crate) fn profile_t2s_log_path(spec: VariantSpec, profile: &str) -> PathBuf {
    profile_root(spec, profile).join("log/t2s.log")
}

pub(crate) fn profile_tun2socks_log_path(spec: VariantSpec, profile: &str) -> PathBuf {
    profile_root(spec, profile).join("log/tun2socks.log")
}

pub(crate) fn read_active_profiles(spec: VariantSpec) -> Result<ActiveProfiles> {
    read_json(Path::new(spec.active_json))
}

pub(crate) fn all_profile_names(spec: VariantSpec) -> Vec<String> {
    read_active_profiles(spec)
        .map(|a| a.profiles.keys().cloned().collect())
        .unwrap_or_default()
}

pub(crate) fn stable_netid_for(spec: VariantSpec, profile: &str) -> Result<u32> {
    stable_netid(spec.netid_base, spec.netid_max, &all_profile_names(spec), profile)
}

pub(crate) fn generated_tun_address_for_index(spec: VariantSpec, index: u32) -> Result<(String, String, String)> {
    generated_ipv4_30(spec.network_base, index, spec.label)
}

pub(crate) fn generated_tun_address_for_profile(spec: VariantSpec, profile: &str) -> Result<(String, String, String)> {
    let netid = stable_netid_for(spec, profile)?;
    generated_tun_address_for_index(spec, netid - spec.netid_base)
}

pub(crate) fn ensure_base_layout(spec: VariantSpec) -> Result<()> {
    ensure_dir("/data/adb/modules/ZDT-D")?;
    ensure_dir("/data/adb/modules/ZDT-D/working_folder")?;
    fs::create_dir_all(spec.root).ok();
    fs::create_dir_all(spec.profiles_root).ok();
    Ok(())
}

pub(crate) fn is_valid_profile_name(name: &str) -> bool {
    is_valid_ascii_profile_name(name, 64)
}

pub(crate) fn ensure_valid_profile_name(name: &str) -> Result<()> {
    if !is_valid_profile_name(name) {
        bail!("profile/server name must contain only English letters/digits/_/-");
    }
    Ok(())
}

pub(crate) fn read_setting(spec: VariantSpec, profile: &str) -> Result<ProfileSetting> {
    ensure_valid_profile_name(profile)?;
    let path = profile_root(spec, profile).join("setting.json");
    let value: Value = read_json(&path).with_context(|| format!("read {}", path.display()))?;
    normalize_setting_value(spec, value)
        .with_context(|| format!("validate {} profile={} setting", spec.label, profile))
}

pub(crate) fn write_setting(spec: VariantSpec, profile: &str, setting: &ProfileSetting) -> Result<()> {
    ensure_valid_profile_name(profile)?;
    let mut setting = setting.clone();
    normalize_setting_defaults(spec, &mut setting)?;
    validate_setting(&setting)?;
    write_json_atomic(&profile_root(spec, profile).join("setting.json"), &setting)
}

pub(crate) fn normalize_cidr(cidr: &str) -> Result<String> { normalize_cidr_network(cidr) }
pub(crate) fn cidr_overlap(a: &str, b: &str) -> Result<bool> { cidrs_overlap(a, b) }
pub(crate) fn ipv4(s: &str) -> bool { ipv4_to_u32(s).is_some() }
pub(crate) fn forbidden_tun(s: &str) -> bool {
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

pub(crate) fn active_path_from_root(spec: VariantSpec) -> PathBuf {
    active_json_path(Path::new(spec.root))
}

pub(crate) fn profiles_path_from_root(spec: VariantSpec) -> PathBuf {
    profiles_root_path(Path::new(spec.root))
}

pub(crate) fn main_pids_exact(spec: VariantSpec) -> Vec<i32> {
    let needle = format!("{} run -c {}/", spec.label, spec.root);
    let mut pids = crate::common::process::ps_ef_pids_where(|line| line.contains(&needle));
    crate::common::process::filter_live_pids(&mut pids);
    pids
}

#[derive(Debug, Clone)]
pub(crate) struct TunInfo {
    pub cidr: String,
    pub gateway: Option<String>,
}

pub(crate) fn app_list_has_real_apps(path: &Path) -> Result<bool> {
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        if !crate::common::apps::is_launch_marker_package(s) {
            return Ok(true);
        }
    }
    Ok(false)
}

pub(crate) fn read_sorted_dirs(spec: VariantSpec, root: &Path) -> Result<Vec<(String, PathBuf)>> {
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
            log::warn!(
                "{}: skip server-dir='{}' (invalid directory name): {e:#}",
                spec.label,
                name
            );
            continue;
        }
        entries.push((name, dir));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(entries)
}

pub(crate) fn wait_tun_ready(tun: &str) -> Result<()> {
    use std::time::{Duration, Instant};
    const TUN_WAIT: Duration = Duration::from_secs(25);
    let start = Instant::now();
    loop {
        if start.elapsed() >= TUN_WAIT {
            bail!("tun {tun} is not ready after {:?}", TUN_WAIT);
        }
        if inspect_tun(tun).is_ok() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(400));
    }
}

pub(crate) fn inspect_tun(tun: &str) -> Result<TunInfo> {
    use crate::common::process::shell::{self, Capture};
    let (code, out) = shell::run_timeout(
        "ip",
        &["-o", "-4", "addr", "show", "dev", tun],
        Capture::Stdout,
        crate::common::network::IP_TIMEOUT,
    )
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
                    if ipv4(&peer_ip) {
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

pub(crate) fn route_gateway_for_tun(tun: &str) -> Result<String> {
    use crate::common::process::shell::{self, Capture};
    let (code, out) = shell::run_timeout(
        "ip",
        &["-4", "route", "show", "dev", tun],
        Capture::Stdout,
        crate::common::network::IP_TIMEOUT,
    )?;
    if code != 0 {
        bail!("ip route show dev {tun} failed");
    }
    for line in out.lines() {
        let tokens = line.split_whitespace().collect::<Vec<_>>();
        for i in 0..tokens.len() {
            if tokens[i] == "via" {
                if let Some(ip) = tokens.get(i + 1) {
                    if ipv4(ip) {
                        return Ok((*ip).to_string());
                    }
                }
            }
        }
    }
    bail!("gateway not found for {tun}")
}

pub(crate) fn singbox_profile_process_running(spec: VariantSpec, config_path: &Path) -> bool {
    let pattern = format!("{} run -c {}", spec.binary, config_path.display());
    crate::common::process::any_live_ps_ef_where(|line| line.contains(&pattern))
}

pub(crate) fn ensure_file_empty(path: &Path) -> Result<()> {
    if !path.exists() {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).ok();
        }
        fs::write(path, b"").with_context(|| format!("create {}", path.display()))?;
    }
    Ok(())
}

pub(crate) fn unique_tmp_path(target: &Path) -> PathBuf {
    use std::time::{Duration, SystemTime, UNIX_EPOCH};
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let pid = std::process::id();
    let name = target.file_name().and_then(|s| s.to_str()).unwrap_or("tmp");
    target.with_file_name(format!(".{name}.{pid}.{ts}.tmp"))
}

pub(crate) fn write_json_atomic<T: serde::Serialize>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = unique_tmp_path(path);
    let text = serde_json::to_string_pretty(value)?;
    fs::write(&tmp, text).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

pub(crate) fn read_json<T: serde::de::DeserializeOwned>(path: &Path) -> Result<T> {
    crate::common::fs::json::read_json(path)
}

pub(crate) fn ensure_dir(path: &str) -> Result<()> {
    let path = Path::new(path);
    if !path.is_dir() {
        bail!("directory missing: {}", path.display());
    }
    Ok(())
}

pub(crate) fn ensure_file(path: &str) -> Result<()> {
    let path = Path::new(path);
    if !path.is_file() {
        bail!("file missing: {}", path.display());
    }
    Ok(())
}

pub(crate) fn append_check_log(log_path: &Path, text: &str) -> Result<()> {
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .with_context(|| format!("open {}", log_path.display()))?;
    file.write_all(text.as_bytes())
        .with_context(|| format!("write {}", log_path.display()))
}

pub(crate) fn first_version_token(text: &str) -> Option<String> {
    text.split_whitespace()
        .map(|token| token.trim_matches(|c: char| !(c.is_ascii_alphanumeric() || c == '.' || c == '-')).trim_start_matches('v'))
        .find(|token| token.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) && token.contains('.'))
        .map(ToOwned::to_owned)
}

pub(crate) fn version_supports_tun_dns_mode(version: &str) -> bool {
    let mut split = version.splitn(2, '-');
    let base = split.next().unwrap_or("");
    let suffix = split.next();
    let nums = base.split('.').filter_map(|p| p.parse::<u32>().ok()).collect::<Vec<_>>();
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

pub(crate) fn singbox_supports_tun_dns_mode(spec: VariantSpec) -> bool {
    use crate::common::process::shell::{self, Capture};
    let Ok((code, out)) = shell::run_timeout(spec.binary, &["version"], Capture::Both, std::time::Duration::from_secs(3)) else {
        return false;
    };
    if code != 0 {
        return false;
    }
    let Some(ver) = first_version_token(&out) else { return false };
    version_supports_tun_dns_mode(&ver)
}
