
use anyhow::{bail, Context, Result};

use log::{warn};
use serde::{Deserialize, Serialize};
use std::{fs::{self}, path::{Path}};


use super::model::*;

/// Load configurable opera-proxy arguments from config/args.json.
/// If the file is missing or unparseable, returns built-in defaults.
pub fn read_opera_args() -> OperaArgs {
    let path = Path::new(OPERA_ARGS_JSON);
    if !path.is_file() {
        return OperaArgs::default();
    }
    match fs::read_to_string(path) {
        Ok(s) => serde_json::from_str::<OperaArgs>(&s).unwrap_or_else(|e| {
            warn!("operaproxy: failed to parse args.json: {} -> using defaults", e);
            OperaArgs::default()
        }),
        Err(e) => {
            warn!("operaproxy: failed to read args.json: {} -> using defaults", e);
            OperaArgs::default()
        }
    }
}

/// Write configurable opera-proxy arguments to config/args.json.
pub fn write_opera_args(args: &OperaArgs) -> Result<()> {
    let path = Path::new(OPERA_ARGS_JSON);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let json = serde_json::to_string_pretty(args)
        .with_context(|| "serialize OperaArgs")?;
    fs::write(path, json.as_bytes())
        .with_context(|| format!("write {}", path.display()))?;
    Ok(())
}


#[derive(Debug, Clone, Deserialize, Serialize)]
pub(super) struct OperaSniEntry {
    pub(super) sni: String,
    #[serde(default)]
    pub(super) use_byedpi: bool,
    #[serde(default)]
    pub(super) proxy_mode: Option<String>,
    #[serde(default)]
    pub(super) custom_proxy: Option<String>,
    #[serde(default)]
    pub(super) override_proxy_address: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum OperaUpstreamMode {
    Direct,
    ByeDpi,
    Custom,
}

pub(super) fn opera_upstream_mode(entry: &OperaSniEntry) -> OperaUpstreamMode {
    match entry
        .proxy_mode
        .as_deref()
        .map(str::trim)
        .map(str::to_ascii_lowercase)
        .as_deref()
    {
        Some("direct") => OperaUpstreamMode::Direct,
        Some("byedpi") => OperaUpstreamMode::ByeDpi,
        Some("custom") => OperaUpstreamMode::Custom,
        _ if entry.use_byedpi => OperaUpstreamMode::ByeDpi,
        _ => OperaUpstreamMode::Direct,
    }
}

pub(super) fn is_valid_custom_upstream_proxy(raw: &str) -> bool {
    let value = raw.trim();
    if value.is_empty() || value.chars().any(char::is_whitespace) {
        return false;
    }
    let Some((scheme, rest)) = value.split_once("://") else {
        return false;
    };
    if !matches!(
        scheme.to_ascii_lowercase().as_str(),
        "http" | "https" | "socks5" | "socks5h"
    ) {
        return false;
    }
    let authority = rest.strip_suffix('/').unwrap_or(rest);
    if authority.is_empty() || authority.contains('/') || authority.contains('?') || authority.contains('#') {
        return false;
    }
    let host_port = authority.rsplit_once('@').map(|(_, tail)| tail).unwrap_or(authority);
    let (host, port_text) = if host_port.starts_with('[') {
        let Some(end) = host_port.find(']') else {
            return false;
        };
        if end <= 1 || host_port.as_bytes().get(end + 1) != Some(&b':') {
            return false;
        }
        (&host_port[1..end], &host_port[end + 2..])
    } else {
        let Some((host, port)) = host_port.rsplit_once(':') else {
            return false;
        };
        (host, port)
    };
    let Ok(port) = port_text.parse::<u16>() else {
        return false;
    };
    !host.trim().is_empty() && port > 0
}

pub(super) fn opera_upstream_proxy(entry: &OperaSniEntry, byedpi_port: u16) -> Result<Option<String>> {
    match opera_upstream_mode(entry) {
        OperaUpstreamMode::Direct => Ok(None),
        OperaUpstreamMode::ByeDpi => Ok(Some(format!("socks5://127.0.0.1:{}", byedpi_port))),
        OperaUpstreamMode::Custom => {
            let proxy = entry.custom_proxy.as_deref().unwrap_or("").trim();
            if !is_valid_custom_upstream_proxy(proxy) {
                bail!("invalid custom Opera upstream proxy for SNI '{}'", entry.sni);
            }
            Ok(Some(proxy.to_string()))
        }
    }
}

