use anyhow::Result;

use crate::{iptables_backup, shell};

const TOR_TORRC_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/tor/torrc";
const OBFS4PROXY_BIN: &str = "/data/adb/modules/ZDT-D/bin/obfs4proxy";

fn pidof(name: &str) -> Vec<i32> {
    // `pidof` returns a space-separated list of PIDs.
    let (rc, out) = match shell::run("pidof", &[name], shell::Capture::Stdout) {
        Ok(v) => v,
        Err(_) => return vec![],
    };
    if rc != 0 {
        return vec![];
    }
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

fn pidof_any(names: &[&str]) -> Vec<i32> {
    let mut all: Vec<i32> = Vec::new();
    for &n in names {
        all.extend(pidof(n));
    }
    all.sort_unstable();
    all.dedup();
    all
}

fn kill_by_name(name: &str) -> Result<()> {
    let pids = pidof(name);
    if pids.is_empty() {
        return Ok(());
    }

    // Try graceful shutdown first.
    for pid in &pids {
        let _ = shell::ok_sh(&format!("kill -15 {}", pid));
    }

    // Wait briefly for processes to exit.
    for _ in 0..15 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }

    // Force kill remaining.
    for pid in &pids {
        if std::path::Path::new("/proc").join(pid.to_string()).is_dir() {
            let _ = shell::ok_sh(&format!("kill -9 {}", pid));
        }
    }

    // Final wait.
    for _ in 0..10 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }

    // Best-effort: do not fail stop sequence if a process refuses to die.
    log::warn!("failed to kill process(es) via pidof {}: {:?}", name, pids);
    Ok(())
}

fn parse_pid_lines(out: &str) -> Vec<i32> {
    out.split_whitespace()
        .filter_map(|s| s.trim().parse::<i32>().ok())
        .filter(|p| *p > 1)
        .collect()
}

fn tor_main_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = format!(
        r#"sh -c "pgrep -f '^torproxy -f {}$' 2>/dev/null || true""#,
        TOR_TORRC_PATH
    );
    if let Ok(out) = shell::capture_quiet(&cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = format!(
            r#"sh -c "ps -ef 2>/dev/null | grep -F 'torproxy -f {}' | grep -v grep || true""#,
            TOR_TORRC_PATH
        );
        if let Ok(out) = shell::capture_quiet(&ps_cmd) {
            for line in out.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 1 {
                    if let Ok(pid) = cols[1].parse::<i32>() {
                        if pid > 1 {
                            pids.push(pid);
                        }
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn obfs4proxy_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = format!(
        r#"sh -c "pgrep -f '^{}' 2>/dev/null || true""#,
        OBFS4PROXY_BIN
    );
    if let Ok(out) = shell::capture_quiet(&cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = format!(
            r#"sh -c "ps -ef 2>/dev/null | grep -F '{}' | grep -v grep || true""#,
            OBFS4PROXY_BIN
        );
        if let Ok(out) = shell::capture_quiet(&ps_cmd) {
            for line in out.lines() {
                let cols: Vec<&str> = line.split_whitespace().collect();
                if cols.len() > 1 {
                    if let Ok(pid) = cols[1].parse::<i32>() {
                        if pid > 1 {
                            pids.push(pid);
                        }
                    }
                }
            }
        }
    }
    pids.sort_unstable();
    pids.dedup();
    pids
}

fn kill_exact_pids(label: &str, pids: &[i32]) -> Result<()> {
    if pids.is_empty() {
        return Ok(());
    }
    for pid in pids {
        let _ = shell::ok_sh(&format!("kill -15 {}", pid));
    }
    for _ in 0..15 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    for pid in pids {
        if std::path::Path::new("/proc").join(pid.to_string()).is_dir() {
            let _ = shell::ok_sh(&format!("kill -9 {}", pid));
        }
    }
    for _ in 0..10 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    log::warn!("failed to kill exact process(es) via {}: {:?}", label, pids);
    Ok(())
}

fn kill_by_any(names: &[&str]) -> Result<()> {
    let pids = pidof_any(names);
    if pids.is_empty() {
        return Ok(());
    }

    for pid in &pids {
        let _ = shell::ok_sh(&format!("kill -15 {}", pid));
    }
    for _ in 0..15 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    for pid in &pids {
        if std::path::Path::new("/proc").join(pid.to_string()).is_dir() {
            let _ = shell::ok_sh(&format!("kill -9 {}", pid));
        }
    }
    for _ in 0..10 {
        if pids.iter().all(|p| !std::path::Path::new("/proc").join(p.to_string()).is_dir()) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
    log::warn!("failed to kill process(es) via pidof {:?}: {:?}", names, pids);
    Ok(())
}

pub fn stop_services_and_restore_iptables() -> Result<()> {
    crate::programs::dnscrypt::request_stop();
    // 1) stop background processes
    // Use `pidof` to avoid killing similarly-named processes.
    kill_by_name("nfqws")?;
    kill_by_name("nfqws2")?;
    // dpitunnel-cli can show up as "DPITunnel-cli" depending on the build.
    kill_by_any(&["DPITunnel-cli", "dpitunnel-cli"])?;
    kill_by_name("dnscrypt")?;
    kill_by_name("byedpi")?;
    kill_by_name("t2s")?;
    kill_by_name("opera-proxy")?;
    kill_by_name("sing-box")?;
    kill_by_name("wireproxy")?;
    // IMPORTANT: do not stop plain substring/name matches for Tor.
    // Some Android systems have unrelated processes containing "tor".
    // Stop only the exact Tor command using our torrc, plus our exact obfs4proxy.
    kill_exact_pids("torproxy -f <our torrc>", &tor_main_pids_exact())?;
    kill_exact_pids("obfs4proxy <our binary>", &obfs4proxy_pids_exact())?;

    // 2) remove runtime guard chains before restore
    let _ = crate::proxyinfo::clear_rules();
    let _ = crate::blockedquic::clear_rules();

    // 3) flush nat/mangle and restore baseline backups independently for IPv4 and IPv6
    let restored_v4 = iptables_backup::reset_restore_v4_if_present()?;
    let _restored_v6 = iptables_backup::reset_restore_v6_if_present()?;

    if !restored_v4 {
        log::warn!(
            "iptables backup is missing; IPv4 nat/mangle were flushed without restore (proxyInfo filter chains already removed)"
        );
    }

    Ok(())
}

// Compatibility alias: runtime expects stop::stop_services()
pub fn stop_services() -> Result<()> {
    stop_services_and_restore_iptables()
}
