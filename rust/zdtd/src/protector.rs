use std::collections::BTreeSet;
use std::fs::OpenOptions;
use std::io::Write;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Mutex, OnceLock,
};
use std::thread;
use std::time::{Duration, Instant};

use crate::android::wake_lock::WakeLock;
use crate::screen;
use crate::settings::{self, ProtectorMode};

const WAKE_LOCK_NAME: &str = "zdtd-protect";
const TARGET_OOM_SCORE_ADJ: &[u8] = b"-1000\n";
const DEFAULT_OOM_SCORE_ADJ: &[u8] = b"0\n";
const AUTO_SCREEN_ON_POLL_INTERVAL: Duration = Duration::from_secs(600);
const AUTO_SCREEN_OFF_POLL_INTERVAL: Duration = Duration::from_secs(300);
const AUTO_SCREEN_OFF_CONFIRM_STEP: Duration = Duration::from_secs(15);
const AUTO_SCREEN_OFF_CONFIRM_SAMPLES: u32 = 2;
const AUTO_SCREEN_ON_CONFIRM_STEP: Duration = Duration::from_secs(5);
const AUTO_SCREEN_ON_CONFIRM_SAMPLES: u32 = 2;

#[derive(Default)]
struct ProtectorState {
    wake_lock: Option<WakeLock>,
    protected_pids: BTreeSet<u32>,
}

static PROTECTOR_STATE: OnceLock<Mutex<ProtectorState>> = OnceLock::new();
static AUTO_MONITOR_TOKEN: AtomicU64 = AtomicU64::new(0);

fn state_slot() -> &'static Mutex<ProtectorState> {
    PROTECTOR_STATE.get_or_init(|| Mutex::new(ProtectorState::default()))
}

fn lock_state() -> std::sync::MutexGuard<'static, ProtectorState> {
    match state_slot().lock() {
        Ok(g) => g,
        Err(poisoned) => {
            crate::logging::warn("protector mutex poisoned; recovering");
            poisoned.into_inner()
        }
    }
}

/// Apply current protector mode after services have fully started.
pub fn activate() {
    let cfg = match settings::load_api_settings() {
        Ok(v) => v,
        Err(e) => {
            crate::logging::warn(&format!(
                "protector: failed to load setting/setting.json, fallback to off: {e:#}"
            ));
            settings::ApiSettings::default()
        }
    };
    apply_mode(cfg.protector_mode);
}

/// Re-read current settings and apply them immediately if services are running.
pub fn refresh(services_running: bool) {
    if services_running {
        activate();
    } else {
        deactivate();
    }
}

/// Disable runtime protection after services stop.
pub fn deactivate() {
    stop_auto_monitor();
    release_protection();
}

fn apply_mode(mode: ProtectorMode) {
    stop_auto_monitor();
    match mode {
        ProtectorMode::Off => {
            release_protection();
            crate::logging::info("protector: mode=off");
        }
        ProtectorMode::On => {
            apply_protection();
            crate::logging::info("protector: mode=on");
        }
        ProtectorMode::Auto => {
            release_protection();
            crate::logging::info("protector: mode=auto");
            start_auto_monitor();
        }
    }
}

fn start_auto_monitor() {
    let probe = match screen::detect_screen_probe() {
        Some(p) => p,
        None => {
            crate::logging::warn(
                "protector: auto mode could not detect screen probe; applying protection immediately",
            );
            apply_protection();
            return;
        }
    };

    let token = AUTO_MONITOR_TOKEN.fetch_add(1, Ordering::SeqCst) + 1;
    thread::spawn(move || auto_monitor_loop(probe, token));
}

fn stop_auto_monitor() {
    AUTO_MONITOR_TOKEN.fetch_add(1, Ordering::SeqCst);
}

fn auto_monitor_loop(probe: screen::ScreenProbe, token: u64) {
    let mut stable_on = screen::raw_screen_on(&probe);
    if stable_on {
        release_protection();
    } else {
        apply_protection();
    }

    loop {
        let poll_interval = if stable_on {
            AUTO_SCREEN_ON_POLL_INTERVAL
        } else {
            AUTO_SCREEN_OFF_POLL_INTERVAL
        };

        if !interruptible_sleep(poll_interval, token) {
            break;
        }

        let current_on = screen::raw_screen_on(&probe);
        if current_on == stable_on {
            continue;
        }

        let confirmed = if current_on {
            confirm_screen_state(
                &probe,
                token,
                true,
                AUTO_SCREEN_ON_CONFIRM_STEP,
                AUTO_SCREEN_ON_CONFIRM_SAMPLES,
            )
        } else {
            confirm_screen_state(
                &probe,
                token,
                false,
                AUTO_SCREEN_OFF_CONFIRM_STEP,
                AUTO_SCREEN_OFF_CONFIRM_SAMPLES,
            )
        };

        match confirmed {
            Some(true) => {
                stable_on = current_on;
                if stable_on {
                    release_protection();
                } else {
                    apply_protection();
                }
            }
            Some(false) => {}
            None => break,
        }
    }
}

fn confirm_screen_state(
    probe: &screen::ScreenProbe,
    token: u64,
    expected_on: bool,
    step: Duration,
    samples: u32,
) -> Option<bool> {
    for _ in 0..samples {
        if !interruptible_sleep(step, token) {
            return None;
        }
        if screen::raw_screen_on(probe) != expected_on {
            return Some(false);
        }
    }
    Some(true)
}

fn interruptible_sleep(total: Duration, token: u64) -> bool {
    let start = Instant::now();
    while start.elapsed() < total {
        if AUTO_MONITOR_TOKEN.load(Ordering::SeqCst) != token {
            return false;
        }
        let remaining = total.saturating_sub(start.elapsed());
        thread::sleep(remaining.min(Duration::from_secs(1)));
    }
    AUTO_MONITOR_TOKEN.load(Ordering::SeqCst) == token
}

fn apply_protection() {
    let mut state = lock_state();
    if state.wake_lock.is_some() || !state.protected_pids.is_empty() {
        return;
    }

    match WakeLock::new(WAKE_LOCK_NAME) {
        Ok(wl) => {
            state.wake_lock = Some(wl);
            crate::logging::info("protector: wake_lock acquired");
        }
        Err(e) => {
            crate::logging::warn(&format!("protector: failed to acquire wake_lock: {e:#}"));
        }
    }

    let pids = crate::stats::protected_pids();
    let mut applied = 0usize;
    let mut protected = BTreeSet::new();
    for pid in pids.iter().copied() {
        match set_pid_oom_score_adj(pid, TARGET_OOM_SCORE_ADJ) {
            Ok(()) => {
                protected.insert(pid);
                applied += 1;
            }
            Err(e) => log::debug!("protector: pid {pid} not protected: {e}"),
        }
    }
    state.protected_pids = protected;

    crate::logging::info(&format!("protector: protection applied to {applied} pid(s)"));
}

fn release_protection() {
    let (wake_lock_released, pids) = {
        let mut state = lock_state();
        let wake_lock_released = state.wake_lock.take().is_some();
        let pids: Vec<u32> = state.protected_pids.iter().copied().collect();
        state.protected_pids.clear();
        (wake_lock_released, pids)
    };

    let mut restored = 0usize;
    for pid in pids.iter().copied() {
        match set_pid_oom_score_adj(pid, DEFAULT_OOM_SCORE_ADJ) {
            Ok(()) => restored += 1,
            Err(e) => log::debug!("protector: pid {pid} not restored: {e}"),
        }
    }

    if wake_lock_released {
        crate::logging::info("protector: wake_lock released");
    }
    if restored > 0 {
        crate::logging::info(&format!("protector: restored defaults for {restored} pid(s)"));
    }
}

fn set_pid_oom_score_adj(pid: u32, value: &[u8]) -> std::io::Result<()> {
    let path = format!("/proc/{pid}/oom_score_adj");
    let mut f = OpenOptions::new().write(true).open(&path)?;
    f.write_all(value)?;
    Ok(())
}
