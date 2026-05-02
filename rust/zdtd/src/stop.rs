use anyhow::Result;
use std::{path::Path, thread, time::Duration};

use crate::{iptables_backup, shell};

const TOR_TORRC_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/tor/torrc";
const LYREBIRD_BIN: &str = "/data/adb/modules/ZDT-D/bin/lyrebird";

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

fn pid_alive(pid: i32) -> bool {
    Path::new("/proc").join(pid.to_string()).is_dir()
}

fn kill_pids_with_escalation(label: &str, pids: &[i32]) -> Result<()> {
    if pids.is_empty() {
        return Ok(());
    }

    for pid in pids {
        let _ = shell::ok_sh(&format!("kill -15 {}", pid));
    }

    for _ in 0..15 {
        if pids.iter().all(|p| !pid_alive(*p)) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }

    for pid in pids {
        if pid_alive(*pid) {
            let _ = shell::ok_sh(&format!("kill -9 {}", pid));
        }
    }

    for _ in 0..10 {
        if pids.iter().all(|p| !pid_alive(*p)) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }

    log::warn!("failed to kill process(es) via {}: {:?}", label, pids);
    Ok(())
}

fn kill_by_name(name: &str) -> Result<()> {
    kill_pids_with_escalation(&format!("pidof {name}"), &pidof(name))
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

fn lyrebird_pids_exact() -> Vec<i32> {
    let mut pids = Vec::new();
    let cmd = format!(
        r#"sh -c "pgrep -f '^{}' 2>/dev/null || true""#,
        LYREBIRD_BIN
    );
    if let Ok(out) = shell::capture_quiet(&cmd) {
        pids.extend(parse_pid_lines(&out));
    }
    if pids.is_empty() {
        let ps_cmd = format!(
            r#"sh -c "ps -ef 2>/dev/null | grep -F '{}' | grep -v grep || true""#,
            LYREBIRD_BIN
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
    kill_pids_with_escalation(label, pids)
}

fn kill_by_any(names: &[&str]) -> Result<()> {
    kill_pids_with_escalation(&format!("pidof {:?}", names), &pidof_any(names))
}

fn stop_process_groups_parallel() -> Result<()> {
    let mut jobs = Vec::new();

    for name in [
        "nfqws",
        "nfqws2",
        "dnscrypt",
        "byedpi",
        "t2s",
        "opera-proxy",
        "sing-box",
        "wireproxy",
    ] {
        let proc_name = name.to_string();
        jobs.push(thread::spawn(move || kill_by_name(&proc_name)));
    }

    jobs.push(thread::spawn(|| kill_by_any(&["DPITunnel-cli", "dpitunnel-cli"])));

    for job in jobs {
        match job.join() {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Err(e),
            Err(_) => anyhow::bail!("stop worker thread panicked"),
        }
    }

    Ok(())
}

pub fn stop_services_and_restore_iptables() -> Result<()> {
    crate::programs::dnscrypt::request_stop();
    crate::programs::dnscrypt::clear_ipv6_resetprops();
    if let Err(e) = crate::vpn_netd::stop_applied() {
        log::warn!("vpn_netd cleanup failed during stop: {e:#}");
    }
    // 1) stop background processes
    // Use `pidof` to avoid killing similarly-named processes.
    stop_process_groups_parallel()?;
    let _ = crate::programs::myprogram::stop_all();
    // Stop VPN profile engines only when they were launched from this module path.
    kill_exact_pids("openvpn --config <profile>/client.ovpn", &crate::programs::openvpn::main_pids_exact())?;
    kill_exact_pids("tun2socks -device tun://<profile tun>", &crate::programs::tun2socks::main_pids_exact())?;

    // IMPORTANT: do not stop plain substring/name matches for Tor.
    // Some Android systems have unrelated processes containing "tor".
    // Stop only the exact Tor command using our torrc, plus our exact lyrebird.
    kill_exact_pids("torproxy -f <our torrc>", &tor_main_pids_exact())?;
    kill_exact_pids("lyrebird <our binary>", &lyrebird_pids_exact())?;

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
