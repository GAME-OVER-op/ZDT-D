use anyhow::Result;
use serde::Serialize;
use std::{collections::HashMap, fs, sync::{Mutex, OnceLock}};

use crate::common::process::{
    pidof_any_u32 as pidof_any, pidof_u32 as pidof, process_pid_is_live,
    read_process_cmdline_u32 as read_cmdline,
};
use crate::programs::registry::{self as program, ProgramId};

fn pidof_program(id: ProgramId) -> Vec<u32> {
    id.process_name().map(pidof).unwrap_or_default()
}

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
    /// Unique-process total usage. This avoids double-counting helper processes that
    /// are intentionally exposed in more than one compatibility bucket.
    pub total: UsageAgg,
    pub zdtd: UsageAgg,
    pub zapret: UsageAgg,   // nfqws
    pub zapret2: UsageAgg,  // nfqws2
    pub byedpi: UsageAgg,   // non-opera byedpi
    pub dnscrypt: UsageAgg,
    pub d2s: UsageAgg,
    pub dpitunnel: UsageAgg,
    pub sing_box: UsageAgg,
    pub sing_box_lx: UsageAgg,
    pub hysteria2: UsageAgg,
    pub wireproxy: UsageAgg,
    pub myproxy: UsageAgg,
    pub myprogram: UsageAgg,
    pub openvpn: UsageAgg,
    pub amneziawg: UsageAgg,
    // Internal-only compatibility bucket used by runtime adoption checks.
    // Do not expose it through /api/status: UI should use `tun2proxy`.
    #[serde(skip_serializing)]
    pub tun2socks: UsageAgg,
    pub mihomo: UsageAgg,
    pub mieru: UsageAgg,
    pub tgwsproxy: UsageAgg,
    pub tun2proxy: UsageAgg, // combined tun2socks/tun2proxy helper processes
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
    pids.extend(pidof_program(ProgramId::DnsCrypt));
    pids.extend(pidof("d2s"));
    pids.extend(pidof_program(ProgramId::Nfqws));
    pids.extend(pidof_program(ProgramId::Nfqws2));
    pids.extend(pidof_any(&["DPITunnel-cli", "dpitunnel-cli"]));
    pids.extend(pidof_any(crate::network::t2s::PROCESS_NAMES));
    pids.extend(pidof_any(crate::programs::operaproxy::PROCESS_NAMES));
    pids.extend(singbox_pids());
    pids.extend(singbox_lx_pids());
    pids.extend(hysteria2_pids());
    pids.extend(wireproxy_pids());
    pids.extend(pidof_program(ProgramId::TgwsProxy));
    pids.extend(myproxy_t2s_pids());
    pids.extend(myprogram_main_pids());
    pids.extend(myprogram_t2s_pids());
    pids.extend(openvpn_pids());
    pids.extend(amneziawg_pids());
    pids.extend(tun2socks_pids());
    pids.extend(mihomo_pids());
    pids.extend(mieru_pids());
    pids.extend(mieru_tun2proxy_pids());
    pids.extend(tor_pids());
    pids.extend(pidof_any(crate::programs::byedpi::PROCESS_NAMES));
    pids.sort_unstable();
    pids.dedup();
    pids
}

/// Collect current process usage for every program.
pub fn collect_status() -> Result<StatusReport> {
    collect_status_inner(None)
}

/// Collect current process usage for the listed programs only.
///
/// PID discovery may invoke `pidof`, `pgrep -f`, or one `ps -ef` scan depending on the backend.
/// Probing all engines when only a few are enabled is therefore one of the more expensive parts
/// of startup. Real program ids accept their registry canonical/API spellings;
/// helper buckets remain the explicit compatibility ids `d2s`, `t2s`, and `tun2proxy`.
///
/// IMPORTANT: buckets that were not requested come back empty, which is indistinguishable from
/// "not running". Only use this where the caller knows exactly which buckets it reads. Never use
/// it for /api/status: the UI needs CPU and memory for every program, including ones that are
/// running while disabled.
pub fn collect_status_for(programs: &[&str]) -> Result<StatusReport> {
    collect_status_inner(Some(programs))
}

fn collect_status_inner(wanted: Option<&[&str]>) -> Result<StatusReport> {
    // None means "everything", which keeps collect_status() behavior unchanged.
    // Real program ids are resolved through the registry so API ids and canonical/storage ids
    // (notably sing-box/singbox) select the same bucket. Helper buckets stay explicit strings.
    let want_program = |id: ProgramId| {
        wanted.map_or(true, |list| list.iter().any(|raw| ProgramId::parse(raw) == Some(id)))
    };
    let want_helper = |id: &str| wanted.map_or(true, |list| list.contains(&id));
    let self_pid = std::process::id();
    let op_byedpi_port = operaproxy_byedpi_port();

    // Gather pids (best-effort; empty on errors).
    // Use `pidof` instead of `pgrep -f` where possible to avoid matching similarly-named processes.
    // This matters on Android where some apps/binaries can have overlapping names.
    let dnscrypt_pids = if want_program(ProgramId::DnsCrypt) { pidof_program(ProgramId::DnsCrypt) } else { Vec::new() };
    let d2s_pids = if want_helper("d2s") { pidof("d2s") } else { Vec::new() };
    let nfqws_pids = if want_program(ProgramId::Nfqws) { pidof_program(ProgramId::Nfqws) } else { Vec::new() };
    let nfqws2_pids = if want_program(ProgramId::Nfqws2) { pidof_program(ProgramId::Nfqws2) } else { Vec::new() };
    // dpitunnel-cli may present a different process name (e.g. "DPITunnel-cli")
    // depending on the build. Collect both variants.
    let dpitunnel_pids = if want_program(ProgramId::DpiTunnel) { pidof_any(&["DPITunnel-cli", "dpitunnel-cli"]) } else { Vec::new() };
    // t2s is used by multiple programs (opera-proxy and sing-box).
    // We expose a global `t2s` aggregate for UI, and keep `opera.t2s` as a legacy bucket
    // (t2s processes without "--web-port" in cmdline).
    // opera.t2s is derived from this group, so it is also needed for the operaproxy view.
    let mut t2s_all_pids = if want_helper("t2s") || want_program(ProgramId::OperaProxy) { pidof_any(crate::network::t2s::PROCESS_NAMES) } else { Vec::new() };
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
    let opera_proxy_pids = if want_program(ProgramId::OperaProxy) { pidof_any(crate::programs::operaproxy::PROCESS_NAMES) } else { Vec::new() };
    let singbox_pids = if want_program(ProgramId::SingBox) { singbox_pids() } else { Vec::new() };
    let singbox_lx_pids = if want_program(ProgramId::SingBoxLx) { singbox_lx_pids() } else { Vec::new() };
    let hysteria2_pids = if want_program(ProgramId::Hysteria2) { hysteria2_pids() } else { Vec::new() };
    let wireproxy_pids = if want_program(ProgramId::WireProxy) { wireproxy_pids() } else { Vec::new() };
    let myproxy_pids = if want_program(ProgramId::MyProxy) { myproxy_t2s_pids() } else { Vec::new() };
    let myprogram_pids = if want_program(ProgramId::MyProgram) { myprogram_main_pids() } else { Vec::new() };
    let openvpn_pids = if want_program(ProgramId::OpenVpn) { openvpn_pids() } else { Vec::new() };
    let amneziawg_pids = if want_program(ProgramId::AmneziaWg) { amneziawg_pids() } else { Vec::new() };
    // The tun2proxy aggregate is built from this group, so it is needed for either id.
    let tun2socks_pids = if want_program(ProgramId::Tun2Socks) || want_helper("tun2proxy") { tun2socks_pids() } else { Vec::new() };
    let mihomo_pids = if want_program(ProgramId::Mihomo) { mihomo_pids() } else { Vec::new() };
    let mihomo_tun2socks_pids = if want_helper("tun2proxy") { mihomo_tun2socks_pids() } else { Vec::new() };
    let mieru_pids = if want_program(ProgramId::Mieru) { mieru_pids() } else { Vec::new() };
    let tgwsproxy_pids = if want_program(ProgramId::TgwsProxy) { pidof_program(ProgramId::TgwsProxy) } else { Vec::new() };
    let mieru_tun2proxy_pids = if want_helper("tun2proxy") { mieru_tun2proxy_pids() } else { Vec::new() };
    let mut tun2proxy_pids = Vec::new();
    tun2proxy_pids.extend(tun2socks_pids.iter().copied());
    tun2proxy_pids.extend(mihomo_tun2socks_pids.iter().copied());
    tun2proxy_pids.extend(mieru_tun2proxy_pids.iter().copied());
    tun2proxy_pids.sort_unstable();
    tun2proxy_pids.dedup();
    let tor_pids = if want_program(ProgramId::Tor) { tor_pids() } else { Vec::new() };

    // opera.byedpi is split out of this group, so it is needed for the operaproxy view too.
    let mut byedpi_all = if want_program(ProgramId::ByeDpi) || want_program(ProgramId::OperaProxy) { pidof_any(crate::programs::byedpi::PROCESS_NAMES) } else { Vec::new() };
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

    // Sample all relevant processes once. This is important for realtime CPU:
    // the value is calculated from /proc deltas between status refreshes, so
    // sampling per bucket would update the cache multiple times and skew the result.
    let mut all_pids = Vec::new();
    all_pids.push(self_pid);
    for group in [
        &nfqws_pids,
        &nfqws2_pids,
        &byedpi_pids,
        &dnscrypt_pids,
        &d2s_pids,
        &dpitunnel_pids,
        &singbox_pids,
        &singbox_lx_pids,
        &hysteria2_pids,
        &wireproxy_pids,
        &myproxy_pids,
        &myprogram_pids,
        &openvpn_pids,
        &amneziawg_pids,
        &tun2socks_pids,
        &mihomo_pids,
        &mieru_pids,
        &tgwsproxy_pids,
        &tun2proxy_pids,
        &tor_pids,
        &t2s_all_pids,
        &opera_proxy_pids,
        &t2s_pids,
        &operaproxy_byedpi,
    ] {
        all_pids.extend(group.iter().copied());
    }
    all_pids.sort_unstable();
    all_pids.dedup();
    let usage = proc_stats_realtime(&all_pids);

    Ok(StatusReport {
        total: agg_from_map(&all_pids, &usage),
        zdtd: agg_from_map(&[self_pid], &usage),
        zapret: agg_from_map(&nfqws_pids, &usage),
        zapret2: agg_from_map(&nfqws2_pids, &usage),
        byedpi: agg_from_map(&byedpi_pids, &usage),
        dnscrypt: agg_from_map(&dnscrypt_pids, &usage),
        d2s: agg_from_map(&d2s_pids, &usage),
        dpitunnel: agg_from_map(&dpitunnel_pids, &usage),
        sing_box: agg_from_map(&singbox_pids, &usage),
        sing_box_lx: agg_from_map(&singbox_lx_pids, &usage),
        hysteria2: agg_from_map(&hysteria2_pids, &usage),
        wireproxy: agg_from_map(&wireproxy_pids, &usage),
        myproxy: agg_from_map(&myproxy_pids, &usage),
        myprogram: agg_from_map(&myprogram_pids, &usage),
        openvpn: agg_from_map(&openvpn_pids, &usage),
        amneziawg: agg_from_map(&amneziawg_pids, &usage),
        tun2socks: agg_from_map(&tun2socks_pids, &usage),
        mihomo: agg_from_map(&mihomo_pids, &usage),
        mieru: agg_from_map(&mieru_pids, &usage),
        tgwsproxy: agg_from_map(&tgwsproxy_pids, &usage),
        tun2proxy: agg_from_map(&tun2proxy_pids, &usage),
        tor: agg_from_map(&tor_pids, &usage),
        t2s: agg_from_map(&t2s_all_pids, &usage),
        opera: OperaAgg {
            opera: agg_from_map(&opera_proxy_pids, &usage),
            t2s: agg_from_map(&t2s_pids, &usage),
            byedpi: agg_from_map(&operaproxy_byedpi, &usage),
        },
    })
}

fn hysteria2_pids() -> Vec<u32> {
    crate::programs::hysteria2::main_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}

fn wireproxy_pids() -> Vec<u32> {
    crate::programs::wireproxy::main_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
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
            if process_pid_is_live(pid) { pids.push(pid); }
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

fn amneziawg_pids() -> Vec<u32> {
    crate::programs::amneziawg::main_pids_exact()
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

fn mieru_pids() -> Vec<u32> {
    crate::programs::mieru::main_pids_exact()
        .into_iter()
        .filter_map(|p| u32::try_from(p).ok())
        .collect()
}

fn mieru_tun2proxy_pids() -> Vec<u32> {
    crate::programs::mieru::tun2proxy_pids_exact()
        .into_iter()
        .filter_map(|p| u32::try_from(p).ok())
        .collect()
}

fn mihomo_tun2socks_pids() -> Vec<u32> {
    crate::programs::mihomo::tun2socks_pids_exact()
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

fn tor_main_pids() -> Vec<u32> {
    crate::programs::tor::main_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}

fn tor_helper_pids() -> Vec<u32> {
    crate::programs::tor::helper_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
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
    crate::programs::singbox::main_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}

fn singbox_lx_pids() -> Vec<u32> {
    crate::programs::singbox::lx::main_pids_exact()
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}

#[derive(Debug, Clone, Copy, Default)]
struct ProcRawStat {
    cpu_ticks: u64,
    start_time: u64,
    rss_kb: u64,
}

#[derive(Debug, Clone, Copy, Default)]
struct ProcUsageSample {
    cpu_percent: f32,
    rss_kb: u64,
}

#[derive(Debug, Default)]
struct ProcUsageCache {
    total_cpu_ticks: u64,
    by_pid: HashMap<u32, ProcRawStat>,
}

static PROC_USAGE_CACHE: OnceLock<Mutex<ProcUsageCache>> = OnceLock::new();

fn proc_usage_cache() -> &'static Mutex<ProcUsageCache> {
    PROC_USAGE_CACHE.get_or_init(|| Mutex::new(ProcUsageCache::default()))
}

fn read_total_cpu_ticks() -> Option<u64> {
    let raw = fs::read_to_string("/proc/stat").ok()?;
    let line = raw.lines().find(|l| l.starts_with("cpu "))?;
    let mut total = 0u64;
    for tok in line.split_whitespace().skip(1) {
        total = total.saturating_add(tok.parse::<u64>().ok()?);
    }
    Some(total)
}

fn page_size_kb() -> u64 {
    let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if page > 0 {
        (page as u64 / 1024).max(1)
    } else {
        4
    }
}

fn read_proc_raw_stat(pid: u32, page_kb: u64) -> Option<ProcRawStat> {
    let stat_path = format!("/proc/{pid}/stat");
    let stat = fs::read_to_string(stat_path).ok()?;
    let rparen = stat.rfind(')')?;
    let fields: Vec<&str> = stat.get(rparen + 2..)?.split_whitespace().collect();
    // fields[0] is state (field 3). Zombie/dead processes can remain visible to
    // pidof briefly after SIGKILL, but they are not running services and must not
    // keep Statistics/runtime adoption in the active state.
    let state = fields.first()?.chars().next()?;
    if matches!(state, 'Z' | 'X' | 'x') {
        return None;
    }
    // utime/stime are fields 14/15, starttime is field 22.
    let utime = fields.get(11)?.parse::<u64>().ok()?;
    let stime = fields.get(12)?.parse::<u64>().ok()?;
    let start_time = fields.get(19)?.parse::<u64>().ok()?;

    let statm_path = format!("/proc/{pid}/statm");
    let rss_pages = fs::read_to_string(statm_path)
        .ok()
        .and_then(|s| s.split_whitespace().nth(1).and_then(|v| v.parse::<u64>().ok()))
        .unwrap_or(0);

    Some(ProcRawStat {
        cpu_ticks: utime.saturating_add(stime),
        start_time,
        rss_kb: rss_pages.saturating_mul(page_kb),
    })
}

/// Read process usage from /proc and calculate CPU from deltas between status refreshes.
/// This avoids Android `ps %CPU`, which is often an averaged/lifetime value and can keep
/// showing high CPU after a process is already idle. The reported percentage is normalized
/// to total device CPU capacity: 100% means all cores are fully occupied by these processes.
fn proc_stats_realtime(pids: &[u32]) -> HashMap<u32, ProcUsageSample> {
    if pids.is_empty() {
        return HashMap::new();
    }

    let total_now = read_total_cpu_ticks().unwrap_or(0);
    let page_kb = page_size_kb();
    let mut current = HashMap::new();
    for &pid in pids {
        if let Some(raw) = read_proc_raw_stat(pid, page_kb) {
            current.insert(pid, raw);
        }
    }
    if current.is_empty() {
        return HashMap::new();
    }

    let mut out = HashMap::new();
    let Ok(mut cache) = proc_usage_cache().lock() else {
        for (&pid, raw) in &current {
            out.insert(pid, ProcUsageSample { cpu_percent: 0.0, rss_kb: raw.rss_kb });
        }
        return out;
    };

    let total_delta = total_now.saturating_sub(cache.total_cpu_ticks);
    for (&pid, raw) in &current {
        let mut cpu_percent = 0.0f32;
        if total_delta > 0 {
            if let Some(prev) = cache.by_pid.get(&pid) {
                // start_time protects against PID reuse between refreshes.
                if prev.start_time == raw.start_time && raw.cpu_ticks >= prev.cpu_ticks {
                    let proc_delta = raw.cpu_ticks - prev.cpu_ticks;
                    cpu_percent = ((proc_delta as f64 * 100.0) / total_delta as f64) as f32;
                }
            }
        }
        out.insert(pid, ProcUsageSample { cpu_percent, rss_kb: raw.rss_kb });
    }

    cache.total_cpu_ticks = total_now;
    cache.by_pid = current;
    out
}

fn agg_from_map(pids: &[u32], map: &HashMap<u32, ProcUsageSample>) -> UsageAgg {
    if pids.is_empty() {
        return UsageAgg::default();
    }
    let mut unique = pids.to_vec();
    unique.sort_unstable();
    unique.dedup();

    let mut total_cpu = 0.0f32;
    let mut total_rss_kb = 0u64;
    let mut count = 0u32;
    for pid in unique {
        if let Some(sample) = map.get(&pid) {
            total_cpu += sample.cpu_percent;
            total_rss_kb = total_rss_kb.saturating_add(sample.rss_kb);
            count = count.saturating_add(1);
        }
    }
    UsageAgg {
        count,
        cpu_percent: total_cpu,
        rss_mb: (total_rss_kb as f32) / 1024.0,
    }
}

fn operaproxy_byedpi_port() -> u16 {
    // Best effort: reuse current port.json, fallback to 10190.
    let p = program::working_program_root(ProgramId::OperaProxy).join("port.json");
    let txt = match fs::read_to_string(&p) {
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
