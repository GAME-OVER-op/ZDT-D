use anyhow::{anyhow, Context, Result};
use std::net::SocketAddr;
use tokio::net::UdpSocket;

pub async fn resolve_prefer_ipv4(host: &str, port: u16) -> Result<SocketAddr> {
    let mut addrs = tokio::net::lookup_host((host, port))
        .await
        .with_context(|| format!("resolve {}:{}", host, port))?;
    let mut first: Option<SocketAddr> = None;
    while let Some(addr) = addrs.next() {
        if first.is_none() {
            first = Some(addr);
        }
        if addr.is_ipv4() {
            return Ok(addr);
        }
    }
    first.ok_or_else(|| anyhow!("no address for {}:{}", host, port))
}

pub async fn resolve_first(host: &str, port: u16) -> Result<SocketAddr> {
    tokio::net::lookup_host((host, port))
        .await
        .with_context(|| format!("resolve {}:{}", host, port))?
        .next()
        .ok_or_else(|| anyhow!("no address for {}:{}", host, port))
}

/// Best-effort local source IP the kernel would use for outbound traffic.
/// A UDP `connect` only picks the route — no packet is sent — so this costs a
/// pair of syscalls and works while the network is down (returns None).
pub async fn local_outbound_ip() -> Option<std::net::IpAddr> {
    let targets = [
        ("0.0.0.0:0", "1.1.1.1:443"),
        ("[::]:0", "[2606:4700:4700::1111]:443"),
    ];
    for (bind, target) in targets {
        let Ok(bind) = bind.parse::<SocketAddr>() else { continue };
        let Ok(target) = target.parse::<SocketAddr>() else { continue };
        let Ok(udp) = UdpSocket::bind(bind).await else { continue };
        if udp.connect(target).await.is_ok() {
            if let Ok(local) = udp.local_addr() {
                return Some(local.ip());
            }
        }
    }
    None
}
