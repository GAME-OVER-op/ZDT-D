use anyhow::{Context, Result};
use log::{info, warn};
use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    fs,
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
};

use crate::shell::{self, Capture};
use crate::logging;

const PROGRESS_EVERY: usize = 25;
const CMD_PACKAGE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);
const DUMPSYS_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);
const STAT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(2);

/// Mode of UID resolution.
/// - Default: dumpsys per package, fallback stat per package.
/// - Dpi: two passes (dumpsys for all, then stat for unresolved) to match the original script.
#[derive(Debug, Clone, Copy)]
pub enum Mode {
    Default,
    Dpi,
}
fn build_uid_map_from_cmd_package() -> Option<HashMap<String, u32>> {
    // Best-effort: `cmd package list packages -U` returns lines like:
    //   package:com.example.app uid:10243
    // Some devices restrict `cmd`/`dumpsys` when run as root uid=0, so we also try via shell uid=2000.
    let out = {
        let direct = shell::run_timeout(
            "cmd",
            &["package", "list", "packages", "-U"],
            Capture::Stdout,
            CMD_PACKAGE_TIMEOUT,
        )
        .ok();
        if let Some((code, s)) = direct {
            if code == 0 && !s.trim().is_empty() {
                Some(s)
            } else {
                None
            }
        } else {
            None
        }
        .or_else(|| {
            // Fallback: run as Android "shell" user (uid 2000)
            let cmdline = "cmd package list packages -U";
            shell::run_timeout(
                "su",
                &["-lp", "2000", "-c", cmdline],
                Capture::Stdout,
                CMD_PACKAGE_TIMEOUT,
            )
                .ok()
                .and_then(|(code, s)| if code == 0 && !s.trim().is_empty() { Some(s) } else { None })
        })?
    };

    let mut map: HashMap<String, u32> = HashMap::new();
    for line in out.lines() {
        let line = line.trim();
        if !line.starts_with("package:") || !line.contains("uid:") {
            continue;
        }
        let mut pkg_name: Option<&str> = None;
        let mut uid_val: Option<u32> = None;

        for tok in line.split_whitespace() {
            if let Some(rest) = tok.strip_prefix("package:") {
                if !rest.is_empty() {
                    pkg_name = Some(rest);
                }
            } else if let Some(rest) = tok.strip_prefix("uid:") {
                if !rest.is_empty() {
                    if let Ok(v) = rest.parse::<u32>() {
                        uid_val = Some(v);
                    }
                }
            } else if tok.chars().all(|c| c.is_ascii_digit()) && uid_val.is_none() {
                // handle "uid: 123"
                if let Ok(v) = tok.parse::<u32>() {
                    uid_val = Some(v);
                }
            }
        }

        if let (Some(p), Some(u)) = (pkg_name, uid_val) {
            map.insert(p.to_string(), u);
        }
    }
    Some(map)
}



impl Mode {
    pub fn from_str(s: &str) -> Self {
        match s {
            "dpi" | "DPI" => Mode::Dpi,
            _ => Mode::Default,
        }
    }
}

/// Tracks sha256 of input files inside a single JSON flag file (`flag.sha256`).
/// Key is the *full path* of the input file to avoid collisions between profiles.
#[derive(Debug, Clone)]
pub struct Sha256Tracker {
    pub flag_file: PathBuf,
}

#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
struct Sha256FlagJson {
    #[serde(default)]
    files: BTreeMap<String, String>,
}

impl Sha256Tracker {
    pub fn new(flag_file: impl Into<PathBuf>) -> Self {
        Self { flag_file: flag_file.into() }
    }

    /// Returns (old_hash, new_hash, changed).
    /// Always updates the JSON entry for the key (full path of input file).
    pub fn check_and_update(&self, input_file: &Path) -> Result<(Option<String>, String, bool)> {
        let key = input_file.display().to_string();
        let new_hash = sha256sum(input_file)?;

        let mut data = self.read_json().unwrap_or_default();
        let old_hash = data.files.get(&key).cloned();
        data.files.insert(key, new_hash.clone());
        self.write_json(&data)?;

        let changed = old_hash.as_deref() != Some(new_hash.as_str());
        Ok((old_hash, new_hash, changed))
    }

    fn read_json(&self) -> Result<Sha256FlagJson> {
        if !self.flag_file.is_file() {
            return Ok(Sha256FlagJson::default());
        }
        let s = fs::read_to_string(&self.flag_file)
            .with_context(|| format!("read flag file {}", self.flag_file.display()))?;
        let data: Sha256FlagJson = serde_json::from_str(&s)
            .with_context(|| format!("parse json flag file {}", self.flag_file.display()))?;
        Ok(data)
    }

    fn write_json(&self, data: &Sha256FlagJson) -> Result<()> {
        if let Some(parent) = self.flag_file.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("mkdir {}", parent.display()))?;
        }
        let s = serde_json::to_string_pretty(data)?;
        fs::write(&self.flag_file, s)
            .with_context(|| format!("write flag file {}", self.flag_file.display()))?;
        Ok(())
    }
}

/// Main entry: read packages from `input_file`, resolve UIDs, and write `package=uid` lines to `output_file`.
///
/// - Uses `tracker` to skip processing when input hash hasn't changed.
/// - Clears output file only when hash changed (same behavior as shell).
pub fn unified_processing(mode: Mode, tracker: &Sha256Tracker, output_file: &Path, input_file: &Path) -> Result<bool> {
    if !input_file.is_file() {
        anyhow::bail!("input_file not found: {}", input_file.display());
    }

    let (old_hash, new_hash, changed) = tracker.check_and_update(input_file)?;
    info!(
        "uidmap: key={} old_hash={:?} new_hash={} changed={}",
        input_file.display(),
        old_hash,
        new_hash,
        changed
    );


    // Read packages early so we can decide whether skipping is safe.
    let packages = read_package_list(input_file)?;
    let label = input_file
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("apps");
let input_has_packages = !packages.is_empty();

let output_exists = output_file.is_file();
let output_nonempty = match fs::metadata(output_file) {
    Ok(meta) => meta.len() > 0,
    Err(_) => false,
};

if !changed && output_exists {
    // If the input has packages but output is empty, rebuild to recover from stale/empty out files.
    if input_has_packages && !output_nonempty {
        warn!("uidmap: input hash unchanged but output is empty -> rebuilding {}", output_file.display());
    } else {
        info!("uidmap: input hash unchanged -> skip");
        return Ok(false);
    }
} else if !changed && !output_exists {
    warn!("uidmap: input hash unchanged but output missing -> rebuilding {}", output_file.display());
}

// If input has no packages, ensure output exists (empty) and finish.
if !input_has_packages {
    if let Some(parent) = output_file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if !output_exists {
        fs::write(output_file, b"")
            .with_context(|| format!("create empty output {}", output_file.display()))?;
    }
    info!("uidmap: no packages in {}", input_file.display());
    return Ok(true);
}
    let map = resolve_uid_map(mode, &packages, label)?;
    write_uid_map(output_file, &map)?;

    info!("uidmap: wrote {} entries to {}", map.len(), output_file.display());
    Ok(true)
}

/// Read package list from file (one per line).
/// - Trims whitespace
/// - Skips empty lines
/// - Skips comment lines (starting with '#', after trimming)
/// - Supports inline comments: `com.pkg.name # comment`
pub fn read_package_list(input_file: &Path) -> Result<Vec<String>> {
    let f = fs::File::open(input_file)
        .with_context(|| format!("open {}", input_file.display()))?;
    let r = BufReader::new(f);

    let mut out: Vec<String> = Vec::new();
    for line in r.lines() {
        let line = line?;
        let mut s = line.trim();

        if s.is_empty() {
            continue;
        }
        // Inline comments support
        if let Some((left, _right)) = s.split_once('#') {
            s = left.trim();
        }
        if s.is_empty() || s.starts_with('#') {
            continue;
        }

        out.push(s.to_string());
    }
    Ok(out)
}

/// Resolve a {package -> uid} map using dumpsys/stat like the original script.
pub fn resolve_uid_map(mode: Mode, packages: &[String], progress_label: &str) -> Result<BTreeMap<String, u32>> {
    let mut map: BTreeMap<String, u32> = BTreeMap::new();
    let mut unresolved: BTreeSet<String> = BTreeSet::new();

// Fast path: try resolve via `cmd package list packages -U` once.
if let Some(all) = build_uid_map_from_cmd_package() {
    for p in packages {
        if let Some(uid) = all.get(p) {
            map.insert(p.clone(), *uid);
        } else {
            unresolved.insert(p.clone());
        }
    }
} else {
    for p in packages {
        unresolved.insert(p.clone());
    }
}

    // If we already resolved all, we're done.
if unresolved.is_empty() {
    return Ok(map);
}
    // First pass: dumpsys
    let total_ds = unresolved.len();
    for pkg in unresolved.clone().iter() {
        if map.contains_key(pkg) { continue; }
        match resolve_uid_via_dumpsys(pkg)? {
            Some(uid) => { map.insert(pkg.clone(), uid); }
            None => { /* keep unresolved */ }
        }
    }

    
// Recompute unresolved after dumpsys pass
unresolved = unresolved
    .into_iter()
    .filter(|p| !map.contains_key(p))
    .collect();

if unresolved.is_empty() {
    return Ok(map);
}

// Second pass
    match mode {
        Mode::Dpi => {
            let total_stat = unresolved.len();
            for pkg in unresolved {
                if map.contains_key(&pkg) { continue; }
                if let Some(uid) = resolve_uid_via_stat(&pkg)? {
                    map.insert(pkg, uid);
                } else {
                    warn!("uidmap: uid not found for {}", pkg);
                }
            }
        }
        Mode::Default => {
            // In default mode we would have tried stat inline in shell.
            // We do it here for unresolved to keep code simpler and output identical.
            let total_stat = unresolved.len();
            for pkg in unresolved {
                if map.contains_key(&pkg) { continue; }
                if let Some(uid) = resolve_uid_via_stat(&pkg)? {
                    map.insert(pkg, uid);
                } else {
                    warn!("uidmap: uid not found for {}", pkg);
                }
            }
        }
    }

    Ok(map)
}

pub fn write_uid_map(output_file: &Path, map: &BTreeMap<String, u32>) -> Result<()> {
    let mut f = fs::OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(output_file)
        .with_context(|| format!("open output {}", output_file.display()))?;

    for (pkg, uid) in map {
        writeln!(f, "{}={}", pkg, uid)?;
    }
    f.flush()?;
    Ok(())
}

fn resolve_uid_via_dumpsys(package: &str) -> Result<Option<u32>> {
    fn parse_userid(out: &str) -> Option<u32> {
        for line in out.lines() {
            if let Some(pos) = line.find("userId=") {
                let tail = &line[pos + "userId=".len()..];
                let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
                if let Ok(uid) = digits.parse::<u32>() {
                    return Some(uid);
                }
            }
            // Some Android builds use "uid=" in dumpsys output
            if let Some(pos) = line.find("uid=") {
                let tail = &line[pos + "uid=".len()..];
                let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
                if let Ok(uid) = digits.parse::<u32>() {
                    return Some(uid);
                }
            }
        }
        None
    }

    // 1) direct
    if let Ok((code, out)) = shell::run_timeout("dumpsys", &["package", package], Capture::Stdout, DUMPSYS_TIMEOUT) {
        if code == 0 {
            if let Some(uid) = parse_userid(&out) {
                return Ok(Some(uid));
            }
        }
    }

    // 2) fallback via shell uid=2000 (often has better permission)
    let cmdline = format!("dumpsys package {}", package);
    if let Ok((code, out)) = shell::run_timeout("su", &["-lp", "2000", "-c", &cmdline], Capture::Stdout, DUMPSYS_TIMEOUT) {
        if code == 0 {
            if let Some(uid) = parse_userid(&out) {
                return Ok(Some(uid));
            }
        }
    }

    Ok(None)
}


fn resolve_uid_via_stat(package: &str) -> Result<Option<u32>> {
    let path = format!("/data/data/{package}");
    let (code, out) = shell::run_timeout("stat", &["-c", "%u", &path], Capture::Stdout, STAT_TIMEOUT)?;
    if code != 0 {
        return Ok(None);
    }
    let s = out.replace('\r', "").trim().to_string();
    if s.is_empty() {
        return Ok(None);
    }
    if let Ok(uid) = s.parse::<u32>() {
        return Ok(Some(uid));
    }
    Ok(None)
}

fn sha256sum(path: &Path) -> Result<String> {
    let p = path.display().to_string();
    // Try sha256sum, then toybox sha256sum.
    let candidates = [
        vec!["sha256sum".to_string(), p.clone()],
        vec!["toybox".to_string(), "sha256sum".to_string(), p.clone()],
        vec!["busybox".to_string(), "sha256sum".to_string(), p.clone()],
    ];

    for cmd in candidates {
        let exe = &cmd[0];
        let args: Vec<&str> = cmd[1..].iter().map(|s| s.as_str()).collect();
        let (code, out) = shell::run(exe, &args, Capture::Stdout)?;
        if code == 0 {
            if let Some(first) = out.split_whitespace().next() {
                if first.len() >= 32 {
                    return Ok(first.to_string());
                }
            }
        }
    }

    anyhow::bail!("sha256sum not available or failed for {}", path.display());
}
