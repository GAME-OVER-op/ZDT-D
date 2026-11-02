use anyhow::{Result};
use log::{info, warn};
use std::{path::{Path, PathBuf}, process::Command, thread, time::Duration};

use crate::common::process::{spawn_detached_logged, spawn_detached_logged_with_clone_context, LogMode};
use crate::network::t2s::{build_tun2socks_command, tun2socks_process_running};

use super::{profile_root, singbox_profile_process_running, VariantSpec, VpnProfilePlan};

const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";

pub(crate) fn spawn_singbox(spec: VariantSpec, config_path: &Path, log_path: &Path) -> Result<()> {
    if singbox_profile_process_running(spec, config_path) {
        info!(
            "{}: already running for config={}, skip spawn",
            spec.label,
            config_path.display()
        );
        return Ok(());
    }

    let mut cmd = Command::new(spec.binary);
    cmd.arg("run").arg("-c").arg(config_path);
    let child = spawn_detached_logged(
        &mut cmd,
        log_path,
        LogMode::Truncate,
        || format!("spawn {}", spec.binary),
    )?;

    info!(
        "spawned {} pid={} cfg={} log={}",
        spec.label,
        child.id(),
        config_path.display(),
        log_path.display()
    );

    thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(child.id().to_string());
    if !proc_path.is_dir() {
        warn!(
            "{} pid={} exited quickly; check log {}",
            spec.label,
            child.id(),
            log_path.display()
        );
    }
    Ok(())
}

pub(crate) fn spawn_tun2socks_for_vpn(spec: VariantSpec, bin: &Path, plan: &VpnProfilePlan) -> Result<()> {
    let proxy = format!("socks5://127.0.0.1:{}", plan.server_port);
    if tun2socks_process_running(TUN2SOCKS_BIN, &plan.tun, &proxy) {
        info!(
            "{} vpn: tun2socks profile={} already running for tun={} proxy={}, skip spawn",
            spec.label,
            plan.name,
            plan.tun,
            proxy
        );
        return Ok(());
    }

    let work_dir = profile_root(spec, &plan.name);
    let mut cmd = build_tun2socks_command(
        bin,
        &plan.tun,
        &proxy,
        &plan.setting.tun2socks_loglevel,
        None,
        &work_dir,
    );

    let child = spawn_detached_logged_with_clone_context(
        &mut cmd,
        &plan.tun2socks_log_path,
        LogMode::Truncate,
        || format!("clone {} tun2socks log", spec.label),
        || format!("spawn {} for {}", bin.display(), spec.label),
    )?;
    info!(
        "{} vpn: spawned tun2socks profile={} pid={} tun={} proxy={} log={}",
        spec.label,
        plan.name,
        child.id(),
        plan.tun,
        proxy,
        plan.tun2socks_log_path.display()
    );
    Ok(())
}
