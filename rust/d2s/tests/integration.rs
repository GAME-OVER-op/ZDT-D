use d2s::{backend::{BackendPool, BackendState}, start, Config};
use std::{
    net::{IpAddr, SocketAddr},
    sync::{Arc, atomic::{AtomicUsize, Ordering}},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, copy_bidirectional},
    net::{TcpListener, TcpStream},
    sync::watch,
    task::JoinHandle,
};

struct EchoServer {
    addr: SocketAddr,
    shutdown: watch::Sender<bool>,
    task: JoinHandle<()>,
}

impl EchoServer {
    async fn start() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let (shutdown, mut rx) = watch::channel(false);
        let task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = rx.changed() => break,
                    accepted = listener.accept() => {
                        let Ok((mut stream, _)) = accepted else { continue; };
                        tokio::spawn(async move {
                            let mut buf = [0u8; 2048];
                            loop {
                                match stream.read(&mut buf).await {
                                    Ok(0) | Err(_) => break,
                                    Ok(n) => {
                                        if stream.write_all(&buf[..n]).await.is_err() { break; }
                                    }
                                }
                            }
                        });
                    }
                }
            }
        });
        Self { addr, shutdown, task }
    }

    async fn stop(self) {
        let _ = self.shutdown.send(true);
        let _ = self.task.await;
    }
}

struct MockSocks {
    addr: SocketAddr,
    reply_code: Arc<AtomicUsize>,
    connects: Arc<AtomicUsize>,
    shutdown: watch::Sender<bool>,
    task: JoinHandle<()>,
}

impl MockSocks {
    async fn start(initially_failing: bool) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let reply_code = Arc::new(AtomicUsize::new(if initially_failing { 0x01 } else { 0x00 }));
        let connects = Arc::new(AtomicUsize::new(0));
        let (shutdown, mut rx) = watch::channel(false);
        let reply_code_task = reply_code.clone();
        let connects_task = connects.clone();
        let task = tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = rx.changed() => break,
                    accepted = listener.accept() => {
                        let Ok((stream, _)) = accepted else { continue; };
                        let reply_code = reply_code_task.clone();
                        let connects = connects_task.clone();
                        tokio::spawn(async move {
                            let _ = handle_mock_socks(stream, reply_code, connects).await;
                        });
                    }
                }
            }
        });
        Self { addr, reply_code, connects, shutdown, task }
    }

    fn set_failing(&self, value: bool) {
        self.reply_code.store(if value { 0x01 } else { 0x00 }, Ordering::Relaxed);
    }


    fn set_reply_code(&self, code: u8) {
        self.reply_code.store(code as usize, Ordering::Relaxed);
    }

    fn reset_count(&self) {
        self.connects.store(0, Ordering::Relaxed);
    }

    fn count(&self) -> usize {
        self.connects.load(Ordering::Relaxed)
    }

    async fn stop(self) {
        let _ = self.shutdown.send(true);
        let _ = self.task.await;
    }
}

async fn handle_mock_socks(
    mut client: TcpStream,
    reply_code: Arc<AtomicUsize>,
    connects: Arc<AtomicUsize>,
) -> std::io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting).await?;
    let mut methods = vec![0u8; greeting[1] as usize];
    client.read_exact(&mut methods).await?;
    client.write_all(&[0x05, 0x00]).await?;

    let mut request = [0u8; 4];
    client.read_exact(&mut request).await?;
    let target = read_target(&mut client, request[3]).await?;
    connects.fetch_add(1, Ordering::Relaxed);

    let reply_code = reply_code.load(Ordering::Relaxed) as u8;
    if reply_code != 0 {
        client.write_all(&[0x05, reply_code, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Ok(());
    }

    let mut upstream = TcpStream::connect(target).await?;
    client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
    let _ = copy_bidirectional(&mut client, &mut upstream).await?;
    Ok(())
}

async fn read_target(stream: &mut TcpStream, atyp: u8) -> std::io::Result<SocketAddr> {
    match atyp {
        0x01 => {
            let mut ip = [0u8; 4];
            stream.read_exact(&mut ip).await?;
            let port = read_port(stream).await?;
            Ok(SocketAddr::new(IpAddr::V4(ip.into()), port))
        }
        0x04 => {
            let mut ip = [0u8; 16];
            stream.read_exact(&mut ip).await?;
            let port = read_port(stream).await?;
            Ok(SocketAddr::new(IpAddr::V6(ip.into()), port))
        }
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            let mut host = vec![0u8; len[0] as usize];
            stream.read_exact(&mut host).await?;
            let port = read_port(stream).await?;
            let host = String::from_utf8(host)
                .map_err(|error| std::io::Error::new(std::io::ErrorKind::InvalidData, error))?;
            tokio::net::lookup_host((host.as_str(), port))
                .await?
                .next()
                .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::NotFound, "domain resolved to no addresses"))
        }
        _ => Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "unsupported test ATYP")),
    }
}

async fn read_port(stream: &mut TcpStream) -> std::io::Result<u16> {
    let mut port = [0u8; 2];
    stream.read_exact(&mut port).await?;
    Ok(u16::from_be_bytes(port))
}

fn config(backends: Vec<SocketAddr>, probe_target: SocketAddr) -> Config {
    Config {
        listen: "127.0.0.1:0".parse().unwrap(),
        backends,
        direct_fallback: true,
        connect_timeout_ms: 500,
        upstream_handshake_timeout_ms: 500,
        backend_attempt_timeout_ms: 700,
        direct_connect_timeout_ms: 700,
        route_timeout_ms: 1500,
        max_backend_attempts: 3,
        max_connecting: 16,
        client_handshake_timeout_ms: 500,
        probe_timeout_ms: 500,
        healthy_probe_interval_secs: 60,
        recovery_probe_interval_secs: 1,
        failure_threshold: 1,
        runtime_cooldown_ms: 100,
        idle_after_secs: 60,
        probe_targets: vec![probe_target.to_string()],
        max_connections: 64,
        tcp_nodelay: true,
        log_level: "error".to_string(),
        status_file: None,
        status_interval_secs: 1,
        shutdown_grace_period_ms: 1000,
    }
}

async fn wait_for_green(server: &d2s::RunningServer, expected: usize) {
    for _ in 0..50 {
        let green = server
            .pool
            .snapshots()
            .await
            .into_iter()
            .filter(|entry| entry.state == BackendState::Green)
            .count();
        if green >= expected {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("timed out waiting for {expected} GREEN backend(s)");
}

async fn roundtrip(proxy: SocketAddr, target: SocketAddr, payload: &[u8]) {
    let mut stream = TcpStream::connect(proxy).await.unwrap();
    stream.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting, [0x05, 0x00]);

    let mut request = vec![0x05, 0x01, 0x00, 0x01];
    let IpAddr::V4(ip) = target.ip() else { panic!("test target must be IPv4") };
    request.extend_from_slice(&ip.octets());
    request.extend_from_slice(&target.port().to_be_bytes());
    stream.write_all(&request).await.unwrap();

    let mut reply = [0u8; 10];
    stream.read_exact(&mut reply).await.unwrap();
    assert_eq!(reply[1], 0x00);

    stream.write_all(payload).await.unwrap();
    let mut echoed = vec![0u8; payload.len()];
    stream.read_exact(&mut echoed).await.unwrap();
    assert_eq!(echoed, payload);
}


async fn roundtrip_domain(proxy: SocketAddr, host: &str, port: u16, payload: &[u8]) {
    let mut stream = TcpStream::connect(proxy).await.unwrap();
    stream.write_all(&[0x05, 0x01, 0x00]).await.unwrap();
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting, [0x05, 0x00]);

    let host_bytes = host.as_bytes();
    assert!(host_bytes.len() <= 255);
    let mut request = vec![0x05, 0x01, 0x00, 0x03, host_bytes.len() as u8];
    request.extend_from_slice(host_bytes);
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request).await.unwrap();

    let mut reply = [0u8; 10];
    stream.read_exact(&mut reply).await.unwrap();
    assert_eq!(reply[1], 0x00);

    stream.write_all(payload).await.unwrap();
    let mut echoed = vec![0u8; payload.len()];
    stream.read_exact(&mut echoed).await.unwrap();
    assert_eq!(echoed, payload);
}

#[tokio::test]
async fn unchecked_backends_are_immediately_available_for_routing() {
    let probe: SocketAddr = "127.0.0.1:9".parse().unwrap();
    let first: SocketAddr = "127.0.0.1:10001".parse().unwrap();
    let second: SocketAddr = "127.0.0.1:10002".parse().unwrap();
    let pool = BackendPool::new(Arc::new(config(vec![first, second], probe))).unwrap();

    let candidates = pool.candidate_order().await;
    assert_eq!(candidates.len(), 2);
    assert!(candidates.contains(&first));
    assert!(candidates.contains(&second));
    assert!(pool
        .snapshots()
        .await
        .iter()
        .all(|entry| entry.state == BackendState::Unknown));
}

#[tokio::test]
async fn target_unreachable_reply_does_not_degrade_healthy_backend() {
    let echo = EchoServer::start().await;
    let first = MockSocks::start(false).await;
    let second = MockSocks::start(false).await;
    let server = start(config(vec![first.addr, second.addr], echo.addr)).await.unwrap();
    wait_for_green(&server, 2).await;

    first.reset_count();
    second.reset_count();
    first.set_reply_code(0x04);
    roundtrip(server.listen_addr, echo.addr, b"soft-failover").await;

    assert!(first.count() >= 1);
    assert!(second.count() >= 1);
    let states = server.pool.snapshots().await;
    assert_eq!(states[0].state, BackendState::Green);

    server.shutdown().await.unwrap();
    first.stop().await;
    second.stop().await;
    echo.stop().await;
}

#[tokio::test]
async fn balances_only_green_backends_round_robin() {
    let echo = EchoServer::start().await;
    let first = MockSocks::start(false).await;
    let second = MockSocks::start(false).await;
    let server = start(config(vec![first.addr, second.addr], echo.addr)).await.unwrap();
    wait_for_green(&server, 2).await;
    first.reset_count();
    second.reset_count();

    for n in 0..4u8 {
        roundtrip(server.listen_addr, echo.addr, &[n, 1, 2, 3]).await;
    }

    assert_eq!(first.count(), 2);
    assert_eq!(second.count(), 2);
    server.shutdown().await.unwrap();
    first.stop().await;
    second.stop().await;
    echo.stop().await;
}


#[tokio::test]
async fn preserves_domain_targets_through_upstream_socks5() {
    let echo = EchoServer::start().await;
    let backend = MockSocks::start(false).await;
    let server = start(config(vec![backend.addr], echo.addr)).await.unwrap();
    wait_for_green(&server, 1).await;

    roundtrip_domain(server.listen_addr, "127.0.0.1", echo.addr.port(), b"domain").await;

    assert_eq!(server.stats.upstream_connections.load(Ordering::Relaxed), 1);
    server.shutdown().await.unwrap();
    backend.stop().await;
    echo.stop().await;
}

#[tokio::test]
async fn failed_backend_is_skipped_within_the_same_request() {
    let echo = EchoServer::start().await;
    let first = MockSocks::start(false).await;
    let second = MockSocks::start(false).await;
    let server = start(config(vec![first.addr, second.addr], echo.addr)).await.unwrap();
    wait_for_green(&server, 2).await;
    first.reset_count();
    second.reset_count();
    first.set_failing(true);

    roundtrip(server.listen_addr, echo.addr, b"failover").await;

    assert!(first.count() >= 1);
    assert!(second.count() >= 1);
    assert_eq!(server.stats.direct_connections.load(Ordering::Relaxed), 0);
    let states = server.pool.snapshots().await;
    assert_ne!(states[0].state, BackendState::Green);

    server.shutdown().await.unwrap();
    first.stop().await;
    second.stop().await;
    echo.stop().await;
}

#[tokio::test]
async fn all_backends_down_uses_direct_fallback() {
    let echo = EchoServer::start().await;
    let first = MockSocks::start(true).await;
    let second = MockSocks::start(true).await;
    let server = start(config(vec![first.addr, second.addr], echo.addr)).await.unwrap();

    roundtrip(server.listen_addr, echo.addr, b"direct").await;

    assert_eq!(server.stats.direct_connections.load(Ordering::Relaxed), 1);
    assert_eq!(server.stats.upstream_connections.load(Ordering::Relaxed), 0);

    server.shutdown().await.unwrap();
    first.stop().await;
    second.stop().await;
    echo.stop().await;
}

#[tokio::test]
async fn recovered_backend_returns_to_green_pool() {
    let echo = EchoServer::start().await;
    let first = MockSocks::start(true).await;
    let second = MockSocks::start(false).await;
    let server = start(config(vec![first.addr, second.addr], echo.addr)).await.unwrap();
    wait_for_green(&server, 1).await;

    first.set_failing(false);
    tokio::time::sleep(Duration::from_millis(2200)).await;

    let states = server.pool.snapshots().await;
    assert_eq!(states[0].state, BackendState::Green);

    server.shutdown().await.unwrap();
    first.stop().await;
    second.stop().await;
    echo.stop().await;
}

#[tokio::test]
async fn empty_backend_pool_uses_direct_fallback() {
    let echo = EchoServer::start().await;
    let server = start(config(Vec::new(), echo.addr)).await.unwrap();

    roundtrip(server.listen_addr, echo.addr, b"direct-empty").await;

    assert_eq!(server.stats.direct_connections.load(Ordering::Relaxed), 1);
    assert_eq!(server.stats.upstream_connections.load(Ordering::Relaxed), 0);

    server.shutdown().await.unwrap();
    echo.stop().await;
}
