use crate::common::network::IP_TIMEOUT;
use anyhow::{bail, Context, Result};
use std::{fs, path::Path};

use crate::{
    platform::android::pkg_uid::{self, Mode, Sha256Tracker},
    settings,
    common::process::shell::{self, Capture},
};

use super::{
    model::{AppliedProfile, AppliedSnapshot},
    ndc::{ndc_ok, ndc_quiet},
    state::{applied_snapshot_path, ensure_working_dir, read_applied_snapshot, write_json_atomic},
    IPT_TIMEOUT, V6_CHAIN,
};

pub(super) fn is_number_range(s: &str) -> bool {
    if let Some((a, b)) = s.split_once('-') {
        let Ok(a) = a.parse::<u32>() else { return false; };
        let Ok(b) = b.parse::<u32>() else { return false; };
        return a <= b;
    }
    s.parse::<u32>().is_ok()
}

pub(super) fn range_start(s: &str) -> Option<u32> {
    s.split('-').next()?.parse::<u32>().ok()
}

pub(super) fn range_end(s: &str) -> Option<u32> {
    s.split('-').nth(1).unwrap_or(s).parse::<u32>().ok()
}

pub(super) fn ranges_overlap(a: &str, b: &str) -> bool {
    let (Some(as_), Some(ae), Some(bs), Some(be)) = (range_start(a), range_end(a), range_start(b), range_end(b)) else {
        return false;
    };
    as_ <= be && bs <= ae
}

pub(super) fn uids_to_ranges(mut uids: Vec<u32>) -> Vec<String> {
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

pub(super) fn resolve_uid_ranges(app_list_path: &Path, app_out_path: &Path) -> Result<Vec<String>> {
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
        if crate::common::apps::file_has_launch_marker(app_list_path).unwrap_or(false) {
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


pub(super) fn resolve_uid_ranges_allow_empty(app_list_path: &Path, app_out_path: &Path) -> Result<Vec<String>> {
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


pub(super) fn remove_uid_ranges(netid: u32, ranges: &[String]) {
    let netid_s = netid.to_string();
    for r in ranges {
        if is_number_range(r) {
            ndc_quiet(vec!["network".into(), "users".into(), "remove".into(), netid_s.clone(), r.clone()]);
        }
    }
    let _ = shell::run_timeout("ip", &["route", "flush", "cache"], Capture::None, IP_TIMEOUT);
}

pub(super) fn add_uid_ranges(netid: u32, ranges: &[String], label: &str) -> Result<()> {
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

pub(super) fn validate_refreshed_ranges(snapshot: &AppliedSnapshot, owner_program: &str, profile: &str, new_ranges: &[String]) -> Result<()> {
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

    // Сначала добавляем новые диапазоны, только потом снимаем ушедшие: раньше
    // между remove и add было окно, в котором весь трафик приложений (включая
    // не изменившиеся UID) шёл напрямую, мимо туннеля.
    let added: Vec<String> = new_ranges.iter().filter(|r| !old.uid_ranges.contains(r)).cloned().collect();
    let removed: Vec<String> = old.uid_ranges.iter().filter(|r| !new_ranges.contains(r)).cloned().collect();
    if let Err(e) = add_uid_ranges(old.netid, &added, &label) {
        log::warn!("vpn_netd: hot-refresh users failed for {label}, trying rollback: {e:#}");
        remove_uid_ranges(old.netid, &added);
        bail!("vpn_netd: hot-refresh users failed for {label}: {e:#}");
    }
    remove_uid_ranges(old.netid, &removed);

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
    sync_ipv6_block(&snapshot.profiles);
    Ok(updated)
}

pub(super) fn ip6tables_ok(args: &[&str]) -> bool {
    matches!(
        crate::iptables::lock::run_timeout_retry("ip6tables", args, Capture::Both, IPT_TIMEOUT),
        Ok((0, _))
    )
}

pub(super) fn expand_uid_range(range: &str) -> Vec<String> {
    let (Some(start), Some(end)) = (range_start(range), range_end(range)) else {
        return Vec::new();
    };
    if end < start || end - start > 1024 {
        return Vec::new();
    }
    (start..=end).map(|uid| uid.to_string()).collect()
}

pub(super) fn add_ipv6_block_rules(uid: &str) -> bool {
    // REJECT, а не DROP: приложение сразу получает отказ и переходит на IPv4,
    // а не ждёт таймаута соединения. DROP остаётся крайним резервом.
    let tcp = ip6tables_ok(&["-A", V6_CHAIN, "-p", "tcp", "-m", "owner", "--uid-owner", uid, "-j", "REJECT", "--reject-with", "tcp-reset"])
        || ip6tables_ok(&["-A", V6_CHAIN, "-p", "tcp", "-m", "owner", "--uid-owner", uid, "-j", "REJECT"])
        || ip6tables_ok(&["-A", V6_CHAIN, "-p", "tcp", "-m", "owner", "--uid-owner", uid, "-j", "DROP"]);
    let rest = ip6tables_ok(&["-A", V6_CHAIN, "-m", "owner", "--uid-owner", uid, "-j", "REJECT", "--reject-with", "icmp6-adm-prohibited"])
        || ip6tables_ok(&["-A", V6_CHAIN, "-m", "owner", "--uid-owner", uid, "-j", "REJECT"])
        || ip6tables_ok(&["-A", V6_CHAIN, "-m", "owner", "--uid-owner", uid, "-j", "DROP"]);
    tcp && rest
}

pub(super) fn clear_ipv6_block_unlocked() {
    while ip6tables_ok(&["-C", "OUTPUT", "-j", V6_CHAIN]) {
        if !ip6tables_ok(&["-D", "OUTPUT", "-j", V6_CHAIN]) {
            break;
        }
    }
    let _ = ip6tables_ok(&["-F", V6_CHAIN]);
    let _ = ip6tables_ok(&["-X", V6_CHAIN]);
}

/// Временное решение: netd заводит в туннель только IPv4, поэтому IPv6
/// закрывается только выбранным приложениям (UID применённых профилей),
/// иначе их IPv6-трафик уходит мимо туннеля. Всё best-effort: нет ip6tables
/// или модуля owner — только предупреждение, запуск не рушим.
pub(super) fn sync_ipv6_block(profiles: &[AppliedProfile]) {
    let _guard = crate::iptables::lock::lock();
    clear_ipv6_block_unlocked();

    let mut ranges: Vec<String> = profiles.iter().flat_map(|p| p.uid_ranges.iter().cloned()).collect();
    ranges.sort();
    ranges.dedup();
    if ranges.is_empty() {
        return;
    }

    if !ip6tables_ok(&["-N", V6_CHAIN]) && !ip6tables_ok(&["-L", V6_CHAIN]) {
        log::warn!("vpn_netd: ip6tables is unavailable, IPv6 for VPN apps is not blocked");
        return;
    }

    // Локальные соединения не рвём.
    let _ = ip6tables_ok(&["-A", V6_CHAIN, "-o", "lo", "-j", "RETURN"]);
    let _ = ip6tables_ok(&["-A", V6_CHAIN, "-d", "::1", "-j", "RETURN"]);

    let mut blocked = 0usize;
    let mut failed = Vec::new();
    for range in &ranges {
        if add_ipv6_block_rules(range) {
            blocked += 1;
            continue;
        }
        // Старый xt_owner не понимает диапазоны UID — падаем на отдельные UID.
        let uids = expand_uid_range(range);
        if !uids.is_empty() && uids.iter().all(|uid| add_ipv6_block_rules(uid)) {
            blocked += 1;
            continue;
        }
        failed.push(range.clone());
    }

    if blocked == 0 {
        log::warn!("vpn_netd: IPv6 block rules were not applied (ranges: {})", ranges.join(", "));
        clear_ipv6_block_unlocked();
        return;
    }

    if !ip6tables_ok(&["-I", "OUTPUT", "1", "-j", V6_CHAIN]) {
        log::warn!("vpn_netd: ip6tables OUTPUT hook failed, IPv6 for VPN apps is not blocked");
        clear_ipv6_block_unlocked();
        return;
    }

    if failed.is_empty() {
        log::info!("vpn_netd: IPv6 blocked for {blocked} UID range(s) of VPN apps");
    } else {
        log::warn!("vpn_netd: IPv6 blocked for {blocked} UID range(s), failed: {}", failed.join(", "));
    }
}
