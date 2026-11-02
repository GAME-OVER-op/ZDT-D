use anyhow::Result;
use std::time::Duration;

use crate::{iptables::lock as xtables_lock, common::process::shell::Capture};

const IPT_SAVE_TIMEOUT: Duration = Duration::from_secs(8);
const XT_WAIT_SECS: &str = "5";
pub(super) fn run_iptables_save(cmd: &str, table: &str) -> Result<String> {
    // Keep the read-only collector in the same xtables critical section as the
    // rule writers.  `iptables-save -c` does not mutate counters, but it still
    // touches xtables state and may collide with apply/restore scripts.
    let _xtables_guard = xtables_lock::lock();

    let wait_args = ["-w", XT_WAIT_SECS, "-c", "-t", table];
    let (mut rc, mut out) = xtables_lock::run_timeout_retry(cmd, &wait_args, Capture::Both, IPT_SAVE_TIMEOUT)?;

    // Some old Android iptables-save builds do not support -w.  In that case we
    // still keep the in-process lock and fall back to the classic arguments.
    if rc != 0 && looks_like_wait_unsupported(&out) {
        let fallback = xtables_lock::run_timeout_retry(cmd, &["-c", "-t", table], Capture::Both, IPT_SAVE_TIMEOUT)?;
        rc = fallback.0;
        out = fallback.1;
    }

    if rc != 0 {
        anyhow::bail!("exit={rc}: {}", out.trim());
    }
    Ok(out)
}

pub(super) fn looks_like_wait_unsupported(out: &str) -> bool {
    let s = out.to_ascii_lowercase();
    // Android/busybox iptables-save may print either "invalid option -- w"
    // or a shorter "unknown option" without echoing "-w" back.  In this
    // helper the only extra option we add before the known-good fallback is
    // `-w`, so any option-parser failure means we should retry without it.
    s.contains("unknown option")
        || s.contains("unrecognized option")
        || s.contains("invalid option")
        || s.contains("illegal option")
}
