use anyhow::Result;
use log::{info, warn};
use std::{thread, time::Duration};

use crate::shell::{self, Capture};

/// Wait for Android system initialization (`sys.boot_completed` == "1").
///
/// Original shell:
/// ```sh
/// while [ "$(getprop sys.boot_completed | tr -d r)" != "1" ]; do sleep 15; done
/// ```
///
/// We trim whitespace and remove '\r' to avoid CRLF artifacts.
pub fn wait_boot_completed(poll: Duration) -> Result<()> {
    info!("waiting for sys.boot_completed=1 ...");
    loop {
        let (code, out) = shell::run("getprop", &["sys.boot_completed"], Capture::Stdout)?;
        if code == 0 {
            let cleaned = out.replace('\r', "").trim().to_string();
            if cleaned == "1" {
                info!("boot completed");
                return Ok(());
            }
        } else {
            warn!("getprop returned code={code}, retrying...");
        }
        thread::sleep(poll);
    }
}

/// Backwards-compatible helper (older code expected this name).
///
/// Uses a sensible default poll interval.
pub fn wait_for_boot_completed() -> Result<()> {
    wait_boot_completed(Duration::from_secs(1))
}
