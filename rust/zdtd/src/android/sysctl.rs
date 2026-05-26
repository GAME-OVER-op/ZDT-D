use anyhow::{Context, Result};

use crate::{settings, shell};

pub fn set_ipv4_forward(enabled: bool) -> Result<()> {
    let value = if enabled { "1" } else { "0" };
    let arg = format!("net.ipv4.ip_forward={value}");
    shell::okv("sysctl", &["-w".to_string(), arg])
        .with_context(|| format!("sysctl net.ipv4.ip_forward={value}"))?;
    Ok(())
}

pub fn sync_ipv4_forward_from_settings_best_effort() {
    let st = match settings::load_api_settings() {
        Ok(st) => st,
        Err(e) => {
            log::warn!("sysctl: failed to load settings for ip_forward sync: {e:#}");
            return;
        }
    };
    if let Err(e) = set_ipv4_forward(st.ip_forward_enabled) {
        log::warn!("sysctl: failed to sync net.ipv4.ip_forward={}: {e:#}", st.ip_forward_enabled);
    }
}

pub fn apply_start_settings_best_effort() {
    sync_ipv4_forward_from_settings_best_effort();
}
