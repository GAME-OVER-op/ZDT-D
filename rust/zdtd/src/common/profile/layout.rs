use anyhow::Result;
use std::fs;
use std::path::{Path, PathBuf};

pub const ACTIVE_JSON: &str = "active.json";
pub const PROFILE_DIR: &str = "profile";
pub const SETTING_JSON: &str = "setting.json";
pub const APP_UID_DIR: &str = "app/uid";
pub const APP_OUT_DIR: &str = "app/out";
pub const APP_UID_USER_PROGRAM: &str = "app/uid/user_program";
pub const APP_OUT_USER_PROGRAM: &str = "app/out/user_program";
pub const LOG_DIR: &str = "log";
pub const TMP_DIR: &str = "tmp";
pub const WORK_DIR: &str = "work";


pub fn active_json_path(program_root: &Path) -> PathBuf {
    program_root.join(ACTIVE_JSON)
}

pub fn profiles_root_path(program_root: &Path) -> PathBuf {
    program_root.join(PROFILE_DIR)
}

pub fn profile_dir(profiles_root: &Path, profile: &str) -> PathBuf {
    profiles_root.join(profile)
}

pub fn setting_path(profile_root: &Path) -> PathBuf {
    profile_root.join(SETTING_JSON)
}

pub fn app_uid_program_path(profile_root: &Path) -> PathBuf {
    profile_root.join(APP_UID_USER_PROGRAM)
}

pub fn app_out_program_path(profile_root: &Path) -> PathBuf {
    profile_root.join(APP_OUT_USER_PROGRAM)
}

/// Creates only the relative directories requested by the backend. Keeping the
/// list explicit preserves backend-specific layouts (`log`, `tmp`, `work`, etc.)
/// instead of silently creating directories a backend never used before.
pub fn ensure_relative_dirs(root: &Path, dirs: &[&str]) -> Result<()> {
    for dir in dirs {
        fs::create_dir_all(root.join(dir))?;
    }
    Ok(())
}
