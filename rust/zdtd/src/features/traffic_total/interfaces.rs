use std::{collections::HashMap, fs};
use serde::Deserialize;
use super::{backends::resolve_vpn_proxy_endpoint, model::{InterfaceTraffic, TrafficBackendPort, VpnApp, VpnTraffic}};

const PROC_NET_DEV: &str = "/proc/net/dev";
const VPN_NETD_APPLIED: &str = "/data/adb/modules/ZDT-D/working_folder/vpn_netd/applied.json";
#[derive(Debug, Clone, Deserialize, Default)]
struct AppliedSnapshot {
    #[serde(default)]
    profiles: Vec<AppliedProfile>,
}

#[derive(Debug, Clone, Deserialize, Default)]
struct AppliedProfile {
    owner_program: String,
    profile: String,
    netid: u32,
    tun: String,
    #[serde(default)]
    uid_ranges: Vec<String>,
}
pub(super) fn read_interfaces(warnings: &mut Vec<String>) -> HashMap<String, InterfaceTraffic> {
    let raw = match fs::read_to_string(PROC_NET_DEV) {
        Ok(s) => s,
        Err(e) => {
            warnings.push(format!("read {PROC_NET_DEV} failed: {e}"));
            return HashMap::new();
        }
    };
    let mut out = HashMap::new();
    for line in raw.lines() {
        let Some((iface, rest)) = line.split_once(':') else { continue; };
        let vals: Vec<&str> = rest.split_whitespace().collect();
        if vals.len() < 16 {
            continue;
        }
        let rx_bytes = vals[0].parse::<u64>().unwrap_or(0);
        let rx_packets = vals[1].parse::<u64>().unwrap_or(0);
        let tx_bytes = vals[8].parse::<u64>().unwrap_or(0);
        let tx_packets = vals[9].parse::<u64>().unwrap_or(0);
        let iface = iface.trim().to_string();
        out.insert(iface.clone(), InterfaceTraffic {
            iface,
            rx_bytes,
            rx_packets,
            tx_bytes,
            tx_packets,
            total_bytes: rx_bytes.saturating_add(tx_bytes),
        });
    }
    out
}

pub(super) fn read_vpn_traffic(
    interfaces: &HashMap<String, InterfaceTraffic>,
    uid_packages: &HashMap<u32, Vec<String>>,
    uid_file_packages: &HashMap<String, HashMap<u32, Vec<String>>>,
    local_registry: &HashMap<u16, TrafficBackendPort>,
    warnings: &mut Vec<String>,
) -> Vec<VpnTraffic> {
    let raw = match fs::read(VPN_NETD_APPLIED) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Vec::new(),
        Err(e) => {
            warnings.push(format!("read vpn_netd applied snapshot failed: {e}"));
            return Vec::new();
        }
    };
    if crate::runtime::sanitize::json_bytes_are_corrupt(&raw) {
        let _ = fs::remove_file(VPN_NETD_APPLIED);
        log::warn!("traffic_total: removed corrupt vpn_netd applied snapshot {}", VPN_NETD_APPLIED);
        return Vec::new();
    }
    let snapshot = match serde_json::from_slice::<AppliedSnapshot>(&raw) {
        Ok(v) => v,
        Err(e) => {
            warnings.push(format!("parse vpn_netd applied snapshot failed: {e}"));
            return Vec::new();
        }
    };

    snapshot.profiles.into_iter().map(|p| {
        let iface = interfaces.get(&p.tun).cloned().unwrap_or_else(|| InterfaceTraffic { iface: p.tun.clone(), ..Default::default() });
        let proxy_endpoint = resolve_vpn_proxy_endpoint(&p.owner_program, &p.profile, local_registry);
        let apps = expand_uid_ranges(&p.uid_ranges)
            .into_iter()
            .map(|uid| {
                let pkgs = uid_packages.get(&uid)
                    .cloned()
                    .or_else(|| uid_file_packages.values().find_map(|m| m.get(&uid).cloned()))
                    .unwrap_or_default();
                VpnApp { uid, package: pkgs.first().cloned(), packages: pkgs }
            })
            .collect();
        VpnTraffic {
            owner_program: p.owner_program,
            profile: p.profile,
            netid: p.netid,
            tun: p.tun,
            rx_bytes: iface.rx_bytes,
            rx_packets: iface.rx_packets,
            tx_bytes: iface.tx_bytes,
            tx_packets: iface.tx_packets,
            total_bytes: iface.total_bytes,
            proxy_endpoint,
            uid_ranges: p.uid_ranges,
            apps,
        }
    }).collect()
}
fn expand_uid_ranges(ranges: &[String]) -> Vec<u32> {
    let mut out = Vec::new();
    for r in ranges {
        if let Some((a, b)) = r.split_once('-') {
            let Ok(start) = a.trim().parse::<u32>() else { continue; };
            let Ok(end) = b.trim().parse::<u32>() else { continue; };
            if start == 0 || end < start || end.saturating_sub(start) > 1024 {
                continue;
            }
            for uid in start..=end {
                out.push(uid);
            }
        } else if let Ok(uid) = r.trim().parse::<u32>() {
            if uid > 0 {
                out.push(uid);
            }
        }
    }
    out.sort_unstable();
    out.dedup();
    out
}
