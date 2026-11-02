use anyhow::{bail, Result};

// from programs/mihomo.rs
pub fn validate_loglevel(v: &str, field: &str) -> Result<()> {
    match v {
        "debug" | "info" | "warn" | "error" | "silent" => Ok(()),
        _ => bail!("{field} must be debug/info/warn/error/silent"),
    }
}
