use std::{fs, path::Path};

use crate::common::process::shell;

pub fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

pub fn sort_dedup_pids(pids: &mut Vec<i32>) {
    pids.sort_unstable();
    pids.dedup();
}

pub fn filter_live_pids(pids: &mut Vec<i32>) {
    sort_dedup_pids(pids);
    pids.retain(|pid| *pid > 1 && process_pid_is_live(*pid as u32));
}

pub fn process_state(pid: i32) -> Option<char> {
    if pid <= 1 {
        return None;
    }
    let raw = fs::read_to_string(Path::new("/proc").join(pid.to_string()).join("stat")).ok()?;
    let rparen = raw.rfind(')')?;
    raw.get(rparen + 2..)?.split_whitespace().next()?.chars().next()
}

/// `/proc/<pid>` may outlive a killed process while it is a zombie. Treat such
/// entries as stopped so backend `is_running()` checks agree with Statistics.
pub fn process_pid_is_live(pid: u32) -> bool {
    if pid <= 1 {
        return false;
    }
    let Ok(pid) = i32::try_from(pid) else { return false; };
    match process_state(pid) {
        Some('Z' | 'X' | 'x') | None => false,
        Some(_) => true,
    }
}

pub fn read_process_cmdline(pid: i32) -> String {
    if pid <= 1 {
        return String::new();
    }
    fs::read(format!("/proc/{pid}/cmdline"))
        .map(|bytes| {
            let mut out = String::new();
            for byte in bytes {
                if byte == 0 {
                    out.push(' ');
                } else {
                    out.push(byte as char);
                }
            }
            out.trim().to_string()
        })
        .unwrap_or_default()
}

pub fn read_process_cmdline_u32(pid: u32) -> String {
    i32::try_from(pid)
        .ok()
        .map(read_process_cmdline)
        .unwrap_or_default()
}

fn pidof_inner(name: &str, log_exec: bool) -> Vec<i32> {
    let result = if log_exec {
        shell::run("pidof", &[name], shell::Capture::Stdout)
    } else {
        shell::run_quiet("pidof", &[name], shell::Capture::Stdout)
    };
    let (rc, out) = match result {
        Ok(v) => v,
        Err(_) => return Vec::new(),
    };
    if rc != 0 {
        return Vec::new();
    }
    let mut pids = parse_pid_lines(&out);
    sort_dedup_pids(&mut pids);
    pids
}

pub fn pidof(name: &str) -> Vec<i32> {
    pidof_inner(name, false)
}

pub fn pidof_logged(name: &str) -> Vec<i32> {
    pidof_inner(name, true)
}

fn pidof_any_inner(names: &[&str], log_exec: bool) -> Vec<i32> {
    let mut all = Vec::new();
    for &name in names {
        all.extend(pidof_inner(name, log_exec));
    }
    sort_dedup_pids(&mut all);
    all
}

pub fn pidof_any(names: &[&str]) -> Vec<i32> {
    pidof_any_inner(names, false)
}

pub fn pidof_any_logged(names: &[&str]) -> Vec<i32> {
    pidof_any_inner(names, true)
}

pub fn pidof_u32(name: &str) -> Vec<u32> {
    pidof(name)
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}

pub fn pidof_any_u32(names: &[&str]) -> Vec<u32> {
    pidof_any(names)
        .into_iter()
        .filter_map(|pid| u32::try_from(pid).ok())
        .collect()
}


/// Run `pgrep -f` with a caller-supplied full-command regular expression.
/// The pattern is passed directly as one argv element (no shell); callers still
/// own the exact identity rule so backend-specific matching stays explicit.
pub fn pgrep_full_regex(pattern: &str) -> Vec<i32> {
    let Ok((rc, out)) = shell::run_quiet(
        "pgrep",
        &["-f", pattern],
        shell::Capture::Stdout,
    ) else {
        return Vec::new();
    };
    if rc != 0 {
        return Vec::new();
    }
    let mut pids = parse_pid_lines(&out);
    sort_dedup_pids(&mut pids);
    pids
}

/// Read `ps -ef` once and return PID-column values for lines accepted by the
/// caller. This replaces repeated `ps | grep | grep -v grep` pipelines without
/// changing the backend-owned matching predicate.
pub fn ps_ef_pids_where<F>(mut predicate: F) -> Vec<i32>
where
    F: FnMut(&str) -> bool,
{
    let Ok((rc, out)) = shell::run_quiet("ps", &["-ef"], shell::Capture::Stdout) else {
        return Vec::new();
    };
    if rc != 0 {
        return Vec::new();
    }
    let mut pids = Vec::new();
    for line in out.lines() {
        if !predicate(line) {
            continue;
        }
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() > 1 {
            if let Ok(pid) = cols[1].parse::<i32>() {
                if pid > 1 {
                    pids.push(pid);
                }
            }
        }
    }
    sort_dedup_pids(&mut pids);
    pids
}

pub fn any_live_ps_ef_where<F>(predicate: F) -> bool
where
    F: FnMut(&str) -> bool,
{
    let mut pids = ps_ef_pids_where(predicate);
    filter_live_pids(&mut pids);
    !pids.is_empty()
}
