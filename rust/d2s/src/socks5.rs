use crate::target::TargetAddr;
use anyhow::{anyhow, Context, Result};
use std::{future::Future, net::{IpAddr, Ipv4Addr, SocketAddr}, time::{Duration, SystemTime, UNIX_EPOCH}};
use thiserror::Error;
use tokio::{io::{AsyncReadExt, AsyncWriteExt}, net::TcpStream};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RuntimeFailureClass {
    /// The SOCKS server answered, but this particular destination/path failed.
    TargetPath,
    /// Timeouts and transient I/O pressure. Keep a verified GREEN backend until
    /// hysteresis or a forced Full probe proves that the route is unhealthy.
    Soft,
    /// Local listener/protocol failures that strongly suggest a backend problem.
    Hard,
}

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

impl SocksClientError {
    /// Classify runtime failures using the same separation as T2S: target/path
    /// errors are not backend-health proof, transient timeouts are soft, and
    /// listener/protocol failures are hard. A forced Full probe remains the
    /// authority for confirmed Internet health.
    pub fn runtime_failure_class(&self) -> RuntimeFailureClass {
        use std::io::ErrorKind;
        match self {
            Self::ConnectReply(code) if (0x02..=0x06).contains(code) => RuntimeFailureClass::TargetPath,
            Self::Timeout(_) => RuntimeFailureClass::Soft,
            Self::BackendConnect(error) | Self::Io(_, error)
                if matches!(error.kind(), ErrorKind::TimedOut | ErrorKind::WouldBlock | ErrorKind::Interrupted) =>
            {
                RuntimeFailureClass::Soft
            }
            Self::BackendConnect(_) | Self::Io(_, _) | Self::Protocol(_) | Self::ConnectReply(_) =>
                RuntimeFailureClass::Hard,
        }
    }

    pub fn is_target_path_failure(&self) -> bool {
        self.runtime_failure_class() == RuntimeFailureClass::TargetPath
    }

    /// A single reconnect is worthwhile for network/host unreachable replies:
    /// local proxy stacks can return these briefly while Android is switching
    /// the active network. Other failures are either target-specific or too
    /// expensive to repeat in the DNS query path.
    pub fn should_retry_once_on_single_backend(&self) -> bool {
        matches!(self, Self::ConnectReply(0x03 | 0x04))
    }

    /// Failures before the SOCKS handshake starts are properties of the local
    /// backend listener, not of the external probe target. Trying more probe
    /// targets cannot change the result and only wastes time and wakeups.
    pub fn backend_unavailable_before_handshake(&self) -> bool {
        matches!(
            self,
            Self::BackendConnect(_) | Self::Timeout("backend TCP connect")
        )
    }
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

/// Stage-1 health check: prove that the local SOCKS listener accepts TCP and
/// completes a NO-AUTH greeting, without making an Internet connection.
pub async fn connect_to_socks5_server(
    backend: SocketAddr,
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
            "expected [05,00], got [{:02x},{:02x}]", greeting[0], greeting[1]
        )));
    }
    Ok(stream)
}

/// Stage-2 health check copied in spirit from T2S. SOCKS CONNECT success alone
/// is insufficient: send real TLS data through the established tunnel and
/// require at least one byte from the remote side. This catches local proxy
/// processes whose listener is alive while their upstream route is dead.
pub async fn verify_tls_data_plane(
    stream: &mut TcpStream,
    target: &TargetAddr,
    timeout: Duration,
) -> bool {
    let probe_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));
    let sni = match target {
        TargetAddr::Domain(host, _) => Some(host.as_str()),
        TargetAddr::Ip(_) => None,
    };
    let hello = build_tls_client_hello(sni);

    match tokio::time::timeout(probe_timeout, stream.write_all(&hello)).await {
        Ok(Ok(())) => {}
        _ => return false,
    }

    let mut first = [0u8; 1];
    matches!(
        tokio::time::timeout(probe_timeout, stream.read_exact(&mut first)).await,
        Ok(Ok(_))
    )
}

fn build_tls_client_hello(sni: Option<&str>) -> Vec<u8> {
    let seed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let mut x = seed ^ 0x9e37_79b9_7f4a_7c15;
    let mut random = [0u8; 32];
    for chunk in random.chunks_mut(8) {
        // xorshift64* is sufficient here; TLS Random only needs unpredictable-ish
        // bytes for a probe, not cryptographic key material.
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        let bytes = x.wrapping_mul(0x2545_f491_4f6c_dd1d).to_be_bytes();
        chunk.copy_from_slice(&bytes[..chunk.len()]);
    }

    let mut extensions = Vec::new();
    if let Some(host) = sni.map(str::trim).filter(|h| !h.is_empty() && h.len() <= 253) {
        let host_bytes = host.as_bytes();
        if host_bytes.len() <= 255 {
            let server_name_len = 1 + 2 + host_bytes.len();
            let list_len = server_name_len;
            let ext_len = 2 + list_len;
            extensions.extend_from_slice(&0x0000u16.to_be_bytes());
            extensions.extend_from_slice(&(ext_len as u16).to_be_bytes());
            extensions.extend_from_slice(&(list_len as u16).to_be_bytes());
            extensions.push(0x00);
            extensions.extend_from_slice(&(host_bytes.len() as u16).to_be_bytes());
            extensions.extend_from_slice(host_bytes);
        }
    }

    extensions.extend_from_slice(&0x000au16.to_be_bytes());
    extensions.extend_from_slice(&6u16.to_be_bytes());
    extensions.extend_from_slice(&4u16.to_be_bytes());
    extensions.extend_from_slice(&0x001du16.to_be_bytes());
    extensions.extend_from_slice(&0x0017u16.to_be_bytes());

    extensions.extend_from_slice(&0x000du16.to_be_bytes());
    extensions.extend_from_slice(&8u16.to_be_bytes());
    extensions.extend_from_slice(&6u16.to_be_bytes());
    extensions.extend_from_slice(&0x0804u16.to_be_bytes());
    extensions.extend_from_slice(&0x0403u16.to_be_bytes());
    extensions.extend_from_slice(&0x0401u16.to_be_bytes());

    extensions.extend_from_slice(&0x002bu16.to_be_bytes());
    extensions.extend_from_slice(&5u16.to_be_bytes());
    extensions.push(4);
    extensions.extend_from_slice(&0x0304u16.to_be_bytes());
    extensions.extend_from_slice(&0x0303u16.to_be_bytes());

    let cipher_suites: [u16; 6] = [0x1301, 0x1302, 0x1303, 0xc02f, 0xc02b, 0x009c];
    let mut body = Vec::new();
    body.extend_from_slice(&0x0303u16.to_be_bytes());
    body.extend_from_slice(&random);
    body.push(0);
    body.extend_from_slice(&((cipher_suites.len() * 2) as u16).to_be_bytes());
    for cs in cipher_suites {
        body.extend_from_slice(&cs.to_be_bytes());
    }
    body.push(1);
    body.push(0);
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);

    let mut handshake = Vec::new();
    handshake.push(0x01);
    let body_len = body.len() as u32;
    handshake.push(((body_len >> 16) & 0xff) as u8);
    handshake.push(((body_len >> 8) & 0xff) as u8);
    handshake.push((body_len & 0xff) as u8);
    handshake.extend_from_slice(&body);

    let mut record = Vec::new();
    record.push(0x16);
    record.extend_from_slice(&0x0301u16.to_be_bytes());
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend_from_slice(&handshake);
    record
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

#[cfg(test)]
mod tests {
    use super::SocksClientError;

    #[test]
    fn classifies_target_path_reply_codes() {
        for code in 0x02..=0x06 {
            assert!(SocksClientError::ConnectReply(code).is_target_path_failure());
        }
        for code in [0x01, 0x07, 0x08] {
            assert!(!SocksClientError::ConnectReply(code).is_target_path_failure());
        }
        assert!(SocksClientError::ConnectReply(0x03).should_retry_once_on_single_backend());
        assert!(SocksClientError::ConnectReply(0x04).should_retry_once_on_single_backend());
        assert!(!SocksClientError::ConnectReply(0x05).should_retry_once_on_single_backend());
        assert!(SocksClientError::BackendConnect(std::io::Error::from(std::io::ErrorKind::ConnectionRefused))
            .backend_unavailable_before_handshake());
        assert!(SocksClientError::Timeout("backend TCP connect").backend_unavailable_before_handshake());
        assert!(!SocksClientError::Timeout("CONNECT reply header read").backend_unavailable_before_handshake());
    }
}
