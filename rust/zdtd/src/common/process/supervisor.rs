use anyhow::Result;
use std::{path::Path, thread, time::Duration};

use super::process_state;

fn reap_pid_if_child(pid: i32) {
    let mut status = 0;
    unsafe {
        // Target only the service PID. Never use waitpid(-1): shutdown runs
        // several process queries in parallel and a global reaper could steal
        // another command's child status.
        let _ = libc::waitpid(pid, &mut status, libc::WNOHANG);
    }
}

fn pid_alive_for_stop(pid: i32) -> bool {
    if pid <= 1 {
        return false;
    }
    match process_state(pid) {
        Some('Z' | 'X' | 'x') => {
            reap_pid_if_child(pid);
            false
        }
        Some(_) => true,
        None => Path::new("/proc").join(pid.to_string()).is_dir(),
    }
}

fn signal_best_effort(pid: i32, signal: i32) {
    if pid <= 1 {
        return;
    }
    unsafe {
        let _ = libc::kill(pid, signal);
    }
}

/// Best-effort service shutdown with the historical ZDT-D timing:
/// SIGTERM, up to 1.5 s wait, SIGKILL for survivors, then up to 1 s wait.
pub fn kill_pids_with_escalation(label: &str, pids: &[i32]) -> Result<()> {
    if pids.is_empty() {
        return Ok(());
    }

    for &pid in pids {
        signal_best_effort(pid, libc::SIGTERM);
    }

    for _ in 0..15 {
        if pids.iter().all(|pid| !pid_alive_for_stop(*pid)) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }

    for &pid in pids {
        if pid_alive_for_stop(pid) {
            signal_best_effort(pid, libc::SIGKILL);
        }
    }

    for _ in 0..10 {
        if pids.iter().all(|pid| !pid_alive_for_stop(*pid)) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }

    log::warn!("failed to kill process(es) via {}: {:?}", label, pids);
    Ok(())
}
