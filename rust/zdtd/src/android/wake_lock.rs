use anyhow::{Context, Result};
use std::fs::OpenOptions;
use std::io::Write;

const WAKE_LOCK_PATH: &str = "/sys/power/wake_lock";
const WAKE_UNLOCK_PATH: &str = "/sys/power/wake_unlock";

/// Захватывает kernel wake_lock с указанным именем.
pub fn acquire(name: &str) -> Result<()> {
    let mut f = OpenOptions::new()
        .write(true)
        .open(WAKE_LOCK_PATH)
        .with_context(|| format!("open {WAKE_LOCK_PATH}"))?;
    f.write_all(name.as_bytes())
        .with_context(|| format!("write {WAKE_LOCK_PATH}"))?;
    Ok(())
}

/// Освобождает ранее захваченный kernel wake_lock.
pub fn release(name: &str) -> Result<()> {
    let mut f = OpenOptions::new()
        .write(true)
        .open(WAKE_UNLOCK_PATH)
        .with_context(|| format!("open {WAKE_UNLOCK_PATH}"))?;
    f.write_all(name.as_bytes())
        .with_context(|| format!("write {WAKE_UNLOCK_PATH}"))?;
    Ok(())
}

/// RAII-обёртка вокруг kernel wake_lock.
pub struct WakeLock {
    name: String,
}

impl WakeLock {
    pub fn new(name: &str) -> Result<Self> {
        acquire(name)?;
        Ok(Self {
            name: name.to_string(),
        })
    }
}

impl Drop for WakeLock {
    fn drop(&mut self) {
        if let Err(e) = release(&self.name) {
            log::warn!("failed to release wake_lock '{}': {e:#}", self.name);
        }
    }
}
