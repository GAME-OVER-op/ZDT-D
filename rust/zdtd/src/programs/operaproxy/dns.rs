
use anyhow::{Context, Result};

use log::{warn};
use std::{collections::BTreeSet, fs::{self}, str::FromStr, path::{Path}};

use crate::{programs::dnscrypt};

use super::model::*;
use super::settings::*;

/// Read the bootstrap DNS resolver list from config/bootstrap_dns.json.
/// Returns a comma-separated string of resolver URLs.
/// Falls back to DEFAULT_BOOTSTRAP_DNS if the file is missing, empty, or invalid.
pub fn read_bootstrap_dns() -> String {
    let path = Path::new(BOOTSTRAP_DNS_JSON);
    if !path.is_file() {
        return DEFAULT_BOOTSTRAP_DNS.to_string();
    }
    let s = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) => {
            warn!("operaproxy: failed to read bootstrap_dns.json: {} -> using defaults", e);
            return DEFAULT_BOOTSTRAP_DNS.to_string();
        }
    };
    // Parse as JSON array of strings
    let arr: Vec<String> = match serde_json::from_str::<Vec<String>>(&s) {
        Ok(v) => v.into_iter().map(|e| e.trim().to_string()).filter(|e| !e.is_empty()).collect(),
        Err(e) => {
            warn!("operaproxy: failed to parse bootstrap_dns.json: {} -> using defaults", e);
            return DEFAULT_BOOTSTRAP_DNS.to_string();
        }
    };
    if arr.is_empty() {
        warn!("operaproxy: bootstrap_dns.json is empty -> using defaults");
        return DEFAULT_BOOTSTRAP_DNS.to_string();
    }
    arr.join(",")
}

/// Write bootstrap DNS resolver list to config/bootstrap_dns.json as a JSON array.
pub fn write_bootstrap_dns(resolvers: &[String]) -> Result<()> {
    let path = Path::new(BOOTSTRAP_DNS_JSON);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let json = serde_json::to_string_pretty(resolvers)
        .with_context(|| "serialize bootstrap DNS list")?;
    fs::write(path, json.as_bytes())
        .with_context(|| format!("write {}", path.display()))?;
    Ok(())
}

pub(super) fn build_bootstrap_dns_list() -> Result<String> {
    // Load configurable external resolvers (falls back to defaults if file missing/invalid)
    let external = read_bootstrap_dns();
    // Prepend local dnscrypt resolver if it is currently enabled
    if let Some(port) = dnscrypt::active_listen_port()? {
        let local = format!("https://127.0.0.1:{},{}", port, external);
        Ok(local)
    } else {
        Ok(external)
    }
}

pub(super) fn read_sni_entries(path: &Path) -> Result<Vec<OperaSniEntry>> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let parsed = serde_json::from_str::<Vec<OperaSniEntry>>(&s)
        .with_context(|| format!("parse {}", path.display()))?;
    let mut out = Vec::new();
    for item in parsed {
        let t = item.sni.trim();
        if t.is_empty() {
            continue;
        }

        let override_proxy_address = match item.override_proxy_address.as_deref().map(str::trim) {
            Some("") | None => None,
            Some(v) => {
                if is_valid_override_proxy_address(v) {
                    Some(v.to_string())
                } else {
                    warn!(
                        "operaproxy: invalid override_proxy_address '{}' for sni '{}' -> ignored",
                        v,
                        t
                    );
                    None
                }
            }
        };

        out.push(OperaSniEntry {
            sni: t.to_string(),
            use_byedpi: item.use_byedpi,
            proxy_mode: item.proxy_mode,
            custom_proxy: item.custom_proxy,
            override_proxy_address,
        });
    }
    Ok(out)
}

pub(super) fn is_valid_override_proxy_address(value: &str) -> bool {
    if value.is_empty() {
        return false;
    }

    if std::net::SocketAddr::from_str(value).is_ok() {
        return true;
    }

    let Some((host, port)) = value.rsplit_once(':') else {
        return false;
    };

    let host = host.trim();
    if host.is_empty() {
        return false;
    }

    port.parse::<u16>().is_ok()
}

/// Reads Opera server region from config file.
/// Allowed values: EU, AS, AM (case-insensitive). Any other value falls back to EU.
pub(super) fn read_server_region(path: &Path) -> String {
    let default = "EU".to_string();
    let s = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(_) => return default,
    };

    for line in s.lines() {
        let t = line.trim();
        if t.is_empty() || t.starts_with('#') {
            continue;
        }
        let first = t.split_whitespace().next().unwrap_or("");
        let up = first.to_uppercase();
        match up.as_str() {
            "EU" | "AS" | "AM" => return up,
            _ => {
                warn!(
                    "operaproxy: invalid server region '{}' in {} -> using EU",
                    first,
                    path.display()
                );
                return default;
            }
        }
    }

    default
}

pub(super) fn has_port_intersection(cfg: &PortJson, service_count: usize, include_t2s: bool) -> bool {
    let mut set = BTreeSet::new();
    let mut all = Vec::new();
    if include_t2s {
        all.push(cfg.t2s_port);
    }
    all.push(cfg.byedpi_port);
    for i in 0..service_count {
        all.push(cfg.opera_start_port.saturating_add(i as u16));
    }
    for p in all {
        if !set.insert(p) {
            return true;
        }
    }
    false
}

