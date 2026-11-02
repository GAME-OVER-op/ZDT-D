#![allow(dead_code)]
use anyhow::{bail, Context, Result};
use serde_json::{Map, Value};
use std::{collections::BTreeSet, fs, path::{Path, PathBuf}, time::Duration};

use crate::common::fs::ensure_parent_dir;
use crate::common::network::is_valid_ifname;
use crate::common::process::shell::{self, Capture};

use super::{append_check_log, forbidden_tun, unique_tmp_path, write_json_atomic, VariantSpec};

const SINGBOX_CHECK_TIMEOUT: Duration = Duration::from_secs(10);

pub(crate) fn collect_singbox_inbound_tags(obj: &Map<String, Value>) -> Vec<String> {
    let mut tags = obj
        .get("inbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|item| item.as_object())
                .filter_map(|o| o.get("tag").and_then(|v| v.as_str()))
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    tags.sort();
    tags.dedup();
    tags
}

pub(crate) fn collect_singbox_direct_dns_domains(obj: &Map<String, Value>) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    if let Some(rules) = obj
        .get("dns")
        .and_then(|v| v.as_object())
        .and_then(|o| o.get("rules"))
        .and_then(|v| v.as_array())
    {
        for rule in rules {
            let Some(rule_obj) = rule.as_object() else { continue };
            if rule_obj
                .get("server")
                .and_then(|v| v.as_str())
                .map(|s| s == "dns-direct")
                .unwrap_or(false)
            {
                collect_domains_from_value(rule_obj.get("domain"), &mut out);
            }
        }
    }
    out
}

pub(crate) fn collect_domains_from_value(value: Option<&Value>, out: &mut BTreeSet<String>) {
    match value {
        Some(Value::String(s)) => {
            let s = s.trim();
            if !s.is_empty() {
                out.insert(s.to_string());
            }
        }
        Some(Value::Array(arr)) => {
            for item in arr {
                collect_domains_from_value(Some(item), out);
            }
        }
        _ => {}
    }
}

pub(crate) fn has_singbox_outbound_tag(obj: &Map<String, Value>, tag: &str) -> bool {
    obj.get("outbounds")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter().any(|item| {
                item.as_object()
                    .and_then(|o| o.get("tag"))
                    .and_then(|v| v.as_str())
                    .map(|s| s == tag)
                    .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

pub(crate) fn is_vpn_removed_route_rule(rule: &Value) -> bool {
    let Some(obj) = rule.as_object() else { return false };
    obj.get("action")
        .and_then(|v| v.as_str())
        .map(|s| s.eq_ignore_ascii_case("hijack-dns"))
        .unwrap_or(false)
}

pub(crate) fn normalize_domain_strategy(strategy: &str) -> String {
    match strategy.trim().to_ascii_lowercase().as_str() {
        "prefer_ipv6" => "prefer_ipv6".to_string(),
        "ipv4_only" => "ipv4_only".to_string(),
        "ipv6_only" => "ipv6_only".to_string(),
        _ => "prefer_ipv4".to_string(),
    }
}

pub(crate) fn effective_vpn_tun_from_config_value(
    spec: VariantSpec,
    value: &Value,
    fallback: &str,
) -> Result<String> {
    let from_config = value
        .get("inbounds")
        .and_then(|v| v.as_array())
        .and_then(|arr| {
            arr.iter().find_map(|item| {
                let obj = item.as_object()?;
                let ty = obj.get("type")?.as_str()?.trim().to_ascii_lowercase();
                if ty != "tun" {
                    return None;
                }
                obj.get("interface_name")
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(ToOwned::to_owned)
            })
        });
    let tun = from_config.unwrap_or_else(|| fallback.trim().to_string()).trim().to_string();
    let tun = if tun.is_empty() { spec.default_tun.to_string() } else { tun };
    if !is_valid_ifname(&tun) || forbidden_tun(&tun) {
        bail!("tun must be 1..15 chars, must be a TUN name, and must not be a physical/system interface");
    }
    Ok(tun)
}

pub(crate) fn write_json_value_if_changed(
    spec: VariantSpec,
    config_path: &Path,
    original: &str,
    value: &Value,
) -> Result<()> {
    let normalized = serde_json::to_string_pretty(value)?;
    let normalized = format!("{}\n", normalized);
    if normalized != original {
        let tmp = unique_tmp_path(config_path);
        fs::write(&tmp, normalized).with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, config_path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), config_path.display()))?;
        log::info!("{}: normalized config {}", spec.label, config_path.display());
    }
    Ok(())
}

pub(crate) fn output_mentions_dns_mode(out: &str) -> bool {
    out.to_ascii_lowercase().contains("dns_mode")
}

pub(crate) fn remove_tun_dns_mode(spec: VariantSpec, config_path: &Path) -> Result<()> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;
    if let Some(inbounds) = value.get_mut("inbounds").and_then(|v| v.as_array_mut()) {
        for inbound in inbounds {
            let Some(obj) = inbound.as_object_mut() else { continue };
            if obj
                .get("type")
                .and_then(|v| v.as_str())
                .map(|s| s.eq_ignore_ascii_case("tun"))
                .unwrap_or(false)
            {
                obj.remove("dns_mode");
                obj.remove("dns_address");
            }
        }
    }
    write_json_value_if_changed(spec, config_path, &original, &value)
}

pub(crate) fn run_singbox_check(spec: VariantSpec, config_path: &Path) -> Result<(i32, String)> {
    let cfg = config_path
        .to_str()
        .ok_or_else(|| anyhow::anyhow!("non-utf8 config path: {}", config_path.display()))?;
    shell::run_timeout(spec.binary, &["check", "-c", cfg], Capture::Both, SINGBOX_CHECK_TIMEOUT)
        .with_context(|| format!("run {} check -c {}", spec.label, config_path.display()))
}

pub(crate) fn write_check_failure_log(
    spec: VariantSpec,
    log_path: &Path,
    config_path: &Path,
    code: i32,
    out: &str,
) -> Result<()> {
    let text = format!(
        "{} check failed\nconfig: {}\nrc: {}\n{}\n",
        spec.label,
        config_path.display(),
        code,
        out.trim()
    );
    fs::write(log_path, text).with_context(|| format!("write {}", log_path.display()))
}

pub(crate) fn singbox_check_config(spec: VariantSpec, config_path: &Path) -> Result<()> {
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code != 0 {
        bail!("{} check failed rc={code}: {}", spec.label, out.trim());
    }
    Ok(())
}

pub(crate) fn singbox_check_config_with_log(
    spec: VariantSpec,
    config_path: &Path,
    log_path: &Path,
) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code != 0 {
        write_check_failure_log(spec, log_path, config_path, code, &out)?;
        bail!("{} check failed rc={code}: {}", spec.label, out.trim());
    }
    Ok(())
}

pub(crate) fn singbox_check_config_with_vpn_retry(
    spec: VariantSpec,
    config_path: &Path,
    log_path: &Path,
) -> Result<()> {
    ensure_parent_dir(log_path)?;
    let (code, out) = run_singbox_check(spec, config_path)?;
    if code == 0 {
        return Ok(());
    }

    if output_mentions_dns_mode(&out) {
        append_check_log(
            log_path,
            &format!(
                "{} check failed with dns_mode, retrying without dns_mode\nconfig: {}\nrc: {}\n{}\n",
                spec.label,
                config_path.display(),
                code,
                out.trim()
            ),
        )?;
        remove_tun_dns_mode(spec, config_path)?;
        let (retry_code, retry_out) = run_singbox_check(spec, config_path)?;
        if retry_code == 0 {
            append_check_log(
                log_path,
                &format!("{} check succeeded after removing unsupported tun dns_mode\n", spec.label),
            )?;
            return Ok(());
        }
        write_check_failure_log(spec, log_path, config_path, retry_code, &retry_out)?;
        bail!("{} check failed rc={retry_code}: {}", spec.label, retry_out.trim());
    }

    write_check_failure_log(spec, log_path, config_path, code, &out)?;
    bail!("{} check failed rc={code}: {}", spec.label, out.trim())
}

pub(crate) fn prepare_runtime_config_with(
    spec: VariantSpec,
    config_path: &Path,
    endpoint_resolve: bool,
    collect_ipv4s: fn(&Value) -> Vec<String>,
    resolve_servers: fn(&mut Value) -> Result<Vec<String>>,
) -> Result<(PathBuf, Vec<String>)> {
    let original = fs::read_to_string(config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let mut value: Value = serde_json::from_str(&original)
        .with_context(|| format!("parse json {}", config_path.display()))?;

    if !endpoint_resolve {
        return Ok((config_path.to_path_buf(), collect_ipv4s(&value)));
    }

    let endpoint_escape_ips = resolve_servers(&mut value)?;
    let runtime_path = config_path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .join("tmp/config.resolved.json");
    write_json_atomic(&runtime_path, &value)
        .with_context(|| format!("write resolved {} config {}", spec.label, runtime_path.display()))?;
    Ok((runtime_path, endpoint_escape_ips))
}
