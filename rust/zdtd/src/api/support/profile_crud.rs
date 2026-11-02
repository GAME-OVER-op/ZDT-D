use anyhow::Result;
use std::path::PathBuf;

use super::fs::{read_json, write_json_pretty};
use super::model::{ProfileState, ProfilesActive};

/// Contract shared by the standard VPN profile create flows in the local API.
/// Backend-specific validation/layout/default creation stays behind callbacks.
pub(crate) struct StandardProfileCreateSpec {
    pub(crate) label: &'static str,
    pub(crate) validate_name: fn(&str) -> Result<()>,
    pub(crate) ensure_root: fn() -> Result<()>,
    pub(crate) active_path: fn() -> PathBuf,
    pub(crate) ensure_layout: fn(&str) -> Result<()>,
    pub(crate) after_create: Option<fn(&str) -> Result<()>>,
}

pub(crate) fn create_standard_profile_named(
    requested: &str,
    spec: &StandardProfileCreateSpec,
) -> Result<String> {
    let name = requested.trim();
    (spec.validate_name)(name)?;
    (spec.ensure_root)()?;

    let active_path = (spec.active_path)();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) {
        anyhow::bail!("profile already exists");
    }
    active
        .profiles
        .insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;
    (spec.ensure_layout)(name)?;
    if let Some(after_create) = spec.after_create {
        after_create(name)?;
    }
    Ok(name.to_string())
}

pub(crate) fn create_standard_profile_next(spec: &StandardProfileCreateSpec) -> Result<String> {
    (spec.ensure_root)()?;
    let active: ProfilesActive = read_json(&(spec.active_path)()).unwrap_or_default();
    for n in 1..=9999u32 {
        let next = format!("profile{n}");
        if next.len() > 10 {
            break;
        }
        if !active.profiles.contains_key(&next) {
            create_standard_profile_named(&next, spec)?;
            return Ok(next);
        }
    }
    anyhow::bail!("no free {} profile name", spec.label)
}

/// Lists non-hidden child directories and validates each name with the caller's
/// existing backend rule. Used by sing-box and sing-box-lx server CRUD.
pub(crate) fn list_validated_child_dirs(
    root: &std::path::Path,
    validate_name: fn(&str) -> Result<()>,
    ensure_root: bool,
) -> Result<Vec<String>> {
    if ensure_root {
        std::fs::create_dir_all(root)?;
    }
    let mut out = Vec::new();
    if let Ok(rd) = std::fs::read_dir(root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            if name.starts_with('.') {
                continue;
            }
            validate_name(name)?;
            out.push(name.to_string());
        }
    }
    out.sort();
    Ok(out)
}

/// Returns the next `<prefix><N>` child-directory name using the same max-N
/// rule historically duplicated by sing-box, sing-box-lx and wireproxy.
pub(crate) fn next_prefixed_child_dir(root: &std::path::Path, prefix: &str) -> Result<String> {
    std::fs::create_dir_all(root)?;
    let mut max_n = 0u32;
    if let Ok(rd) = std::fs::read_dir(root) {
        for ent in rd.flatten() {
            let path = ent.path();
            if !path.is_dir() {
                continue;
            }
            let Some(name) = path.file_name().and_then(|s| s.to_str()) else {
                continue;
            };
            if name.starts_with('.') {
                continue;
            }
            if let Some(rest) = name.strip_prefix(prefix) {
                if let Ok(n) = rest.parse::<u32>() {
                    max_n = max_n.max(n);
                }
            }
        }
    }
    Ok(format!("{prefix}{}", max_n + 1))
}

pub(crate) fn profile_list_response(active_path: &std::path::Path) -> serde_json::Value {
    let active: ProfilesActive = read_json(active_path).unwrap_or_default();
    let mut profiles = Vec::new();
    for (name, state) in active.profiles {
        profiles.push(serde_json::json!({"name": name, "enabled": state.enabled}));
    }
    profiles.sort_by(|a, b| a["name"].as_str().cmp(&b["name"].as_str()));
    serde_json::json!({"ok": true, "profiles": profiles})
}

/// Removes a profile from active.json. `require_existing=false` preserves the
/// historic idempotent DELETE semantics of wireproxy/myproxy/myprogram.
pub(crate) fn remove_profile_from_active(
    active_path: &std::path::Path,
    profile: &str,
    require_existing: bool,
) -> Result<()> {
    let mut active: ProfilesActive = read_json(active_path).unwrap_or_default();
    let existed = active.profiles.remove(profile).is_some();
    if require_existing && !existed {
        anyhow::bail!("profile not found");
    }
    write_json_pretty(active_path, &active)
}

/// Best-effort archive behavior used by the API delete routes: create the
/// deleted root if possible, timestamp to whole seconds, ignore rename errors.
pub(crate) fn archive_profile_dir(
    source: &std::path::Path,
    deleted_root: &std::path::Path,
    profile: &str,
) {
    if !source.exists() {
        return;
    }
    std::fs::create_dir_all(deleted_root).ok();
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let destination = deleted_root.join(format!("{profile}.{ts}"));
    let _ = std::fs::rename(source, destination);
}

pub(crate) fn set_profile_enabled(
    active_path: &std::path::Path,
    profile: &str,
    enabled: bool,
) -> Result<()> {
    let mut active: ProfilesActive = read_json(active_path).unwrap_or_default();
    let state = active
        .profiles
        .get_mut(profile)
        .ok_or_else(|| anyhow::anyhow!("profile not found"))?;
    state.enabled = enabled;
    write_json_pretty(active_path, &active)
}

/// Contract shared by profile create flows that allocate t2s/web ports and a default setting.
/// Backend-specific validation/layout/port allocation stays behind callbacks.
pub(crate) struct ProfileCreateSpec {
    pub(crate) validate_name: fn(&str) -> Result<()>,
    pub(crate) active_path: fn() -> PathBuf,
    pub(crate) ensure_layout: fn(&str) -> Result<()>,
    pub(crate) profile_root: fn(&str) -> PathBuf,
    pub(crate) suggest_ports: fn() -> Result<(u16, u16)>,
    pub(crate) default_setting: fn(u16, u16) -> serde_json::Value,
}

pub(crate) fn create_profile_named_generic(requested: &str, spec: &ProfileCreateSpec) -> Result<String> {
    let name = requested.trim();
    (spec.validate_name)(name)?;

    let active_path = (spec.active_path)();
    let mut active: ProfilesActive = read_json(&active_path).unwrap_or_default();
    if active.profiles.contains_key(name) {
        anyhow::bail!("profile already exists");
    }
    active.profiles.insert(name.to_string(), ProfileState { enabled: false });
    write_json_pretty(&active_path, &active)?;

    (spec.ensure_layout)(name)?;
    let profile_setting = (spec.profile_root)(name).join("setting.json");
    if !profile_setting.exists() {
        let (t2s_port, t2s_web_port) = (spec.suggest_ports)()?;
        write_json_pretty(&profile_setting, &(spec.default_setting)(t2s_port, t2s_web_port))?;
    }

    Ok(name.to_string())
}

/// Picks the next `profileN` name exactly like the previous backend-local copies:
/// bare numeric names and `profile<N>` names both count towards the maximum.
pub(crate) fn create_profile_next_generic(spec: &ProfileCreateSpec) -> Result<String> {
    let active: ProfilesActive = read_json(&(spec.active_path)()).unwrap_or_default();
    let mut max_n = 0u32;
    for k in active.profiles.keys() {
        if let Ok(n) = k.parse::<u32>() {
            max_n = max_n.max(n);
            continue;
        }
        if let Some(rest) = k.strip_prefix("profile") {
            if let Ok(n) = rest.parse::<u32>() {
                max_n = max_n.max(n);
            }
        }
    }
    let next = format!("profile{}", max_n + 1);
    create_profile_named_generic(&next, spec)?;
    Ok(next)
}
