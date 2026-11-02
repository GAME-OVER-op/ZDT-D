use anyhow::{Context, Result};
use log::{info, warn};
use serde::Deserialize;
use std::{fs, io::Write, net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener}, path::Path, time::{SystemTime, UNIX_EPOCH}};



use super::runtime::*;
use super::storage::*;

pub(super) const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
pub(super) const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
pub(super) const DNSCRYPT_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt";
pub(super) const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

pub(super) const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json";
pub(super) const DNSCRYPT_TOML: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/setting/dnscrypt-proxy.toml";
pub(super) const D2S_TOML: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/d2set/d2s.toml";
pub(super) const D2S_LOG: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/log/d2s.log";
pub(super) const D2S_MINIMAL_CONFIG: &str = "backends = []\ndirect_fallback = true\n";
pub(super) const D2S_AUTO_PORT_START: u16 = 11990;

pub(super) const IPV6_RESETPROP_KEYS: [&str; 4] = [
    "net.ipv6.conf.all.accept_redirects",
    "net.ipv6.conf.all.disable_ipv6",
    "net.ipv6.conf.default.accept_redirects",
    "net.ipv6.conf.default.disable_ipv6",
];

#[derive(Debug, Deserialize)]
pub(super) struct ActiveJson {
    pub(super) enabled: bool,
}



pub fn ensure_d2s_config_exists() -> Result<()> {
    let path = Path::new(D2S_TOML);
    if path.is_file() {
        return Ok(());
    }

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("create D2S config directory {}", parent.display()))?;
    }

    match fs::OpenOptions::new().write(true).create_new(true).open(path) {
        Ok(mut file) => {
            file.write_all(D2S_MINIMAL_CONFIG.as_bytes())
                .with_context(|| format!("write minimal D2S config {}", path.display()))?;
            info!("created minimal D2S config: {}", path.display());
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists && path.is_file() => Ok(()),
        Err(error) => Err(error)
            .with_context(|| format!("create minimal D2S config {}", path.display())),
    }
}

pub fn active_listen_port() -> Result<Option<u16>> {
    crate::common::fs::require_dir_str(MODULE_DIR)?;
    crate::common::fs::require_dir_str(WORKING_DIR)?;
    crate::common::fs::require_dir_str(DNSCRYPT_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.enabled {
        return Ok(None);
    }

    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        return Ok(None);
    }

    let port = parse_listen_port(toml_path)?;
    if port == 0 {
        return Ok(None);
    }
    Ok(Some(port))
}

pub fn configured_d2s_listen_addr() -> Result<Option<SocketAddr>> {
    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        return Ok(None);
    }
    parse_active_d2s_listener(toml_path)
}

/// Connect D2S to DNSCrypt without reserializing the user's TOML.
///
/// The first free IPv4 loopback TCP port starting at 11990 is selected. If an
/// active non-D2S proxy is already configured, its exact line is preserved as
/// a comment directly above the new D2S proxy line. Existing local D2S
/// listeners are kept unchanged.
pub fn connect_d2s_proxy() -> Result<SocketAddr> {
    crate::common::fs::require_dir_str(MODULE_DIR)?;
    crate::common::fs::require_dir_str(WORKING_DIR)?;
    crate::common::fs::require_dir_str(DNSCRYPT_ROOT)?;
    ensure_d2s_config_exists()?;

    let path = Path::new(DNSCRYPT_TOML);
    if !path.is_file() {
        anyhow::bail!("DNSCrypt config not found: {}", path.display());
    }

    match parse_active_d2s_listener(path) {
        Ok(Some(existing)) => return Ok(existing),
        Ok(None) => {}
        Err(error) => {
            // A valid DNSCrypt proxy can use authentication or another format
            // that is intentionally not a D2S listener. Treat it as
            // "not connected" here; the exact original line is preserved by
            // the text editor below before the local D2S proxy is inserted.
            warn!("existing DNSCrypt proxy is not a usable D2S listener: {error:#}");
        }
    }

    let port = first_free_d2s_port(D2S_AUTO_PORT_START)?;
    let listener = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    let raw = fs::read_to_string(path)
        .with_context(|| format!("read {}", path.display()))?;
    let updated = connect_d2s_proxy_text(&raw, listener);

    // Refuse to save a malformed result. This also protects against unusual
    // hand-edited TOML layouts while preserving comments and formatting.
    toml::from_str::<toml::Value>(&updated)
        .with_context(|| format!("validate updated {}", path.display()))?;
    write_text_atomic(path, &updated)?;

    info!("D2S connected to DNSCrypt on {}", listener);
    Ok(listener)
}

pub(super) fn first_free_d2s_port(start: u16) -> Result<u16> {
    for port in start..=u16::MAX {
        match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
            Ok(listener) => {
                drop(listener);
                return Ok(port);
            }
            Err(error) if error.kind() == std::io::ErrorKind::AddrInUse => continue,
            Err(error) => {
                return Err(error).with_context(|| format!("probe D2S port 127.0.0.1:{port}"));
            }
        }
    }
    anyhow::bail!("no free D2S TCP port available from {start}")
}

pub(super) fn connect_d2s_proxy_text(raw: &str, listener: SocketAddr) -> String {
    let newline = if raw.contains("\r\n") { "\r\n" } else { "\n" };
    let had_trailing_newline = raw.ends_with('\n') || raw.ends_with('\r');
    let mut lines: Vec<String> = raw.lines().map(|line| line.trim_end_matches('\r').to_string()).collect();
    let new_proxy = format!("proxy = 'socks5://{}'", listener);

    let mut first_section: Option<usize> = None;
    let mut active_proxy: Option<usize> = None;
    let mut local_commented_proxy: Option<usize> = None;

    for (index, line) in lines.iter().enumerate() {
        let trimmed = line.trim_start();
        if trimmed.starts_with('[') {
            first_section = Some(index);
            break;
        }
        if is_proxy_assignment(trimmed) {
            active_proxy = Some(index);
            break;
        }
        if local_commented_proxy.is_none() && is_commented_local_proxy_assignment(trimmed) {
            local_commented_proxy = Some(index);
        }
    }

    if let Some(index) = active_proxy {
        let original = lines[index].clone();
        let trimmed = original.trim_start();
        let indent_len = original.len().saturating_sub(trimmed.len());
        let indent = &original[..indent_len];
        lines[index] = format!("{indent}# ZDT-D previous proxy: {trimmed}");
        lines.insert(index + 1, format!("{indent}{new_proxy}"));
    } else if let Some(index) = local_commented_proxy {
        let original = lines[index].clone();
        let trimmed = original.trim_start();
        let indent_len = original.len().saturating_sub(trimmed.len());
        let indent = &original[..indent_len];
        lines[index] = format!("{indent}{new_proxy}");
    } else {
        let index = first_section.unwrap_or(lines.len());
        lines.insert(index, new_proxy);
    }

    let mut out = lines.join(newline);
    if had_trailing_newline || out.is_empty() {
        out.push_str(newline);
    }
    out
}

pub(super) fn is_proxy_assignment(line: &str) -> bool {
    if line.starts_with('#') {
        return false;
    }
    line.split_once('=')
        .map(|(key, _)| key.trim() == "proxy")
        .unwrap_or(false)
}

pub(super) fn is_commented_local_proxy_assignment(line: &str) -> bool {
    let Some(commented) = line.strip_prefix('#') else {
        return false;
    };
    let commented = commented.trim_start();
    if !is_proxy_assignment(commented) {
        return false;
    }
    commented.contains("socks5://127.0.0.1:")
        || commented.contains("socks5://localhost:")
        || commented.contains("socks5://[::1]:")
}

pub(super) fn write_text_atomic(path: &Path, content: &str) -> Result<()> {
    let parent = path.parent().ok_or_else(|| anyhow::anyhow!("path has no parent: {}", path.display()))?;
    fs::create_dir_all(parent)
        .with_context(|| format!("create {}", parent.display()))?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let tmp = parent.join(format!(".{}.{}.tmp", path.file_name().and_then(|v| v.to_str()).unwrap_or("dnscrypt-proxy.toml"), stamp));
    fs::write(&tmp, content.as_bytes())
        .with_context(|| format!("write {}", tmp.display()))?;
    if let Err(error) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(error).with_context(|| format!("replace {}", path.display()));
    }
    Ok(())
}

pub fn active_d2s_listen_addr() -> Result<Option<SocketAddr>> {
    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;
    if !active.enabled {
        return Ok(None);
    }

    configured_d2s_listen_addr()
}

pub fn active_d2s_listen_port() -> Result<Option<u16>> {
    Ok(active_d2s_listen_addr()?.map(|addr| addr.port()))
}

