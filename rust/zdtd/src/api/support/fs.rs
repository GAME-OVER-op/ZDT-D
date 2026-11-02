use anyhow::{Context, Result};
use serde::de::DeserializeOwned;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs;
use std::path::Path;

/// API-facing text reader. Keep its historic error strings separate from the
/// runtime/common JSON helpers because these messages are returned to clients.
pub(crate) fn read_text(path: &Path) -> Result<String> {
    let data = fs::read(path).map_err(|e| anyhow::anyhow!("read failed {}: {e}", path.display()))?;
    crate::common::fs::external_text::decode_external_text(&data)
        .with_context(|| format!("decode text {}", path.display()))
}

pub(crate) fn read_text_or_empty(path: &Path) -> Result<String> {
    match fs::read(path) {
        Ok(data) => crate::common::fs::external_text::decode_external_text(&data)
            .with_context(|| format!("decode text {}", path.display())),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read failed {}: {e}", path.display())),
    }
}

/// API-compatible sibling-.tmp replacement. Parent creation remains
/// best-effort exactly as it was in api.rs.
pub(crate) fn write_text_atomic(path: &Path, content: &str) -> Result<()> {
    crate::common::fs::write_text_atomic_best_effort(path, content)
}

pub(crate) fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let txt = read_text(path)?;
    serde_json::from_str(&txt).map_err(|e| anyhow::anyhow!("bad JSON {}: {e}", path.display()))
}

pub(crate) fn write_json_pretty<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    crate::common::fs::write_json_pretty_best_effort(path, value)
}

pub(in crate::api) fn sha256_hex_bytes(data: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(data);
    hex::encode(h.finalize())
}

pub(in crate::api) fn write_bytes_atomic(p: &Path, data: &[u8]) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, data)
        .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p)
        .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
    Ok(())
}

pub(in crate::api) fn chmod_best_effort(path: &Path, mode: u32) {
    // On Android, chmod is available; use std::process::Command to avoid pulling extra deps.
    let _ = std::process::Command::new("chmod")
        .arg(format!("{:o}", mode))
        .arg(path)
        .status();
}
