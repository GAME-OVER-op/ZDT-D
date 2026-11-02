use std::fs;
use std::path::Path;

use anyhow::{Context, Result};

/// Service marker package: allows a profile to start without routing the ZDT-D app UID.
/// This marker is filtered before UID resolution and must never appear in app/out files.
pub const LAUNCH_MARKER_PACKAGE: &str = "com.android.zdtd.service";

pub fn is_launch_marker_package(s: &str) -> bool {
    s.trim().eq_ignore_ascii_case(LAUNCH_MARKER_PACKAGE)
}

pub fn content_has_launch_marker(raw: &str) -> bool {
    raw.lines().any(|line| {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        !s.is_empty() && is_launch_marker_package(s)
    })
}

pub fn file_has_launch_marker(p: &Path) -> Result<bool> {
    match fs::read_to_string(p) {
        Ok(s) => Ok(content_has_launch_marker(&s)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e).with_context(|| format!("read {}", p.display())),
    }
}

// from programs/byedpi.rs
pub fn count_valid_uid_pairs(path: &Path) -> Result<usize> {
    if !path.is_file() {
        return Ok(0);
    }
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut n = 0usize;
    for line in s.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((_pkg, uid_s)) = line.split_once('=') {
            let uid_s = uid_s.trim();
            if !uid_s.is_empty() && uid_s.chars().all(|c| c.is_ascii_digit()) {
                n += 1;
            }
        }
    }
    Ok(n)
}

// from programs/myvpn.rs
pub fn enabled_app_list_empty(path: &Path) -> bool {
    fs::read_to_string(path)
        .map(|raw| raw.lines().map(str::trim).all(|l| l.is_empty() || l.starts_with('#')))
        .unwrap_or(true)
}

/// Reads an app UID input list. Missing files are treated as empty, matching
/// the historical proxyInfo/blockedquic/Tor API contract.
pub fn read_optional_uid_program_text(path: &Path) -> Result<String> {
    match fs::read_to_string(path) {
        Ok(s) => Ok(s),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(String::new()),
        Err(e) => Err(anyhow::anyhow!("read uid_program failed: {e}")),
    }
}

/// Reads the generated `package=uid` output and returns sorted unique non-zero
/// UIDs. Missing output is equivalent to an empty assignment.
pub fn read_out_uids(path: &Path) -> Result<Vec<u32>> {
    let raw = match fs::read_to_string(path) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };
    Ok(parse_out_uids(&raw))
}

pub fn parse_out_uids(raw: &str) -> Vec<u32> {
    let mut out = std::collections::BTreeSet::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() {
            continue;
        }
        let Some((_, rhs)) = s.rsplit_once('=') else {
            continue;
        };
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.insert(uid);
            }
        }
    }
    out.into_iter().collect()
}

/// Parses package-only UID input lists used by API assignment inspection and
/// proxyInfo. Comments, blank lines and launch markers are ignored.
pub fn parse_package_set(raw: &str) -> std::collections::BTreeSet<String> {
    let mut out = std::collections::BTreeSet::new();
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        if is_launch_marker_package(s) {
            continue;
        }
        out.insert(s.to_string());
    }
    out
}


#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppSelection {
    Empty,
    MarkerOnly,
    RealApps,
}

/// Classifies an app input file using the shared ZDT-D launch-marker rules.
/// The read error context intentionally matches the former Mihomo/Mieru copies.
pub fn app_selection(path: &Path) -> Result<AppSelection> {
    let raw = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut has_marker = false;
    let mut has_real = false;
    for line in raw.lines() {
        let mut s = line.trim();
        if let Some((left, _)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() {
            continue;
        }
        if is_launch_marker_package(s) {
            has_marker = true;
        } else {
            has_real = true;
        }
    }
    Ok(if has_real {
        AppSelection::RealApps
    } else if has_marker {
        AppSelection::MarkerOnly
    } else {
        AppSelection::Empty
    })
}

pub fn app_list_requires_netd(path: &Path) -> bool {
    matches!(app_selection(path), Ok(AppSelection::RealApps))
}

pub fn app_list_has_real_apps(raw: &str) -> bool {
    raw.lines().map(str::trim).any(|s| {
        !s.is_empty()
            && !s.starts_with('#')
            && !is_launch_marker_package(s)
    })
}
