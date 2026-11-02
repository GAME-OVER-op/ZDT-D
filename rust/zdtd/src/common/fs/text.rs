use anyhow::Result;
use std::fs;
use std::path::Path;

/// Creates an empty file only when it does not already exist. Existing content
/// is never truncated.
pub fn ensure_empty_file(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if !path.exists() {
        fs::write(path, "")?;
    }
    Ok(())
}

/// Atomic replacement style used by several program backends: write a sibling
/// `<name>.tmp` file and rename it over the destination.
pub fn write_text_tmp_rename(path: &Path, content: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Sibling `.tmp` replacement with best-effort parent creation and the
/// historical `write <tmp>` / `rename <tmp> -> <path>` anyhow contexts used by
/// proxyInfo, blockedquic and Tor.
pub fn write_text_atomic_context(path: &Path, content: &str) -> Result<()> {
    use anyhow::Context as _;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path)
        .with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

/// Sibling `.tmp` replacement used by the API/ports/tgwsproxy family. Parent
/// creation is intentionally best-effort and errors retain the historical
/// `write failed` / `rename failed` wording.
pub fn write_text_atomic_best_effort(path: &Path, content: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, content.as_bytes())
        .map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, path)
        .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), path.display()))?;
    Ok(())
}

/// Creates an empty file only when absent using the atomic/context semantics
/// shared by proxyInfo, blockedquic and Tor.
pub fn ensure_empty_file_atomic_context(path: &Path) -> Result<()> {
    if !path.exists() {
        write_text_atomic_context(path, "")?;
    }
    Ok(())
}
