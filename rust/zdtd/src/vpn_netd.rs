use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::{
    android::pkg_uid::{self, Mode, Sha256Tracker},
    settings,
    shell::{self, Capture},
};

const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
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
}

fn working_file(name: &str) -> PathBuf {
    Path::new(WORKING_DIR).join(name)
}

pub fn applied_snapshot_path() -> PathBuf {
    working_file("vpn_netd_applied.json")
}

pub fn profiles_tmp_path() -> PathBuf {
    working_file("vpn_netd_profiles.tmp")
}

pub fn last_ndc_out_path() -> PathBuf {
    working_file("vpn_netd_last_ndc.out")
}

fn ensure_working_dir() -> Result<()> {
    fs::create_dir_all(WORKING_DIR).with_context(|| format!("mkdir {WORKING_DIR}"))
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
}

fn trim_ndc_output(out: &str) -> String {
    out.replace('\r', "").trim().to_string()
}

fn remember_ndc_output(out: &str) {
    if ensure_working_dir().is_ok() {
        let _ = fs::write(last_ndc_out_path(), out);
    }
}

fn ndc_capture(args: &[String]) -> Result<(i32, String)> {
    let refs = args.iter().map(String::as_str).collect::<Vec<_>>();
    let (code, out) = shell::run_timeout("ndc", &refs, Capture::Both, NDC_TIMEOUT)?;
    remember_ndc_output(&out);
    Ok((code, trim_ndc_output(&out)))
}

fn ndc_ok(args: Vec<String>, what: &str) -> Result<()> {
    let (code, out) = ndc_capture(&args)?;
    if code == 0 {
        return Ok(());
    }
    bail!("{what} failed rc={code} out={out}");
}

fn ndc_quiet(args: Vec<String>) {
    let _ = ndc_capture(&args);
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
    if p.netid < 100 {
        bail!("vpn netd profile {} netid is too small: {}", p.profile, p.netid);
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
        bail!("no resolved UIDs for app list {}", app_list_path.display());
    }
    Ok(ranges)
}

fn create_vpn_network_universal(netid: u32) -> Result<()> {
    let netid_s = netid.to_string();
    let modern = vec!["network", "create", &netid_s, "vpn", "1"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code1, out1) = ndc_capture(&modern)?;
    if code1 == 0 {
        log::info!("vpn_netd: network create netid={netid} mode=modern");
        return Ok(());
    }

    let fallback = vec!["network", "create", &netid_s, "vpn", "1", "0"]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code2, out2) = ndc_capture(&fallback)?;
    if code2 == 0 {
        log::info!("vpn_netd: network create netid={netid} mode=fallback");
        return Ok(());
    }

    bail!("vpn_netd: create netd vpn network netid={netid} failed; modern rc={code1} out={out1}; fallback rc={code2} out={out2}");
}

fn add_route_universal(netid: u32, tun: &str, dest: &str, gateway: Option<&str>) -> Result<()> {
    let netid_s = netid.to_string();
    if let Some(gw) = gateway {
        let args = vec!["network", "route", "add", &netid_s, tun, dest, gw]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
        let (code, out) = ndc_capture(&args)?;
        if code == 0 {
            return Ok(());
        }
        log::warn!("vpn_netd: route with gateway failed netid={netid} dest={dest} gw={gw}: rc={code} out={out}");
    }

    let args = vec!["network", "route", "add", &netid_s, tun, dest]
        .into_iter()
        .map(str::to_string)
        .collect::<Vec<_>>();
    let (code, out) = ndc_capture(&args)?;
    if code == 0 {
        return Ok(());
    }
    bail!("vpn_netd: route add failed netid={netid} dest={dest}: rc={code} out={out}");
}

fn set_dns_universal(netid: u32, dns: &[String]) -> Result<()> {
    let netid_s = netid.to_string();
    let mut args = vec!["resolver".to_string(), "setnetdns".to_string(), netid_s, String::new()];
    args.extend(dns.iter().cloned());
    let (code, out) = ndc_capture(&args)?;
    if code == 0 {
        return Ok(());
    }
    bail!("vpn_netd: resolver setnetdns failed rc={code} out={out}");
}

fn remove_netd_profile(applied: &AppliedProfile) {
    let netid_s = applied.netid.to_string();
    for r in &applied.uid_ranges {
        if is_number_range(r) {
            ndc_quiet(vec!["network".into(), "users".into(), "remove".into(), netid_s.clone(), r.clone()]);
        }
    }
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

    if let Err(e) = set_dns_universal(profile.netid, &profile.dns) {
        log::warn!("vpn_netd: profile {}/{} DNS was not applied: {e:#}", profile.owner_program, profile.profile);
    }

    let mut failed_users = Vec::new();
    for r in uid_ranges {
        let res = ndc_ok(
            vec!["network".into(), "users".into(), "add".into(), netid_s.clone(), r.clone()],
            &format!("vpn_netd: users add netid={} range={}", profile.netid, r),
        );
        if let Err(e) = res {
            failed_users.push(format!("{r}: {e:#}"));
        }
    }
    if !failed_users.is_empty() {
        bail!("vpn_netd: failed to add UID ranges for {}/{}: {}", profile.owner_program, profile.profile, failed_users.join("; "));
    }

    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);


    Ok(AppliedProfile {
        owner_program: profile.owner_program.clone(),
        profile: profile.profile.clone(),
        netid: profile.netid,
        tun: profile.tun.clone(),
        uid_ranges: uid_ranges.to_vec(),
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
        if had_error {
            crate::logging::user_warn("VPN/netd: ошибка применения, запуск продолжен");
        }
        let _ = fs::remove_file(applied_snapshot_path());
        return Ok(());
    }

    if let Err(e) = validate_no_profile_collisions(&prepared) {
        log::warn!("vpn_netd: profile collision, skipping VPN/netd apply and continuing: {e:#}");
        crate::logging::user_warn("VPN/netd: конфликт профилей, запуск продолжен");
        let _ = fs::remove_file(applied_snapshot_path());
        return Ok(());
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
                });
            }
        }
    }

    if had_error {
        crate::logging::user_warn("VPN/netd: часть профилей не применена, запуск продолжен");
    }

    let snapshot = AppliedSnapshot { profiles: applied };
    write_json_atomic(&applied_snapshot_path(), &snapshot)?;
    log::info!("vpn_netd: applied {} profiles", snapshot.profiles.len());
    Ok(())
}

pub fn stop_applied() -> Result<()> {
    let path = applied_snapshot_path();
    if !path.is_file() {
        cleanup_runtime_files();
        return Ok(());
    }

    let text = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let snapshot: AppliedSnapshot = serde_json::from_str(&text).with_context(|| format!("parse {}", path.display()))?;

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
    let path = applied_snapshot_path();
    if !path.is_file() {
        return Ok(AppliedSnapshot::default());
    }
    let text = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| anyhow!("parse {}: {e}", path.display()))
}
