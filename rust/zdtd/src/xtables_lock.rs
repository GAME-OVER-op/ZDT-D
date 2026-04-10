use anyhow::Result;
use std::{
    sync::{Mutex, MutexGuard, OnceLock},
    thread,
    time::Duration,
};

use crate::shell::{self, Capture};

const LOCK_RETRY_COUNT: usize = 8;
const LOCK_RETRY_DELAY: Duration = Duration::from_millis(150);

fn global_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

pub fn lock<'a>() -> MutexGuard<'a, ()> {
    match global_lock().lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

fn looks_like_xtables_busy(out: &str) -> bool {
    let s = out.to_ascii_lowercase();
    s.contains("xtables lock")
        || s.contains("another app is currently holding the xtables lock")
        || s.contains("temporarily unavailable")
        || s.contains("resource temporarily unavailable")
}

pub fn run_timeout_retry(
    cmd: &str,
    args: &[&str],
    capture: Capture,
    timeout: Duration,
) -> Result<(i32, String)> {
    let mut last = shell::run_timeout(cmd, args, capture, timeout)?;
    if last.0 == 0 || !looks_like_xtables_busy(&last.1) {
        return Ok(last);
    }

    for _ in 1..LOCK_RETRY_COUNT {
        thread::sleep(LOCK_RETRY_DELAY);
        last = shell::run_timeout(cmd, args, capture, timeout)?;
        if last.0 == 0 || !looks_like_xtables_busy(&last.1) {
            return Ok(last);
        }
    }

    Ok(last)
}

pub fn runv_timeout_retry(
    cmd: &str,
    args: &[String],
    capture: Capture,
    timeout: Duration,
) -> Result<(i32, String)> {
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    run_timeout_retry(cmd, &refs, capture, timeout)
}
