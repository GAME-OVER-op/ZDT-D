use anyhow::{Context, Result};
use log::{info, warn};
use serde_json::Value;
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};

use crate::programs::dnscrypt;

const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const API_PORT: u16 = 1006;

#[derive(Debug, Clone)]
struct PortEntry {
    program: &'static str,
    profile: String,
    port_path: PathBuf,
    port: u16,
    base: u16,
}

fn program_base(program: &str) -> Option<u16> {
    match program {
        // zapret / zapret2
        "nfqws" | "nfqws2" => Some(200),
        // byedpi
        "byedpi" => Some(1130),
        // dpitunnel
        "dpitunnel" => Some(1840),
        _ => None,
    }
}

fn working_program_dir(program: &str) -> PathBuf {
    Path::new(WORKING_DIR).join(program)
}

fn read_json_value(p: &Path) -> Result<Value> {
    let txt = fs::read_to_string(p).map_err(|e| anyhow::anyhow!("read failed {}: {e}", p.display()))?;
    serde_json::from_str(&txt).map_err(|e| anyhow::anyhow!("bad JSON {}: {e}", p.display()))
}

fn write_json_pretty(p: &Path, v: &Value) -> Result<()> {
    if let Some(parent) = p.parent() {
        fs::create_dir_all(parent).ok();
    }
    let tmp = p.with_extension("tmp");
    let txt = serde_json::to_string_pretty(v)?;
    fs::write(&tmp, txt.as_bytes()).map_err(|e| anyhow::anyhow!("write failed {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p)
        .map_err(|e| anyhow::anyhow!("rename failed {} -> {}: {e}", tmp.display(), p.display()))?;
    Ok(())
}

fn collect_reserved_ports() -> BTreeSet<u16> {
    let mut used = BTreeSet::new();

    // Daemon API server.
    used.insert(API_PORT);

    // dnscrypt listen port (if parseable).
    if let Ok(Some(p)) = dnscrypt::active_listen_port() {
        if p != 0 {
            used.insert(p);
        }
    }

    // operaproxy pipeline ports (t2s, byedpi, and the opera socks pool).
    let op = working_program_dir("operaproxy").join("port.json");
    if let Ok(v) = read_json_value(&op) {
        let t2s = v.get("t2s_port").and_then(|x| x.as_u64()).and_then(|x| u16::try_from(x).ok());
        let byedpi = v
            .get("byedpi_port")
            .and_then(|x| x.as_u64())
            .and_then(|x| u16::try_from(x).ok());
        let start = v
            .get("opera_start_port")
            .and_then(|x| x.as_u64())
            .and_then(|x| u16::try_from(x).ok());
        let max = v.get("max_services").and_then(|x| x.as_u64()).unwrap_or(1);

        if let Some(p) = t2s {
            if p != 0 {
                used.insert(p);
            }
        }
        if let Some(p) = byedpi {
            if p != 0 {
                used.insert(p);
            }
        }
        if let Some(start) = start {
            let max = max.min(16) as u16; // safety cap
            for i in 0..max {
                used.insert(start.saturating_add(i));
            }
        }
    }

    used
}

/// Collect all ports currently used by other programs/services.
///
/// This is intended for *conflict checks* by programs that manage their own ports
/// outside of the standard `*/port.json` profile layout (e.g. sing-box).
pub fn collect_used_ports_for_conflict_check() -> Result<BTreeSet<u16>> {
    let mut used = collect_reserved_ports();

    // Add current ports from editable profile-based programs.
    for e in collect_adjustable_ports()? {
        if e.port != 0 {
            used.insert(e.port);
        }
    }
    Ok(used)
}

fn collect_adjustable_ports() -> Result<Vec<PortEntry>> {
    let mut out = Vec::new();

    for program in ["nfqws", "nfqws2", "byedpi", "dpitunnel"] {
        let base = match program_base(program) {
            Some(b) => b,
            None => continue,
        };
        let root = working_program_dir(program);
        if let Ok(rd) = fs::read_dir(&root) {
            for ent in rd.flatten() {
                let path = ent.path();
                if !path.is_dir() {
                    continue;
                }
                let profile = match path.file_name().and_then(|s| s.to_str()) {
                    Some(s) => s.to_string(),
                    None => continue,
                };
                let port_path = path.join("port.json");
                if !port_path.is_file() {
                    continue;
                }
                let v = match read_json_value(&port_path) {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                let port = v
                    .get("port")
                    .and_then(|x| x.as_u64())
                    .and_then(|x| u16::try_from(x).ok())
                    .unwrap_or(0);

                out.push(PortEntry {
                    program,
                    profile,
                    port_path,
                    port,
                    base,
                });
            }
        }
    }

    out.sort_by(|a, b| (a.program, &a.profile).cmp(&(b.program, &b.profile)));
    Ok(out)
}

fn next_free_port(mut start: u16, base: u16, used: &BTreeSet<u16>) -> Result<u16> {
    // Keep within u16 range.
    if start == 0 {
        start = base;
    }
    if start < base {
        start = base;
    }

    let mut p = start as u32;
    while p <= u16::MAX as u32 {
        let pu = p as u16;
        if !used.contains(&pu) {
            return Ok(pu);
        }
        p += 1;
    }

    anyhow::bail!("no free port available starting from {start}");
}

/// Ensure that *all* ports/queue numbers used by editable profile-based programs are unique
/// and do not collide with fixed ports (dnscrypt, operaproxy, daemon API).
///
/// We only rewrite ports for: nfqws, nfqws2, byedpi, dpitunnel.
///
/// If a collision is found, we bump the conflicting entry to the next free number.
pub fn normalize_ports() -> Result<()> {
    let reserved = collect_reserved_ports();
    let mut used = reserved.clone();

    let entries = collect_adjustable_ports().context("collect adjustable ports")?;
    let mut changed = 0usize;

    for mut e in entries {
        // Treat port=0 as "uninitialized".
        let mut desired = if e.port == 0 { e.base } else { e.port };

        if used.contains(&desired) {
            // Collision: move forward (prefer minimal change).
            let start = desired.saturating_add(1);
            let new_port = next_free_port(start, e.base, &used)?;

            // Rewrite port.json preserving other fields.
            let mut v = match read_json_value(&e.port_path) {
                Ok(v) => v,
                Err(_) => serde_json::json!({"port": e.port}),
            };
            if let Value::Object(ref mut map) = v {
                map.insert("port".to_string(), Value::Number(serde_json::Number::from(new_port as u64)));
            } else {
                // If port.json isn't an object, replace with minimal schema.
                v = serde_json::json!({"port": new_port});
            }

            write_json_pretty(&e.port_path, &v)
                .with_context(|| format!("write {}", e.port_path.display()))?;

            warn!(
                "port collision: {}:{} {} -> {}",
                e.program, e.profile, desired, new_port
            );
            desired = new_port;
            changed += 1;
        }

        used.insert(desired);
    }

    if changed > 0 {
        info!("normalized ports: {changed} change(s)");
    }

    Ok(())
}

/// Allocate a port for a *new* profile (best-effort).
///
/// We try to pick the next port based on existing ports in the same program,
/// then resolve collisions globally via `normalize_ports()`.
pub fn suggest_port_for_new_profile(program: &str) -> Result<u16> {
    let base = program_base(program).context("unknown program")?;

    // Build a global used-set: fixed/reserved ports + all existing adjustable profile ports.
    let mut used = collect_reserved_ports();

    let entries = collect_adjustable_ports().unwrap_or_default();
    let mut max_self: Option<u16> = None;

    for e in entries {
        let port = if e.port == 0 { e.base } else { e.port };
        used.insert(port);
        if e.program == program {
            max_self = Some(max_self.map(|m| m.max(port)).unwrap_or(port));
        }
    }

    // Default start is next-after-max within the same program, otherwise base.
    let start = match max_self {
        Some(p) => p.saturating_add(1),
        None => base,
    };

    next_free_port(start, base, &used)
}

