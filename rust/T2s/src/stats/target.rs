#[derive(Clone, Debug)]
pub enum Target {
    SockAddr(SocketAddr),
    HostPort(String, u16),
}

impl Target {
    pub fn to_host_port_string(&self) -> (String, u16) {
        match self {
            Target::SockAddr(sa) => (sa.ip().to_string(), sa.port()),
            Target::HostPort(h, p) => (h.clone(), *p),
        }
    }

    pub async fn resolve_socket_addr(&self) -> Result<SocketAddr> {
        match self {
            Target::SockAddr(sa) => Ok(*sa),
            Target::HostPort(host, port) => {
                // Prefer IPv4 (many users disable IPv6 on-device).
                let addrs = (host.as_str(), *port)
                    .to_socket_addrs()
                    .context("resolve target")?;
                let mut first: Option<SocketAddr> = None;
                for sa in addrs {
                    if first.is_none() {
                        first = Some(sa);
                    }
                    if sa.is_ipv4() {
                        return Ok(sa);
                    }
                }
                first.ok_or_else(|| anyhow!("no addr for target"))
            }
        }
    }

    pub async fn to_socks_target(&self) -> Result<TargetAddr> {
        match self {
            Target::SockAddr(sa) => Ok(TargetAddr::Ip(*sa)),
            Target::HostPort(host, port) => Ok(TargetAddr::Domain(host.clone(), *port)),
        }
    }
}

