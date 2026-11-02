use crate::common::network::is_ipv4;
use anyhow::{bail, Context, Result};

#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{collections::{BTreeMap, BTreeSet}, fs::{self}, path::{Path, PathBuf}};

use crate::{platform::android::dns as android_dns};

use super::process::*;

pub(super) const ZDTD_OPENVPN_BLOCK_BEGIN: &str = "# ZDT-D Android CLI UID-only mode BEGIN";
pub(super) const ZDTD_OPENVPN_BLOCK_END: &str = "# ZDT-D Android CLI UID-only mode END";

pub(super) fn read_openvpn_config_text(path: &Path) -> Result<String> {
    let data = fs::read(path).with_context(|| format!("read {}", path.display()))?;
    let text = crate::common::fs::external_text::decode_external_text(&data)
        .with_context(|| format!("decode {}", path.display()))?;

    // Heal configs imported by older app versions. From this point on the
    // profile is guaranteed to be UTF-8/LF, so all later OpenVPN parsing and
    // runtime normalization sees the same bytes as the editor.
    if data.as_slice() != text.as_bytes() {
        let tmp = path.with_file_name("client.ovpn.encoding.tmp");
        fs::write(&tmp, text.as_bytes())
            .with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    }
    Ok(text)
}

pub(super) fn normalize_client_config_in_place(config_path: &Path, tun: &str, tmp_dir: &Path) -> Result<()> {
    let original = read_openvpn_config_text(config_path)?;
    let original_lines: Vec<&str> = original.lines().collect();
    let mut kept = Vec::<String>::new();
    let mut in_inline_block = false;
    let mut i = 0usize;

    while i < original_lines.len() {
        let raw = original_lines[i];
        let trimmed = raw.trim();

        if trimmed == ZDTD_OPENVPN_BLOCK_BEGIN {
            // Remove only a complete old managed block. If END is missing because a previous
            // write was interrupted, do not drop the rest of the user's config/certificates.
            if let Some(rel_end) = original_lines[i + 1..]
                .iter()
                .position(|line| line.trim() == ZDTD_OPENVPN_BLOCK_END)
            {
                i += rel_end + 2;
                continue;
            }
            i += 1;
            continue;
        }

        if starts_openvpn_inline_block(trimmed) {
            in_inline_block = true;
            kept.push(raw.to_string());
            i += 1;
            continue;
        }
        if in_inline_block {
            kept.push(raw.to_string());
            if ends_openvpn_inline_block(trimmed) {
                in_inline_block = false;
            }
            i += 1;
            continue;
        }

        if is_openvpn_line_managed_by_zdtd(trimmed) {
            i += 1;
            continue;
        }
        kept.push(raw.to_string());
        i += 1;
    }

    while kept.last().map(|s| s.trim().is_empty()).unwrap_or(false) {
        kept.pop();
    }

    kept.push(String::new());
    kept.push(ZDTD_OPENVPN_BLOCK_BEGIN.to_string());
    kept.push("# OpenVPN only raises the TUN interface; ZDT-D routes selected UIDs via Android netd.".to_string());
    kept.push("route-noexec".to_string());
    kept.push("pull-filter ignore \"redirect-gateway\"".to_string());
    kept.push("pull-filter ignore \"dhcp-option DNS\"".to_string());
    kept.push("pull-filter ignore \"tun-ipv6\"".to_string());
    kept.push("pull-filter ignore \"ifconfig-ipv6\"".to_string());
    kept.push("pull-filter ignore \"route-ipv6\"".to_string());
    kept.push(format!("tmp-dir {}", tmp_dir.display()));
    kept.push(format!("dev {tun}"));
    kept.push("dev-type tun".to_string());
    kept.push(ZDTD_OPENVPN_BLOCK_END.to_string());

    let normalized = format!("{}\n", kept.join("\n"));
    let effective_dev = effective_openvpn_dev(&normalized).unwrap_or_default();
    if effective_dev != tun {
        bail!(
            "openvpn config normalization failed: expected dev {}, got {}",
            tun,
            if effective_dev.is_empty() { "<none>" } else { effective_dev.as_str() }
        );
    }

    if normalized != original {
        info!(
            "openvpn: normalized config {} for tun={} tmp_dir={}",
            config_path.display(),
            tun,
            tmp_dir.display()
        );
        let tmp = config_path.with_file_name("client.ovpn.zdtd.tmp");
        fs::write(&tmp, normalized)
            .with_context(|| format!("write {}", tmp.display()))?;
        fs::rename(&tmp, config_path)
            .with_context(|| format!("rename {} -> {}", tmp.display(), config_path.display()))?;
    }
    Ok(())
}


pub(super) fn starts_openvpn_inline_block(trimmed: &str) -> bool {
    trimmed.starts_with('<') && !trimmed.starts_with("</") && trimmed.ends_with('>')
}

pub(super) fn ends_openvpn_inline_block(trimmed: &str) -> bool {
    trimmed.starts_with("</") && trimmed.ends_with('>')
}

pub(super) fn effective_openvpn_dev(config: &str) -> Option<String> {
    let mut in_inline_block = false;
    let mut dev = None;
    for raw in config.lines() {
        let trimmed = raw.trim();
        if starts_openvpn_inline_block(trimmed) {
            in_inline_block = true;
            continue;
        }
        if in_inline_block {
            if ends_openvpn_inline_block(trimmed) {
                in_inline_block = false;
            }
            continue;
        }
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        if matches!(parts.next(), Some(d) if d.eq_ignore_ascii_case("dev")) {
            if let Some(value) = parts.next() {
                dev = Some(value.to_string());
            }
        }
    }
    dev
}

pub(super) fn is_openvpn_line_managed_by_zdtd(trimmed: &str) -> bool {
    if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
        return false;
    }

    let lower = trimmed.to_ascii_lowercase();
    let mut parts = lower.split_whitespace();
    let directive = parts.next().unwrap_or("");
    match directive {
        "block-outside-dns" | "route-noexec" | "redirect-gateway" | "tmp-dir" | "dev" | "dev-type" | "tun-ipv6" => true,
        "ifconfig-ipv6" | "route-ipv6" => true,
        "dhcp-option" => parts.next() == Some("dns"),
        "pull-filter" => {
            let rest = parts.collect::<Vec<_>>().join(" ");
            rest.contains("ignore")
                && (rest.contains("redirect-gateway")
                    || rest.contains("dhcp-option dns")
                    || rest.contains("tun-ipv6")
                    || rest.contains("ifconfig-ipv6")
                    || rest.contains("route-ipv6"))
        }
        _ => false,
    }
}

pub(super) fn prepare_runtime_config(
    profile: &str,
    profile_dir: &Path,
    config_path: &Path,
    endpoint_resolve: bool,
) -> Result<(PathBuf, Vec<String>)> {
    let raw = read_openvpn_config_text(config_path)?;
    let (runtime, ips, skipped_domains, resolved_domain) = resolve_remote_lines(&raw, endpoint_resolve)?;

    if !skipped_domains.is_empty() {
        warn!(
            "openvpn: profile={} endpoint_resolve=false; domain remote left unchanged and endpoint escape skipped for {}",
            profile,
            skipped_domains.into_iter().collect::<Vec<_>>().join(",")
        );
    }

    if !ips.is_empty() {
        info!(
            "openvpn: profile={} endpoint escape ips={}",
            profile,
            ips.join(",")
        );
    }

    if !endpoint_resolve || !resolved_domain {
        return Ok((config_path.to_path_buf(), ips));
    }

    let runtime_path = profile_dir.join("tmp/client.resolved.ovpn");
    write_text_atomic(&runtime_path, &runtime)?;
    info!(
        "openvpn: profile={} endpoint_resolve enabled; runtime config={}",
        profile,
        runtime_path.display()
    );
    Ok((runtime_path, ips))
}

pub(super) fn resolve_remote_lines(raw: &str, endpoint_resolve: bool) -> Result<(String, Vec<String>, BTreeSet<String>, bool)> {
    let mut out = Vec::<String>::new();
    let mut ips = BTreeSet::<String>::new();
    let mut skipped_domains = BTreeSet::<String>::new();
    let mut cache = BTreeMap::<String, Vec<String>>::new();
    let mut resolved_domain = false;
    let mut in_inline_block = false;

    for line in raw.lines() {
        let trimmed = line.trim();
        if starts_openvpn_inline_block(trimmed) {
            in_inline_block = true;
            out.push(line.to_string());
            continue;
        }
        if in_inline_block {
            out.push(line.to_string());
            if ends_openvpn_inline_block(trimmed) {
                in_inline_block = false;
            }
            continue;
        }

        let (code, comment) = split_openvpn_inline_comment(line);
        let parts = code.split_whitespace().collect::<Vec<_>>();
        if parts.len() < 2 || !parts[0].eq_ignore_ascii_case("remote") {
            out.push(line.to_string());
            continue;
        }

        let host = parts[1]
            .trim()
            .trim_matches('"')
            .trim_matches('\'')
            .trim_end_matches('.')
            .to_string();
        if host.is_empty() {
            out.push(line.to_string());
            continue;
        }

        if is_ipv4(&host) {
            ips.insert(host);
            out.push(line.to_string());
            continue;
        }

        if !endpoint_resolve {
            skipped_domains.insert(host);
            out.push(line.to_string());
            continue;
        }

        let resolved = if let Some(cached) = cache.get(&host) {
            cached.clone()
        } else {
            let values = android_dns::resolve_ipv4_all(&host);
            cache.insert(host.clone(), values.clone());
            values
        };
        if resolved.is_empty() {
            bail!("cannot resolve OpenVPN remote host {host} to IPv4");
        }
        resolved_domain = true;

        let indent_len = code.len() - code.trim_start().len();
        let indent = &code[..indent_len];
        let directive = parts[0];
        let tail = if parts.len() > 2 {
            format!(" {}", parts[2..].join(" "))
        } else {
            String::new()
        };
        let comment_suffix = if comment.is_empty() {
            String::new()
        } else {
            format!(" {}", comment)
        };

        for ip in resolved {
            ips.insert(ip.clone());
            out.push(format!("{indent}{directive} {ip}{tail}{comment_suffix}"));
        }
    }

    Ok((
        format!("{}\n", out.join("\n")),
        ips.into_iter().collect(),
        skipped_domains,
        resolved_domain,
    ))
}

pub(super) fn split_openvpn_inline_comment(line: &str) -> (&str, &str) {
    let mut quote = None::<char>;
    let mut escaped = false;
    for (idx, ch) in line.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if ch == '\\' {
            escaped = true;
            continue;
        }
        match quote {
            Some(q) if ch == q => quote = None,
            Some(_) => continue,
            None if ch == '"' || ch == '\'' => quote = Some(ch),
            None if ch == '#' || ch == ';' => {
                return (line[..idx].trim_end(), line[idx..].trim_start());
            }
            None => {}
        }
    }
    (line.trim_end(), "")
}

