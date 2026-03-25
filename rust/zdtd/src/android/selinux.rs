use anyhow::{Context, Result};
use log::{info, warn};
use std::{fs, path::Path};

use crate::shell::{self, Capture};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SelinuxMode {
    Enforcing,
    Permissive,
    Disabled,
    Unknown,
}

impl SelinuxMode {
    pub fn as_str(self) -> &'static str {
        match self {
            SelinuxMode::Enforcing => "Enforcing",
            SelinuxMode::Permissive => "Permissive",
            SelinuxMode::Disabled => "Disabled",
            SelinuxMode::Unknown => "Unknown",
        }
    }
}

/// Try to detect current SELinux mode.
/// Prefers `getenforce`, falls back to `/sys/fs/selinux/enforce`.
pub fn get_mode() -> Result<SelinuxMode> {
    // 1) getenforce
    let (code, out) = shell::run("getenforce", &[], Capture::Stdout)?;
    if code == 0 {
        return Ok(parse_getenforce(&out));
    }

    // 2) fallback: /sys/fs/selinux/enforce (1 enforcing, 0 permissive)
    let p = Path::new("/sys/fs/selinux/enforce");
    if p.exists() {
        let s = fs::read_to_string(p).context("read /sys/fs/selinux/enforce")?;
        return Ok(match s.trim() {
            "1" => SelinuxMode::Enforcing,
            "0" => SelinuxMode::Permissive,
            _ => SelinuxMode::Unknown,
        });
    }

    Ok(SelinuxMode::Unknown)
}

fn parse_getenforce(s: &str) -> SelinuxMode {
    let cleaned = s.replace('\r', "").trim().to_lowercase();
    match cleaned.as_str() {
        "enforcing" => SelinuxMode::Enforcing,
        "permissive" => SelinuxMode::Permissive,
        "disabled" => SelinuxMode::Disabled,
        _ => SelinuxMode::Unknown,
    }
}

/// Set SELinux enforcing/permissive.
/// Uses `setenforce 1|0`. If command is missing/fails, returns error.
pub fn set_enforce(enforcing: bool) -> Result<()> {
    let v = if enforcing { "1" } else { "0" };
    shell::ok("setenforce", &[v]).with_context(|| format!("setenforce {v}"))?;
    Ok(())
}

/// Guard that temporarily switches SELinux to permissive (only if it was enforcing),
/// and can restore the previous state later.
#[derive(Debug)]
pub struct SelinuxGuard {
    prev: SelinuxMode,
    changed: bool,
}

impl SelinuxGuard {
    /// If current mode is Enforcing, switches to Permissive and returns a guard.
    /// Otherwise returns a guard that does nothing.
    pub fn enter_permissive_if_enforcing() -> Result<Self> {
        let prev = get_mode()?;
        match prev {
            SelinuxMode::Enforcing => {
                info!("SELinux is Enforcing -> switching to Permissive (temporary)");
                if let Err(e) = set_enforce(false) {
                    warn!("failed to setenforce 0: {e:?}");
                    return Ok(Self { prev, changed: false });
                }
                Ok(Self { prev, changed: true })
            }
            _ => {
                info!("SELinux mode is {} -> no change", prev.as_str());
                Ok(Self { prev, changed: false })
            }
        }
    }

    /// Disable auto-restore on Drop (keeps current state).
    pub fn disarm(&mut self) {
        self.changed = false;
    }

    /// Restore previous state. Safe to call multiple times.
    pub fn restore(&mut self) -> Result<()> {
        if !self.changed {
            return Ok(());
        }
        if self.prev == SelinuxMode::Enforcing {
            info!("Restoring SELinux -> Enforcing");
            // best-effort restore
            if let Err(e) = set_enforce(true) {
                warn!("failed to restore setenforce 1: {e:?}");
            }
        }
        self.changed = false;
        Ok(())
    }
}

impl Drop for SelinuxGuard {
    fn drop(&mut self) {
        // Best-effort: avoid panics.
        let _ = self.restore();
    }
}
