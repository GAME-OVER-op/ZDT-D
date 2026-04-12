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


fn tor_pids() -> Vec<u32> {
    let mut pids = pidof_any(&["tor", "obfs4proxy"]);
    if !pids.is_empty() {
        return pids;
    }

    if let Ok(out) = shell::capture_quiet(
        "sh -c \"pgrep -f 'tor -f /data/adb/modules/ZDT-D/working_folder/tor/torrc' 2>/dev/null || true\"",
    ) {
        for tok in out.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                pids.push(pid);
            }
        }
    }
    if let Ok(out) = shell::capture_quiet(
        "sh -c \"pgrep -f '/data/adb/modules/ZDT-D/bin/obfs4proxy' 2>/dev/null || true\"",
    ) {
        for tok in out.split_whitespace() {
            if let Ok(pid) = tok.parse::<u32>() {
                pids.push(pid);
            }
        }
    }

    let out = shell::capture_quiet("ps -A").unwrap_or_default();
    for line in out.lines() {
        if !(line.contains(" tor")
            || line.contains("/bin/tor")
            || line.contains("working_folder/tor/torrc")
            || line.contains("obfs4proxy"))
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