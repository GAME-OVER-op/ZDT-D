#![allow(dead_code)]
#![allow(unused_imports)]
#![allow(unused_variables)]
#![allow(unused_mut)]
#![allow(unused_unsafe)]
#![allow(unused_comparisons)]

mod android;
mod api;
mod blockedquic;
mod config;
mod daemon;
mod iptables;
mod iptables_backup;
mod logging;
mod ports;
mod programs;
mod protector;
mod proxyinfo;
mod runtime;
mod scan_detector;
mod screen;
mod settings;
mod shell;
mod stats;
mod stop;
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
    info!("zdtd starting (daemon)");

    daemon::run(&cfg)
}
