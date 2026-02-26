use anyhow::Result;

use crate::{iptables_backup, shell};

const IPT_FLUSH_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

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

    // 2) iptables: flush nat/mangle then restore baseline backup
    if crate::settings::iptables_backup_path().exists() {
        iptables_backup::reset_tables_then_restore_backup()?;
    } else {
        log::warn!("iptables backup is missing; flushing nat/mangle without restore");
        let _ = shell::ok_sh_timeout("iptables -t nat -F", IPT_FLUSH_TIMEOUT);
        let _ = shell::ok_sh_timeout("iptables -t mangle -F", IPT_FLUSH_TIMEOUT);

        // IPv6 best-effort cleanup
        let _ = shell::ok_sh_timeout("ip6tables -t nat -F", IPT_FLUSH_TIMEOUT);
        let _ = shell::ok_sh_timeout("ip6tables -t mangle -F", IPT_FLUSH_TIMEOUT);
    }

    Ok(())
}

// Compatibility alias: runtime expects stop::stop_services()
pub fn stop_services() -> Result<()> {
    stop_services_and_restore_iptables()
}
