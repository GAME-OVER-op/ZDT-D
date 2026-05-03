use anyhow::Result;
use serde::Serialize;
use std::{collections::HashMap, fs};

use crate::shell;

/// Aggregated resource usage for a group of processes.
#[derive(Debug, Clone, Copy, Serialize, Default)]
pub struct UsageAgg {
    pub count: u32,
    pub cpu_percent: f32,
    pub rss_mb: f32,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct OperaAgg {
    pub opera: UsageAgg,
    pub t2s: UsageAgg,
    pub byedpi: UsageAgg,
}

/// Slim status report:
/// - only counts + CPU + RSS (MB)
/// - always includes zdtd and per-service aggregates
#[derive(Debug, Clone, Serialize, Default)]
pub struct StatusReport {
    pub zdtd: UsageAgg,
    pub zapret: UsageAgg,   // nfqws
    pub zapret2: UsageAgg,  // nfqws2
    pub byedpi: UsageAgg,   // non-opera byedpi
    pub dnscrypt: UsageAgg,
    pub dpitunnel: UsageAgg,
    pub sing_box: UsageAgg,
    pub wireproxy: UsageAgg,
    pub myproxy: UsageAgg,
    pub myprogram: UsageAgg,
    pub openvpn: UsageAgg,
    pub tun2socks: UsageAgg,
    pub mihomo: UsageAgg,
    pub tor: UsageAgg,
    pub t2s: UsageAgg,       // t2s used by opera-proxy, sing-box, wireproxy and tor
    pub opera: OperaAgg,    // opera-proxy + t2s + operaproxy-byedpi
}

// Compatibility alias used by daemon.rs
pub type Report = StatusReport;

/// Backward-compatible entrypoint (daemon passes a flag; we ignore it and detect running processes).
pub fn collect_report(_services_running: bool) -> Result<Report> {
    collect_status()
}


pub(crate) fn protected_pids() -> Vec<u32> {
    let mut pids = Vec::new();
    pids.push(std::process::id());
    pids.extend(pidof("dnscrypt"));
    pids.extend(pidof("nfqws"));
    pids.extend(pidof("nfqws2"));
    pids.extend(pidof_any(&["DPITunnel-cli", "dpitunnel-cli"]));
    pids.extend(pidof("t2s"));
    pids.extend(pidof("opera-proxy"));
    pids.extend(singbox_pids());
    pids.extend(wireproxy_pids());
    pids.extend(myproxy_t2s_pids());
    pids.extend(myprogram_main_pids());
    pids.extend(myprogram_t2s_pids());
    pids.extend(openvpn_pids());
    pids.extend(tun2socks_pids());
    pids.extend(mihomo_pids());
    pids.extend(tor_pids());
    pids.extend(pidof("byedpi"));
    pids.sort_unstable();
    pids.dedup();
    pids
}

/// Collect current process usage.
pub fn collect_status() -> Result<StatusReport> {
    let self_pid = std::process::id();
    let self_map = ps_stats(&[self_pid]);
    let (self_cpu, self_rss_kb) = self_map.get(&self_pid).cloned().unwrap_or((0.0, 0));
    let zdtd = UsageAgg {
        count: 1,
        cpu_percent: self_cpu,
        rss_mb: (self_rss_kb as f32) / 1024.0,
    };

    let op_byedpi_port = operaproxy_byedpi_port();

    // Gather pids (best-effort; empty on errors)
    // Use `pidof` instead of `pgrep -f` to avoid matching similarly-named processes.
    // This matters on Android where some apps/binaries can have overlapping names.
    let dnscrypt_pids = pidof("dnscrypt");
    let nfqws_pids = pidof("nfqws");
    let nfqws2_pids = pidof("nfqws2");
    // dpitunnel-cli may present a different process name (e.g. "DPITunnel-cli")
    // depending on the build. Collect both variants.
    let dpitunnel_pids = pidof_any(&["DPITunnel-cli", "dpitunnel-cli"]);
    // t2s is used by multiple programs (opera-proxy and sing-box).
    // We expose a global `t2s` aggregate for UI, and keep `opera.t2s` as a legacy bucket
    // (t2s processes without "--web-port" in cmdline).
    let mut t2s_all_pids = pidof("t2s");
    t2s_all_pids.sort_unstable();
    t2s_all_pids.dedup();

    let mut t2s_pids = Vec::new();
    for pid in &t2s_all_pids {
        let cmd = read_cmdline(*pid);
        if cmd.contains("--web-port") {
            // sing-box t2s; keep it only in global bucket.
            continue;
        }
        t2s_pids.push(*pid);
    }
    let opera_proxy_pids = pidof("opera-proxy");
    let singbox_pids = singbox_pids();
    let wireproxy_pids = wireproxy_pids();
    let myproxy_pids = myproxy_t2s_pids();
    let myprogram_pids = myprogram_main_pids();
    let openvpn_pids = openvpn_pids();
    let tun2socks_pids = tun2socks_pids();
    let mihomo_pids = mihomo_pids();
    let tor_pids = tor_pids();

    let mut byedpi_all = pidof("byedpi");
    byedpi_all.sort_unstable();
    byedpi_all.dedup();

    let mut operaproxy_byedpi = Vec::new();
    let mut byedpi_pids = Vec::new();
    for pid in byedpi_all {
        let cmd = read_cmdline(pid);
        if cmd.contains(&format!("-p {op_byedpi_port}")) {
            operaproxy_byedpi.push(pid);
        } else {
            byedpi_pids.push(pid);
        }
    }

    Ok(StatusReport {
        zdtd,
        zapret: agg(&nfqws_pids),
        zapret2: agg(&nfqws2_pids),
        byedpi: agg(&byedpi_pids),
        dnscrypt: agg(&dnscrypt_pids),
        dpitunnel: agg(&dpitunnel_pids),
        sing_box: agg(&singbox_pids),
        wireproxy: agg(&wireproxy_pids),
        myproxy: agg(&myproxy_pids),
        myprogram: agg(&myprogram_pids),
        openvpn: agg(&openvpn_pids),
        tun2socks: agg(&tun2socks_pids),
        mihomo: agg(&mihomo_pids),
        tor: agg(&tor_pids),
        t2s: agg(&t2s_all_pids),
        opera: OperaAgg {
            opera: agg(&opera_proxy_pids),
            t2s: agg(&t2s_pids),
            byedpi: agg(&operaproxy_byedpi),
        },
    })
}

fn read_cmdline(pid: u32) -> String {
    let path = format!("/proc/{pid}/cmdline");
    match fs::read(&path) {
        Ok(bytes) => {
            let mut s = String::new();
            for &b in &bytes {
                if b == 0 {
                    s.push(' ');
                } else {
                    s.push(b as char);
                }
            }
            s.trim().to_string()
        }
        Err(_) => String::new(),
    }
}

fn pidof(name: &str) -> Vec<u32> {
    let (rc, out) = match shell::run_quiet("pidof", &[name], shell::Capture::Stdout) {
        Ok(v) => v,
        Err(_) => return vec![],
    };
    if rc != 0 {
        return vec![];
    }
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<u32>().ok())
        .collect()
}

fn pidof_any(names: &[&str]) -> Vec<u32> {
    let mut all: Vec<u32> = Vec::new();
    for &n in names {
        all.extend(pidof(n));
    }
    all.sort_unstable();
    all.dedup();
    all
}
/// Best-effort detection of sing-box pids.
/// Some Android builds may not report the binary name consistently for `pidof`,
/// so we fall back to parsing `ps -A` output.

fn wireproxy_pids() -> Vec<u32> {
    let mut pids = pidof_any(&["wireproxy"]);
    if !pids.is_empty() {
        return pids;
    }

    if let Ok(out) = shell::capture_quiet(
        "sh -c \"pgrep -f 'wireproxy -c /data/adb/modules/ZDT-D/working_folder/wireproxy/' 2>/dev/null || true\"",
    ) {
        for tok in out.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                pids.push(pid);
            }
        }
        pids.sort_unstable();
        pids.dedup();
        if !pids.is_empty() {
            return pids;
        }
    }

    let out = shell::capture_quiet("ps -A").unwrap_or_default();
    for line in out.lines() {
        if !(line.contains("wireproxy")
            || line.contains("/bin/wireproxy")
            || line.contains("working_folder/wireproxy"))
        {
            continue;
        }
        for tok in line.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                pids.push(pid);
                break;
            }
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}



fn myproxy_t2s_pids() -> Vec<u32> {
    let mut ports = std::collections::BTreeSet::new();
    let root = std::path::Path::new("/data/adb/modules/ZDT-D/working_folder/myproxy/profile");
    if let Ok(rd) = std::fs::read_dir(root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
            let setting_path = profile_dir.join("setting.json");
            let Ok(raw) = std::fs::read_to_string(&setting_path) else { continue; };
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue; };
            let Some(port) = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) else { continue; };
            if port != 0 { ports.insert(port); }
        }
    }
    if ports.is_empty() { return Vec::new(); }

    let mut matched = Vec::new();
    let mut all_t2s = pidof("t2s");
    all_t2s.sort_unstable();
    all_t2s.dedup();
    for pid in all_t2s {
        let cmd = read_cmdline(pid);
        if cmd.is_empty() { continue; }
        for port in &ports {
            if cmd.contains(&format!("--listen-port {}", port)) {
                matched.push(pid);
                break;
            }
        }
    }
    matched.sort_unstable();
    matched.dedup();
    matched
}


fn myprogram_main_pids() -> Vec<u32> {
    let mut pids = Vec::new();
    let root = std::path::Path::new("/data/adb/modules/ZDT-D/working_folder/myprogram/profile");
    if let Ok(rd) = std::fs::read_dir(root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            let runtime_path = profile_dir.join("runtime.json");
            let Ok(raw) = std::fs::read_to_string(&runtime_path) else { continue; };
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue; };
            let Some(pid) = v.get("pid").and_then(|x| x.as_u64()).and_then(|x| u32::try_from(x).ok()) else { continue; };
            if pid == 0 { continue; }
            if std::path::Path::new("/proc").join(pid.to_string()).is_dir() { pids.push(pid); }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn myprogram_t2s_pids() -> Vec<u32> {
    let mut ports = std::collections::BTreeSet::new();
    let root = std::path::Path::new("/data/adb/modules/ZDT-D/working_folder/myprogram/profile");
    if let Ok(rd) = std::fs::read_dir(root) {
        for ent in rd.flatten() {
            let profile_dir = ent.path();
            if !profile_dir.is_dir() { continue; }
            if profile_dir.file_name().and_then(|s| s.to_str()).map(|s| s.starts_with('.')).unwrap_or(false) { continue; }
            let setting_path = profile_dir.join("setting.json");
            let Ok(raw) = std::fs::read_to_string(&setting_path) else { continue; };
            let Ok(v) = serde_json::from_str::<serde_json::Value>(&raw) else { continue; };
            let apps_mode = v.get("apps_mode").and_then(|x| x.as_bool()).unwrap_or(false);
            let route_mode = v.get("route_mode").and_then(|x| x.as_str()).unwrap_or("t2s");
            if !apps_mode || route_mode != "t2s" { continue; }
            let Some(port) = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok()) else { continue; };
            if port != 0 { ports.insert(port); }
        }
    }
    if ports.is_empty() { return Vec::new(); }

    let mut matched = Vec::new();
    let mut all_t2s = pidof("t2s");
    all_t2s.sort_unstable();
    all_t2s.dedup();
    for pid in all_t2s {
        let cmd = read_cmdline(pid);
        if cmd.is_empty() { continue; }
        for port in &ports {
            if cmd.contains(&format!("--listen-port {}", port)) {
                matched.push(pid);
                break;
            }
        }
    }
    matched.sort_unstable();
    matched.dedup();
    matched
}


fn openvpn_pids() -> Vec<u32> {
    crate::programs::openvpn::main_pids_exact()
        .into_iter()
        .filter_map(|p| u32::try_from(p).ok())
        .collect()
}

fn mihomo_pids() -> Vec<u32> {
    crate::programs::mihomo::main_pids_exact()
        .into_iter()
        .filter_map(|p| u32::try_from(p).ok())
        .collect()
}

fn tun2socks_pids() -> Vec<u32> {
    crate::programs::tun2socks::main_pids_exact()
        .into_iter()
        .filter_map(|p| u32::try_from(p).ok())
        .collect()
}

const TOR_TORRC_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/tor/torrc";
const LYREBIRD_BIN: &str = "/data/adb/modules/ZDT-D/bin/lyrebird";

fn parse_pids(out: &str) -> Vec<u32> {
    out.split_whitespace()
        .filter_map(|tok| tok.parse::<u32>().ok())
        .collect()
}

fn tor_main_pids() -> Vec<u32> {
    let mut pids = Vec::new();
    let pgrep_cmd = format!(
        r#"sh -c "pgrep -f '^torproxy -f {}$' 2>/dev/null || true""#,
        TOR_TORRC_PATH
    );
    if let Ok(out) = shell::capture_quiet(&pgrep_cmd) {
        pids.extend(parse_pids(&out));
    }
    if pids.is_empty() {
        let ps_cmd = format!(
            r#"sh -c "ps -ef 2>/dev/null | grep -F 'torproxy -f {}' | grep -v grep || true""#,
            TOR_TORRC_PATH
        );
        if let Ok(out) = shell::capture_quiet(&ps_cmd) {
            for line in out.lines() {
                for tok in line.split_whitespace() {
                    if let Ok(pid) = tok.parse::<u32>() {
                        pids.push(pid);
                        break;
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn tor_helper_pids() -> Vec<u32> {
    let mut pids = Vec::new();
    let pgrep_cmd = format!(
        r#"sh -c "pgrep -f '^{}' 2>/dev/null || true""#,
        LYREBIRD_BIN
    );
    if let Ok(out) = shell::capture_quiet(&pgrep_cmd) {
        pids.extend(parse_pids(&out));
    }
    if pids.is_empty() {
        let ps_cmd = format!(
            r#"sh -c "ps -ef 2>/dev/null | grep -F '{}' | grep -v grep || true""#,
            LYREBIRD_BIN
        );
        if let Ok(out) = shell::capture_quiet(&ps_cmd) {
            for line in out.lines() {
                for tok in line.split_whitespace() {
                    if let Ok(pid) = tok.parse::<u32>() {
                        pids.push(pid);
                        break;
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn tor_pids() -> Vec<u32> {
    // IMPORTANT: do not match plain substring "tor" here.
    // Some Android kernels/services contain "tor" in unrelated names
    // (torture_task, storaged, keystore2, regulator-*, etc.), which causes
    // false "running" state after Tor is already stopped.
    // Tor is considered running ONLY when the exact main command
    // `torproxy -f <our torrc>` exists. lyrebird is auxiliary and is included
    // only when the main torproxy process is present.
    let main = tor_main_pids();
    if main.is_empty() {
        return main;
    }
    let mut pids = main;
    pids.extend(tor_helper_pids());
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn singbox_pids() -> Vec<u32> {
    let mut pids = pidof_any(&["sing-box", "singbox"]);
    if !pids.is_empty() {
        return pids;
    }

    // Fallback #1: use pgrep with a very specific cmdline pattern.
    // We intentionally avoid a generic `pgrep sing` to prevent false positives.
    // The working_folder path is unique to this module.
    if let Ok(out) = shell::capture_quiet(
        "sh -c \"pgrep -f 'sing-box run -c /data/adb/modules/ZDT-D/working_folder/singbox/' 2>/dev/null || true\"",
    ) {
        for tok in out.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                pids.push(pid);
            }
        }
        pids.sort_unstable();
        pids.dedup();
        if !pids.is_empty() {
            return pids;
        }
    }

    let out = shell::capture_quiet("ps -A").unwrap_or_default();
    for line in out.lines() {
        if !(line.contains("sing-box")
            || line.contains("/bin/sing-box")
            || line.contains("working_folder/singbox"))
        {
            continue;
        }
        for tok in line.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                // First numeric token on Android `ps` output is typically PID.
                pids.push(pid);
                break;
            }
        }
    }

    pids.sort_unstable();
    pids.dedup();
    pids
}

fn ps_stats(pids: &[u32]) -> HashMap<u32, (f32, u64)> {
    if pids.is_empty() {
        return HashMap::new();
    }
    let list = pids
        .iter()
        .map(|p| p.to_string())
        .collect::<Vec<_>>()
        .join(",");
    let cmd = format!("ps -o pid,%cpu,rss -p {list}");
    let out = match shell::capture_quiet(&cmd) {
        Ok(s) => s,
        Err(_) => return HashMap::new(),
    };
    let mut map = HashMap::new();
    for line in out.lines().skip(1) {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() < 3 {
            continue;
        }
        let pid: u32 = match parts[0].parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        let cpu: f32 = parts[1].parse().unwrap_or(0.0);
        let rss_kb: u64 = parts[2].parse().unwrap_or(0);
        map.insert(pid, (cpu, rss_kb));
    }
    map
}

fn agg(pids: &[u32]) -> UsageAgg {
    if pids.is_empty() {
        return UsageAgg::default();
    }
    let map = ps_stats(pids);
    let mut total_cpu = 0.0f32;
    let mut total_rss_kb = 0u64;
    for (_pid, (cpu, rss_kb)) in map.iter() {
        total_cpu += *cpu;
        total_rss_kb += *rss_kb;
    }
    UsageAgg {
        count: map.len() as u32,
        cpu_percent: total_cpu,
        rss_mb: (total_rss_kb as f32) / 1024.0,
    }
}

fn operaproxy_byedpi_port() -> u16 {
    // Best effort: reuse current port.json, fallback to 10190.
    let p = "/data/adb/modules/ZDT-D/working_folder/operaproxy/port.json";
    let txt = match fs::read_to_string(p) {
        Ok(t) => t,
        Err(_) => return 10190,
    };
    let v: serde_json::Value = match serde_json::from_str(&txt) {
        Ok(v) => v,
        Err(_) => return 10190,
    };
    v.get("byedpi_port")
        .and_then(|x| x.as_u64())
        .and_then(|x| u16::try_from(x).ok())
        .unwrap_or(10190)
}