use anyhow::Result;
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::fs;
use std::path::Path;

use super::ActiveProfiles;
use crate::common::fs::json::{read_json_short_ctx, write_json_pretty_tmp_rename};

/// Ensures the standard profile root and `active.json` exist using the same
/// pretty-json + sibling `.tmp` rename semantics used by the migrated backends.
pub fn ensure_active_profiles_file(profiles_root: &Path, active_path: &Path) -> Result<()> {
    fs::create_dir_all(profiles_root)?;
    if !active_path.exists() {
        write_json_pretty_tmp_rename(active_path, &ActiveProfiles::default())?;
    }
    Ok(())
}

pub fn read_active_profiles(profiles_root: &Path, active_path: &Path) -> Result<ActiveProfiles> {
    ensure_active_profiles_file(profiles_root, active_path)?;
    read_json_short_ctx(active_path)
}

pub fn write_active_profiles(
    profiles_root: &Path,
    active_path: &Path,
    active: &ActiveProfiles,
) -> Result<()> {
    ensure_active_profiles_file(profiles_root, active_path)?;
    write_json_pretty_tmp_rename(active_path, active)
}

pub fn read_profile_json<T: DeserializeOwned>(path: &Path) -> Result<T> {
    read_json_short_ctx(path)
}

pub fn write_profile_json<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    write_json_pretty_tmp_rename(path, value)
}

pub fn ensure_profile_json<T: Serialize>(path: &Path, default_value: &T) -> Result<()> {
    if !path.exists() {
        write_profile_json(path, default_value)?;
    }
    Ok(())
}
