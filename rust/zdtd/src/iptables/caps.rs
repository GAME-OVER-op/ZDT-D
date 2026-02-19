use std::sync::atomic::{AtomicBool, Ordering};

use crate::shell::{self, Capture};

static MULTIPORT_V4: AtomicBool = AtomicBool::new(false);
static MULTIPORT_V6: AtomicBool = AtomicBool::new(false);

/// Probe whether `-m multiport` is supported by iptables/ip6tables.
///
/// This is a best-effort probe, executed once during daemon initialization.
pub fn init_multiport_caps() {
    let v4 = shell::run_timeout(
        "iptables",
        &["-m", "multiport", "-h"],
        Capture::None,
        std::time::Duration::from_secs(3),
    )
        .map(|(c, _)| c == 0)
        .unwrap_or(false);
    MULTIPORT_V4.store(v4, Ordering::Relaxed);

    let v6 = shell::run_timeout(
        "ip6tables",
        &["-m", "multiport", "-h"],
        Capture::None,
        std::time::Duration::from_secs(3),
    )
        .map(|(c, _)| c == 0)
        .unwrap_or(false);
    MULTIPORT_V6.store(v6, Ordering::Relaxed);

    log::info!("iptables multiport support: v4={} v6={}", v4, v6);
}

pub fn multiport_v4() -> bool {
    MULTIPORT_V4.load(Ordering::Relaxed)
}

pub fn multiport_v6() -> bool {
    MULTIPORT_V6.load(Ordering::Relaxed)
}
