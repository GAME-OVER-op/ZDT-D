use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{bail, Result};

use crate::common::process::shell::{self, Capture};

use super::IP_TIMEOUT;

// merged: identical body, per-engine timeout passed as param (was TUN_WAIT)
pub fn wait_tun_link(tun: &str, timeout: Duration) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= timeout {
            bail!("tun {tun} was not created after {:?}", timeout);
        }
        let (code, _) = shell::run_timeout("ip", &["link", "show", tun], Capture::Both, IP_TIMEOUT)
            .unwrap_or((1, String::new()));
        if code == 0 {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}

// merged: identical body, per-engine timeout passed as param (was PORT_WAIT)
pub fn wait_tcp_port(host: &str, port: u16, timeout: Duration) -> Result<()> {
    let ip: IpAddr = host.parse().unwrap_or(IpAddr::V4(Ipv4Addr::LOCALHOST));
    let addr = SocketAddr::new(ip, port);
    let start = Instant::now();
    loop {
        if start.elapsed() >= timeout {
            bail!("127.0.0.1:{port} is not listening after {:?}", timeout);
        }
        if TcpStream::connect_timeout(&addr, Duration::from_millis(250)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}


/// Waits until a TUN interface has an IPv4 address. Several VPN backends used
/// this exact polling loop independently; the backend-specific timeout remains
/// a caller argument.
pub fn wait_tun_ipv4_ready(tun: &str, timeout: Duration) -> Result<()> {
    let start = Instant::now();
    loop {
        if start.elapsed() >= timeout {
            bail!("tun {tun} is not ready after {:?}", timeout);
        }
        let (code, out) = shell::run_timeout(
            "ip",
            &["-o", "-4", "addr", "show", "dev", tun],
            Capture::Stdout,
            IP_TIMEOUT,
        )
        .unwrap_or((1, String::new()));
        if code == 0
            && out
                .lines()
                .any(|line| line.contains(" inet ") || line.split_whitespace().any(|token| token == "inet"))
        {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(300));
    }
}
