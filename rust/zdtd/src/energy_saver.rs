use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{atomic::{AtomicBool, AtomicU64, Ordering}, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::{logging, screen, settings};

const SETTINGS_FILE: &str = "/data/adb/modules/ZDT-D/setting/energy_saver.json";
const STATE_FILE: &str = "/data/adb/modules/ZDT-D/working_folder/energy_saver/state.json";
const MIN_FREEZE_DELAY_SECS: u64 = 10;
const MAX_FREEZE_DELAY_SECS: u64 = 24 * 60 * 60;
const DEFAULT_FREEZE_DELAY_SECS: u64 = 300;
const MONITOR_POLL_SECS: u64 = 5;
const AFFINITY_REAPPLY_SECS: u64 = 20;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnergySaverProgramSetting {
    #[serde(default)]
    pub freeze_on_screen_off: bool,
    #[serde(default)]
    pub cpu_affinity_enabled: bool,
    #[serde(default)]
    pub cpu_cores: Vec<usize>,
}

impl Default for EnergySaverProgramSetting {
    fn default() -> Self {
        Self {
            freeze_on_screen_off: false,
            cpu_affinity_enabled: false,
            cpu_cores: default_little_cores(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnergySaverSettings {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_freeze_delay_secs")]
    pub freeze_delay_seconds: u64,
    #[serde(default)]
    pub programs: BTreeMap<String, EnergySaverProgramSetting>,
}

impl Default for EnergySaverSettings {
    fn default() -> Self {
        Self {
            enabled: false,
            freeze_delay_seconds: DEFAULT_FREEZE_DELAY_SECS,
            programs: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct EnergySaverProgramInfo {
    pub id: &'static str,
    pub display_name: &'static str,
    pub binary: &'static str,
    pub binary_path: String,
    pub exists: bool,
    pub allow_freeze: bool,
    pub allow_affinity: bool,
    pub running_pids: Vec<u32>,
}

#[derive(Debug, Clone)]
struct ManagedBinary {
    id: &'static str,
    display_name: &'static str,
    binary: &'static str,
    allow_freeze: bool,
    allow_affinity: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct FrozenStateFile {
    #[serde(default)]
    frozen: BTreeMap<String, Vec<u32>>,
    #[serde(default)]
    updated_at_unix: u64,
}

#[derive(Default)]
struct RuntimeState {
    frozen: BTreeMap<String, BTreeSet<u32>>,
}

static MONITOR_TOKEN: AtomicU64 = AtomicU64::new(0);
static MONITOR_RUNNING: AtomicBool = AtomicBool::new(false);
static RUNTIME_STATE: OnceLock<Mutex<RuntimeState>> = OnceLock::new();

fn runtime_state() -> &'static Mutex<RuntimeState> {
    RUNTIME_STATE.get_or_init(|| Mutex::new(RuntimeState::default()))
}

fn lock_runtime_state() -> std::sync::MutexGuard<'static, RuntimeState> {
    match runtime_state().lock() {
        Ok(g) => g,
        Err(poisoned) => {
            logging::warn("energy_saver: runtime state mutex poisoned; recovering");
            poisoned.into_inner()
        }
    }
}

fn default_freeze_delay_secs() -> u64 { DEFAULT_FREEZE_DELAY_SECS }

fn default_little_cores() -> Vec<usize> {
    let online = online_cpu_count().unwrap_or(4).max(1);
    let n = online.min(3);
    (0..n).collect()
}

fn known_binaries() -> &'static [ManagedBinary] {
    &[
        ManagedBinary { id: "t2s", display_name: "T2s", binary: "t2s", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "nfqws", display_name: "Zapret", binary: "nfqws", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "nfqws2", display_name: "Zapret 2", binary: "nfqws2", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "byedpi", display_name: "ByeDPI", binary: "byedpi", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "dnscrypt", display_name: "DNSCrypt", binary: "dnscrypt", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "dpitunnel", display_name: "DPITunnel", binary: "dpitunnel-cli", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "operaproxy", display_name: "Opera Proxy", binary: "opera-proxy", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "sing-box", display_name: "sing-box", binary: "sing-box", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "wireproxy", display_name: "WireProxy", binary: "wireproxy", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "openvpn", display_name: "OpenVPN", binary: "openvpn", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "amneziawg", display_name: "AmneziaWG", binary: "amneziawg-go", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "tun2socks", display_name: "tun2socks", binary: "tun2socks", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "mihomo", display_name: "Mihomo", binary: "mihomo", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "mieru", display_name: "mieru", binary: "mieru", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "tor", display_name: "Tor", binary: "torproxy", allow_freeze: true, allow_affinity: true },
        ManagedBinary { id: "lyrebird", display_name: "Lyrebird", binary: "lyrebird", allow_freeze: true, allow_affinity: true },
    ]
}

fn module_bin_path(binary: &str) -> PathBuf {
    Path::new(settings::MODULE_DIR).join("bin").join(binary)
}

pub fn settings_path() -> PathBuf { PathBuf::from(SETTINGS_FILE) }
fn state_path() -> PathBuf { PathBuf::from(STATE_FILE) }

pub fn settings_file_exists() -> bool {
    settings_path().is_file()
}

pub fn load_settings() -> Result<EnergySaverSettings> {
    let path = settings_path();
    if !path.exists() {
        return Ok(EnergySaverSettings::default());
    }
    let raw = fs::read_to_string(&path).with_context(|| format!("read {}", path.display()))?;
    let mut cfg: EnergySaverSettings = serde_json::from_str(&raw).with_context(|| format!("parse {}", path.display()))?;
    normalize_settings(&mut cfg);
    Ok(cfg)
}

pub fn save_settings(mut cfg: EnergySaverSettings) -> Result<EnergySaverSettings> {
    normalize_settings(&mut cfg);
    if let Some(parent) = settings_path().parent() {
        fs::create_dir_all(parent).ok();
    }
    let data = serde_json::to_string_pretty(&cfg)?;
    fs::write(settings_path(), data).with_context(|| format!("write {}", settings_path().display()))?;
    Ok(cfg)
}

fn normalize_settings(cfg: &mut EnergySaverSettings) {
    if cfg.freeze_delay_seconds == 0 {
        cfg.freeze_delay_seconds = DEFAULT_FREEZE_DELAY_SECS;
    }
    cfg.freeze_delay_seconds = cfg.freeze_delay_seconds.clamp(MIN_FREEZE_DELAY_SECS, MAX_FREEZE_DELAY_SECS);

    let valid: BTreeSet<&'static str> = known_binaries().iter().map(|b| b.id).collect();
    cfg.programs.retain(|id, setting| {
        if !valid.contains(id.as_str()) {
            return false;
        }
        sanitize_program_setting(setting);
        true
    });
}

fn sanitize_program_setting(setting: &mut EnergySaverProgramSetting) {
    let cpu_count = online_cpu_count().unwrap_or(8).max(1);
    let mut unique = BTreeSet::new();
    for core in setting.cpu_cores.iter().copied() {
        if core < cpu_count && core < 128 {
            unique.insert(core);
        }
    }
    if unique.is_empty() {
        unique.extend(default_little_cores());
    }
    setting.cpu_cores = unique.into_iter().collect();
}

pub fn available_programs() -> Vec<EnergySaverProgramInfo> {
    known_binaries()
        .iter()
        .filter_map(|b| {
            let path = module_bin_path(b.binary);
            let exists = path.is_file();
            if !exists {
                return None;
            }
            let pids = find_pids_for_binary(b.binary, Some(&path));
            Some(EnergySaverProgramInfo {
                id: b.id,
                display_name: b.display_name,
                binary: b.binary,
                binary_path: path.to_string_lossy().to_string(),
                exists,
                allow_freeze: b.allow_freeze,
                allow_affinity: b.allow_affinity,
                running_pids: pids,
            })
        })
        .collect()
}

pub fn active() -> bool {
    MONITOR_RUNNING.load(Ordering::SeqCst)
}

pub fn start_monitor() {
    let cfg = match load_settings() {
        Ok(v) => v,
        Err(e) => {
            logging::warn(&format!("energy_saver: failed to read settings: {e:#}"));
            return;
        }
    };
    if !cfg.enabled || !settings_file_exists() {
        stop_monitor();
        return;
    }

    let probe = match screen::detect_screen_probe() {
        Some(p) => p,
        None => {
            logging::warn("energy_saver: screen probe unavailable; freeze monitor disabled, affinity can still be applied");
            let _ = apply_affinity_now();
            return;
        }
    };

    stop_monitor_without_unfreeze();
    let token = MONITOR_TOKEN.fetch_add(1, Ordering::SeqCst) + 1;
    MONITOR_RUNNING.store(true, Ordering::SeqCst);
    thread::spawn(move || monitor_loop(probe, token));
    logging::info("energy_saver: monitor started");
}

pub fn stop_monitor() {
    stop_monitor_without_unfreeze();
    unfreeze_all_best_effort();
}

fn stop_monitor_without_unfreeze() {
    MONITOR_TOKEN.fetch_add(1, Ordering::SeqCst);
    MONITOR_RUNNING.store(false, Ordering::SeqCst);
}

pub fn refresh(services_running: bool) {
    if services_running {
        start_monitor();
        let _ = apply_affinity_now();
    } else {
        stop_monitor();
    }
}

fn monitor_loop(probe: screen::ScreenProbe, token: u64) {
    let mut screen_off_since: Option<Instant> = None;
    let mut last_affinity = Instant::now() - Duration::from_secs(AFFINITY_REAPPLY_SECS);

    while MONITOR_TOKEN.load(Ordering::SeqCst) == token {
        let cfg = match load_settings() {
            Ok(v) => v,
            Err(e) => {
                logging::warn(&format!("energy_saver: failed to reload settings: {e:#}"));
                break;
            }
        };
        if !cfg.enabled || !settings_file_exists() {
            break;
        }

        if last_affinity.elapsed() >= Duration::from_secs(AFFINITY_REAPPLY_SECS) {
            let _ = apply_affinity_from_settings(&cfg);
            last_affinity = Instant::now();
        }

        let screen_on = screen::raw_screen_on(&probe);
        if screen_on {
            screen_off_since = None;
            unfreeze_all_best_effort();
        } else {
            let since = screen_off_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= Duration::from_secs(cfg.freeze_delay_seconds) {
                freeze_selected_programs(&cfg);
            }
        }

        if !interruptible_sleep(Duration::from_secs(MONITOR_POLL_SECS), token) {
            break;
        }
    }

    MONITOR_RUNNING.store(false, Ordering::SeqCst);
    logging::info("energy_saver: monitor stopped");
}

fn interruptible_sleep(total: Duration, token: u64) -> bool {
    let start = Instant::now();
    while start.elapsed() < total {
        if MONITOR_TOKEN.load(Ordering::SeqCst) != token {
            return false;
        }
        let remaining = total.saturating_sub(start.elapsed());
        thread::sleep(remaining.min(Duration::from_secs(1)));
    }
    MONITOR_TOKEN.load(Ordering::SeqCst) == token
}

pub fn apply_affinity_now() -> Result<usize> {
    let cfg = load_settings()?;
    if !cfg.enabled || !settings_file_exists() {
        return Ok(0);
    }
    apply_affinity_from_settings(&cfg)
}

fn apply_affinity_from_settings(cfg: &EnergySaverSettings) -> Result<usize> {
    let known = known_binaries();
    let mut applied = 0usize;
    for b in known {
        if !b.allow_affinity || b.id == "zdtd" {
            continue;
        }
        let Some(ps) = cfg.programs.get(b.id) else { continue };
        if !ps.cpu_affinity_enabled {
            continue;
        }
        if !module_bin_path(b.binary).is_file() {
            continue;
        }
        for pid in find_pids_for_binary(b.binary, Some(&module_bin_path(b.binary))) {
            if pid <= 1 || pid == std::process::id() {
                continue;
            }
            match set_affinity(pid, &ps.cpu_cores) {
                Ok(()) => {
                    applied += 1;
                    log::debug!("energy_saver: affinity applied pid={pid} program={} cores={:?}", b.id, ps.cpu_cores);
                }
                Err(e) => log::debug!("energy_saver: affinity failed pid={pid} program={}: {e:#}", b.id),
            }
        }
    }
    if applied > 0 {
        logging::info(&format!("energy_saver: CPU affinity applied to {applied} pid(s)"));
    }
    Ok(applied)
}

fn freeze_selected_programs(cfg: &EnergySaverSettings) {
    let mut newly_frozen = 0usize;
    for b in known_binaries() {
        if !b.allow_freeze || b.id == "zdtd" {
            continue;
        }
        let Some(ps) = cfg.programs.get(b.id) else { continue };
        if !ps.freeze_on_screen_off {
            continue;
        }
        if !module_bin_path(b.binary).is_file() {
            continue;
        }
        let pids = find_pids_for_binary(b.binary, Some(&module_bin_path(b.binary)));
        for pid in pids {
            if pid <= 1 || pid == std::process::id() {
                continue;
            }
            if mark_frozen(b.id, pid) {
                if signal_pid(pid, libc::SIGSTOP).is_ok() {
                    newly_frozen += 1;
                    logging::info(&format!("energy_saver: frozen pid={pid} program={}", b.id));
                } else {
                    unmark_frozen(b.id, pid);
                }
            }
        }
    }
    if newly_frozen > 0 {
        persist_state_best_effort();
    }
}

fn mark_frozen(program: &str, pid: u32) -> bool {
    let mut st = lock_runtime_state();
    let pids = st.frozen.entry(program.to_string()).or_default();
    pids.insert(pid)
}

fn unmark_frozen(program: &str, pid: u32) {
    let mut st = lock_runtime_state();
    if let Some(pids) = st.frozen.get_mut(program) {
        pids.remove(&pid);
        if pids.is_empty() {
            st.frozen.remove(program);
        }
    }
}

pub fn unfreeze_all_best_effort() {
    load_state_file_into_memory_best_effort();
    let frozen: Vec<(String, Vec<u32>)> = {
        let mut st = lock_runtime_state();
        let snapshot = st.frozen.iter().map(|(k, v)| (k.clone(), v.iter().copied().collect())).collect();
        st.frozen.clear();
        snapshot
    };
    let mut count = 0usize;
    for (program, pids) in frozen {
        for pid in pids {
            if pid <= 1 || pid == std::process::id() {
                continue;
            }
            if signal_pid(pid, libc::SIGCONT).is_ok() {
                count += 1;
                logging::info(&format!("energy_saver: unfrozen pid={pid} program={program}"));
            }
        }
    }
    if count > 0 {
        logging::info(&format!("energy_saver: unfroze {count} pid(s)"));
    }
    let _ = fs::remove_file(state_path());
}

fn load_state_file_into_memory_best_effort() {
    let path = state_path();
    if !path.is_file() {
        return;
    }
    let Ok(raw) = fs::read_to_string(&path) else { return };
    let Ok(file) = serde_json::from_str::<FrozenStateFile>(&raw) else { return };
    let mut st = lock_runtime_state();
    for (program, pids) in file.frozen {
        let dst = st.frozen.entry(program).or_default();
        for pid in pids {
            if pid > 1 {
                dst.insert(pid);
            }
        }
    }
}

fn persist_state_best_effort() {
    let file = {
        let st = lock_runtime_state();
        FrozenStateFile {
            frozen: st.frozen.iter().map(|(k, v)| (k.clone(), v.iter().copied().collect())).collect(),
            updated_at_unix: now_unix(),
        }
    };
    if let Some(parent) = state_path().parent() {
        fs::create_dir_all(parent).ok();
    }
    if let Ok(data) = serde_json::to_string_pretty(&file) {
        let _ = fs::write(state_path(), data);
    }
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn signal_pid(pid: u32, sig: libc::c_int) -> Result<()> {
    let rc = unsafe { libc::kill(pid as libc::pid_t, sig) };
    if rc == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error()).with_context(|| format!("kill({pid}, {sig})"))
    }
}

fn set_affinity(pid: u32, cores: &[usize]) -> Result<()> {
    if cores.is_empty() {
        anyhow::bail!("empty CPU core list");
    }
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for &core in cores {
            libc::CPU_SET(core, &mut set);
        }
        let rc = libc::sched_setaffinity(
            pid as libc::pid_t,
            std::mem::size_of::<libc::cpu_set_t>(),
            &set,
        );
        if rc == 0 {
            Ok(())
        } else {
            Err(std::io::Error::last_os_error()).with_context(|| format!("sched_setaffinity({pid})"))
        }
    }
}

fn online_cpu_count() -> Option<usize> {
    let text = fs::read_to_string("/sys/devices/system/cpu/online").ok()?;
    let mut max_cpu = 0usize;
    for part in text.trim().split(',') {
        if let Some((a, b)) = part.split_once('-') {
            let start = a.trim().parse::<usize>().ok()?;
            let end = b.trim().parse::<usize>().ok()?;
            if end >= start {
                max_cpu = max_cpu.max(end);
            }
        } else if let Ok(cpu) = part.trim().parse::<usize>() {
            max_cpu = max_cpu.max(cpu);
        }
    }
    Some(max_cpu + 1)
}

fn find_pids_for_binary(binary: &str, expected_path: Option<&Path>) -> Vec<u32> {
    let mut out = BTreeSet::new();
    let Ok(entries) = fs::read_dir("/proc") else { return Vec::new() };
    for ent in entries.flatten() {
        let Some(name) = ent.file_name().to_str().map(|s| s.to_string()) else { continue };
        let Ok(pid) = name.parse::<u32>() else { continue };
        if pid <= 1 || pid == std::process::id() {
            continue;
        }
        let proc_dir = ent.path();
        if process_matches(&proc_dir, binary, expected_path) {
            out.insert(pid);
        }
    }
    out.into_iter().collect()
}

fn process_matches(proc_dir: &Path, binary: &str, expected_path: Option<&Path>) -> bool {
    if let Ok(exe) = fs::read_link(proc_dir.join("exe")) {
        if let Some(expected) = expected_path {
            if exe == expected {
                return true;
            }
        }
        if exe.file_name().and_then(|s| s.to_str()) == Some(binary) {
            return true;
        }
    }

    if let Ok(comm) = fs::read_to_string(proc_dir.join("comm")) {
        if comm.trim() == binary {
            return true;
        }
    }

    if let Ok(cmd) = fs::read(proc_dir.join("cmdline")) {
        let mut parts = cmd.split(|b| *b == 0).filter(|s| !s.is_empty());
        if let Some(first) = parts.next() {
            let first = String::from_utf8_lossy(first);
            if Path::new(first.as_ref()).file_name().and_then(|s| s.to_str()) == Some(binary) {
                return true;
            }
            if first == binary {
                return true;
            }
        }
    }

    false
}

pub fn api_snapshot() -> serde_json::Value {
    let cfg = load_settings().unwrap_or_default();
    json!({
        "ok": true,
        "exists": settings_file_exists(),
        "active": active(),
        "settings": cfg,
        "programs": available_programs(),
        "online_cpu_count": online_cpu_count().unwrap_or(0),
    })
}
