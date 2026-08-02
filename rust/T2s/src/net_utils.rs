use anyhow::{anyhow, Context, Result};
use std::net::SocketAddr;

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
