use std::fs;
use std::path::Path;

use anyhow::{Context, Result};

// from programs/myprogram.rs
pub fn ensure_parent_dir(p: &Path) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    Ok(())
}

// from programs/singbox.rs
pub fn is_nonempty_file(p: &Path) -> Result<bool> {
    let md = fs::metadata(p).with_context(|| format!("stat {}", p.display()))?;
    Ok(md.len() > 0)
}


/// Create or truncate a file after ensuring its parent directory exists.
///
/// This is the exact behavior shared by the profile-oriented backends that
/// previously carried identical local helpers.
pub fn truncate_file(path: &Path) -> Result<()> {
    ensure_parent_dir(path)?;
    let _ = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(path)?;
    Ok(())
}

/// Requires an existing directory and preserves the historical
/// `directory missing: <path>` error used by component backends.
pub fn require_dir_str(path: &str) -> Result<()> {
    let path = Path::new(path);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}

/// Requires an existing regular file and preserves the historical
/// `file missing: <path>` error used by component backends.
pub fn require_file_str(path: &str) -> Result<()> {
    let path = Path::new(path);
    if !path.is_file() {
        anyhow::bail!("file missing: {}", path.display());
    }
    Ok(())
}

/// Creates a directory tree using the `mkdir <path>` context used by the
/// myproxy/myprogram profile helpers.
pub fn create_dir_all_context_str(path: &str) -> Result<()> {
    fs::create_dir_all(path).with_context(|| format!("mkdir {path}"))?;
    Ok(())
}

/// Creates an empty file only when missing, with the historical
/// `write <path>` context used by myproxy/myprogram.
pub fn ensure_empty_file_write_context(path: &Path) -> Result<()> {
    if !path.exists() {
        ensure_parent_dir(path)?;
        fs::write(path, "").with_context(|| format!("write {}", path.display()))?;
    }
    Ok(())
}
