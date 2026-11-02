use anyhow::{Context, Result};
use crate::common::apps::{count_valid_uid_pairs};
use crate::common::config::{normalize_config_args};
use crate::common::process::{LogMode, spawn_detached_logged};
use log::info;
use serde::Deserialize;
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    process::Command,
    time::Duration,
};

use crate::platform::android::pkg_uid::{self, Mode, Sha256Tracker};
use crate::settings;
use crate::iptables::{iptables_v1, iptables_v2};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const SHA_FLAG_FILE: &str = settings::SHARED_SHA_FLAG_FILE;

#[derive(Clone, Copy)]
pub(super) struct NfqwsVariant {
    root: &'static str,
    binary: &'static str,
    status_id: &'static str,
    user_label: &'static str,
}

impl NfqwsVariant {
    pub(super) const fn new(
        root: &'static str,
        binary: &'static str,
        status_id: &'static str,
        user_label: &'static str,
    ) -> Self {
        Self {
            root,
            binary,
            status_id,
            user_label,
        }
    }
}

#[derive(Debug, Deserialize)]
struct ActiveJson {
    profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize)]
struct ProfileState {
    enabled: bool,
}

#[derive(Debug, Deserialize)]
struct PortJson {
    port: u16,
    iface_mobile: String,
    iface_wifi: String,
}

pub(super) fn start_active_profiles(variant: &NfqwsVariant) -> Result<()> {
    crate::common::fs::require_dir_str(MODULE_DIR)?;
    crate::common::fs::require_dir_str(WORKING_DIR)?;
    crate::common::fs::require_dir_str(variant.root)?;

    let active_path = Path::new(variant.root).join("active.json");
    let active = read_json::<ActiveJson>(&active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.profiles.values().any(|st| st.enabled) {
        log::info!("{}: no enabled profiles in active.json", variant.status_id);
        return Ok(());
    }
    crate::common::fs::require_file_str(variant.binary)?;

    let tracker = Sha256Tracker::new(SHA_FLAG_FILE);

    for (profile_name, st) in active.profiles.iter() {
        if !st.enabled {
            continue;
        }
        start_profile(variant, profile_name, &tracker)?;
    }
    Ok(())
}

fn start_profile(variant: &NfqwsVariant, profile_name: &str, tracker: &Sha256Tracker) -> Result<()> {
    let profile_dir = Path::new(variant.root).join(profile_name);
    crate::common::fs::require_dir_str(profile_dir.to_string_lossy().as_ref())?;

    let port_path = profile_dir.join("port.json");
    let port_cfg = read_json::<PortJson>(&port_path)
        .with_context(|| format!("read {}", port_path.display()))?;

    // lists
    let uid_dir = profile_dir.join("app/uid");
    let out_dir = profile_dir.join("app/out");
    fs::create_dir_all(&out_dir).with_context(|| format!("mkdir {}", out_dir.display()))?;

    let in_mobile = uid_dir.join("mobile_program");
    let in_wifi = uid_dir.join("wifi_program");
    let in_user = uid_dir.join("user_program");

    let out_mobile = out_dir.join("mobile_program");
    let out_wifi = out_dir.join("wifi_program");
    let out_user = out_dir.join("user_program");

    // Convert package list -> package=uid (sha256 gated)
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_mobile, &in_mobile)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_wifi, &in_wifi)?;
    let _ = pkg_uid::unified_processing(Mode::Default, tracker, &out_user, &in_user)?;

    // Pass config file path as argument (nfqws will parse the file itself).

    // Spawn nfqws: nfqws --uid=0:0 --qnum=<port> <config_path>
    let log_dir = profile_dir.join("log");
    fs::create_dir_all(&log_dir).with_context(|| format!("mkdir {}", log_dir.display()))?;
    let log_path = log_dir.join("nfqws.log");

    let resolved_mobile = count_valid_uid_pairs(&out_mobile)?;
    let resolved_wifi = count_valid_uid_pairs(&out_wifi)?;
    let resolved_user = count_valid_uid_pairs(&out_user)?;
    let resolved_total = resolved_mobile + resolved_wifi + resolved_user;
    let has_launch_marker = crate::common::apps::file_has_launch_marker(&in_mobile).unwrap_or(false)
        || crate::common::apps::file_has_launch_marker(&in_wifi).unwrap_or(false)
        || crate::common::apps::file_has_launch_marker(&in_user).unwrap_or(false);
    if resolved_total == 0 && !has_launch_marker {
        log::warn!(
            "{}: no apps resolved for {} -> skip start/iptables",
            variant.status_id,
            profile_dir.display()
        );
        return Ok(());
    }
    if resolved_total == 0 && has_launch_marker {
        log::info!(
            "{}: launch marker present for {}, starting without routing app UIDs",
            variant.status_id,
            profile_dir.display()
        );
    }

    let config_path = profile_dir.join("config/config.txt");
    let raw = fs::read_to_string(&config_path)
        .with_context(|| format!("read {}", config_path.display()))?;
    let config_args = normalize_config_args(&raw);

    let port_filter = super::filters::extract_proto_port_filter(&raw);
    let port_filter_ref = if port_filter.is_empty() { None } else { Some(&port_filter) };

    crate::logging::user_info(&format!("{}[{profile_name}]: запуск", variant.user_label));
    spawn_nfqws(variant, &profile_dir, port_cfg.port, &config_args, &log_path)?;

    // Apply iptables:
    crate::logging::user_info(&format!("{}[{profile_name}]: iptables", variant.user_label));
    // v2 (no iface) for USER
    iptables_v2::apply(port_cfg.port, Some(&out_user), port_filter_ref)?;
    // v1 full for mobile + wifi with iface mapping
    iptables_v1::apply(
        "full",
        port_cfg.port,
        Some(port_cfg.iface_mobile.as_str()),
        Some(&out_mobile),
        port_filter_ref,
    )?;
    iptables_v1::apply(
        "full",
        port_cfg.port,
        Some(port_cfg.iface_wifi.as_str()),
        Some(&out_wifi),
        port_filter_ref,
    )?;

    info!("nfqws profile started: {} port={}", profile_name, port_cfg.port);
    Ok(())
}

fn spawn_nfqws(
    variant: &NfqwsVariant,
    workdir: &Path,
    port: u16,
    config_args: &[String],
    log_path: &Path,
) -> Result<()> {
    let q = format!("--qnum={}", port);

    // We intentionally pass `config_arg` as ONE argument, exactly like "$config" in shell.
    let mut cmd = Command::new(variant.binary);
    cmd.current_dir(workdir);
    cmd.arg("--uid=0:0")
        .arg(q)
        .args(config_args);

    let child = spawn_detached_logged(
        &mut cmd,
        log_path,
        LogMode::Truncate,
        || format!("spawn {}", variant.binary),
    )?;
    let pid = child.id();
    info!("spawned nfqws pid={} qnum={} log={}", pid, port, log_path.display());

    // Quick liveness check (best-effort)
    std::thread::sleep(Duration::from_millis(150));
    let proc_path = PathBuf::from("/proc").join(pid.to_string());
    if !proc_path.is_dir() {
        info!("nfqws pid={} is not running after spawn (check log {})", pid, log_path.display());
    }

    Ok(())
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::common::fs::json::read_json(path)
}
