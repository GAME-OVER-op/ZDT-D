use std::fs::OpenOptions;
use std::io::Write;
use std::sync::{Mutex, OnceLock};

use crate::android::wake_lock::WakeLock;

const WAKE_LOCK_NAME: &str = "zdtd-protect";
const TARGET_OOM_SCORE_ADJ: &str = "-1000\n";

static WAKE_LOCK_SLOT: OnceLock<Mutex<Option<WakeLock>>> = OnceLock::new();

fn wake_lock_slot() -> &'static Mutex<Option<WakeLock>> {
    WAKE_LOCK_SLOT.get_or_init(|| Mutex::new(None))
}

/// Enable runtime protection once after services have fully started.
pub fn activate() {
    acquire_wake_lock();
    protect_once();
}

/// Disable runtime protection after services stop.
pub fn deactivate() {
    release_wake_lock();
}

fn acquire_wake_lock() {
    let slot = wake_lock_slot();
    let mut guard = match slot.lock() {
        Ok(g) => g,
        Err(poisoned) => {
            crate::logging::warn("protector wake_lock mutex poisoned; recovering");
            poisoned.into_inner()
        }
    };

    if guard.is_some() {
        return;
    }

    match WakeLock::new(WAKE_LOCK_NAME) {
        Ok(wl) => {
            *guard = Some(wl);
            crate::logging::info("protector: wake_lock acquired");
        }
        Err(e) => {
            crate::logging::warn(&format!("protector: failed to acquire wake_lock: {e:#}"));
        }
    }
}

fn release_wake_lock() {
    let slot = wake_lock_slot();
    let mut guard = match slot.lock() {
        Ok(g) => g,
        Err(poisoned) => {
            crate::logging::warn("protector wake_lock mutex poisoned; recovering");
            poisoned.into_inner()
        }
    };

    if guard.take().is_some() {
        crate::logging::info("protector: wake_lock released");
    }
}

fn protect_once() {
    let mut applied = 0usize;
    let pids = crate::stats::protected_pids();
    for pid in pids.iter().copied() {
        match protect_pid(pid) {
            Ok(()) => applied += 1,
            Err(e) => log::debug!("protector: pid {pid} not protected: {e}"),
        }
    }
    crate::logging::info(&format!("protector: protection applied to {applied} pid(s)"));
}

fn protect_pid(pid: u32) -> std::io::Result<()> {
    let path = format!("/proc/{pid}/oom_score_adj");
    let mut f = OpenOptions::new().write(true).open(&path)?;
    f.write_all(TARGET_OOM_SCORE_ADJ.as_bytes())?;
    Ok(())
}
