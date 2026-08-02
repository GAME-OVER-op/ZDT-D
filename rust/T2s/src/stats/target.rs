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
                crate::net_utils::resolve_prefer_ipv4(host, *port)
                    .await
                    .context("resolve target")
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

