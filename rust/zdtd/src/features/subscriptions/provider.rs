use anyhow::{bail, Result};
use base64::{engine::general_purpose, Engine as _};
use serde_yaml::{Mapping as YamlMapping, Value as YamlValue};
use std::{fs, path::{PathBuf}};

use super::storage::*;
use super::crud::*;

pub(super) fn normalize_provider_body(text: &str) -> Result<(String, usize)> {
    // A full Clash YAML subscription may contain groups/rules in addition to `proxies`.
    // A file proxy-provider only needs the proxies collection, so extract it and keep
    // the generated provider deterministic. URI/base64 subscriptions are supported by
    // Mihomo directly and can stay in their native form.
    if let Ok(root) = serde_yaml::from_str::<YamlValue>(text) {
        if let Some(map) = root.as_mapping() {
            let key = YamlValue::String("proxies".to_string());
            if let Some(proxies) = map.get(&key).and_then(YamlValue::as_sequence) {
                let count = proxies.len();
                let mut out = YamlMapping::new();
                out.insert(key, YamlValue::Sequence(proxies.clone()));
                return Ok((serde_yaml::to_string(&YamlValue::Mapping(out))?, count));
            }
        }
    }

    // Some valid Clash YAML uses constructs unsupported by the generic YAML parser.
    // If a top-level proxies section is clearly present, keep the original body and
    // let Mihomo be the final parser rather than rejecting a configuration it accepts.
    let yaml_count = count_yaml_proxy_names(text);
    if yaml_count > 0 { return Ok((text.trim().to_string() + "\n", yaml_count)); }

    let uri_lines: Vec<&str> = text.lines()
        .map(str::trim)
        .filter(|line| is_supported_uri_line(line))
        .collect();
    if !uri_lines.is_empty() { return Ok((uri_lines.join("\n") + "\n", uri_lines.len())); }

    if let Some(decoded) = decode_base64_subscription(text) {
        if let Ok(root) = serde_yaml::from_str::<YamlValue>(&decoded) {
            if let Some(map) = root.as_mapping() {
                let key = YamlValue::String("proxies".to_string());
                if let Some(proxies) = map.get(&key).and_then(YamlValue::as_sequence) {
                    let count = proxies.len();
                    let mut out = YamlMapping::new();
                    out.insert(key, YamlValue::Sequence(proxies.clone()));
                    return Ok((serde_yaml::to_string(&YamlValue::Mapping(out))?, count));
                }
            }
        }
        let decoded_yaml_count = count_yaml_proxy_names(&decoded);
        if decoded_yaml_count > 0 {
            return Ok((decoded.trim().to_string() + "\n", decoded_yaml_count));
        }
        let decoded_uri_count = count_uri_lines(&decoded);
        if decoded_uri_count > 0 {
            // Keep the original base64 representation; Mihomo accepts base64 URI providers.
            return Ok((text.trim().to_string() + "\n", decoded_uri_count));
        }
    }

    bail!("subscription response does not look like a Mihomo/Clash provider or URI subscription")
}

pub(super) fn count_yaml_proxy_names(text: &str) -> usize {
    let mut in_proxies = false;
    let mut count = 0usize;
    for line in text.lines() {
        if !line.starts_with(' ') && !line.starts_with('\t') {
            let trimmed = line.trim();
            if trimmed == "proxies:" { in_proxies = true; continue; }
            if in_proxies && !trimmed.is_empty() && !trimmed.starts_with('#') { break; }
        }
        if in_proxies {
            let trimmed = line.trim_start();
            if trimmed.starts_with("- name:") || trimmed.starts_with("- {name:") || trimmed.starts_with("- { name:") { count += 1; }
        }
    }
    count
}

pub(super) fn is_supported_uri_line(line: &str) -> bool {
    const SCHEMES: &[&str] = &[
        "ss://", "ssr://", "vmess://", "vless://", "trojan://",
        "hysteria://", "hysteria2://", "hy2://", "tuic://",
        "socks://", "socks5://", "http://", "https://", "wireguard://",
        "anytls://", "snell://", "ssh://", "mieru://",
    ];
    SCHEMES.iter().any(|scheme| line.starts_with(scheme))
}

pub(super) fn count_uri_lines(text: &str) -> usize {
    text.lines().map(str::trim).filter(|line| is_supported_uri_line(line)).count()
}

pub(super) fn decode_base64_subscription(text: &str) -> Option<String> {
    let compact: String = text.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() < 16 || compact.len() > 32 * 1024 * 1024 { return None; }
    let bytes = general_purpose::STANDARD.decode(&compact)
        .or_else(|_| general_purpose::STANDARD_NO_PAD.decode(&compact))
        .or_else(|_| general_purpose::URL_SAFE.decode(&compact))
        .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(&compact))
        .ok()?;
    String::from_utf8(bytes).ok()
}

pub(super) fn profile_provider_dir(profile: &str) -> PathBuf {
    crate::programs::mihomo::profile_root(profile).join("work/subscriptions")
}

pub(super) fn profile_provider_path(profile: &str, id: &str) -> PathBuf {
    profile_provider_dir(profile).join(format!("{id}.yaml"))
}

pub(super) fn sync_provider_to_profile(profile: &str, id: &str) -> Result<()> {
    crate::programs::mihomo::ensure_valid_profile_name(profile)?;
    ensure_id(id)?;
    let src = provider_path(id);
    if !src.is_file() { bail!("subscription provider is not available"); }
    let dst = profile_provider_path(profile, id);
    if let Some(parent) = dst.parent() { fs::create_dir_all(parent)?; }
    let data = fs::read(&src)?;
    let tmp = atomic_tmp_path(&dst);
    fs::write(&tmp, data)?;
    if let Err(e) = fs::rename(&tmp, &dst) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    chmod_600_best_effort(&dst);
    Ok(())
}

pub(super) fn sync_provider_to_profiles(id: &str) {
    for profile in crate::programs::mihomo::profiles_using_subscription(id) {
        if let Err(e) = sync_provider_to_profile(&profile, id) {
            log::warn!("mihomo subscription sync failed id={} profile={}: {e:#}", id, profile);
        }
    }
}

pub(super) fn cleanup_profile_provider_copies(id: &str) {
    if let Ok(active) = crate::programs::mihomo::read_active() {
        for profile in active.profiles.keys() {
            let _ = fs::remove_file(profile_provider_path(profile, id));
        }
    }
}

