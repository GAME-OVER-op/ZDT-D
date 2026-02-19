use anyhow::{anyhow, Context, Result};
use std::{net::SocketAddr, time::Duration};
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpStream};

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

    // Greeting
    let mut methods = vec![0x00u8]; // no auth
    if auth.is_some() {
        methods.push(0x02u8); // username/password
    }
    stream.write_all(&[0x05u8, methods.len() as u8]).await?;
    stream.write_all(&methods).await?;
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).await.context("socks greeting reply")?;
    if resp[0] != 0x05 {
        return Err(anyhow!("invalid SOCKS version {}", resp[0]));
    }
    match resp[1] {
        0x00 => {}
        0x02 => {
            let (u, p) = auth.clone().ok_or_else(|| anyhow!("server requires auth but no creds"))?;
            do_userpass_auth(&mut stream, &u, &p).await?;
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

    stream.write_all(&req).await?;

    // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT
    let mut hdr = [0u8; 4];
    stream.read_exact(&mut hdr).await.context("socks reply hdr")?;
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
            stream.read_exact(&mut buf).await?;
        }
        0x04 => {
            let mut buf = [0u8; 16+2];
            stream.read_exact(&mut buf).await?;
        }
        0x03 => {
            let mut ln = [0u8; 1];
            stream.read_exact(&mut ln).await?;
            let l = ln[0] as usize;
            let mut buf = vec![0u8; l+2];
            stream.read_exact(&mut buf).await?;
        }
        _ => return Err(anyhow!("unknown ATYP in reply {}", atyp)),
    }

    Ok(stream)
}

async fn do_userpass_auth(stream: &mut TcpStream, user: &str, pass: &str) -> Result<()> {
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
