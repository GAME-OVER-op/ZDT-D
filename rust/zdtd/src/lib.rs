pub mod common;
pub mod api;
mod api_status;
mod config;
pub mod features;
pub mod iptables;
mod logging;
pub mod network;
pub mod platform;
pub mod programs;
pub mod runtime;
mod settings;

use anyhow::Result;
use log::info;

pub fn run() -> Result<()> {
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

    runtime::daemon::run(&cfg)
}
