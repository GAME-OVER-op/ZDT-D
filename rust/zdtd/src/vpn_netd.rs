use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::{
    android::pkg_uid::{self, Mode, Sha256Tracker},
    settings,
    shell::{self, Capture},
};

const WORKING_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder";
const VPN_NETD_DIR: &str = "/data/adb/modules/ZDT-D/working_folder/vpn_netd";
const NDC_TIMEOUT: Duration = Duration::from_secs(5);
const IP_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Serialize)]
pub struct VpnNetdProfile {
    pub owner_program: String,
    pub profile: String,
    pub netid: u32,
    pub tun: String,
    pub cidr: String,
    pub gateway: Option<String>,
    pub dns: Vec<String>,
    pub app_list_path: PathBuf,
    pub app_out_path: PathBuf,
    pub endpoint_escape_ips: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppliedSnapshot {
    #[serde(default)]
    pub profiles: Vec<AppliedProfile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppliedProfile {
    pub owner_program: String,
    pub profile: String,
    pub netid: u32,
    pub tun: String,
    #[serde(default)]
    pub uid_ranges: Vec<String>,
    #[serde(default)]
    pub endpoint_escape_ips: Vec<String>,
}

fn runtime_file(name: &str) -> PathBuf {
    Path::new(VPN_NETD_DIR).join(name)
}

fn legacy_working_file(name: &str) -> PathBuf {
    Path::new(WORKING_ROOT).join(name)
}

pub fn applied_snapshot_path() -> PathBuf {
    runtime_file("applied.json")
}

pub fn profiles_tmp_path() -> PathBuf {
    runtime_file("profiles.tmp")
}

pub fn last_ndc_out_path() -> PathBuf {
    runtime_file("last_ndc.out")
}

fn ndc_history_path() -> PathBuf {
    runtime_file("ndc_history.log")
}

fn ensure_working_dir() -> Result<()> {
    fs::create_dir_all(VPN_NETD_DIR).with_context(|| format!("mkdir {VPN_NETD_DIR}"))?;
    migrate_legacy_runtime_files();
    Ok(())
}

fn migrate_legacy_runtime_files() {
    let pairs = [
        ("vpn_netd_applied.json", applied_snapshot_path()),
        ("vpn_netd_profiles.tmp", profiles_tmp_path()),
        ("vpn_netd_last_ndc.out", last_ndc_out_path()),
    ];
    for (legacy, new_path) in pairs {
        let old_path = legacy_working_file(legacy);
        if old_path.is_file() && !new_path.exists() {
            if let Some(parent) = new_path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let _ = fs::rename(&old_path, &new_path);
        } else if old_path.exists() {
            let _ = fs::remove_file(&old_path);
        }
    }
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

fn cleanup_runtime_files() {
    let _ = fs::remove_file(applied_snapshot_path());
    let _ = fs::remove_file(profiles_tmp_path());
    let _ = fs::remove_file(last_ndc_out_path());
    let _ = fs::remove_file(ndc_history_path());

    // Remove legacy top-level files left by older ZDT-D builds.
    let _ = fs::remove_file(legacy_working_file("vpn_netd_applied.json"));
    let _ = fs::remove_file(legacy_working_file("vpn_netd_profiles.tmp"));
    let _ = fs::remove_file(legacy_working_file("vpn_netd_last_ndc.out"));

    // Keep the directory if it still contains future files, but remove it when empty.
    let _ = fs::remove_dir(VPN_NETD_DIR);
}

fn snapshot_text_is_empty_or_nul(text: &str) -> bool {
    text.chars().all(|c| c == '\0' || c.is_whitespace())
}

fn trim_ndc_output(out: &str) -> String {
    out.replace('\r', "").trim().to_string()
}

fn ndc_command_for_log(args: &[String]) -> String {
    format!("ndc {}", args.join(" "))
}

fn remember_ndc_output(args: &[String], code: i32, out: &str) {
    if ensure_working_dir().is_ok() {
        let cmd = ndc_command_for_log(args);
        let trimmed = trim_ndc_output(out);
        let last = if trimmed.is_empty() {
            format!("cmd={cmd}\nrc={code}\nout=<empty>\n")
        } else {
            format!("cmd={cmd}\nrc={code}\nout={trimmed}\n")
        };
        let _ = fs::write(last_ndc_out_path(), &last);

        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(ndc_history_path()) {
            let ts = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or(Duration::from_secs(0))
                .as_secs();
            let _ = writeln!(f, "[{ts}] {cmd}");
            let _ = writeln!(f, "rc={code}");
            if trimmed.is_empty() {
                let _ = writeln!(f, "out=<empty>");
            } else {
                let _ = writeln!(f, "out={trimmed}");
            }
            let _ = writeln!(f);
        }
    }
}

fn ndc_capture(args: &[String]) -> Result<(i32, String)> {
    let refs = args.iter().map(String::as_str).collect::<Vec<_>>();
    let (code, out) = shell::run_timeout("ndc", &refs, Capture::Both, NDC_TIMEOUT)?;
    remember_ndc_output(args, code, &out);
    Ok((code, trim_ndc_output(&out)))
}

fn ndc_failure_reason(code: i32, out: &str) -> Option<String> {
    if code != 0 {
        return Some(format!("rc={code}"));
    }

    for line in out.lines() {
        let line = line.trim();
        let Some(first) = line.split_whitespace().next() else { continue; };
        if let Ok(status) = first.parse::<i32>() {
            if (400..=599).contains(&status) {
                return Some(line.to_string());
            }
        }
    }

    let lower = out.to_ascii_lowercase();
    for needle in [
        "unknown argument",
        "command not recognized",
        "permission denied",
        "invalid argument",
        "operation not permitted",
        "failed",
        "failure",
    ] {
        if lower.contains(needle) {
            return Some(needle.to_string());
        }
    }

    None
}

fn ndc_has_success_status(out: &str) -> bool {
    out.lines().any(|line| {
        let line = line.trim();
        let Some(first) = line.split_whitespace().next() else { return false; };
        first
            .parse::<i32>()
            .map(|status| (200..=299).contains(&status))
            .unwrap_or(false)
    })
}

fn ndc_is_ok(code: i32, out: &str) -> bool {
    if ndc_failure_reason(code, out).is_some() {
        return false;
    }
    if code != 0 {
        return false;
    }
    if out.trim().is_empty() {
        return true;
    }
    ndc_has_success_status(out)
}

fn ndc_ok(args: Vec<String>, what: &str) -> Result<()> {
    let (code, out) = ndc_capture(&args)?;
    if ndc_is_ok(code, &out) {
        return Ok(());
    }
    let reason = ndc_failure_reason(code, &out).unwrap_or_else(|| "unknown ndc failure".to_string());
    bail!("{what} failed ({reason}) rc={code} out={out}");
}

fn ndc_quiet(args: Vec<String>) {
    match ndc_capture(&args) {
        Ok((code, out)) if !ndc_is_ok(code, &out) => {
            log::warn!(
                "vpn_netd: cleanup ndc command failed: {} rc={} out={}",
                ndc_command_for_log(&args),
                code,
                out
            );
        }
        _ => {}
    }
}

fn is_number_range(s: &str) -> bool {
    if let Some((a, b)) = s.split_once('-') {
        let Ok(a) = a.parse::<u32>() else { return false; };
        let Ok(b) = b.parse::<u32>() else { return false; };
        return a <= b;
    }
    s.parse::<u32>().is_ok()
}

fn range_start(s: &str) -> Option<u32> {
    s.split('-').next()?.parse::<u32>().ok()
}

fn range_end(s: &str) -> Option<u32> {
    s.split('-').nth(1).unwrap_or(s).parse::<u32>().ok()
}

fn ranges_overlap(a: &str, b: &str) -> bool {
    let (Some(as_), Some(ae), Some(bs), Some(be)) = (range_start(a), range_end(a), range_start(b), range_end(b)) else {
        return false;
    };
    as_ <= be && bs <= ae
}

fn uids_to_ranges(mut uids: Vec<u32>) -> Vec<String> {
    uids.sort_unstable();
    uids.dedup();
    let mut out = Vec::new();
    let mut iter = uids.into_iter();
    let Some(mut start) = iter.next() else { return out; };
    let mut prev = start;
    for uid in iter {
        if uid == prev.saturating_add(1) {
            prev = uid;
            continue;
        }
        out.push(format!("{start}-{prev}"));
        start = uid;
        prev = uid;
    }
    out.push(format!("{start}-{prev}"));
    out
}

fn is_profile_name(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

fn is_ifname(s: &str) -> bool {
    !s.is_empty() && s.len() <= 15 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

fn is_ipv4(s: &str) -> bool {
    let parts = s.split('.').collect::<Vec<_>>();
    if parts.len() != 4 { return false; }
    parts.iter().all(|p| !p.is_empty() && p.parse::<u8>().is_ok())
}

fn is_cidr(s: &str) -> bool {
    let Some((ip, prefix)) = s.split_once('/') else { return false; };
    let Ok(prefix) = prefix.parse::<u8>() else { return false; };
    is_ipv4(ip) && prefix <= 32
}

fn validate_profile(p: &VpnNetdProfile) -> Result<()> {
    if p.owner_program.trim().is_empty() {
        bail!("vpn netd profile owner_program is empty");
    }
    if !is_profile_name(&p.profile) {
        bail!("vpn netd profile name is invalid: {}", p.profile);
    }
    if !(100..=65535).contains(&p.netid) {
        bail!("vpn netd profile {} netid is outside Android netId range 100..65535: {}", p.profile, p.netid);
    }
    if !is_ifname(&p.tun) {
        bail!("vpn netd profile {} tun is invalid: {}", p.profile, p.tun);
    }
    if !is_cidr(&p.cidr) {
        bail!("vpn netd profile {} cidr is invalid: {}", p.profile, p.cidr);
    }
    if let Some(gw) = &p.gateway {
        if !is_ipv4(gw) {
            bail!("vpn netd profile {} gateway is invalid: {}", p.profile, gw);
        }
    }
    if p.dns.is_empty() || p.dns.len() > 8 || !p.dns.iter().all(|d| is_ipv4(d)) {
        bail!("vpn netd profile {} dns list is invalid", p.profile);
    }
    if !p.app_list_path.is_file() {
        bail!("vpn netd profile {} app list is missing: {}", p.profile, p.app_list_path.display());
    }
    if p.endpoint_escape_ips.iter().any(|ip| !is_ipv4(ip)) {
        bail!("vpn netd profile {} endpoint escape IP list is invalid", p.profile);
    }
    Ok(())
}

fn check_tun_ready(tun: &str) -> Result<()> {
    let (code, out) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
        .with_context(|| format!("check tun link {tun}"))?;
    if code != 0 {
        bail!("tun interface {tun} not found: {}", trim_ndc_output(&out));
    }

    // OpenVPN and tun2socks validate or assign IPv4 before they register a profile.
    // Universal binders such as myvpn may provide a manual CIDR for an already-created
    // interface, so vpn_netd itself only requires the interface to exist.
    let (code, out) = shell::run_timeout("ip", &["-4", "addr", "show", "dev", tun], Capture::Stdout, IP_TIMEOUT)
        .unwrap_or((1, String::new()));
    if code != 0 || !out.lines().any(|l| l.trim_start().starts_with("inet ")) {
        log::warn!("vpn_netd: tun {tun} has no visible IPv4 address; continuing because profile CIDR is provided by owner program");
    }
    Ok(())
}

fn resolve_uid_ranges(app_list_path: &Path, app_out_path: &Path) -> Result<Vec<String>> {
    if let Some(parent) = app_out_path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tracker = Sha256Tracker::new(settings::SHARED_SHA_FLAG_FILE);
    let _changed = pkg_uid::unified_processing(Mode::Default, &tracker, app_out_path, app_list_path)?;

    let text = fs::read_to_string(app_out_path)
        .with_context(|| format!("read uid output {}", app_out_path.display()))?;
    let mut uids = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') { continue; }
        let uid_s = line.split_once('=').map(|(_, uid)| uid.trim()).unwrap_or(line);
        if let Ok(uid) = uid_s.parse::<u32>() {
            if uid > 0 {
                uids.push(uid);
            }
        }
    }
    let ranges = uids_to_ranges(uids);
    if ranges.is_empty() {
        if pkg_uid::file_has_launch_marker(app_list_path).unwrap_or(false) {
            log::info!(
                "vpn_netd: launch marker present in {}, applying profile without UID users",
                app_list_path.display()
            );
            return Ok(Vec::new());
        }
        bail!("no resolved UIDs for app list {}", app_list_path.display());
    }
    Ok(ranges)
}


fn resolve_uid_ranges_allow_empty(app_list_path: &Path, app_out_path: &Path) -> Result<Vec<String>> {
    if let Some(parent) = app_out_path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tracker = Sha256Tracker::new(settings::SHARED_SHA_FLAG_FILE);
    let _changed = pkg_uid::unified_processing(Mode::Default, &tracker, app_out_path, app_list_path)?;

    let text = fs::read_to_string(app_out_path)
        .with_context(|| format!("read uid output {}", app_out_path.display()))?;
    let mut uids = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') { continue; }
        let uid_s = line.split_once('=').map(|(_, uid)| uid.trim()).unwrap_or(line);
        if let Ok(uid) = uid_s.parse::<u32>() {
            if uid > 0 {
                uids.push(uid);
            }
        }
    }
    Ok(uids_to_ranges(uids))
}

fn create_vpn_network_universal(netid: u32) -> Result<()> {
    let netid_s = netid.to_string();
    let modern = vec!["network", "create", &netid_s, "vpn", "1"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code1, out1) = ndc_capture(&modern)?;
    if ndc_is_ok(code1, &out1) {
        log::info!("vpn_netd: network create netid={netid} mode=modern");
        return Ok(());
    }

    // Legacy netd syntax is: network create <netId> vpn <hasDns> <secure>.
    // Keep the VPN secure first; older builds that reject this form can still fall
    // through to the historical compatibility attempt below.
    let legacy_secure = vec!["network", "create", &netid_s, "vpn", "1", "1"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code2, out2) = ndc_capture(&legacy_secure)?;
    if ndc_is_ok(code2, &out2) {
        log::info!("vpn_netd: network create netid={netid} mode=legacy-secure");
        return Ok(());
    }

    // Some older Android/netd builds use: network create <netId> vpn
    // and reject any trailing secure/hasDns arguments. Try this before the
    // insecure compatibility form so devices with the no-arg syntax do not fall
    // through to a less safe legacy mode.
    let legacy_noargs = vec!["network", "create", &netid_s, "vpn"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code3, out3) = ndc_capture(&legacy_noargs)?;
    if ndc_is_ok(code3, &out3) {
        log::info!("vpn_netd: network create netid={netid} mode=legacy-noargs");
        return Ok(());
    }

    // Last-resort compatibility with the previous ZDT-D behavior. This may allow
    // bypass on legacy Android, so log it loudly and only use it if secure forms
    // and the historical no-argument form fail.
    let legacy_compat = vec!["network", "create", &netid_s, "vpn", "1", "0"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code4, out4) = ndc_capture(&legacy_compat)?;
    if ndc_is_ok(code4, &out4) {
        log::warn!("vpn_netd: network create netid={netid} mode=legacy-compat-insecure");
        return Ok(());
    }

    bail!(
        "vpn_netd: create netd vpn network netid={netid} failed; modern rc={code1} out={out1}; legacy-secure rc={code2} out={out2}; legacy-noargs rc={code3} out={out3}; legacy-compat rc={code4} out={out4}"
    );
}

fn add_route_universal(netid: u32, tun: &str, dest: &str, gateway: Option<&str>) -> Result<()> {
    let netid_s = netid.to_string();
    if let Some(gw) = gateway {
        let args = vec!["network", "route", "add", &netid_s, tun, dest, gw]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
        let (code, out) = ndc_capture(&args)?;
        if ndc_is_ok(code, &out) {
            return Ok(());
        }
        log::warn!("vpn_netd: route with gateway failed netid={netid} dest={dest} gw={gw}: rc={code} out={out}");
    }

    let args = vec!["network", "route", "add", &netid_s, tun, dest]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code, out) = ndc_capture(&args)?;
    if ndc_is_ok(code, &out) {
        return Ok(());
    }
    bail!("vpn_netd: route add failed netid={netid} dest={dest}: rc={code} out={out}");
}

fn set_dns_universal(netid: u32, tun: &str, dns: &[String]) -> Result<()> {
    let netid_s = netid.to_string();
    let mut setnetdns = vec!["resolver".to_string(), "setnetdns".to_string(), netid_s, String::new()];
    setnetdns.extend(dns.iter().cloned());
    let (code1, out1) = ndc_capture(&setnetdns)?;
    if ndc_is_ok(code1, &out1) {
        log::info!("vpn_netd: resolver setnetdns applied netid={netid}");
        return Ok(());
    }

    // Older netd builds used interface DNS configuration before setnetdns.
    // DNS remains warning-only for vpn_netd, but trying setifdns improves
    // compatibility on these devices and records the result in ndc_history.log.
    let mut setifdns = vec!["resolver".to_string(), "setifdns".to_string(), tun.to_string(), String::new()];
    setifdns.extend(dns.iter().cloned());
    let (code2, out2) = ndc_capture(&setifdns)?;
    if ndc_is_ok(code2, &out2) {
        log::info!("vpn_netd: resolver setifdns applied tun={tun} netid={netid}");
        return Ok(());
    }

    bail!("vpn_netd: resolver DNS setup failed; setnetdns rc={code1} out={out1}; setifdns rc={code2} out={out2}");
}

fn unique_endpoint_escape_ips(profile: &VpnNetdProfile) -> Vec<String> {
    let mut ips = profile
        .endpoint_escape_ips
        .iter()
        .map(|ip| ip.trim())
        .filter(|ip| is_ipv4(ip))
        .map(str::to_string)
        .collect::<Vec<_>>();
    ips.sort();
    ips.dedup();
    ips
}

fn ensure_endpoint_escape_routes(profile: &VpnNetdProfile) {
    for ip in unique_endpoint_escape_ips(profile) {
        let dest = format!("{ip}/32");
        // Android/netd may mark backend packets with the VPN netId.  The table
        // created for the VPN interface contains a default route through that
        // same interface, so endpoint packets can self-capture into the tunnel.
        // A throw route only says "not through this table"; Android will then
        // continue to the following rules and pick the current physical network
        // such as wlan0/rmnet_data*/ccmni*/wwan*.
        let attempts: Vec<Vec<&str>> = vec![
            vec!["-4", "route", "replace", "throw", &dest, "table", &profile.tun],
            vec!["-4", "route", "replace", &dest, "type", "throw", "table", &profile.tun],
        ];
        let mut applied = false;
        let mut errors = Vec::new();
        for args in attempts {
            match shell::run_timeout("ip", &args, Capture::Both, IP_TIMEOUT) {
                Ok((0, out)) => {
                    log::info!(
                        "vpn_netd: endpoint escape route applied {}/{} tun={} ip={} out={}",
                        profile.owner_program,
                        profile.profile,
                        profile.tun,
                        ip,
                        trim_ndc_output(&out)
                    );
                    applied = true;
                    break;
                }
                Ok((code, out)) => errors.push(format!("rc={code} out={}", trim_ndc_output(&out))),
                Err(e) => errors.push(format!("{e:#}")),
            }
        }
        if !applied {
            log::warn!(
                "vpn_netd: endpoint escape route failed {}/{} tun={} ip={}: {}",
                profile.owner_program,
                profile.profile,
                profile.tun,
                ip,
                errors.join("; ")
            );
        }
    }
}

fn remove_endpoint_escape_routes(applied: &AppliedProfile) {
    let mut ips = applied
        .endpoint_escape_ips
        .iter()
        .map(|ip| ip.trim())
        .filter(|ip| is_ipv4(ip))
        .map(str::to_string)
        .collect::<Vec<_>>();
    ips.sort();
    ips.dedup();
    for ip in ips {
        let dest = format!("{ip}/32");
        let attempts: Vec<Vec<&str>> = vec![
            vec!["-4", "route", "del", "throw", &dest, "table", &applied.tun],
            vec!["-4", "route", "del", &dest, "type", "throw", "table", &applied.tun],
        ];
        for args in attempts {
            let _ = shell::run_timeout("ip", &args, Capture::None, IP_TIMEOUT);
        }
    }
}

fn verify_post_apply(profile: &VpnNetdProfile, uid_ranges: &[String]) {
    match shell::run_timeout("ip", &["rule", "show"], Capture::Stdout, IP_TIMEOUT) {
        Ok((code, out)) if code == 0 => {
            if !uid_ranges.is_empty() {
                let missing = uid_ranges
                    .iter()
                    .filter(|r| !out.contains(&format!("uidrange {r}")))
                    .cloned()
                    .collect::<Vec<_>>();
                if !missing.is_empty() {
                    log::warn!(
                        "vpn_netd: post-check did not see uidrange(s) in ip rule for {}/{} netid={} tun={}: {}",
                        profile.owner_program,
                        profile.profile,
                        profile.netid,
                        profile.tun,
                        missing.join(", ")
                    );
                }
            }
            if !uid_ranges.is_empty() && !out.contains(&format!("lookup {}", profile.tun)) {
                log::warn!(
                    "vpn_netd: post-check did not see lookup {} in ip rule for {}/{} netid={}",
                    profile.tun,
                    profile.owner_program,
                    profile.profile,
                    profile.netid
                );
            }
        }
        Ok((code, out)) => log::warn!("vpn_netd: post-check ip rule failed rc={code} out={}", trim_ndc_output(&out)),
        Err(e) => log::warn!("vpn_netd: post-check ip rule failed: {e:#}"),
    }

    match shell::run_timeout("ip", &["route", "show", "table", "all"], Capture::Stdout, IP_TIMEOUT) {
        Ok((code, out)) if code == 0 => {
            if !out.contains(&profile.tun) {
                log::warn!(
                    "vpn_netd: post-check did not see routes for tun {} in table all for {}/{} netid={}",
                    profile.tun,
                    profile.owner_program,
                    profile.profile,
                    profile.netid
                );
            }
        }
        Ok((code, out)) => log::warn!("vpn_netd: post-check ip route failed rc={code} out={}", trim_ndc_output(&out)),
        Err(e) => log::warn!("vpn_netd: post-check ip route failed: {e:#}"),
    }

    for ip in unique_endpoint_escape_ips(profile) {
        let dest_cidr = format!("throw {ip}/32");
        let dest_host = format!("throw {ip}");
        match shell::run_timeout("ip", &["-4", "route", "show", "table", &profile.tun], Capture::Stdout, IP_TIMEOUT) {
            Ok((code, out)) if code == 0 && !out.contains(&dest_cidr) && !out.contains(&dest_host) => {
                log::warn!(
                    "vpn_netd: post-check did not see endpoint escape route {} in table {} for {}/{}",
                    dest_cidr,
                    profile.tun,
                    profile.owner_program,
                    profile.profile
                );
            }
            _ => {}
        }
    }
}


fn remove_uid_ranges(netid: u32, ranges: &[String]) {
    let netid_s = netid.to_string();
    for r in ranges {
        if is_number_range(r) {
            ndc_quiet(vec!["network".into(), "users".into(), "remove".into(), netid_s.clone(), r.clone()]);
        }
    }
    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);
}

fn add_uid_ranges(netid: u32, ranges: &[String], label: &str) -> Result<()> {
    let netid_s = netid.to_string();
    let mut failed = Vec::new();
    for r in ranges {
        let res = ndc_ok(
            vec!["network".into(), "users".into(), "add".into(), netid_s.clone(), r.clone()],
            &format!("vpn_netd: users add netid={} range={}", netid, r),
        );
        if let Err(e) = res {
            failed.push(format!("{r}: {e:#}"));
        }
    }
    if !failed.is_empty() {
        bail!("vpn_netd: failed to add UID ranges for {label}: {}", failed.join("; "));
    }
    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);
    Ok(())
}

fn validate_refreshed_ranges(snapshot: &AppliedSnapshot, owner_program: &str, profile: &str, new_ranges: &[String]) -> Result<()> {
    let label = format!("{owner_program}/{profile}");
    for other in &snapshot.profiles {
        if other.owner_program == owner_program && other.profile == profile {
            continue;
        }
        let other_label = format!("{}/{}", other.owner_program, other.profile);
        for new_range in new_ranges {
            for other_range in &other.uid_ranges {
                if ranges_overlap(new_range, other_range) {
                    bail!("vpn_netd: UID range {new_range} in {label} overlaps with {other_range} in {other_label}");
                }
            }
        }
    }
    Ok(())
}

/// Hot-refresh only the UID users assigned to an already-applied VPN/netd profile.
///
/// This is intentionally UID-only. Runtime network shape (netId, TUN, routes, DNS,
/// endpoint escape routes) remains the one that was applied at service start.
/// Editing those settings while the service is running is allowed, but it is not
/// reflected here until the next stop/start cycle.
pub fn refresh_profile_users(owner_program: &str, profile: &str, app_list_path: &Path, app_out_path: &Path) -> Result<AppliedProfile> {
    ensure_working_dir()?;
    let mut snapshot = read_applied_snapshot()?;
    let Some(index) = snapshot.profiles.iter().position(|item| item.owner_program == owner_program && item.profile == profile) else {
        log::info!("vpn_netd: applied profile not found for hot UID refresh, skipping: {owner_program}/{profile}");
        return Ok(AppliedProfile {
            owner_program: owner_program.to_string(),
            profile: profile.to_string(),
            netid: 0,
            tun: String::new(),
            uid_ranges: Vec::new(),
            endpoint_escape_ips: Vec::new(),
        });
    };

    let old = snapshot.profiles[index].clone();
    let new_ranges = resolve_uid_ranges_allow_empty(app_list_path, app_out_path)?;
    validate_refreshed_ranges(&snapshot, owner_program, profile, &new_ranges)?;

    if old.uid_ranges == new_ranges {
        log::info!("vpn_netd: UID users unchanged for {owner_program}/{profile}");
        return Ok(old);
    }

    let label = format!("{owner_program}/{profile}");
    log::info!(
        "vpn_netd: hot-refresh users for {} netid={} old_ranges={} new_ranges={}",
        label,
        old.netid,
        old.uid_ranges.len(),
        new_ranges.len()
    );

    remove_uid_ranges(old.netid, &old.uid_ranges);
    if let Err(e) = add_uid_ranges(old.netid, &new_ranges, &label) {
        log::warn!("vpn_netd: hot-refresh users failed for {label}, trying rollback: {e:#}");
        let _ = add_uid_ranges(old.netid, &old.uid_ranges, &format!("{label} rollback"));
        bail!("vpn_netd: hot-refresh users failed for {label}: {e:#}");
    }

    let updated = AppliedProfile {
        owner_program: old.owner_program,
        profile: old.profile,
        netid: old.netid,
        tun: old.tun,
        uid_ranges: new_ranges,
        endpoint_escape_ips: old.endpoint_escape_ips,
    };
    snapshot.profiles[index] = updated.clone();
    write_json_atomic(&applied_snapshot_path(), &snapshot)?;
    Ok(updated)
}

fn remove_netd_profile(applied: &AppliedProfile) {
    remove_endpoint_escape_routes(applied);
    remove_uid_ranges(applied.netid, &applied.uid_ranges);
    let netid_s = applied.netid.to_string();
    ndc_quiet(vec!["network".into(), "interface".into(), "remove".into(), netid_s.clone(), applied.tun.clone()]);
    ndc_quiet(vec!["network".into(), "destroy".into(), netid_s]);
    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);
}

fn apply_one_profile(profile: &VpnNetdProfile, uid_ranges: &[String]) -> Result<AppliedProfile> {
    check_tun_ready(&profile.tun)?;

    create_vpn_network_universal(profile.netid)?;

    let netid_s = profile.netid.to_string();
    ndc_ok(
        vec!["network".into(), "interface".into(), "add".into(), netid_s.clone(), profile.tun.clone()],
        &format!("vpn_netd: interface add netid={} tun={}", profile.netid, profile.tun),
    )?;

    if let Err(e) = add_route_universal(profile.netid, &profile.tun, &profile.cidr, None) {
        log::warn!("vpn_netd: profile {}/{} route {} skipped: {e:#}", profile.owner_program, profile.profile, profile.cidr);
    }

    add_route_universal(profile.netid, &profile.tun, "0.0.0.0/0", profile.gateway.as_deref())?;
    ensure_endpoint_escape_routes(profile);

    if let Err(e) = set_dns_universal(profile.netid, &profile.tun, &profile.dns) {
        log::warn!("vpn_netd: profile {}/{} DNS was not applied: {e:#}", profile.owner_program, profile.profile);
    }

    add_uid_ranges(profile.netid, uid_ranges, &format!("{}/{}", profile.owner_program, profile.profile))?;

    verify_post_apply(profile, uid_ranges);

    Ok(AppliedProfile {
        owner_program: profile.owner_program.clone(),
        profile: profile.profile.clone(),
        netid: profile.netid,
        tun: profile.tun.clone(),
        uid_ranges: uid_ranges.to_vec(),
        endpoint_escape_ips: unique_endpoint_escape_ips(profile),
    })
}

fn validate_no_profile_collisions(items: &[(VpnNetdProfile, Vec<String>)]) -> Result<()> {
    let mut netids = BTreeSet::new();
    let mut tuns = BTreeMap::<String, String>::new();
    let mut cidrs: Vec<(String, String)> = Vec::new();
    let mut uid_ranges: Vec<(String, String)> = Vec::new();

    for (profile, ranges) in items {
        let label = format!("{}/{}", profile.owner_program, profile.profile);
        if !netids.insert(profile.netid) {
            bail!("vpn_netd: duplicate NETID {} in profile {label}", profile.netid);
        }
        if let Some(other_label) = tuns.insert(profile.tun.clone(), label.clone()) {
            bail!(
                "vpn_netd: tun {} in {label} conflicts with {other_label}",
                profile.tun
            );
        }
        for (other_label, other_cidr) in &cidrs {
            if cidrs_overlap(&profile.cidr, other_cidr)? {
                bail!("vpn_netd: CIDR {} in {label} overlaps with {} in {other_label}", profile.cidr, other_cidr);
            }
        }
        cidrs.push((label.clone(), profile.cidr.clone()));
        for r in ranges {
            for (other_label, other_range) in &uid_ranges {
                if ranges_overlap(r, other_range) {
                    bail!("vpn_netd: UID range {r} in {label} overlaps with {other_range} in {other_label}");
                }
            }
            uid_ranges.push((label.clone(), r.clone()));
        }
    }
    Ok(())
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
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 {
        bail!("bad cidr prefix {cidr}");
    }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
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

pub fn start_from_registered_programs() -> Result<()> {
    start_profiles(Vec::<VpnNetdProfile>::new())
}

pub fn start_profiles(profiles: Vec<VpnNetdProfile>) -> Result<()> {
    ensure_working_dir()?;
    let requested_count = profiles.len();

    // Rebuild only VPN/netd state created by this builder. This does not touch iptables,
    // ip rules, or any profile configuration owned by future VPN programs.
    stop_applied()?;

    if profiles.is_empty() {
        let _ = fs::remove_file(profiles_tmp_path());
        log::info!("vpn_netd: no profiles registered; nothing to apply");
        return Ok(());
    }

    write_json_atomic(&profiles_tmp_path(), &profiles)?;

    let mut prepared = Vec::new();
    let mut had_error = false;
    for profile in profiles {
        let label = format!("{}/{}", profile.owner_program, profile.profile);
        match (|| -> Result<(VpnNetdProfile, Vec<String>)> {
            validate_profile(&profile)?;
            let ranges = resolve_uid_ranges(&profile.app_list_path, &profile.app_out_path)?;
            Ok((profile, ranges))
        })() {
            Ok(item) => prepared.push(item),
            Err(e) => {
                had_error = true;
                log::warn!("vpn_netd: profile {label} skipped before apply, startup continues: {e:#}");
            }
        }
    }

    if prepared.is_empty() {
        let _ = fs::remove_file(applied_snapshot_path());
        if had_error {
            crate::logging::user_warn("VPN/netd: ошибка применения, запуск продолжен");
            bail!("vpn_netd: no profiles prepared out of {requested_count}");
        }
        return Ok(());
    }

    if let Err(e) = validate_no_profile_collisions(&prepared) {
        log::warn!("vpn_netd: profile collision, skipping VPN/netd apply and continuing: {e:#}");
        crate::logging::user_warn("VPN/netd: конфликт профилей, запуск продолжен");
        let _ = fs::remove_file(applied_snapshot_path());
        bail!("vpn_netd: profile collision: {e:#}");
    }

    let mut applied = Vec::new();
    for (profile, ranges) in &prepared {
        match apply_one_profile(profile, ranges) {
            Ok(item) => applied.push(item),
            Err(e) => {
                had_error = true;
                log::warn!("vpn_netd: apply failed for {}/{}, startup continues: {e:#}", profile.owner_program, profile.profile);
                // Clean only this failed profile/netid best-effort. Keep already applied profiles.
                remove_netd_profile(&AppliedProfile {
                    owner_program: profile.owner_program.clone(),
                    profile: profile.profile.clone(),
                    netid: profile.netid,
                    tun: profile.tun.clone(),
                    uid_ranges: ranges.clone(),
                    endpoint_escape_ips: unique_endpoint_escape_ips(profile),
                });
            }
        }
    }

    if had_error {
        crate::logging::user_warn("VPN/netd: часть профилей не применена, запуск продолжен");
    }

    if applied.is_empty() {
        let _ = fs::remove_file(applied_snapshot_path());
        bail!("vpn_netd: no prepared profile was applied out of {requested_count}");
    }

    let snapshot = AppliedSnapshot { profiles: applied };
    write_json_atomic(&applied_snapshot_path(), &snapshot)?;
    log::info!("vpn_netd: applied {} profiles", snapshot.profiles.len());
    Ok(())
}

pub fn stop_applied() -> Result<()> {
    ensure_working_dir()?;
    let path = applied_snapshot_path();
    if !path.is_file() {
        cleanup_runtime_files();
        return Ok(());
    }

    let text = match fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!(
                "vpn_netd: stale applied snapshot is unreadable ({}), removing runtime files and continuing fresh apply: {e}",
                path.display()
            );
            cleanup_runtime_files();
            return Ok(());
        }
    };

    if snapshot_text_is_empty_or_nul(&text) {
        log::warn!(
            "vpn_netd: stale applied snapshot is empty/NUL-filled ({}), removing runtime files and continuing fresh apply",
            path.display()
        );
        cleanup_runtime_files();
        return Ok(());
    }

    let snapshot: AppliedSnapshot = match serde_json::from_str(&text) {
        Ok(snapshot) => snapshot,
        Err(e) => {
            log::warn!(
                "vpn_netd: stale applied snapshot is corrupted ({}), removing runtime files and continuing fresh apply: {e}",
                path.display()
            );
            cleanup_runtime_files();
            return Ok(());
        }
    };

    if !snapshot.profiles.is_empty() {
        log::info!("vpn_netd: cleanup {} applied profile(s)", snapshot.profiles.len());
    }
    for item in snapshot.profiles.iter().rev() {
        log::info!(
            "vpn_netd: removing applied profile {}/{} netid={} tun={}",
            item.owner_program,
            item.profile,
            item.netid,
            item.tun
        );
        remove_netd_profile(item);
    }

    cleanup_runtime_files();
    Ok(())
}

pub fn read_applied_snapshot() -> Result<AppliedSnapshot> {
    ensure_working_dir()?;
    let path = applied_snapshot_path();
    if !path.is_file() {
        return Ok(AppliedSnapshot::default());
    }
    let text = match fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) => {
            log::warn!("vpn_netd: applied snapshot is unreadable ({}), treating as not applied: {e}", path.display());
            return Ok(AppliedSnapshot::default());
        }
    };
    if snapshot_text_is_empty_or_nul(&text) {
        log::warn!("vpn_netd: applied snapshot is empty/NUL-filled ({}), treating as not applied", path.display());
        return Ok(AppliedSnapshot::default());
    }
    match serde_json::from_str(&text) {
        Ok(snapshot) => Ok(snapshot),
        Err(e) => {
            log::warn!("vpn_netd: applied snapshot is corrupted ({}), treating as not applied: {e}", path.display());
            Ok(AppliedSnapshot::default())
        }
    }
}
