use std::{fs, path::Path, sync::atomic::{AtomicBool, Ordering}};

use crate::{settings, shell::{self, Capture}};

static MULTIPORT_V4: AtomicBool = AtomicBool::new(false);
static MULTIPORT_V6: AtomicBool = AtomicBool::new(false);

const MULTIPORT_NO_FILE: &str = "multiport_no";

fn multiport_no_path() -> std::path::PathBuf {
    Path::new(settings::SETTING_DIR).join(MULTIPORT_NO_FILE)
}

pub fn multiport_disabled_by_flag() -> bool {
    multiport_no_path().is_file()
}

pub fn disable_multiport_persistently(reason: &str) {
    MULTIPORT_V4.store(false, Ordering::Relaxed);
    MULTIPORT_V6.store(false, Ordering::Relaxed);

    let path = multiport_no_path();
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            log::warn!("iptables multiport disabled in memory, but failed to create setting dir: {e:#}");
            return;
        }
    }

    let body = format!("disabled_by=zdtd\nreason={}\n", reason.trim());
    if let Err(e) = fs::write(&path, body) {
        log::warn!("iptables multiport disabled in memory, but failed to write {}: {e:#}", path.display());
        return;
    }
    log::warn!("iptables multiport disabled persistently: {} ({})", path.display(), reason.trim());
}

/// Probe whether `-m multiport` is supported by iptables/ip6tables.
///
/// This is a best-effort probe, executed once during daemon initialization.
pub fn init_multiport_caps() {
    if multiport_disabled_by_flag() {
        MULTIPORT_V4.store(false, Ordering::Relaxed);
        MULTIPORT_V6.store(false, Ordering::Relaxed);
        log::warn!("iptables multiport disabled by {}/{}", settings::SETTING_DIR, MULTIPORT_NO_FILE);
        return;
    }

    let v4 = shell::run_timeout(
        "iptables",
        &["-m", "multiport", "-h"],
        Capture::None,
        std::time::Duration::from_secs(3),
    )
        .map(|(c, _)| c == 0)
        .unwrap_or(false);
    MULTIPORT_V4.store(v4, Ordering::Relaxed);
    if !v4 {
        disable_multiport_persistently("iptables multiport help probe failed");
        return;
    }

    let v6 = shell::run_timeout(
        "ip6tables",
        &["-m", "multiport", "-h"],
        Capture::None,
        std::time::Duration::from_secs(3),
    )
        .map(|(c, _)| c == 0)
        .unwrap_or(false);
    MULTIPORT_V6.store(v6, Ordering::Relaxed);
    if !v6 {
        disable_multiport_persistently("ip6tables multiport help probe failed");
        return;
    }

    log::info!("iptables multiport support: v4={} v6={}", v4, v6);
}

pub fn multiport_v4() -> bool {
    MULTIPORT_V4.load(Ordering::Relaxed)
}

pub fn multiport_v6() -> bool {
    MULTIPORT_V6.load(Ordering::Relaxed)
}
