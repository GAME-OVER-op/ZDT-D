use serde::Serialize;
use std::fs;

#[derive(Clone, Debug, Default, Serialize)]
pub struct SystemStats {
    pub cpu_percent: f64,
    pub mem_total_kb: u64,
    pub mem_avail_kb: u64,
    pub mem_used_percent: f64,
    pub proc_rss_kb: u64,
    pub net_rx_bytes: u64,
    pub net_tx_bytes: u64,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct CpuSample {
    pub total: u64,
    pub idle: u64,
}

/// Collect system stats from /proc (works well on Android/Termux).
/// Returns (stats, new_cpu_sample).
pub fn collect(prev_cpu: Option<CpuSample>) -> (SystemStats, CpuSample) {
    let (total, idle) = read_proc_stat();
    let new_sample = CpuSample { total, idle };

    let cpu_percent = if let Some(prev) = prev_cpu {
        let dt = new_sample.total.saturating_sub(prev.total);
        let di = new_sample.idle.saturating_sub(prev.idle);
        if dt == 0 { 0.0 } else { ((dt - di) as f64) * 100.0 / (dt as f64) }
    } else {
        0.0
    };

    let (mem_total_kb, mem_avail_kb) = read_meminfo();
    let mem_used_percent = if mem_total_kb == 0 {
        0.0
    } else {
        let used = mem_total_kb.saturating_sub(mem_avail_kb);
        (used as f64) * 100.0 / (mem_total_kb as f64)
    };

    let proc_rss_kb = read_self_rss_kb();
    let (net_rx_bytes, net_tx_bytes) = read_net_dev();

    (
        SystemStats {
            cpu_percent,
            mem_total_kb,
            mem_avail_kb,
            mem_used_percent,
            proc_rss_kb,
            net_rx_bytes,
            net_tx_bytes,
        },
        new_sample,
    )
}

fn read_proc_stat() -> (u64, u64) {
    // /proc/stat: "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
    let s = fs::read_to_string("/proc/stat").unwrap_or_default();
    for line in s.lines() {
        if let Some(rest) = line.strip_prefix("cpu ") {
            let parts: Vec<u64> = rest
                .split_whitespace()
                .filter_map(|x| x.parse::<u64>().ok())
                .collect();
            if parts.len() >= 4 {
                let user = parts[0];
                let nice = parts[1];
                let system = parts[2];
                let idle = parts[3];
                let iowait = parts.get(4).copied().unwrap_or(0);
                let irq = parts.get(5).copied().unwrap_or(0);
                let softirq = parts.get(6).copied().unwrap_or(0);
                let steal = parts.get(7).copied().unwrap_or(0);
                let total = user + nice + system + idle + iowait + irq + softirq + steal;
                let idle_all = idle + iowait;
                return (total, idle_all);
            }
        }
    }
    (0, 0)
}

fn read_meminfo() -> (u64, u64) {
    let s = fs::read_to_string("/proc/meminfo").unwrap_or_default();
    let mut total = 0u64;
    let mut avail = 0u64;
    let mut free = 0u64;
    let mut buffers = 0u64;
    let mut cached = 0u64;

    for line in s.lines() {
        if let Some(v) = parse_kb(line, "MemTotal:") { total = v; }
        if let Some(v) = parse_kb(line, "MemAvailable:") { avail = v; }
        if let Some(v) = parse_kb(line, "MemFree:") { free = v; }
        if let Some(v) = parse_kb(line, "Buffers:") { buffers = v; }
        if let Some(v) = parse_kb(line, "Cached:") { cached = v; }
    }

    if avail == 0 {
        // Fallback for older kernels: MemFree+Buffers+Cached (rough)
        avail = free + buffers + cached;
    }
    (total, avail)
}

fn parse_kb(line: &str, key: &str) -> Option<u64> {
    let rest = line.strip_prefix(key)?;
    let num = rest.trim().split_whitespace().next()?;
    num.parse::<u64>().ok()
}

fn read_self_rss_kb() -> u64 {
    // /proc/self/status: "VmRSS:  12345 kB"
    let s = fs::read_to_string("/proc/self/status").unwrap_or_default();
    for line in s.lines() {
        if let Some(v) = parse_kb(line, "VmRSS:") {
            return v;
        }
    }
    0
}

fn read_net_dev() -> (u64, u64) {
    // /proc/net/dev: per iface counters; sum all except lo.
    let s = fs::read_to_string("/proc/net/dev").unwrap_or_default();
    let mut rx = 0u64;
    let mut tx = 0u64;
    for line in s.lines().skip(2) {
        // "wlan0: 123 0 0 0 ... 456 0 0 0 ..."
        let line = line.trim();
        let mut parts = line.split(':');
        let iface = parts.next().unwrap_or("").trim();
        let data = parts.next().unwrap_or("");
        if iface.is_empty() || iface == "lo" { continue; }
        let cols: Vec<u64> = data.split_whitespace().filter_map(|x| x.parse().ok()).collect();
        if cols.len() >= 16 {
            rx += cols[0];
            tx += cols[8];
        }
    }
    (rx, tx)
}
