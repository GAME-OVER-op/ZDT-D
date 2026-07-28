use crate::{cli::PriorityZeroMode, rules, socks5, stats, AppState};
use anyhow::{Context, Result};
use parking_lot::Mutex;
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    collections::HashMap,
    hash::{Hash, Hasher},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    os::unix::io::{AsRawFd, RawFd},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{io::unix::AsyncFd, sync::{mpsc, Mutex as AsyncMutex}};

const IP_TRANSPARENT_OPT: libc::c_int = 19;
const IP_RECVORIGDSTADDR_OPT: libc::c_int = 20;
const IPV6_TRANSPARENT_OPT: libc::c_int = 75;

const UDP_SESSION_IDLE: Duration = Duration::from_secs(60);
const UDP_FIRST_RESPONSE_TIMEOUT: Duration = Duration::from_secs(6);
const UDP_RESPONSE_STALL_TIMEOUT: Duration = Duration::from_secs(15);
const UDP_BACKEND_WAIT: Duration = Duration::from_millis(3500);
const UDP_SESSION_MAX: usize = 4096;
const UDP_SESSION_QUEUE: usize = 256;
const UDP_RECV_BUF_SIZE: usize = 65_535;

#[derive(Debug)]
struct UdpPacket { peer: SocketAddr, original_dst: SocketAddr, data: Vec<u8> }
struct AsyncUdpSocket { inner: std::net::UdpSocket }
impl AsRawFd for AsyncUdpSocket { fn as_raw_fd(&self) -> RawFd { self.inner.as_raw_fd() } }

#[derive(Clone, Copy, Debug, Eq)]
struct UdpSessionKey {
    peer: SocketAddr,
    original_dst: SocketAddr,
}

impl PartialEq for UdpSessionKey {
    fn eq(&self, other: &Self) -> bool {
        self.peer == other.peer && self.original_dst == other.original_dst
    }
}

impl Hash for UdpSessionKey {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.peer.hash(state);
        self.original_dst.hash(state);
    }
}

#[derive(Clone)]
struct UdpSessionHandle {
    tx: mpsc::Sender<Vec<u8>>,
    last_activity_ms: Arc<AtomicU64>,
}

type UdpSessions = Arc<Mutex<HashMap<UdpSessionKey, UdpSessionHandle>>>;
type UdpCreationLocks = Arc<AsyncMutex<HashMap<UdpSessionKey, Arc<AsyncMutex<()>>>>>;

pub async fn run_udp_tproxy(state: AppState) -> Result<()> {
    let addr: SocketAddr = format!("{}:{}", state.args.listen_addr, state.args.listen_port).parse().context("udp listen addr parse")?;
    let udp = Arc::new(bind_udp_tproxy(addr).context("bind udp tproxy")?);
    let sessions: UdpSessions = Arc::new(Mutex::new(HashMap::new()));
    let creation_locks: UdpCreationLocks = Arc::new(AsyncMutex::new(HashMap::new()));
    tracing::info!("UDP TPROXY session relay listening on 0.0.0.0:{}", addr.port());
    loop {
        let pkt = recv_udp_packet(udp.clone()).await?;
        let st = state.clone();
        let udp_send = udp.clone();
        let sessions_for_pkt = sessions.clone();
        let creation_locks_for_pkt = creation_locks.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_udp_packet(st, udp_send, sessions_for_pkt, creation_locks_for_pkt, pkt).await {
                tracing::debug!("udp packet handling failed: {:#}", e);
            }
        });
    }
}

fn set_transparent(fd: RawFd, ipv6: bool) -> Result<()> {
    unsafe {
        let one: libc::c_int = 1;
        let (level, opt) = if ipv6 { (libc::SOL_IPV6, IPV6_TRANSPARENT_OPT) } else { (libc::SOL_IP, IP_TRANSPARENT_OPT) };
        let rc = libc::setsockopt(fd, level, opt, &one as *const _ as *const libc::c_void, std::mem::size_of_val(&one) as libc::socklen_t);
        if rc != 0 { return Err(std::io::Error::last_os_error()).context("setsockopt transparent"); }
    }
    Ok(())
}

fn bind_udp_tproxy(addr: SocketAddr) -> Result<AsyncFd<AsyncUdpSocket>> {
    let bind_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), addr.port());
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)).context("create udp socket")?;
    socket.set_reuse_address(true).ok();
    set_transparent(socket.as_raw_fd(), false)?;
    unsafe {
        let one: libc::c_int = 1;
        let rc = libc::setsockopt(socket.as_raw_fd(), libc::SOL_IP, IP_RECVORIGDSTADDR_OPT, &one as *const _ as *const libc::c_void, std::mem::size_of_val(&one) as libc::socklen_t);
        if rc != 0 { return Err(std::io::Error::last_os_error()).context("setsockopt IP_RECVORIGDSTADDR"); }
    }
    socket.bind(&bind_addr.into()).with_context(|| format!("bind udp transparent on {}", bind_addr))?;
    socket.set_nonblocking(true).context("set udp nonblocking")?;
    let inner: std::net::UdpSocket = socket.into();
    AsyncFd::new(AsyncUdpSocket { inner }).context("asyncfd udp socket")
}

async fn recv_udp_packet(sock: Arc<AsyncFd<AsyncUdpSocket>>) -> Result<UdpPacket> {
    loop {
        let mut guard = sock.readable().await.context("udp readable")?;
        match guard.try_io(|inner| recv_udp_packet_once(inner.get_ref().inner.as_raw_fd())) {
            Ok(res) => return Ok(res?),
            Err(_would_block) => continue,
        }
    }
}

fn recv_udp_packet_once(fd: RawFd) -> std::io::Result<UdpPacket> {
    let mut data = vec![0u8; UDP_RECV_BUF_SIZE];
    let mut control = vec![0u8; 256];
    let mut peer_storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let mut iov = libc::iovec { iov_base: data.as_mut_ptr() as *mut libc::c_void, iov_len: data.len() };
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_name = &mut peer_storage as *mut _ as *mut libc::c_void;
    msg.msg_namelen = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = control.len();
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 { return Err(std::io::Error::last_os_error()); }
    data.truncate(n as usize);
    let peer = sockaddr_to_addr(&peer_storage).ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid udp peer"))?;
    let mut original_dst = None;
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_IP && (*cmsg).cmsg_type == IP_RECVORIGDSTADDR_OPT {
                let sin = libc::CMSG_DATA(cmsg) as *const libc::sockaddr_in;
                if !sin.is_null() {
                    let a = *sin;
                    let ip = IpAddr::V4(Ipv4Addr::from(u32::from_be(a.sin_addr.s_addr)));
                    let port = u16::from_be(a.sin_port);
                    original_dst = Some(SocketAddr::new(ip, port));
                }
            }
            cmsg = libc::CMSG_NXTHDR(&msg, cmsg);
        }
    }
    let original_dst = original_dst.ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "missing UDP original dst"))?;
    Ok(UdpPacket { peer, original_dst, data })
}

fn sockaddr_to_addr(storage: &libc::sockaddr_storage) -> Option<SocketAddr> {
    match storage.ss_family as libc::c_int {
        libc::AF_INET => unsafe {
            let sin = *(storage as *const _ as *const libc::sockaddr_in);
            let ip = IpAddr::V4(Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr)));
            let port = u16::from_be(sin.sin_port);
            Some(SocketAddr::new(ip, port))
        },
        libc::AF_INET6 => unsafe {
            let sin6 = *(storage as *const _ as *const libc::sockaddr_in6);
            let ip = IpAddr::V6(std::net::Ipv6Addr::from(sin6.sin6_addr.s6_addr));
            let port = u16::from_be(sin6.sin6_port);
            Some(SocketAddr::new(ip, port))
        },
        _ => None,
    }
}

async fn handle_udp_packet(
    state: AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    sessions: UdpSessions,
    creation_locks: UdpCreationLocks,
    pkt: UdpPacket,
) -> Result<()> {
    let key = UdpSessionKey { peer: pkt.peer, original_dst: pkt.original_dst };
    cleanup_sessions(&sessions);

    let mut data = pkt.data;
    let existing_handle = {
        let guard = sessions.lock();
        guard.get(&key).cloned()
    };
    if let Some(handle) = existing_handle {
        match send_to_session(&handle, data).await {
            Ok(()) => return Ok(()),
            Err(returned_data) => {
                data = returned_data;
                sessions.lock().remove(&key);
            }
        }
    }

    // Serialize creation only for this 4-tuple. QUIC commonly emits several
    // Initial packets at once; without this guard each packet could create a
    // separate UDP ASSOCIATE and close the association selected by the map.
    let key_lock = {
        let mut guard = creation_locks.lock().await;
        guard.entry(key).or_insert_with(|| Arc::new(AsyncMutex::new(()))).clone()
    };
    let create_guard = key_lock.lock().await;

    // Another packet may have completed session creation while this packet was
    // waiting for the per-key lock.
    let existing_handle = {
        let guard = sessions.lock();
        guard.get(&key).cloned()
    };
    if let Some(handle) = existing_handle {
        drop(create_guard);
        let result = send_to_session(&handle, data).await;
        cleanup_creation_lock(&creation_locks, key, &key_lock).await;
        if let Err(returned_data) = result {
            sessions.lock().remove(&key);
            return Err(anyhow::anyhow!("newly created UDP session closed before queued packet ({} bytes)", returned_data.len()));
        }
        return Ok(());
    }

    let new_handle = match create_udp_session(state, udp_sock, key).await {
        Ok(handle) => handle,
        Err(e) => {
            drop(create_guard);
            cleanup_creation_lock(&creation_locks, key, &key_lock).await;
            return Err(e);
        }
    };
    let handle = if let Some(new_handle) = new_handle {
        let mut guard = sessions.lock();
        if guard.len() >= UDP_SESSION_MAX {
            tracing::warn!(
                "udp session limit reached ({}), dropping new session peer={} dst={}",
                UDP_SESSION_MAX,
                key.peer,
                key.original_dst
            );
            None
        } else {
            guard.insert(key, new_handle.clone());
            Some(new_handle)
        }
    } else {
        None
    };

    drop(create_guard);
    cleanup_creation_lock(&creation_locks, key, &key_lock).await;

    if let Some(handle) = handle {
        if send_to_session(&handle, data).await.is_err() {
            sessions.lock().remove(&key);
        }
    }
    Ok(())
}

async fn cleanup_creation_lock(
    creation_locks: &UdpCreationLocks,
    key: UdpSessionKey,
    key_lock: &Arc<AsyncMutex<()>>,
) {
    let mut guard = creation_locks.lock().await;
    if guard.get(&key).map(|v| Arc::ptr_eq(v, key_lock)).unwrap_or(false)
        && Arc::strong_count(key_lock) <= 2
    {
        guard.remove(&key);
    }
}

fn cleanup_sessions(sessions: &UdpSessions) {
    let now = now_ms();
    let idle_ms = UDP_SESSION_IDLE.as_millis() as u64;
    sessions.lock().retain(|_, h| {
        !h.tx.is_closed() && now.saturating_sub(h.last_activity_ms.load(Ordering::Relaxed)) <= idle_ms
    });
}

async fn send_to_session(handle: &UdpSessionHandle, data: Vec<u8>) -> std::result::Result<(), Vec<u8>> {
    handle.last_activity_ms.store(now_ms(), Ordering::Relaxed);
    handle.tx.send(data).await.map_err(|e| e.0)
}

async fn create_udp_session(
    state: AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    key: UdpSessionKey,
) -> Result<Option<UdpSessionHandle>> {
    let target = stats::Target::SockAddr(key.original_dst);
    let (target_host, target_port) = target.to_host_port_string();
    let proto = rules::classify_protocol(target_port);
    let mut udp_socks_available = state.backends.lock().udp_available();
    let action = state.rules.decide(&proto, &target_host, target_port, udp_socks_available, true);

    match action {
        Some(rules::Action::Drop) | Some(rules::Action::Reset) => {
            state.stats.inc_policy_drop();
            return Ok(None);
        }
        Some(rules::Action::Wait) => {
            if !wait_for_udp_backend(&state, UDP_BACKEND_WAIT).await {
                state.stats.inc_policy_drop();
                return Ok(None);
            }
            udp_socks_available = true;
        }
        Some(rules::Action::Direct) => {
            return start_direct_session(state, udp_sock, key).await.map(Some);
        }
        Some(rules::Action::Socks) | None => {}
    }

    let priority_zero_mode = state.args.priority_zero_mode();
    if priority_zero_mode == PriorityZeroMode::DirectOnly {
        return start_direct_session(state, udp_sock, key).await.map(Some);
    }
    if priority_zero_mode == PriorityZeroMode::DirectFirst {
        return start_direct_session(state, udp_sock, key).await.map(Some);
    }

    if !udp_socks_available {
        udp_socks_available = wait_for_udp_backend(&state, UDP_BACKEND_WAIT).await;
    }

    if udp_socks_available {
        // A setup error marks that backend UDP-unhealthy and retries selection,
        // so a broken relay cannot pin a new QUIC session forever.
        let backend_count = state.backends.lock().len().max(1);
        for _ in 0..backend_count {
            let selected = {
                let mut b = state.backends.lock();
                b.select_udp_with_auth(global_auth(&state), true)
            };
            let Some((idx, backend, auth)) = selected else { break; };
            match start_socks_session(state.clone(), udp_sock.clone(), key, idx, backend, auth).await {
                Ok(handle) => return Ok(Some(handle)),
                Err(e) => {
                    mark_udp_backend_failure(&state, idx, format!("UDP session setup failed: {:#}", e));
                    tracing::debug!(
                        "udp socks session setup failed for backend {} peer={} dst={}: {:#}",
                        backend,
                        key.peer,
                        key.original_dst,
                        e
                    );
                }
            }
        }
    }

    if matches!(action, Some(rules::Action::Socks) | Some(rules::Action::Wait))
        || priority_zero_mode == PriorityZeroMode::BlockDirectFallback
    {
        state.stats.inc_policy_drop();
        return Ok(None);
    }

    // Preserve the existing default fallback policy, but only after the UDP
    // backend check has completed. This removes the startup race where the
    // first QUIC flow was permanently assigned to DIRECT while SOCKS health
    // was still unknown.
    start_direct_session(state, udp_sock, key).await.map(Some)
}

async fn wait_for_udp_backend(state: &AppState, timeout: Duration) -> bool {
    if state.backends.lock().udp_available() {
        return true;
    }
    state.runtime.backend_wakeup.notify_waiters();
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if state.backends.lock().udp_available() {
            return true;
        }
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return false;
        }
        tokio::time::sleep(Duration::from_millis(50).min(deadline - now)).await;
    }
}

fn mark_udp_backend_failure(state: &AppState, idx: usize, reason: String) {
    let changed = state.backends.lock().update_udp(idx, None, Some(reason));
    if changed {
        state.runtime.backend_wakeup.notify_waiters();
    }
}

fn global_auth(state: &AppState) -> Option<(String, String)> {
    match (state.args.socks_user.clone(), state.args.socks_pass.clone()) { (Some(u), Some(p)) => Some((u, p)), _ => None }
}

async fn start_socks_session(
    state: AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    key: UdpSessionKey,
    idx: usize,
    backend: SocketAddr,
    auth: Option<(String, String)>,
) -> Result<UdpSessionHandle> {
    if state.args.wrapped_socks_addr()?.is_some() {
        return Err(anyhow::anyhow!("UDP ASSOCIATE through wrapped SOCKS is unsupported"));
    }
    let timeout = Duration::from_secs(state.args.connect_timeout as u64)
        .min(Duration::from_secs(5))
        .max(Duration::from_millis(800));

    // Bind first and include this real local endpoint in UDP ASSOCIATE. The old
    // 0.0.0.0:0 request could succeed at the control layer while sing-box had
    // no usable client endpoint for the UDP data plane.
    let udp = tokio::net::UdpSocket::bind(if backend.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" })
        .await
        .context("bind socks udp client")?;
    let client_udp_addr = udp.local_addr().context("read socks udp client address")?;
    let (control, relay) = socks5::udp_associate(backend, auth, timeout, client_udp_addr).await?;
    udp.connect(relay).await.context("connect socks udp relay")?;

    let (tx, rx) = mpsc::channel(UDP_SESSION_QUEUE);
    let last_activity_ms = Arc::new(AtomicU64::new(now_ms()));
    let handle = UdpSessionHandle { tx, last_activity_ms: last_activity_ms.clone() };

    tokio::spawn(async move {
        if let Err(e) = socks_session_loop(
            state.clone(),
            udp_sock,
            key,
            backend,
            control,
            udp,
            rx,
            last_activity_ms,
        )
        .await
        {
            mark_udp_backend_failure(&state, idx, format!("UDP data-plane failure: {:#}", e));
            tracing::debug!(
                "udp socks session ended peer={} dst={} backend={}: {:#}",
                key.peer,
                key.original_dst,
                backend,
                e
            );
        }
    });
    Ok(handle)
}

async fn socks_session_loop(
    state: AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    key: UdpSessionKey,
    backend: SocketAddr,
    _control: tokio::net::TcpStream,
    udp: tokio::net::UdpSocket,
    mut rx: mpsc::Receiver<Vec<u8>>,
    last_activity_ms: Arc<AtomicU64>,
) -> Result<()> {
    let mut buf = vec![0u8; UDP_RECV_BUF_SIZE];
    let mut idle_sleep = Box::pin(tokio::time::sleep(UDP_SESSION_IDLE));
    let mut response_deadline: Option<tokio::time::Instant> = None;
    let mut received_any = false;

    loop {
        let deadline_snapshot = response_deadline;
        let response_wait = async move {
            if let Some(deadline) = deadline_snapshot {
                tokio::time::sleep_until(deadline).await;
            } else {
                std::future::pending::<()>().await;
            }
        };
        tokio::pin!(response_wait);

        tokio::select! {
            maybe_data = rx.recv() => {
                let Some(data) = maybe_data else { break; };
                let enc = socks5::encode_udp_packet(socks5::TargetAddr::Ip(key.original_dst), &data)?;
                udp.send(&enc).await.context("send socks udp packet")?;
                state.stats.add_up(data.len() as u64);
                state.backends.lock().add_bytes(backend, data.len() as u64);
                touch_session(&last_activity_ms, &mut idle_sleep);

                // Do not refresh this deadline on retransmissions. A stream of
                // unanswered QUIC Initial packets must terminate instead of
                // keeping a dead association alive forever.
                if response_deadline.is_none() {
                    response_deadline = Some(
                        tokio::time::Instant::now()
                            + if received_any { UDP_RESPONSE_STALL_TIMEOUT } else { UDP_FIRST_RESPONSE_TIMEOUT }
                    );
                }
            }
            res = udp.recv(&mut buf) => {
                let n = res.context("recv socks udp response")?;
                let (src, payload) = socks5::decode_udp_packet(&buf[..n])?;
                let source = match src {
                    socks5::TargetAddr::Ip(sa) => sa,
                    socks5::TargetAddr::Domain(_, port) => SocketAddr::new(key.original_dst.ip(), port),
                };
                send_spoofed_udp(udp_sock.clone(), source, key.peer, payload).await?;
                state.stats.add_down(payload.len() as u64);
                state.backends.lock().add_bytes(backend, payload.len() as u64);
                received_any = true;
                response_deadline = None;
                touch_session(&last_activity_ms, &mut idle_sleep);
            }
            _ = &mut response_wait => {
                return Err(anyhow::anyhow!(
                    "no UDP response from SOCKS relay within {}s (received_any={})",
                    if received_any { UDP_RESPONSE_STALL_TIMEOUT.as_secs() } else { UDP_FIRST_RESPONSE_TIMEOUT.as_secs() },
                    received_any
                ));
            }
            _ = &mut idle_sleep => break,
        }
    }
    Ok(())
}

async fn start_direct_session(state: AppState, udp_sock: Arc<AsyncFd<AsyncUdpSocket>>, key: UdpSessionKey) -> Result<UdpSessionHandle> {
    let outbound = tokio::net::UdpSocket::bind(if key.original_dst.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }).await.context("bind direct udp outbound")?;
    let (tx, rx) = mpsc::channel(UDP_SESSION_QUEUE);
    let last_activity_ms = Arc::new(AtomicU64::new(now_ms()));
    let handle = UdpSessionHandle { tx, last_activity_ms: last_activity_ms.clone() };

    tokio::spawn(async move {
        if let Err(e) = direct_session_loop(state, udp_sock, key, outbound, rx, last_activity_ms).await {
            tracing::debug!("udp direct session ended peer={} dst={}: {:#}", key.peer, key.original_dst, e);
        }
    });
    Ok(handle)
}

async fn direct_session_loop(
    state: AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    key: UdpSessionKey,
    outbound: tokio::net::UdpSocket,
    mut rx: mpsc::Receiver<Vec<u8>>,
    last_activity_ms: Arc<AtomicU64>,
) -> Result<()> {
    let mut buf = vec![0u8; UDP_RECV_BUF_SIZE];
    let mut idle_sleep = Box::pin(tokio::time::sleep(UDP_SESSION_IDLE));
    let mut response_deadline: Option<tokio::time::Instant> = None;
    let mut received_any = false;

    loop {
        let deadline_snapshot = response_deadline;
        let response_wait = async move {
            if let Some(deadline) = deadline_snapshot {
                tokio::time::sleep_until(deadline).await;
            } else {
                std::future::pending::<()>().await;
            }
        };
        tokio::pin!(response_wait);

        tokio::select! {
            maybe_data = rx.recv() => {
                let Some(data) = maybe_data else { break; };
                outbound.send_to(&data, key.original_dst).await.context("send direct udp")?;
                state.stats.add_up(data.len() as u64);
                touch_session(&last_activity_ms, &mut idle_sleep);
                if response_deadline.is_none() {
                    response_deadline = Some(
                        tokio::time::Instant::now()
                            + if received_any { UDP_RESPONSE_STALL_TIMEOUT } else { UDP_FIRST_RESPONSE_TIMEOUT }
                    );
                }
            }
            res = outbound.recv_from(&mut buf) => {
                let (n, from) = res.context("recv direct udp response")?;
                let source = if from.port() == key.original_dst.port() { from } else { key.original_dst };
                send_spoofed_udp(udp_sock.clone(), source, key.peer, &buf[..n]).await?;
                state.stats.add_down(n as u64);
                received_any = true;
                response_deadline = None;
                touch_session(&last_activity_ms, &mut idle_sleep);
            }
            _ = &mut response_wait => {
                state.runtime.note_direct_failure(20);
                return Err(anyhow::anyhow!(
                    "no direct UDP response within {}s",
                    if received_any { UDP_RESPONSE_STALL_TIMEOUT.as_secs() } else { UDP_FIRST_RESPONSE_TIMEOUT.as_secs() }
                ));
            }
            _ = &mut idle_sleep => break,
        }
    }
    Ok(())
}

fn touch_session(last_activity_ms: &Arc<AtomicU64>, idle_sleep: &mut std::pin::Pin<Box<tokio::time::Sleep>>) {
    last_activity_ms.store(now_ms(), Ordering::Relaxed);
    idle_sleep.as_mut().reset(tokio::time::Instant::now() + UDP_SESSION_IDLE);
}

async fn send_spoofed_udp(_base: Arc<AsyncFd<AsyncUdpSocket>>, source: SocketAddr, peer: SocketAddr, data: &[u8]) -> Result<()> {
    let payload = data.to_vec();
    tokio::task::spawn_blocking(move || -> Result<()> {
        let socket = Socket::new(if source.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }, Type::DGRAM, Some(Protocol::UDP)).context("create spoof udp socket")?;
        socket.set_reuse_address(true).ok();
        set_transparent(socket.as_raw_fd(), source.is_ipv6())?;
        socket.bind(&source.into()).with_context(|| format!("bind spoof udp source {}", source))?;
        let std_sock: std::net::UdpSocket = socket.into();
        std_sock.send_to(&payload, peer).context("send spoofed udp response")?;
        Ok(())
    }).await.context("join spoof udp send")??;
    Ok(())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
