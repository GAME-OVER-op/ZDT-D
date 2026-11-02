use anyhow::Result;
use serde::Deserialize;
use std::path::{Component, Path, PathBuf};

use crate::settings;

pub(in crate::api) fn safe_module_path(rel: &str) -> Result<PathBuf> {
    let rel = rel.trim();
    if rel.is_empty() {
        anyhow::bail!("path is empty");
    }
    if rel.starts_with('/') {
        anyhow::bail!("absolute paths are not allowed");
    }
    // Generic FS API is intentionally limited to runtime/profile data.
    // Do not expose setting/* here: settings, start state and iptables backups have
    // dedicated API paths and must not be writable through this broad file endpoint.
    if !rel.starts_with("working_folder/") {
        anyhow::bail!("path must start with working_folder/");
    }

    let p = Path::new(rel);
    for c in p.components() {
        match c {
            Component::Normal(_) => {}
            _ => anyhow::bail!("invalid path component"),
        }
    }

    Ok(PathBuf::from(settings::MODULE_DIR).join(p))
}

#[derive(Deserialize)]
pub(in crate::api) struct FsReadReq {
    pub(in crate::api) path: String,
}

#[derive(Deserialize)]
pub(in crate::api) struct FsWriteReq {
    pub(in crate::api) path: String,
    pub(in crate::api) content: String,
}

pub(in crate::api) fn is_safe_segment(s: &str) -> bool {
    if s.is_empty() || s.len() > 64 {
        return false;
    }
    s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.')
}

pub(in crate::api) fn ensure_safe_segment(s: &str, what: &str) -> Result<()> {
    if !is_safe_segment(s) {
        anyhow::bail!("invalid {what}");
    }
    Ok(())
}

pub(in crate::api) fn is_safe_filename(s: &str) -> bool {
    // File names from Android's document picker are UTF-8 display names. Keep
    // international names intact, but never allow them to become paths.
    if s.trim().is_empty() || s.as_bytes().len() > 240 {
        return false;
    }
    if s == "." || s == ".." || s.contains('/') || s.contains('\\') || s.contains('\0') {
        return false;
    }
    s.chars().all(|c| !c.is_control())
}

pub(in crate::api) fn ensure_safe_filename(s: &str) -> Result<()> {
    if !is_safe_filename(s) {
        anyhow::bail!("invalid filename");
    }
    Ok(())
}

pub(in crate::api) fn decode_url_component(raw: &str) -> Result<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0usize;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                if i + 2 >= bytes.len() {
                    anyhow::bail!("bad percent-encoding");
                }
                let hi = (bytes[i + 1] as char)
                    .to_digit(16)
                    .ok_or_else(|| anyhow::anyhow!("bad percent-encoding"))?;
                let lo = (bytes[i + 2] as char)
                    .to_digit(16)
                    .ok_or_else(|| anyhow::anyhow!("bad percent-encoding"))?;
                out.push(((hi << 4) | lo) as u8);
                i += 3;
            }
            // Android currently builds path segments with URLEncoder, where a
            // space is represented as '+'. Literal plus signs are encoded as %2B.
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).map_err(|e| anyhow::anyhow!("path segment is not UTF-8: {e}"))
}
