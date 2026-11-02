use anyhow::{Context, Result};
use crate::common::process::{filter_live_pids, pgrep_full_regex, ps_ef_pids_where, any_live_ps_ef_where, LogMode, spawn_detached_logged_with_clone_context};
use crate::network::t2s::{build_tun2socks_command, tun2socks_process_running};
#[allow(unused_imports)]
pub use crate::common::profile::{ActiveProfiles, ProfileState};
use log::{info, warn};
use std::{fs::{self, OpenOptions}, os::unix::process::CommandExt, path::{Path, PathBuf}, process::{Command, Stdio}, thread, time::Duration};


use super::model::*;

pub(super) fn spawn_mihomo(plan: &ProfilePlan) -> Result<()> {
    if mihomo_profile_process_running(&plan.work_dir, &plan.runtime_config_path) {
        info!(
            "mihomo: profile={} already running for runtime_config={}, skip spawn",
            plan.name,
            plan.runtime_config_path.display()
        );
        return Ok(());
    }

    fs::create_dir_all(&plan.work_dir)?;
    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.mihomo_log_path)
        .with_context(|| format!("open log {}", plan.mihomo_log_path.display()))?;
    let logf_err = logf.try_clone().context("clone mihomo log")?;

    let mut cmd = Command::new(MIHOMO_BIN);
    cmd.arg("-d")
        .arg(&plan.work_dir)
        .arg("-f")
        .arg(&plan.runtime_config_path)
        .current_dir(&plan.work_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {MIHOMO_BIN}"))?;
    info!(
        "mihomo: spawned profile={} pid={} mixed_port={} log={}",
        plan.name,
        child.id(),
        plan.setting.mixed_port,
        plan.mihomo_log_path.display()
    );

    thread::sleep(Duration::from_millis(350));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!("mihomo: profile={} pid={} exited quickly; check log {}", plan.name, child.id(), plan.mihomo_log_path.display());
    }
    Ok(())
}

pub(super) fn spawn_tun2socks(plan: &ProfilePlan) -> Result<()> {
    let proxy = format!("socks5://127.0.0.1:{}", plan.setting.mixed_port);
    if tun2socks_process_running(TUN2SOCKS_BIN, &plan.setting.tun, &proxy) {
        info!(
            "mihomo: tun2socks profile={} already running for tun={} proxy={}, skip spawn",
            plan.name,
            plan.setting.tun,
            proxy
        );
        return Ok(());
    }

    fs::create_dir_all(plan.profile_dir.join("log"))?;
    let mut cmd = build_tun2socks_command(
        Path::new(TUN2SOCKS_BIN),
        &plan.setting.tun,
        &proxy,
        &plan.setting.tun2socks_loglevel,
        None,
        &plan.profile_dir,
    );
    let child = spawn_detached_logged_with_clone_context(
        &mut cmd,
        &plan.tun2socks_log_path,
        LogMode::Truncate,
        || "clone mihomo tun2socks log".to_string(),
        || format!("spawn {TUN2SOCKS_BIN} for mihomo"),
    )?;
    info!(
        "mihomo: spawned tun2socks profile={} pid={} tun={} proxy={} log={}",
        plan.name,
        child.id(),
        plan.setting.tun,
        proxy,
        plan.tun2socks_log_path.display()
    );
    Ok(())
}

pub(super) fn mihomo_profile_process_running(work_dir: &Path, runtime_config: &Path) -> bool {
    let pattern = format!(
        "{} -d {} -f {}",
        MIHOMO_BIN,
        work_dir.display(),
        runtime_config.display()
    );
    any_live_ps_ef_where(|line| line.contains(&pattern))
}

pub fn main_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(
        r#"^/data/adb/modules/ZDT-D/bin/mihomo -d /data/adb/modules/ZDT-D/working_folder/mihomo/profile/.*/work -f /data/adb/modules/ZDT-D/working_folder/mihomo/profile/.*/config.runtime.yaml$"#,
    );
    if pids.is_empty() {
        pids = ps_ef_pids_where(|line| {
            line.contains("/data/adb/modules/ZDT-D/bin/mihomo")
                && line.contains("/data/adb/modules/ZDT-D/working_folder/mihomo/profile/")
                && line.contains("config.runtime.yaml")
        });
    }
    filter_live_pids(&mut pids);
    pids
}

pub fn tun2socks_pids_exact() -> Vec<i32> {
    let mut pids = pgrep_full_regex(
        r#"^/data/adb/modules/ZDT-D/bin/tun2socks --device tun://.* --proxy socks5://127\.0\.0\.1:[0-9]+.*$"#,
    );
    filter_live_pids(&mut pids);
    pids
}

