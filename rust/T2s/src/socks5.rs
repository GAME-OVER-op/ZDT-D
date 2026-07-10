use anyhow::{anyhow, Context, Result};
use std::{future::Future, net::{SocketAddr, ToSocketAddrs}, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpStream};

async fn io_step<T, F>(timeout: Duration, label: &'static str, fut: F) -> Result<T>
where
    F: Future<Output = std::io::Result<T>> + Send,
{
    tokio::time::timeout(timeout, fut)
        .await
        .with_context(|| format!("socks handshake timeout: {}", label))?
        .with_context(|| format!("socks handshake failed: {}", label))
}

fn handshake_timeout(total_timeout: Duration) -> Duration {
    total_timeout.min(Duration::from_secs(3)).max(Duration::from_millis(800))
}

#[derive(Clone, Debug)]
pub enum TargetAddr {
    Ip(SocketAddr),
    Domain(String, u16),
}

pub async fn connect_via_socks5(
    backend: SocketAddr,
    target: TargetAddr,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    let mut stream = tokio::time::timeout(timeout, TcpStream::connect(backend))
        .await
        .context("socks tcp connect timeout")?
        .context("socks tcp connect failed")?;

    let hs_timeout = handshake_timeout(timeout);

    // Greeting
    let mut methods = vec![0x00u8]; // no auth
    if auth.is_some() {
        methods.push(0x02u8); // username/password
    }
    io_step(hs_timeout, "greeting header write", stream.write_all(&[0x05u8, methods.len() as u8])).await?;
    io_step(hs_timeout, "greeting methods write", stream.write_all(&methods)).await?;
    let mut resp = [0u8; 2];
    io_step(hs_timeout, "greeting reply read", stream.read_exact(&mut resp)).await?;
    if resp[0] != 0x05 {
        return Err(anyhow!("invalid SOCKS version {}", resp[0]));
    }
    match resp[1] {
        0x00 => {}
        0x02 => {
            let (u, p) = auth.clone().ok_or_else(|| anyhow!("server requires auth but no creds"))?;
            tokio::time::timeout(hs_timeout, do_userpass_auth_for_healthcheck(&mut stream, &u, &p))
                .await
                .context("socks handshake timeout: userpass auth")??;
        }
        0xFF => return Err(anyhow!("no acceptable auth methods")),
        m => return Err(anyhow!("unsupported auth method {:#x}", m)),
    }

    // CONNECT
    let mut req = vec![0x05u8, 0x01u8, 0x00u8]; // VER, CMD=CONNECT, RSV
    match target {
        TargetAddr::Ip(sa) => {
            match sa.ip() {
                std::net::IpAddr::V4(v4) => {
                    req.push(0x01u8);
                    req.extend_from_slice(&v4.octets());
                }
                std::net::IpAddr::V6(v6) => {
                    req.push(0x04u8);
                    req.extend_from_slice(&v6.octets());
                }
            }
            req.extend_from_slice(&sa.port().to_be_bytes());
        }
        TargetAddr::Domain(host, port) => {
            let hb = host.as_bytes();
            if hb.len() > 255 {
                return Err(anyhow!("domain too long for SOCKS5"));
            }
            req.push(0x03u8);
            req.push(hb.len() as u8);
            req.extend_from_slice(hb);
            req.extend_from_slice(&port.to_be_bytes());
        }
    }

    io_step(hs_timeout, "connect request write", stream.write_all(&req)).await?;

    // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
    let mut hdr = [0u8; 4];
    io_step(hs_timeout, "connect reply head read", stream.read_exact(&mut hdr)).await?;
    if hdr[0] != 0x05 {
        return Err(anyhow!("invalid SOCKS version in reply {}", hdr[0]));
    }
    if hdr[1] != 0x00 {
        return Err(anyhow!("SOCKS CONNECT failed, REP={:#x}", hdr[1]));
    }
    let atyp = hdr[3];
    match atyp {
        0x01 => {
            let mut buf = [0u8; 4+2];
            io_step(hs_timeout, "connect reply ipv4 read", stream.read_exact(&mut buf)).await?;
        }
        0x04 => {
            let mut buf = [0u8; 16+2];
            io_step(hs_timeout, "connect reply ipv6 read", stream.read_exact(&mut buf)).await?;
        }
        0x03 => {
            let mut ln = [0u8; 1];
            io_step(hs_timeout, "connect reply domain len read", stream.read_exact(&mut ln)).await?;
            let l = ln[0] as usize;
            let mut buf = vec![0u8; l+2];
            io_step(hs_timeout, "connect reply domain read", stream.read_exact(&mut buf)).await?;
        }
        _ => return Err(anyhow!("unknown ATYP in reply {}", atyp)),
    }

    Ok(stream)
}


pub async fn connect_via_socks5_wrapped(
    wrapper: SocketAddr,
    remote_socks: SocketAddr,
    target: TargetAddr,
    wrapper_auth: Option<(String, String)>,
    remote_auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    let tunnel = connect_via_socks5(wrapper, TargetAddr::Ip(remote_socks), wrapper_auth, timeout)
        .await
        .with_context(|| format!("wrapped SOCKS5: connect wrapper {} -> remote socks {}", wrapper, remote_socks))?;
    connect_via_socks5_on_stream(tunnel, target, remote_auth, timeout)
        .await
        .with_context(|| format!("wrapped SOCKS5: remote socks CONNECT through {}", remote_socks))
}

pub async fn connect_to_socks5_server(
    backend: SocketAddr,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    let stream = tokio::time::timeout(timeout, TcpStream::connect(backend))
        .await
        .context("socks tcp connect timeout")?
        .context("socks tcp connect failed")?;
    socks5_greeting_on_stream(stream, auth, timeout).await
}

pub async fn connect_to_socks5_server_wrapped(
    wrapper: SocketAddr,
    remote_socks: SocketAddr,
    wrapper_auth: Option<(String, String)>,
    remote_auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    let tunnel = connect_via_socks5(wrapper, TargetAddr::Ip(remote_socks), wrapper_auth, timeout)
        .await
        .with_context(|| format!("wrapped SOCKS5: connect wrapper {} -> remote socks {}", wrapper, remote_socks))?;
    socks5_greeting_on_stream(tunnel, remote_auth, timeout)
        .await
        .with_context(|| format!("wrapped SOCKS5: remote socks greeting {}", remote_socks))
}

async fn socks5_greeting_on_stream(
    mut stream: TcpStream,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    let hs_timeout = handshake_timeout(timeout);
    let mut methods = vec![0x00u8];
    if auth.is_some() { methods.push(0x02u8); }
    io_step(hs_timeout, "greeting header write", stream.write_all(&[0x05u8, methods.len() as u8])).await?;
    io_step(hs_timeout, "greeting methods write", stream.write_all(&methods)).await?;
    let mut resp = [0u8; 2];
    io_step(hs_timeout, "greeting reply read", stream.read_exact(&mut resp)).await?;
    if resp[0] != 0x05 { return Err(anyhow!("invalid SOCKS version {}", resp[0])); }
    match resp[1] {
        0x00 => Ok(stream),
        0x02 => {
            let (u, p) = auth.ok_or_else(|| anyhow!("server requires auth but no creds"))?;
            tokio::time::timeout(hs_timeout, do_userpass_auth_for_healthcheck(&mut stream, &u, &p))
                .await
                .context("socks handshake timeout: userpass auth")??;
            Ok(stream)
        }
        0xFF => Err(anyhow!("no acceptable auth methods")),
        m => Err(anyhow!("unsupported auth method {:#x}", m)),
    }
}

async fn connect_via_socks5_on_stream(
    mut stream: TcpStream,
    target: TargetAddr,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<TcpStream> {
    stream = socks5_greeting_on_stream(stream, auth, timeout).await?;
    socks5_connect_on_stream(&mut stream, target, timeout).await?;
    Ok(stream)
}

async fn socks5_connect_on_stream(stream: &mut TcpStream, target: TargetAddr, timeout: Duration) -> Result<()> {
    let hs_timeout = handshake_timeout(timeout);
    let mut req = vec![0x05u8, 0x01u8, 0x00u8];
    match target {
        TargetAddr::Ip(sa) => {
            match sa.ip() {
                std::net::IpAddr::V4(v4) => { req.push(0x01u8); req.extend_from_slice(&v4.octets()); }
                std::net::IpAddr::V6(v6) => { req.push(0x04u8); req.extend_from_slice(&v6.octets()); }
            }
            req.extend_from_slice(&sa.port().to_be_bytes());
        }
        TargetAddr::Domain(host, port) => {
            let hb = host.as_bytes();
            if hb.len() > 255 { return Err(anyhow!("domain too long for SOCKS5")); }
            req.push(0x03u8); req.push(hb.len() as u8); req.extend_from_slice(hb); req.extend_from_slice(&port.to_be_bytes());
        }
    }
    io_step(hs_timeout, "connect request write", stream.write_all(&req)).await?;
    let mut hdr = [0u8; 4];
    io_step(hs_timeout, "connect reply head read", stream.read_exact(&mut hdr)).await?;
    if hdr[0] != 0x05 { return Err(anyhow!("invalid SOCKS version in reply {}", hdr[0])); }
    if hdr[1] != 0x00 { return Err(anyhow!("SOCKS CONNECT failed, REP={:#x}", hdr[1])); }
    match hdr[3] {
        0x01 => { let mut buf = [0u8; 6]; io_step(hs_timeout, "connect reply ipv4 read", stream.read_exact(&mut buf)).await?; }
        0x04 => { let mut buf = [0u8; 18]; io_step(hs_timeout, "connect reply ipv6 read", stream.read_exact(&mut buf)).await?; }
        0x03 => { let mut ln=[0u8;1]; io_step(hs_timeout, "connect reply domain len read", stream.read_exact(&mut ln)).await?; let mut buf=vec![0u8; ln[0] as usize + 2]; io_step(hs_timeout, "connect reply domain read", stream.read_exact(&mut buf)).await?; }
        _ => return Err(anyhow!("unknown ATYP in reply {}", hdr[3])),
    }
    Ok(())
}

pub(crate) async fn do_userpass_auth_for_healthcheck(stream: &mut TcpStream, user: &str, pass: &str) -> Result<()> {
    let ub = user.as_bytes();
    let pb = pass.as_bytes();
    if ub.len() > 255 || pb.len() > 255 {
        return Err(anyhow!("username/password too long"));
    }
    let mut req = Vec::with_capacity(3 + ub.len() + pb.len());
    req.push(0x01);
    req.push(ub.len() as u8);
    req.extend_from_slice(ub);
    req.push(pb.len() as u8);
    req.extend_from_slice(pb);

    stream.write_all(&req).await?;
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).await?;
    if resp[0] != 0x01 || resp[1] != 0x00 {
        return Err(anyhow!("SOCKS auth failed"));
    }
    Ok(())
}


pub async fn udp_associate(
    backend: SocketAddr,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Result<(TcpStream, SocketAddr)> {
    let mut stream = connect_to_socks5_server(backend, auth, timeout).await?;
    let hs_timeout = handshake_timeout(timeout);
    let req = vec![
        0x05u8, 0x03u8, 0x00u8, // VER, CMD=UDP ASSOCIATE, RSV
        0x01u8, 0, 0, 0, 0,     // ATYP=IPv4, ADDR=0.0.0.0
        0, 0,                   // PORT=0
    ];
    io_step(hs_timeout, "udp associate request write", stream.write_all(&req)).await?;
    let mut hdr = [0u8; 4];
    io_step(hs_timeout, "udp associate reply head read", stream.read_exact(&mut hdr)).await?;
    if hdr[0] != 0x05 {
        return Err(anyhow!("invalid SOCKS version in UDP ASSOCIATE reply {}", hdr[0]));
    }
    if hdr[1] != 0x00 {
        return Err(anyhow!("SOCKS UDP ASSOCIATE failed, REP={:#x}", hdr[1]));
    }
    let relay = match hdr[3] {
        0x01 => {
            let mut buf = [0u8; 6];
            io_step(hs_timeout, "udp associate ipv4 relay read", stream.read_exact(&mut buf)).await?;
            let ip = std::net::Ipv4Addr::new(buf[0], buf[1], buf[2], buf[3]);
            let port = u16::from_be_bytes([buf[4], buf[5]]);
            SocketAddr::new(std::net::IpAddr::V4(ip), port)
        }
        0x04 => {
            let mut buf = [0u8; 18];
            io_step(hs_timeout, "udp associate ipv6 relay read", stream.read_exact(&mut buf)).await?;
            let ip = std::net::Ipv6Addr::from(<[u8; 16]>::try_from(&buf[..16]).map_err(|_| anyhow!("invalid ipv6 relay length"))?);
            let port = u16::from_be_bytes([buf[16], buf[17]]);
            SocketAddr::new(std::net::IpAddr::V6(ip), port)
        }
        0x03 => {
            let mut ln = [0u8; 1];
            io_step(hs_timeout, "udp associate domain relay len read", stream.read_exact(&mut ln)).await?;
            let l = ln[0] as usize;
            let mut buf = vec![0u8; l + 2];
            io_step(hs_timeout, "udp associate domain relay read", stream.read_exact(&mut buf)).await?;
            let host = std::str::from_utf8(&buf[..l]).context("UDP ASSOCIATE relay domain utf8")?;
            let port = u16::from_be_bytes([buf[l], buf[l + 1]]);
            let mut addrs = (host, port).to_socket_addrs().context("resolve UDP ASSOCIATE relay domain")?;
            addrs.next().ok_or_else(|| anyhow!("no UDP ASSOCIATE relay address"))?
        }
        atyp => return Err(anyhow!("unknown ATYP in UDP ASSOCIATE reply {}", atyp)),
    };
    let relay = if relay.ip().is_unspecified() {
        SocketAddr::new(backend.ip(), relay.port())
    } else {
        relay
    };
    Ok((stream, relay))
}

pub async fn check_udp_associate(
    backend: SocketAddr,
    auth: Option<(String, String)>,
    timeout: Duration,
) -> Option<f64> {
    let start = std::time::Instant::now();
    match udp_associate(backend, auth, timeout).await {
        Ok((_control, _relay)) => Some(start.elapsed().as_secs_f64() * 1000.0),
        Err(_) => None,
    }
}

pub fn encode_udp_packet(target: TargetAddr, data: &[u8]) -> Result<Vec<u8>> {
    let mut out = vec![0u8, 0u8, 0u8]; // RSV, RSV, FRAG=0
    match target {
        TargetAddr::Ip(sa) => {
            match sa.ip() {
                std::net::IpAddr::V4(v4) => { out.push(0x01); out.extend_from_slice(&v4.octets()); }
                std::net::IpAddr::V6(v6) => { out.push(0x04); out.extend_from_slice(&v6.octets()); }
            }
            out.extend_from_slice(&sa.port().to_be_bytes());
        }
        TargetAddr::Domain(host, port) => {
            let hb = host.as_bytes();
            if hb.len() > 255 { return Err(anyhow!("domain too long for SOCKS5 UDP")); }
            out.push(0x03);
            out.push(hb.len() as u8);
            out.extend_from_slice(hb);
            out.extend_from_slice(&port.to_be_bytes());
        }
    }
    out.extend_from_slice(data);
    Ok(out)
}

pub fn decode_udp_packet(buf: &[u8]) -> Result<(TargetAddr, &[u8])> {
    if buf.len() < 4 || buf[0] != 0 || buf[1] != 0 {
        return Err(anyhow!("invalid SOCKS5 UDP header"));
    }
    if buf[2] != 0 {
        return Err(anyhow!("fragmented SOCKS5 UDP packets are not supported"));
    }
    let atyp = buf[3];
    let mut i = 4usize;
    let target = match atyp {
        0x01 => {
            if buf.len() < i + 4 + 2 { return Err(anyhow!("short SOCKS5 UDP IPv4 packet")); }
            let ip = std::net::Ipv4Addr::new(buf[i], buf[i + 1], buf[i + 2], buf[i + 3]);
            i += 4;
            let port = u16::from_be_bytes([buf[i], buf[i + 1]]);
            i += 2;
            TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(ip), port))
        }
        0x04 => {
            if buf.len() < i + 16 + 2 { return Err(anyhow!("short SOCKS5 UDP IPv6 packet")); }
            let ip = std::net::Ipv6Addr::from(<[u8; 16]>::try_from(&buf[i..i + 16]).map_err(|_| anyhow!("invalid IPv6 length"))?);
            i += 16;
            let port = u16::from_be_bytes([buf[i], buf[i + 1]]);
            i += 2;
            TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V6(ip), port))
        }
        0x03 => {
            if buf.len() < i + 1 { return Err(anyhow!("short SOCKS5 UDP domain packet")); }
            let l = buf[i] as usize;
            i += 1;
            if buf.len() < i + l + 2 { return Err(anyhow!("short SOCKS5 UDP domain body")); }
            let host = std::str::from_utf8(&buf[i..i + l]).context("SOCKS5 UDP domain utf8")?.to_string();
            i += l;
            let port = u16::from_be_bytes([buf[i], buf[i + 1]]);
            i += 2;
            TargetAddr::Domain(host, port)
        }
        _ => return Err(anyhow!("unknown SOCKS5 UDP ATYP {}", atyp)),
    };
    Ok((target, &buf[i..]))
}
