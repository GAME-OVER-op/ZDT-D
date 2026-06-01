use anyhow::Result;
use log::{info, warn};
use std::time::Duration;

use crate::shell::Capture;
use crate::xtables_lock;

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";
const CHAIN: &str = "MANGLE_APP";

#[derive(Debug)]
pub struct PreparedMangleApp {
    cmd: String,
    next_insert_line: u32,
}

fn run_timeout_retry(cmd: &str, args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<&str> = Vec::with_capacity(args.len() + 2);
    a.push("-w");
    a.push(XT_WAIT_SECS);
    a.extend_from_slice(args);
    xtables_lock::run_timeout_retry(cmd, &a, capture, timeout)
}

fn runv_timeout_retry(cmd: &str, args: &[String], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<String> = Vec::with_capacity(args.len() + 2);
    a.push("-w".into());
    a.push(XT_WAIT_SECS.into());
    a.extend_from_slice(args);
    xtables_lock::runv_timeout_retry(cmd, &a, capture, timeout)
}

/// Ensure the shared mangle app chain exists and OUTPUT jumps into it.
///
/// nfqws/nfqws2 must never place NFQUEUE rules directly in mangle OUTPUT:
/// the shared base RETURN guards must run before UID-specific NFQUEUE rules.
/// This helper keeps the existing shared MANGLE_APP structure and prepares
/// the anchor point without adding per-UID RETURN exclusions.
pub fn prepare(cmd: &str) -> Result<PreparedMangleApp> {
    let (c, _) = run_timeout_retry(cmd, &["-t", "mangle", "-nL", CHAIN], Capture::None, IPT_CMD_TIMEOUT)?;
    if c != 0 {
        let (rc, out) = run_timeout_retry(cmd, &["-t", "mangle", "-N", CHAIN], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{cmd}: create {CHAIN} failed: {}", out.trim());
        }
    }

    let (hook, _) = run_timeout_retry(cmd, &["-t", "mangle", "-C", "OUTPUT", "-j", CHAIN], Capture::None, IPT_CMD_TIMEOUT)?;
    if hook != 0 {
        let (rc, out) = run_timeout_retry(cmd, &["-t", "mangle", "-I", "OUTPUT", "1", "-j", CHAIN], Capture::Both, IPT_CMD_TIMEOUT)?;
        if rc != 0 {
            anyhow::bail!("{cmd}: hook OUTPUT -> {CHAIN} failed: {}", out.trim());
        }
    }

    ensure_base_returns(cmd);
    if let Err(e) = cleanup_owner_returns(cmd) {
        warn!("{cmd}: cleanup legacy {CHAIN} owner RETURN rules failed: {e:#}");
    }

    let next_insert_line = ensure_final_return(cmd)?;
    Ok(PreparedMangleApp {
        cmd: cmd.to_string(),
        next_insert_line,
    })
}

#[allow(dead_code)]
pub fn ensure(cmd: &str) -> Result<()> {
    prepare(cmd).map(|_| ())
}

/// Add a rule into an already prepared MANGLE_APP chain before the final
/// unconditional RETURN. Preparation is intentionally not repeated here:
/// nfqws/nfqws2 may add many UID/proto/port rules, and re-running prepare()
/// for every rule turns compact final chains into very slow builds.
pub fn add_rule_prepared_idempotent(prepared: &mut PreparedMangleApp, rule_tail: &[String]) -> Result<bool> {
    let cmd = prepared.cmd.clone();
    let cmd_s = cmd.as_str();

    let mut check: Vec<String> = vec!["-t".into(), "mangle".into(), "-C".into(), CHAIN.into()];
    check.extend_from_slice(rule_tail);
    let (exists, _) = runv_timeout_retry(cmd_s, &check, Capture::None, IPT_CMD_TIMEOUT)?;
    if exists == 0 {
        return Ok(false);
    }

    let mut add: Vec<String> = vec![
        "-t".into(),
        "mangle".into(),
        "-I".into(),
        CHAIN.into(),
        prepared.next_insert_line.to_string(),
    ];
    add.extend_from_slice(rule_tail);

    let (rc, out) = runv_timeout_retry(cmd_s, &add, Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{cmd_s}: add {CHAIN} rule failed: {}", out.trim());
    }
    prepared.next_insert_line = prepared.next_insert_line.saturating_add(1);
    Ok(true)
}

#[allow(dead_code)]
pub fn add_rule_idempotent(cmd: &str, rule_tail: &[String]) -> Result<bool> {
    let mut prepared = prepare(cmd)?;
    add_rule_prepared_idempotent(&mut prepared, rule_tail)
}


/// Remove legacy per-UID RETURN exclusions from MANGLE_APP.
///
/// MANGLE_APP now uses UID-specific NFQUEUE rules, so non-targeted apps naturally
/// fall through to the final RETURN. Keeping separate `-m owner --uid-owner ... -j RETURN`
/// rules only bloats the chain and can hide selection conflicts.
pub fn cleanup_owner_returns(cmd: &str) -> Result<usize> {
    let (rc, out) = run_timeout_retry(
        cmd,
        &["-t", "mangle", "-S", CHAIN],
        Capture::Stdout,
        IPT_CMD_TIMEOUT,
    )?;
    if rc != 0 {
        return Ok(0);
    }

    let mut removed = 0usize;
    for raw in out.lines() {
        let line = raw.trim();
        if !is_owner_return_rule(line) {
            continue;
        }

        let tail: Vec<String> = line
            .split_whitespace()
            .skip(2) // skip `-A MANGLE_APP`
            .map(|s| s.to_string())
            .collect();
        if tail.is_empty() {
            continue;
        }

        loop {
            let mut del: Vec<String> = vec![
                "-t".into(),
                "mangle".into(),
                "-D".into(),
                CHAIN.into(),
            ];
            del.extend_from_slice(&tail);

            match runv_timeout_retry(cmd, &del, Capture::Both, IPT_CMD_TIMEOUT) {
                Ok((0, _)) => {
                    removed += 1;
                    continue;
                }
                Ok((_rc, _out)) => break,
                Err(e) => {
                    warn!("{cmd}: remove legacy {CHAIN} owner RETURN failed: {e:#}");
                    break;
                }
            }
        }
    }

    if removed > 0 {
        info!("{cmd}: removed {removed} legacy {CHAIN} owner RETURN rule(s)");
    }
    Ok(removed)
}

fn is_owner_return_rule(line: &str) -> bool {
    line.starts_with("-A MANGLE_APP ")
        && line.ends_with(" -j RETURN")
        && line.contains(" -m owner ")
        && line.contains(" --uid-owner ")
}

fn ensure_base_returns(cmd: &str) {
    let Some(expected) = expected_base_returns(cmd) else {
        return;
    };

    match base_returns_already_ordered(cmd, &expected) {
        Ok(true) => return,
        Ok(false) => {}
        Err(e) => {
            warn!("{cmd}: inspect {CHAIN} base RETURN order failed: {e}");
        }
    }

    // Reorder only when needed. The old path removed/reinserted DNS RETURN rules
    // on every prepare() call, which was cheap compared with the per-rule ensure()
    // bug but still added avoidable xtables work for nfqws/nfqws2 restarts.
    for tail in &expected {
        remove_return_rule(cmd, tail.as_slice());
    }
    for (idx, tail) in expected.iter().enumerate() {
        ensure_return_rule(cmd, (idx + 1) as u32, tail.as_slice());
    }
}

fn expected_base_returns(cmd: &str) -> Option<Vec<Vec<&'static str>>> {
    match cmd {
        "iptables" => Some(vec![
            vec!["-o", "lo", "-j", "RETURN"],
            vec!["-d", "127.0.0.0/8", "-j", "RETURN"],
            vec!["-p", "sctp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
            vec!["-p", "tcp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
            vec!["-p", "udp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
        ]),
        "ip6tables" => Some(vec![
            vec!["-o", "lo", "-j", "RETURN"],
            vec!["-d", "::1/128", "-j", "RETURN"],
            vec!["-p", "sctp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
            vec!["-p", "tcp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
            vec!["-p", "udp", "-m", "multiport", "--dports", "53,853,5353", "-j", "RETURN"],
        ]),
        _ => None,
    }
}

fn base_returns_already_ordered(cmd: &str, expected: &[Vec<&'static str>]) -> Result<bool> {
    let (rc, out) = run_timeout_retry(cmd, &["-t", "mangle", "-S", CHAIN], Capture::Stdout, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        return Ok(false);
    }

    let expected_lines: Vec<String> = expected
        .iter()
        .map(|tail| format!("-A {CHAIN} {}", tail.join(" ")))
        .collect();
    let app_lines: Vec<&str> = out
        .lines()
        .map(str::trim)
        .filter(|line| line.starts_with("-A "))
        .collect();

    if app_lines.len() < expected_lines.len() {
        return Ok(false);
    }

    for (idx, expected_line) in expected_lines.iter().enumerate() {
        if app_lines.get(idx).copied() != Some(expected_line.as_str()) {
            return Ok(false);
        }
    }

    // Treat duplicates later in the chain as not clean; one reorder pass removes
    // them and keeps only the strict prefix guards.
    for line in app_lines.iter().skip(expected_lines.len()) {
        if expected_lines.iter().any(|expected_line| *line == expected_line.as_str()) {
            return Ok(false);
        }
    }

    Ok(true)
}

fn remove_return_rule(cmd: &str, tail: &[&str]) {
    loop {
        let mut del = vec!["-t", "mangle", "-D", CHAIN];
        del.extend_from_slice(tail);
        match run_timeout_retry(cmd, &del, Capture::Both, IPT_CMD_TIMEOUT) {
            Ok((0, _)) => continue,
            Ok((_rc, _out)) => break,
            Err(e) => {
                warn!("{cmd}: remove {CHAIN} RETURN before reorder failed: {e}");
                break;
            }
        }
    }
}

fn ensure_return_rule(cmd: &str, pos: u32, tail: &[&str]) {
    let mut check = vec!["-t", "mangle", "-C", CHAIN];
    check.extend_from_slice(tail);
    match run_timeout_retry(cmd, &check, Capture::None, IPT_CMD_TIMEOUT) {
        Ok((0, _)) => return,
        Ok(_) => {}
        Err(e) => {
            warn!("{cmd}: check {CHAIN} base RETURN failed: {e}");
            return;
        }
    }

    let pos_s = pos.to_string();
    let mut add = vec!["-t", "mangle", "-I", CHAIN, pos_s.as_str()];
    add.extend_from_slice(tail);
    match run_timeout_retry(cmd, &add, Capture::Both, IPT_CMD_TIMEOUT) {
        Ok((0, _)) => {}
        Ok((_rc, out)) => warn!("{cmd}: add {CHAIN} base RETURN failed: {}", out.trim()),
        Err(e) => warn!("{cmd}: add {CHAIN} base RETURN failed: {e}"),
    }
}

fn ensure_final_return(cmd: &str) -> Result<u32> {
    if let Some(line) = final_return_line(cmd)? {
        return Ok(line);
    }
    let (rc, out) = run_timeout_retry(cmd, &["-t", "mangle", "-A", CHAIN, "-j", "RETURN"], Capture::Both, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        anyhow::bail!("{cmd}: add final {CHAIN} RETURN failed: {}", out.trim());
    }
    final_return_line(cmd)?.ok_or_else(|| anyhow::anyhow!("{cmd}: final {CHAIN} RETURN is still missing after append"))
}

fn final_return_line(cmd: &str) -> Result<Option<u32>> {
    let (rc, out) = run_timeout_retry(cmd, &["-t", "mangle", "-S", CHAIN], Capture::Stdout, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        return Ok(None);
    }
    let expected = format!("-A {CHAIN} -j RETURN");
    let mut idx = 0u32;
    for line in out.lines() {
        let line = line.trim();
        if line.starts_with("-A ") {
            idx += 1;
            if line == expected.as_str() {
                return Ok(Some(idx));
            }
        }
    }
    Ok(None)
}
