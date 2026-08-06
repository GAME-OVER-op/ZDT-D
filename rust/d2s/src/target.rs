use anyhow::{anyhow, Context, Result};
use std::{fmt, net::{IpAddr, SocketAddr}, str::FromStr};
use tokio::net::lookup_host;

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub enum TargetAddr {
    Ip(SocketAddr),
    Domain(String, u16),
}

impl TargetAddr {
    pub fn port(&self) -> u16 {
        match self {
            Self::Ip(addr) => addr.port(),
            Self::Domain(_, port) => *port,
        }
    }

    pub async fn resolve(&self) -> Result<Vec<SocketAddr>> {
        match self {
            Self::Ip(addr) => Ok(vec![*addr]),
            Self::Domain(host, port) => {
                let addrs: Vec<_> = lookup_host((host.as_str(), *port))
                    .await
                    .with_context(|| format!("resolve direct target {host}:{port}"))?
                    .collect();
                if addrs.is_empty() {
                    return Err(anyhow!("direct target {host}:{port} resolved to no addresses"));
                }
                Ok(addrs)
            }
        }
    }

    pub fn encode_socks5(&self, out: &mut Vec<u8>) -> Result<()> {
        match self {
            Self::Ip(addr) => {
                match addr.ip() {
                    IpAddr::V4(ip) => {
                        out.push(0x01);
                        out.extend_from_slice(&ip.octets());
                    }
                    IpAddr::V6(ip) => {
                        out.push(0x04);
                        out.extend_from_slice(&ip.octets());
                    }
                }
                out.extend_from_slice(&addr.port().to_be_bytes());
            }
            Self::Domain(host, port) => {
                let bytes = host.as_bytes();
                if bytes.is_empty() || bytes.len() > 255 {
                    return Err(anyhow!("invalid SOCKS5 domain length: {}", bytes.len()));
                }
                out.push(0x03);
                out.push(bytes.len() as u8);
                out.extend_from_slice(bytes);
                out.extend_from_slice(&port.to_be_bytes());
            }
        }
        Ok(())
    }
}

impl fmt::Display for TargetAddr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Ip(addr) => write!(f, "{addr}"),
            Self::Domain(host, port) => write!(f, "{host}:{port}"),
        }
    }
}

impl FromStr for TargetAddr {
    type Err = anyhow::Error;

    fn from_str(value: &str) -> Result<Self> {
        if let Ok(addr) = value.parse::<SocketAddr>() {
            return Ok(Self::Ip(addr));
        }

        let (host, port) = split_host_port(value)?;
        if let Ok(ip) = host.parse::<IpAddr>() {
            return Ok(Self::Ip(SocketAddr::new(ip, port)));
        }
        if host.trim().is_empty() {
            return Err(anyhow!("target host is empty"));
        }
        Ok(Self::Domain(host.to_string(), port))
    }
}

fn split_host_port(value: &str) -> Result<(&str, u16)> {
    let idx = value
        .rfind(':')
        .ok_or_else(|| anyhow!("target must be HOST:PORT: {value}"))?;
    let host = &value[..idx];
    let port = value[idx + 1..]
        .parse::<u16>()
        .with_context(|| format!("invalid target port in {value}"))?;
    Ok((host.trim_start_matches('[').trim_end_matches(']'), port))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ipv4_ipv6_and_domain() {
        assert!(matches!("1.1.1.1:443".parse::<TargetAddr>().unwrap(), TargetAddr::Ip(_)));
        assert!(matches!("[::1]:443".parse::<TargetAddr>().unwrap(), TargetAddr::Ip(_)));
        assert_eq!(
            "dns.example:8443".parse::<TargetAddr>().unwrap(),
            TargetAddr::Domain("dns.example".to_string(), 8443)
        );
    }
}
