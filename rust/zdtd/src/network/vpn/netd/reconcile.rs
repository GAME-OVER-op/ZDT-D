use anyhow::{bail, Context, Result};
use std::{collections::{BTreeMap, BTreeSet}, fs};

use crate::{
    common::network::{cidrs_overlap, is_ipv4},
    common::process::shell::{self, Capture},
};

use super::{
    model::{AppliedProfile, VpnNetdProfile},
    ndc::trim_ndc_output,
    uid::ranges_overlap,
    IP_TIMEOUT,
};

pub(super) fn is_profile_name(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

pub(super) fn is_ifname(s: &str) -> bool {
    !s.is_empty() && s.len() <= 15 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}





pub(super) fn validate_profile(p: &VpnNetdProfile) -> Result<()> {
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
    if !crate::common::network::is_ipv4_cidr(&p.cidr) {
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

pub(super) fn check_tun_ready(tun: &str) -> Result<()> {
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


pub(super) fn unique_endpoint_escape_ips(profile: &VpnNetdProfile) -> Vec<String> {
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

// Таблица маршрутов интерфейса. Обращение по имени (`table wg0`) работает
// только при наличии алиаса в rt_tables, поэтому оставляем его первой попыткой,
// но добавляем числовые резервы: netd нумерует таблицы интерфейсов как
// ifindex + 1000, и тот же номер виден в `ip rule show`.
pub(super) fn route_table_ids(tun: &str) -> Vec<String> {
    let mut out = vec![tun.to_string()];
    if let Ok(raw) = fs::read_to_string(format!("/sys/class/net/{tun}/ifindex")) {
        if let Ok(ifindex) = raw.trim().parse::<u32>() {
            let table = (ifindex + 1000).to_string();
            if !out.contains(&table) {
                out.push(table);
            }
        }
    }
    if let Ok((0, rules)) = shell::run_timeout("ip", &["rule", "show"], Capture::Stdout, IP_TIMEOUT) {
        for line in rules.lines() {
            if !line.contains(tun) {
                continue;
            }
            if let Some(rest) = line.split("lookup ").nth(1) {
                let table = rest.split_whitespace().next().unwrap_or("").to_string();
                if !table.is_empty() && !out.contains(&table) {
                    out.push(table);
                }
            }
        }
    }
    out
}

pub(super) fn ensure_endpoint_escape_routes(profile: &VpnNetdProfile) {
    for ip in unique_endpoint_escape_ips(profile) {
        let dest = format!("{ip}/32");
        // Android/netd may mark backend packets with the VPN netId.  The table
        // created for the VPN interface contains a default route through that
        // same interface, so endpoint packets can self-capture into the tunnel.
        // A throw route only says "not through this table"; Android will then
        // continue to the following rules and pick the current physical network
        // such as wlan0/rmnet_data*/ccmni*/wwan*.
        let mut applied = false;
        let mut errors = Vec::new();
        'tables: for table in route_table_ids(&profile.tun) {
            let attempts: Vec<Vec<&str>> = vec![
                vec!["-4", "route", "replace", "throw", &dest, "table", &table],
                vec!["-4", "route", "replace", &dest, "type", "throw", "table", &table],
            ];
            for args in attempts {
                match shell::run_timeout("ip", &args, Capture::Both, IP_TIMEOUT) {
                    Ok((0, out)) => {
                        log::info!(
                            "vpn_netd: endpoint escape route applied {}/{} tun={} table={} ip={} out={}",
                            profile.owner_program,
                            profile.profile,
                            profile.tun,
                            table,
                            ip,
                            trim_ndc_output(&out)
                        );
                        applied = true;
                        break 'tables;
                    }
                    Ok((code, out)) => errors.push(format!("table={table} rc={code} out={}", trim_ndc_output(&out))),
                    Err(e) => errors.push(format!("table={table} {e:#}")),
                }
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

pub(super) fn remove_endpoint_escape_routes(applied: &AppliedProfile) {
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
        for table in route_table_ids(&applied.tun) {
            let attempts: Vec<Vec<&str>> = vec![
                vec!["-4", "route", "del", "throw", &dest, "table", &table],
                vec!["-4", "route", "del", &dest, "type", "throw", "table", &table],
            ];
            for args in attempts {
                let _ = shell::run_timeout("ip", &args, Capture::None, IP_TIMEOUT);
            }
        }
    }
}

pub(super) fn verify_post_apply(profile: &VpnNetdProfile, uid_ranges: &[String]) {
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
            let tables = route_table_ids(&profile.tun);
            if !uid_ranges.is_empty() && !tables.iter().any(|t| out.contains(&format!("lookup {t}"))) {
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
        let mut seen = false;
        for table in route_table_ids(&profile.tun) {
            if let Ok((0, out)) = shell::run_timeout("ip", &["-4", "route", "show", "table", &table], Capture::Stdout, IP_TIMEOUT) {
                if out.contains(&dest_cidr) || out.contains(&dest_host) {
                    seen = true;
                    break;
                }
            }
        }
        if !seen {
            log::warn!(
                "vpn_netd: post-check did not see endpoint escape route {} in route tables of tun {} for {}/{}",
                dest_cidr,
                profile.tun,
                profile.owner_program,
                profile.profile
            );
        }
    }
}

/// Разводит конфликты между профилями до применения. Раньше первый же
/// конфликт валил весь VPN/netd целиком; теперь исключается только
/// конфликтующий профиль: ресурс остаётся за тем, кто раньше в списке
/// стартеров (см. runtime.rs::netd_starters).
pub(super) fn drop_conflicting_profiles(
    items: Vec<(VpnNetdProfile, Vec<String>)>,
) -> (Vec<(VpnNetdProfile, Vec<String>)>, Vec<String>) {
    let mut netids = BTreeSet::new();
    let mut tuns = BTreeMap::<String, String>::new();
    let mut cidrs: Vec<(String, String)> = Vec::new();
    let mut uid_ranges: Vec<(String, String)> = Vec::new();
    let mut kept: Vec<(VpnNetdProfile, Vec<String>)> = Vec::new();
    let mut rejected: Vec<String> = Vec::new();

    for (profile, ranges) in items {
        let label = format!("{}/{}", profile.owner_program, profile.profile);
        let mut reason: Option<String> = None;

        if netids.contains(&profile.netid) {
            reason = Some(format!("duplicate NETID {}", profile.netid));
        }
        if reason.is_none() {
            if let Some(other_label) = tuns.get(&profile.tun) {
                reason = Some(format!("tun {} conflicts with {other_label}", profile.tun));
            }
        }
        if reason.is_none() {
            for (other_label, other_cidr) in &cidrs {
                if cidrs_overlap(&profile.cidr, other_cidr).unwrap_or(false) {
                    reason = Some(format!("CIDR {} overlaps with {other_cidr} in {other_label}", profile.cidr));
                    break;
                }
            }
        }
        if reason.is_none() {
            'ranges: for r in &ranges {
                for (other_label, other_range) in &uid_ranges {
                    if ranges_overlap(r, other_range) {
                        reason = Some(format!("UID range {r} overlaps with {other_range} in {other_label}"));
                        break 'ranges;
                    }
                }
            }
        }

        match reason {
            Some(reason) => {
                log::warn!("vpn_netd: profile {label} skipped, other profiles keep working: {reason}");
                rejected.push(label);
            }
            None => {
                netids.insert(profile.netid);
                tuns.insert(profile.tun.clone(), label.clone());
                cidrs.push((label.clone(), profile.cidr.clone()));
                for r in &ranges {
                    uid_ranges.push((label.clone(), r.clone()));
                }
                kept.push((profile, ranges));
            }
        }
    }

    (kept, rejected)
}
