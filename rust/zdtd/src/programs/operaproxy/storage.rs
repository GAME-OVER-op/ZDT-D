
use anyhow::{Context, Result};

use serde::{Deserialize};
use std::{fs::{self, OpenOptions}, path::{Path, PathBuf}};


use super::model::*;

pub(super) fn truncate_file(p: &Path) -> Result<()> {
    let _ = OpenOptions::new().create(true).write(true).truncate(true).open(p)
        .with_context(|| format!("truncate {}", p.display()))?;
    Ok(())
}

pub(super) fn find_bin(name: &str) -> Result<PathBuf> {
    let p = Path::new(BIN_DIR).join(name);
    if p.is_file() {
        return Ok(p);
    }
    // some builds may ship with .bin suffix
    let p2 = Path::new(BIN_DIR).join(format!("{}.bin", name));
    if p2.is_file() {
        return Ok(p2);
    }
    anyhow::bail!("binary not found: {}", p.display())
}

pub(super) fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::common::fs::json::read_json_short_ctx(path)
}

pub(super) fn ensure_dir<P: AsRef<Path>>(p: P) -> Result<()> {
    let p = p.as_ref();
    fs::create_dir_all(p).with_context(|| format!("mkdir {}", p.display()))?;
    Ok(())
}

pub(super) fn ensure_file_empty(path: &Path) -> Result<()> {
    if path.is_file() {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("mkdir {}", parent.display()))?;
    }
    fs::write(path, b"")
        .with_context(|| format!("create empty file {}", path.display()))?;
    Ok(())
}
