use crate::target::TargetAddr;
use anyhow::{anyhow, Context, Result};
use std::{future::Future, net::{IpAddr, Ipv4Addr, SocketAddr}, time::Duration};
use thiserror::Error;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpStream};

#[derive(Debug, Error)]
pub enum SocksClientError {
    #[error("connect to SOCKS5 backend failed: {0}")]
    BackendConnect(#[source] std::io::Error),
    #[error("SOCKS5 handshake timed out during {0}")]
    Timeout(&'static str),
    #[error("SOCKS5 I/O failed during {0}: {1}")]
    Io(&'static str, #[source] std::io::Error),
    #[error("invalid SOCKS5 response: {0}")]
    Protocol(String),
    #[error("SOCKS5 CONNECT failed with reply code 0x{0:02x}")]
    ConnectReply(u8),
}

async fn io_step<T, F>(timeout: Duration, label: &'static str, future: F) -> std::result::Result<T, SocksClientError>
where
    F: Future<Output = std::io::Result<T>>,
{
    match tokio::time::timeout(timeout, future).await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(error)) => Err(SocksClientError::Io(label, error)),
        Err(_) => Err(SocksClientError::Timeout(label)),
    }
}

pub async fn connect_via_socks5(
    backend: SocketAddr,
    target: &TargetAddr,
    connect_timeout: Duration,
    handshake_timeout: Duration,
    tcp_nodelay: bool,
) -> std::result::Result<TcpStream, SocksClientError> {
    let mut stream = match tokio::time::timeout(connect_timeout, TcpStream::connect(backend)).await {
        Ok(Ok(stream)) => stream,
        Ok(Err(error)) => return Err(SocksClientError::BackendConnect(error)),
        Err(_) => return Err(SocksClientError::Timeout("backend TCP connect")),
    };
    let _ = stream.set_nodelay(tcp_nodelay);

    io_step(handshake_timeout, "greeting write", stream.write_all(&[0x05, 0x01, 0x00])).await?;
    let mut greeting = [0u8; 2];
    io_step(handshake_timeout, "greeting read", stream.read_exact(&mut greeting)).await?;
    if greeting != [0x05, 0x00] {
        return Err(SocksClientError::Protocol(format!(
            "expected [05,00], got [{:02x},{:02x}]",
            greeting[0], greeting[1]
        )));
    }

    let mut request = vec![0x05, 0x01, 0x00];
    target
        .encode_socks5(&mut request)
        .map_err(|error| SocksClientError::Protocol(error.to_string()))?;
    io_step(handshake_timeout, "CONNECT request write", stream.write_all(&request)).await?;

    let mut header = [0u8; 4];
    io_step(handshake_timeout, "CONNECT reply header read", stream.read_exact(&mut header)).await?;
    if header[0] != 0x05 {
        return Err(SocksClientError::Protocol(format!("reply version is {}", header[0])));
    }
    if header[1] != 0x00 {
        return Err(SocksClientError::ConnectReply(header[1]));
    }
    consume_address(&mut stream, header[3], handshake_timeout).await?;
    Ok(stream)
}

async fn consume_address(
    stream: &mut TcpStream,
    atyp: u8,
    timeout: Duration,
) -> std::result::Result<(), SocksClientError> {
    match atyp {
        0x01 => {
            let mut rest = [0u8; 6];
            io_step(timeout, "IPv4 bound address read", stream.read_exact(&mut rest)).await?;
        }
        0x04 => {
            let mut rest = [0u8; 18];
            io_step(timeout, "IPv6 bound address read", stream.read_exact(&mut rest)).await?;
        }
        0x03 => {
            let mut len = [0u8; 1];
            io_step(timeout, "domain bound length read", stream.read_exact(&mut len)).await?;
            let mut rest = vec![0u8; len[0] as usize + 2];
            io_step(timeout, "domain bound address read", stream.read_exact(&mut rest)).await?;
        }
        other => return Err(SocksClientError::Protocol(format!("unknown ATYP 0x{other:02x}"))),
    }
    Ok(())
}

pub async fn read_client_request(stream: &mut TcpStream, timeout: Duration) -> Result<TargetAddr> {
    let mut head = [0u8; 2];
    io_server_step(timeout, "client greeting header", stream.read_exact(&mut head)).await?;
    if head[0] != 0x05 {
        return Err(anyhow!("unsupported SOCKS version {}", head[0]));
    }
    if head[1] == 0 {
        return Err(anyhow!("client sent no authentication methods"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    io_server_step(timeout, "client greeting methods", stream.read_exact(&mut methods)).await?;
    if !methods.contains(&0x00) {
        stream.write_all(&[0x05, 0xff]).await.ok();
        return Err(anyhow!("client does not support SOCKS5 NO AUTH"));
    }
    io_server_step(timeout, "server greeting reply", stream.write_all(&[0x05, 0x00])).await?;

    let mut request = [0u8; 4];
    io_server_step(timeout, "client CONNECT header", stream.read_exact(&mut request)).await?;
    if request[0] != 0x05 {
        return Err(anyhow!("invalid request SOCKS version {}", request[0]));
    }
    if request[1] != 0x01 {
        send_reply(stream, 0x07).await.ok();
        return Err(anyhow!("unsupported SOCKS5 command 0x{:02x}", request[1]));
    }
    if request[2] != 0x00 {
        send_reply(stream, 0x01).await.ok();
        return Err(anyhow!("invalid SOCKS5 reserved byte"));
    }

    let target = match request[3] {
        0x01 => {
            let mut raw = [0u8; 4];
            io_server_step(timeout, "client IPv4 address", stream.read_exact(&mut raw)).await?;
            let port = read_port(stream, timeout).await?;
            TargetAddr::Ip(SocketAddr::new(IpAddr::V4(raw.into()), port))
        }
        0x04 => {
            let mut raw = [0u8; 16];
            io_server_step(timeout, "client IPv6 address", stream.read_exact(&mut raw)).await?;
            let port = read_port(stream, timeout).await?;
            TargetAddr::Ip(SocketAddr::new(IpAddr::V6(raw.into()), port))
        }
        0x03 => {
            let mut len = [0u8; 1];
            io_server_step(timeout, "client domain length", stream.read_exact(&mut len)).await?;
            if len[0] == 0 {
                send_reply(stream, 0x08).await.ok();
                return Err(anyhow!("empty SOCKS5 domain"));
            }
            let mut raw = vec![0u8; len[0] as usize];
            io_server_step(timeout, "client domain", stream.read_exact(&mut raw)).await?;
            let host = String::from_utf8(raw).context("SOCKS5 domain is not UTF-8")?;
            let port = read_port(stream, timeout).await?;
            TargetAddr::Domain(host, port)
        }
        atyp => {
            send_reply(stream, 0x08).await.ok();
            return Err(anyhow!("unsupported SOCKS5 address type 0x{atyp:02x}"));
        }
    };
    Ok(target)
}

async fn read_port(stream: &mut TcpStream, timeout: Duration) -> Result<u16> {
    let mut raw = [0u8; 2];
    io_server_step(timeout, "client destination port", stream.read_exact(&mut raw)).await?;
    Ok(u16::from_be_bytes(raw))
}

async fn io_server_step<T, F>(timeout: Duration, label: &'static str, future: F) -> Result<T>
where
    F: Future<Output = std::io::Result<T>>,
{
    tokio::time::timeout(timeout, future)
        .await
        .with_context(|| format!("timeout during {label}"))?
        .with_context(|| format!("I/O failed during {label}"))
}

pub async fn send_success(stream: &mut TcpStream) -> Result<()> {
    send_reply(stream, 0x00).await
}

pub async fn send_failure(stream: &mut TcpStream, reply: u8) -> Result<()> {
    send_reply(stream, reply).await
}

async fn send_reply(stream: &mut TcpStream, reply: u8) -> Result<()> {
    let mut response = vec![0x05, reply, 0x00, 0x01];
    response.extend_from_slice(&Ipv4Addr::UNSPECIFIED.octets());
    response.extend_from_slice(&0u16.to_be_bytes());
    stream.write_all(&response).await.context("write SOCKS5 reply")
}
