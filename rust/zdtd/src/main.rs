mod android;
mod blockedquic;
mod api;
mod api_status;
mod config;
mod daemon;
mod energy_saver;
mod iptables;
mod iptables_backup;
mod internet_wait;
mod logging;
mod ports;
mod proxyinfo;
mod protector;
mod screen;
mod scan_detector;
mod programs;
mod runtime;
mod runtime_refresh;
mod runtime_sanitize;
mod runtime_state;
mod settings;
mod shell;
mod stats;
mod stop;
mod traffic_total;
mod vpn_netd;
mod vpn_tether;
mod xtables_lock;

use anyhow::Result;
use log::info;

fn main() -> Result<()> {
    // Root is strict: if we don't have root, we don't start at all.
    // (The Android app will handle requesting root and re-launching.)
    if unsafe { libc::geteuid() } != 0 {
        eprintln!("zdtd: root privileges are required");
        anyhow::bail!("root privileges are required");
    }

    // The binary is now a daemon: it stays running and serves a local API.
    // A config file is not required; we always use fixed module paths.
    let cfg = config::Config::default_fixed();

    logging::init(&cfg)?;
    logging::init_user_locale();
    info!("zdtd starting (daemon)");

    daemon::run(&cfg)
}
