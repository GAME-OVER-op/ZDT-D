mod android;
mod api;
mod config;
mod daemon;
mod iptables;
mod iptables_backup;
mod logging;
mod ports;
mod programs;
mod runtime;
mod settings;
mod shell;
mod stats;
mod stop;

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
    info!("zdtd starting (daemon)");

    daemon::run(&cfg)
}
