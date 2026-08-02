use crate::{cli::PriorityZeroMode, rules, socks5, stats, AppState};
use anyhow::{Context, Result};
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    collections::{HashMap, VecDeque},
    hash::{Hash, Hasher},
    net::{IpAddr, Ipv4Addr, SocketAddr},
    os::unix::io::{AsRawFd, RawFd},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{io::unix::AsyncFd, sync::Notify};

const IP_TRANSPARENT_OPT: libc::c_int = 19;
const IP_RECVORIGDSTADDR_OPT: libc::c_int = 20;
const IPV6_TRANSPARENT_OPT: libc::c_int = 75;

const UDP_SESSION_IDLE: Duration = Duration::from_secs(60);
const UDP_FIRST_RESPONSE_TIMEOUT: Duration = Duration::from_secs(6);
const UDP_RESPONSE_STALL_TIMEOUT: Duration = Duration::from_secs(15);
const UDP_BACKEND_WAIT: Duration = Duration::from_millis(3500);
const UDP_SESSION_MAX: usize = 4096;
const UDP_RECV_BUF_SIZE: usize = 65_535;
const UDP_SPOOF_CACHE_IDLE: Duration = Duration::from_secs(120);
const UDP_SPOOF_CACHE_CLEANUP: Duration = Duration::from_secs(30);
const UDP_SPOOF_CACHE_MAX: usize = 256;
const UDP_SMALL_PAYLOAD_CAPACITY: usize = 2_048;
const UDP_MEDIUM_PAYLOAD_CAPACITY: usize = 8_192;
const UDP_PAYLOAD_POOL_MAX_PER_CLASS: usize = 256;

static NEXT_UDP_SESSION_ID: AtomicU64 = AtomicU64::new(1);


#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum UdpPayloadClass {
    Small,
    Medium,
    Unpooled,
}

#[derive(Default)]
struct UdpPayloadPool {
    small: Mutex<Vec<Vec<u8>>>,
    medium: Mutex<Vec<Vec<u8>>>,
}

impl UdpPayloadPool {
    fn acquire(&self, len: usize) -> (Vec<u8>, UdpPayloadClass) {
        if len <= UDP_SMALL_PAYLOAD_CAPACITY {
            let buffer = self
                .small
                .lock()
                .pop()
                .unwrap_or_else(|| Vec::with_capacity(UDP_SMALL_PAYLOAD_CAPACITY));
            (buffer, UdpPayloadClass::Small)
        } else if len <= UDP_MEDIUM_PAYLOAD_CAPACITY {
            let buffer = self
                .medium
                .lock()
                .pop()
                .unwrap_or_else(|| Vec::with_capacity(UDP_MEDIUM_PAYLOAD_CAPACITY));
            (buffer, UdpPayloadClass::Medium)
        } else {
            (Vec::with_capacity(len), UdpPayloadClass::Unpooled)
        }
    }

    fn release(&self, mut buffer: Vec<u8>, class: UdpPayloadClass) {
        buffer.clear();
        let bucket = match class {
            UdpPayloadClass::Small => Some(&self.small),
            UdpPayloadClass::Medium => Some(&self.medium),
            UdpPayloadClass::Unpooled => None,
        };
        if let Some(bucket) = bucket {
            let mut bucket = bucket.lock();
            if bucket.len() < UDP_PAYLOAD_POOL_MAX_PER_CLASS {
                bucket.push(buffer);
            }
        }
    }
}

static UDP_PAYLOAD_POOL: Lazy<UdpPayloadPool> = Lazy::new(UdpPayloadPool::default);

#[derive(Debug)]
struct UdpPayload {
    buffer: Option<Vec<u8>>,
    class: UdpPayloadClass,
}

impl UdpPayload {
    fn copy_from_slice(data: &[u8]) -> Self {
        let (mut buffer, class) = UDP_PAYLOAD_POOL.acquire(data.len());
        buffer.clear();
        buffer.extend_from_slice(data);
        Self {
            buffer: Some(buffer),
            class,
        }
    }

    fn as_slice(&self) -> &[u8] {
        self.buffer.as_deref().unwrap_or(&[])
    }

    fn len(&self) -> usize {
        self.buffer.as_ref().map(Vec::len).unwrap_or(0)
    }
}

impl std::ops::Deref for UdpPayload {
    type Target = [u8];

    fn deref(&self) -> &Self::Target {
        self.as_slice()
    }
}

impl Drop for UdpPayload {
    fn drop(&mut self) {
        if let Some(buffer) = self.buffer.take() {
            UDP_PAYLOAD_POOL.release(buffer, self.class);
        }
    }
}

#[derive(Debug)]
struct UdpPacket {
    peer: SocketAddr,
    original_dst: SocketAddr,
    data: UdpPayload,
}

struct AsyncUdpSocket {
    inner: std::net::UdpSocket,
}

impl AsRawFd for AsyncUdpSocket {
    fn as_raw_fd(&self) -> RawFd {
        self.inner.as_raw_fd()
    }
}

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

struct UdpSessionQueue {
    pending: Mutex<VecDeque<UdpPayload>>,
    notify: Notify,
    closed: AtomicBool,
}

impl UdpSessionQueue {
    fn new() -> Self {
        Self {
            pending: Mutex::new(VecDeque::new()),
            notify: Notify::new(),
            closed: AtomicBool::new(false),
        }
    }

    fn send(&self, data: UdpPayload) -> std::result::Result<(), UdpPayload> {
        if self.closed.load(Ordering::Acquire) {
            return Err(data);
        }
        let should_notify = {
            let mut pending = self.pending.lock();
            if self.closed.load(Ordering::Acquire) {
                return Err(data);
            }
            let should_notify = pending.is_empty();
            pending.push_back(data);
            should_notify
        };
        if should_notify {
            self.notify.notify_one();
        }
        Ok(())
    }

    async fn recv(&self) -> Option<UdpPayload> {
        loop {
            let notified = self.notify.notified();
            {
                let mut pending = self.pending.lock();
                if let Some(data) = pending.pop_front() {
                    return Some(data);
                }
                if self.closed.load(Ordering::Acquire) {
                    return None;
                }
            }
            notified.await;
        }
    }

    fn seed(&self, initial: &mut VecDeque<UdpPayload>) {
        let mut pending = self.pending.lock();
        debug_assert!(pending.is_empty());
        pending.append(initial);
    }

    fn close(&self) {
        {
            let _pending = self.pending.lock();
            self.closed.store(true, Ordering::Release);
        }
        // There is exactly one consumer per session. notify_one() also stores
        // a permit when the consumer is between checking the queue and await.
        self.notify.notify_one();
    }
}

#[derive(Clone)]
struct UdpSessionHandle {
    id: u64,
    queue: Arc<UdpSessionQueue>,
}

impl UdpSessionHandle {
    fn send(&self, data: UdpPayload) -> std::result::Result<(), UdpPayload> {
        self.queue.send(data)
    }
}

enum UdpSessionEntry {
    Creating {
        id: u64,
        pending: VecDeque<UdpPayload>,
    },
    Active(UdpSessionHandle),
}

enum PreparedUdpSession {
    Socks {
        handle: UdpSessionHandle,
        state: AppState,
        key: UdpSessionKey,
        id: u64,
        backend_index: usize,
        backend: SocketAddr,
        control: tokio::net::TcpStream,
        udp: tokio::net::UdpSocket,
    },
    Direct {
        handle: UdpSessionHandle,
        state: AppState,
        key: UdpSessionKey,
        id: u64,
        outbound: tokio::net::UdpSocket,
    },
}

impl PreparedUdpSession {
    fn handle(&self) -> UdpSessionHandle {
        match self {
            Self::Socks { handle, .. } | Self::Direct { handle, .. } => handle.clone(),
        }
    }

    fn start(self, sessions: UdpSessions, spoof_cache: Arc<SpoofSocketCache>) {
        match self {
            Self::Socks {
                handle,
                state,
                key,
                id,
                backend_index,
                backend,
                control,
                udp,
            } => {
                let queue = handle.queue.clone();
                tokio::spawn(async move {
                    let task_guard =
                        UdpSessionTaskGuard::new(sessions, key, id, queue.clone());
                    let result = socks_session_loop(
                        state.clone(),
                        spoof_cache,
                        key,
                        backend,
                        control,
                        udp,
                        queue,
                    )
                    .await;
                    // Close the queue and remove the map entry before health/log
                    // handling, matching mpsc receiver-close semantics. Packets
                    // arriving after the loop exits will retry on a new session.
                    drop(task_guard);
                    if let Err(e) = result {
                        mark_udp_backend_failure(
                            &state,
                            backend_index,
                            format!("UDP data-plane failure: {:#}", e),
                        );
                        tracing::debug!(
                            "udp socks session ended peer={} dst={} backend={}: {:#}",
                            key.peer,
                            key.original_dst,
                            backend,
                            e
                        );
                    }
                });
            }
            Self::Direct {
                handle,
                state,
                key,
                id,
                outbound,
            } => {
                let queue = handle.queue.clone();
                tokio::spawn(async move {
                    let task_guard =
                        UdpSessionTaskGuard::new(sessions, key, id, queue.clone());
                    let result =
                        direct_session_loop(state, spoof_cache, key, outbound, queue).await;
                    drop(task_guard);
                    if let Err(e) = result {
                        tracing::debug!(
                            "udp direct session ended peer={} dst={}: {:#}",
                            key.peer,
                            key.original_dst,
                            e
                        );
                    }
                });
            }
        }
    }
}

type UdpSessions = Arc<Mutex<HashMap<UdpSessionKey, UdpSessionEntry>>>;

struct UdpSessionTaskGuard {
    sessions: UdpSessions,
    key: UdpSessionKey,
    id: u64,
    queue: Arc<UdpSessionQueue>,
}

impl UdpSessionTaskGuard {
    fn new(
        sessions: UdpSessions,
        key: UdpSessionKey,
        id: u64,
        queue: Arc<UdpSessionQueue>,
    ) -> Self {
        Self {
            sessions,
            key,
            id,
            queue,
        }
    }
}

impl Drop for UdpSessionTaskGuard {
    fn drop(&mut self) {
        self.queue.close();
        remove_active_session_if_current(&self.sessions, self.key, self.id);
    }
}

struct UdpCreationGuard {
    sessions: UdpSessions,
    key: UdpSessionKey,
    id: u64,
    armed: bool,
}

impl UdpCreationGuard {
    fn new(sessions: UdpSessions, key: UdpSessionKey, id: u64) -> Self {
        Self {
            sessions,
            key,
            id,
            armed: true,
        }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for UdpCreationGuard {
    fn drop(&mut self) {
        if self.armed {
            remove_session_if_current(&self.sessions, self.key, self.id);
        }
    }
}

struct SpoofSocketEntry {
    socket: Arc<tokio::net::UdpSocket>,
    last_used_ms: AtomicU64,
}

#[derive(Default)]
struct SpoofSocketCache {
    sockets: Mutex<HashMap<SocketAddr, Arc<SpoofSocketEntry>>>,
}

impl SpoofSocketCache {
    async fn send(&self, source: SocketAddr, peer: SocketAddr, data: &[u8]) -> Result<()> {
        for attempt in 0..2 {
            let entry = self.get_or_create(source)?;
            entry.last_used_ms.store(now_ms(), Ordering::Relaxed);
            match entry.socket.send_to(data, peer).await {
                Ok(_) => return Ok(()),
                Err(err) => {
                    self.remove_if_current(source, &entry);
                    if attempt == 1 {
                        return Err(err).context("send spoofed udp response");
                    }
                }
            }
        }
        unreachable!("spoof UDP send retry loop must return")
    }

    fn get_or_create(&self, source: SocketAddr) -> Result<Arc<SpoofSocketEntry>> {
        let mut sockets = self.sockets.lock();
        if let Some(entry) = sockets.get(&source) {
            return Ok(entry.clone());
        }
        if sockets.len() >= UDP_SPOOF_CACHE_MAX {
            let oldest_idle = sockets
                .iter()
                .filter(|(_, entry)| Arc::strong_count(entry) == 1)
                .min_by_key(|(_, entry)| entry.last_used_ms.load(Ordering::Relaxed))
                .map(|(source, _)| *source);
            if let Some(oldest) = oldest_idle {
                sockets.remove(&oldest);
            }
        }

        let socket = Arc::new(create_spoof_socket(source)?);
        let entry = Arc::new(SpoofSocketEntry {
            socket,
            last_used_ms: AtomicU64::new(now_ms()),
        });
        sockets.insert(source, entry.clone());
        Ok(entry)
    }

    fn remove_if_current(&self, source: SocketAddr, expected: &Arc<SpoofSocketEntry>) {
        let mut sockets = self.sockets.lock();
        if sockets
            .get(&source)
            .map(|entry| Arc::ptr_eq(entry, expected))
            .unwrap_or(false)
        {
            sockets.remove(&source);
        }
    }

    fn cleanup(&self) {
        let now = now_ms();
        let idle_ms = UDP_SPOOF_CACHE_IDLE.as_millis() as u64;
        self.sockets.lock().retain(|_, entry| {
            Arc::strong_count(entry) > 1
                || now.saturating_sub(entry.last_used_ms.load(Ordering::Relaxed)) <= idle_ms
        });
    }
}

pub async fn run_udp_tproxy(state: AppState) -> Result<()> {
    let addr: SocketAddr = format!("{}:{}", state.args.listen_addr, state.args.listen_port)
        .parse()
        .context("udp listen addr parse")?;
    let udp = Arc::new(bind_udp_tproxy(addr).context("bind udp tproxy")?);
    let sessions: UdpSessions = Arc::new(Mutex::new(HashMap::new()));
    let spoof_cache = Arc::new(SpoofSocketCache::default());

    tracing::info!(
        "UDP TPROXY session relay listening on 0.0.0.0:{}",
        addr.port()
    );

    let mut recv_buf = vec![0u8; UDP_RECV_BUF_SIZE];
    let mut control_buf = vec![0u8; 256];
    let mut cache_cleanup = tokio::time::interval(UDP_SPOOF_CACHE_CLEANUP);
    cache_cleanup.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    cache_cleanup.tick().await;

    loop {
        tokio::select! {
            packet = recv_udp_packet(&udp, &mut recv_buf, &mut control_buf) => {
                dispatch_udp_packet(&state, &sessions, &spoof_cache, packet?);
            }
            _ = cache_cleanup.tick() => spoof_cache.cleanup(),
        }
    }
}

fn set_transparent(fd: RawFd, ipv6: bool) -> Result<()> {
    unsafe {
        let one: libc::c_int = 1;
        let (level, opt) = if ipv6 {
            (libc::SOL_IPV6, IPV6_TRANSPARENT_OPT)
        } else {
            (libc::SOL_IP, IP_TRANSPARENT_OPT)
        };
        let rc = libc::setsockopt(
            fd,
            level,
            opt,
            &one as *const _ as *const libc::c_void,
            std::mem::size_of_val(&one) as libc::socklen_t,
        );
        if rc != 0 {
            return Err(std::io::Error::last_os_error()).context("setsockopt transparent");
        }
    }
    Ok(())
}

fn bind_udp_tproxy(addr: SocketAddr) -> Result<AsyncFd<AsyncUdpSocket>> {
    let bind_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), addr.port());
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .context("create udp socket")?;
    socket.set_reuse_address(true).ok();
    set_transparent(socket.as_raw_fd(), false)?;
    unsafe {
        let one: libc::c_int = 1;
        let rc = libc::setsockopt(
            socket.as_raw_fd(),
            libc::SOL_IP,
            IP_RECVORIGDSTADDR_OPT,
            &one as *const _ as *const libc::c_void,
            std::mem::size_of_val(&one) as libc::socklen_t,
        );
        if rc != 0 {
            return Err(std::io::Error::last_os_error())
                .context("setsockopt IP_RECVORIGDSTADDR");
        }
    }
    socket
        .bind(&bind_addr.into())
        .with_context(|| format!("bind udp transparent on {}", bind_addr))?;
    socket.set_nonblocking(true).context("set udp nonblocking")?;
    let inner: std::net::UdpSocket = socket.into();
    AsyncFd::new(AsyncUdpSocket { inner }).context("asyncfd udp socket")
}

fn create_spoof_socket(source: SocketAddr) -> Result<tokio::net::UdpSocket> {
    let socket = Socket::new(
        if source.is_ipv4() {
            Domain::IPV4
        } else {
            Domain::IPV6
        },
        Type::DGRAM,
        Some(Protocol::UDP),
    )
    .context("create spoof udp socket")?;
    socket.set_reuse_address(true).ok();
    set_transparent(socket.as_raw_fd(), source.is_ipv6())?;
    socket
        .bind(&source.into())
        .with_context(|| format!("bind spoof udp source {}", source))?;
    socket
        .set_nonblocking(true)
        .context("set spoof udp nonblocking")?;
    let std_sock: std::net::UdpSocket = socket.into();
    tokio::net::UdpSocket::from_std(std_sock).context("register spoof udp socket")
}

async fn recv_udp_packet(
    sock: &Arc<AsyncFd<AsyncUdpSocket>>,
    data: &mut [u8],
    control: &mut [u8],
) -> Result<UdpPacket> {
    loop {
        let mut guard = sock.readable().await.context("udp readable")?;
        match guard.try_io(|inner| {
            recv_udp_packet_once(inner.get_ref().inner.as_raw_fd(), data, control)
        }) {
            Ok(res) => return Ok(res?),
            Err(_would_block) => continue,
        }
    }
}

fn recv_udp_packet_once(
    fd: RawFd,
    data: &mut [u8],
    control: &mut [u8],
) -> std::io::Result<UdpPacket> {
    let mut peer_storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let mut iov = libc::iovec {
        iov_base: data.as_mut_ptr() as *mut libc::c_void,
        iov_len: data.len(),
    };
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_name = &mut peer_storage as *mut _ as *mut libc::c_void;
    msg.msg_namelen = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = control.len();
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(std::io::Error::last_os_error());
    }
    let peer = sockaddr_to_addr(&peer_storage).ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid udp peer")
    })?;
    let mut original_dst = None;
    unsafe {
        let mut cmsg = libc::CMSG_FIRSTHDR(&msg);
        while !cmsg.is_null() {
            if (*cmsg).cmsg_level == libc::SOL_IP
                && (*cmsg).cmsg_type == IP_RECVORIGDSTADDR_OPT
            {
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
    let original_dst = original_dst.ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::InvalidData, "missing UDP original dst")
    })?;
    Ok(UdpPacket {
        peer,
        original_dst,
        data: UdpPayload::copy_from_slice(&data[..n as usize]),
    })
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

fn dispatch_udp_packet(
    state: &AppState,
    sessions: &UdpSessions,
    spoof_cache: &Arc<SpoofSocketCache>,
    pkt: UdpPacket,
) {
    enum Dispatch {
        Active(UdpSessionHandle),
        Creating,
        Start(u64),
    }

    let key = UdpSessionKey {
        peer: pkt.peer,
        original_dst: pkt.original_dst,
    };
    let mut data = Some(pkt.data);

    loop {
        let action = {
            let mut guard = sessions.lock();
            match guard.get_mut(&key) {
                Some(UdpSessionEntry::Creating { pending, .. }) => {
                    pending.push_back(data.take().expect("UDP packet available"));
                    Dispatch::Creating
                }
                Some(UdpSessionEntry::Active(handle)) => Dispatch::Active(handle.clone()),
                None => {
                    let id = NEXT_UDP_SESSION_ID.fetch_add(1, Ordering::Relaxed);
                    let mut pending = VecDeque::new();
                    pending.push_back(data.take().expect("UDP packet available"));
                    guard.insert(key, UdpSessionEntry::Creating { id, pending });
                    Dispatch::Start(id)
                }
            }
        };

        match action {
            Dispatch::Creating => return,
            Dispatch::Start(id) => {
                let state = state.clone();
                let sessions = sessions.clone();
                let spoof_cache = spoof_cache.clone();
                tokio::spawn(async move {
                    finish_udp_session_creation(state, sessions, spoof_cache, key, id).await;
                });
                return;
            }
            Dispatch::Active(handle) => {
                let packet = data.take().expect("UDP packet available");
                match handle.send(packet) {
                    Ok(()) => return,
                    Err(returned) => {
                        data = Some(returned);
                        remove_session_if_current(sessions, key, handle.id);
                    }
                }
            }
        }
    }
}

async fn finish_udp_session_creation(
    state: AppState,
    sessions: UdpSessions,
    spoof_cache: Arc<SpoofSocketCache>,
    key: UdpSessionKey,
    id: u64,
) {
    let mut creation_guard = UdpCreationGuard::new(sessions.clone(), key, id);
    let created = create_udp_session(state.clone(), key, id).await;

    let prepared = match created {
        Ok(Some(prepared)) => prepared,
        Ok(None) => {
            let pending = take_creating_if_current(&sessions, key, id);
            creation_guard.disarm();
            retry_remaining_udp_packets(&state, &sessions, &spoof_cache, key, pending);
            return;
        }
        Err(e) => {
            tracing::debug!(
                "udp session creation failed peer={} dst={}: {:#}",
                key.peer,
                key.original_dst,
                e
            );
            let pending = take_creating_if_current(&sessions, key, id);
            creation_guard.disarm();
            retry_remaining_udp_packets(&state, &sessions, &spoof_cache, key, pending);
            return;
        }
    };
    let handle = prepared.handle();

    let mut guard = sessions.lock();
    let is_current = matches!(
        guard.get(&key),
        Some(UdpSessionEntry::Creating { id: current, .. }) if *current == id
    );
    if !is_current {
        handle.queue.close();
        return;
    }

    let active_count = guard
        .values()
        .filter(|entry| matches!(entry, UdpSessionEntry::Active(_)))
        .count();
    let mut pending = match guard.remove(&key) {
        Some(UdpSessionEntry::Creating {
            id: current,
            pending,
        }) if current == id => pending,
        other => {
            if let Some(other) = other {
                guard.insert(key, other);
            }
            handle.queue.close();
            return;
        }
    };

    if active_count >= UDP_SESSION_MAX {
        handle.queue.close();
        creation_guard.disarm();
        drop(guard);
        drop(prepared);
        tracing::warn!(
            "udp session limit reached ({}), dropping new session peer={} dst={}",
            UDP_SESSION_MAX,
            key.peer,
            key.original_dst
        );
        retry_remaining_udp_packets(
            &state,
            &sessions,
            &spoof_cache,
            key,
            Some(pending),
        );
        return;
    }

    // Move the whole initial burst into the session queue in O(1) while the
    // session-map lock still guarantees packet ordering. The consumer starts
    // only after activation, so no wakeup is needed here.
    handle.queue.seed(&mut pending);
    guard.insert(key, UdpSessionEntry::Active(handle));
    creation_guard.disarm();
    drop(guard);

    prepared.start(sessions, spoof_cache);
}

fn take_creating_if_current(
    sessions: &UdpSessions,
    key: UdpSessionKey,
    id: u64,
) -> Option<VecDeque<UdpPayload>> {
    let mut guard = sessions.lock();
    let is_current = matches!(
        guard.get(&key),
        Some(UdpSessionEntry::Creating { id: current, .. }) if *current == id
    );
    if !is_current {
        return None;
    }
    match guard.remove(&key) {
        Some(UdpSessionEntry::Creating { pending, .. }) => Some(pending),
        _ => None,
    }
}

fn retry_remaining_udp_packets(
    state: &AppState,
    sessions: &UdpSessions,
    spoof_cache: &Arc<SpoofSocketCache>,
    key: UdpSessionKey,
    pending: Option<VecDeque<UdpPayload>>,
) {
    let Some(mut pending) = pending else {
        return;
    };

    // The first queued packet owned the failed creation attempt. In the old
    // per-packet implementation that packet returned with an error, while each
    // later packet retried after the per-key creation lock was released.
    pending.pop_front();
    for data in pending {
        dispatch_udp_packet(
            state,
            sessions,
            spoof_cache,
            UdpPacket {
                peer: key.peer,
                original_dst: key.original_dst,
                data,
            },
        );
    }
}

fn remove_active_session_if_current(sessions: &UdpSessions, key: UdpSessionKey, id: u64) {
    let mut guard = sessions.lock();
    let is_current = matches!(
        guard.get(&key),
        Some(UdpSessionEntry::Active(handle)) if handle.id == id
    );
    if is_current {
        guard.remove(&key);
    }
}

fn remove_session_if_current(sessions: &UdpSessions, key: UdpSessionKey, id: u64) {
    let mut guard = sessions.lock();
    let is_current = match guard.get(&key) {
        Some(UdpSessionEntry::Creating { id: current, .. }) => *current == id,
        Some(UdpSessionEntry::Active(handle)) => handle.id == id,
        None => false,
    };
    if is_current {
        guard.remove(&key);
    }
}

async fn create_udp_session(
    state: AppState,
    key: UdpSessionKey,
    id: u64,
) -> Result<Option<PreparedUdpSession>> {
    let target = stats::Target::SockAddr(key.original_dst);
    let (target_host, target_port) = target.to_host_port_string();
    let proto = rules::classify_protocol(target_port);
    let mut udp_socks_available = state.backends.lock().udp_available();
    let action = state
        .rules
        .decide(&proto, &target_host, target_port, udp_socks_available, true);

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
            return prepare_direct_session(state, key, id).await.map(Some);
        }
        Some(rules::Action::Socks) | None => {}
    }

    let priority_zero_mode = state.args.priority_zero_mode();
    if priority_zero_mode == PriorityZeroMode::DirectOnly {
        return prepare_direct_session(state, key, id).await.map(Some);
    }
    if priority_zero_mode == PriorityZeroMode::DirectFirst {
        return prepare_direct_session(state, key, id).await.map(Some);
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
            let Some((idx, backend, auth)) = selected else {
                break;
            };
            match prepare_socks_session(
                state.clone(),
                key,
                id,
                idx,
                backend,
                auth,
            )
            .await
            {
                Ok(handle) => return Ok(Some(handle)),
                Err(e) => {
                    mark_udp_backend_failure(
                        &state,
                        idx,
                        format!("UDP session setup failed: {:#}", e),
                    );
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

    if matches!(
        action,
        Some(rules::Action::Socks) | Some(rules::Action::Wait)
    ) || priority_zero_mode == PriorityZeroMode::BlockDirectFallback
    {
        state.stats.inc_policy_drop();
        return Ok(None);
    }

    // Preserve the existing default fallback policy, but only after the UDP
    // backend check has completed. This removes the startup race where the
    // first QUIC flow was permanently assigned to DIRECT while SOCKS health
    // was still unknown.
    prepare_direct_session(state, key, id).await.map(Some)
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
    match (
        state.args.socks_user.clone(),
        state.args.socks_pass.clone(),
    ) {
        (Some(u), Some(p)) => Some((u, p)),
        _ => None,
    }
}

fn new_session_handle(id: u64) -> UdpSessionHandle {
    UdpSessionHandle {
        id,
        queue: Arc::new(UdpSessionQueue::new()),
    }
}

async fn prepare_socks_session(
    state: AppState,
    key: UdpSessionKey,
    id: u64,
    idx: usize,
    backend: SocketAddr,
    auth: Option<(String, String)>,
) -> Result<PreparedUdpSession> {
    if state.wrapped_socks_addr.is_some() {
        return Err(anyhow::anyhow!(
            "UDP ASSOCIATE through wrapped SOCKS is unsupported"
        ));
    }
    let timeout = Duration::from_secs(state.args.connect_timeout as u64)
        .min(Duration::from_secs(5))
        .max(Duration::from_millis(800));

    // Bind first and include this real local endpoint in UDP ASSOCIATE. The old
    // 0.0.0.0:0 request could succeed at the control layer while sing-box had
    // no usable client endpoint for the UDP data plane.
    let udp = tokio::net::UdpSocket::bind(if backend.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    })
    .await
    .context("bind socks udp client")?;
    let client_udp_addr = udp
        .local_addr()
        .context("read socks udp client address")?;
    let (control, relay) =
        socks5::udp_associate(backend, auth, timeout, client_udp_addr).await?;
    udp.connect(relay).await.context("connect socks udp relay")?;

    Ok(PreparedUdpSession::Socks {
        handle: new_session_handle(id),
        state,
        key,
        id,
        backend_index: idx,
        backend,
        control,
        udp,
    })
}

async fn socks_session_loop(
    state: AppState,
    spoof_cache: Arc<SpoofSocketCache>,
    key: UdpSessionKey,
    backend: SocketAddr,
    _control: tokio::net::TcpStream,
    udp: tokio::net::UdpSocket,
    queue: Arc<UdpSessionQueue>,
) -> Result<()> {
    let mut buf = vec![0u8; UDP_RECV_BUF_SIZE];
    let mut encoded = Vec::with_capacity(UDP_SMALL_PAYLOAD_CAPACITY + 22);
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
            maybe_data = queue.recv() => {
                let Some(data) = maybe_data else { break; };
                socks5::encode_udp_packet_into(
                    &mut encoded,
                    socks5::TargetAddr::Ip(key.original_dst),
                    data.as_slice(),
                )?;
                udp.send(&encoded).await.context("send socks udp packet")?;
                state.stats.add_up(data.len() as u64);
                state.backends.lock().add_bytes(backend, data.len() as u64);
                touch_session(&mut idle_sleep);

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
                send_spoofed_udp(&spoof_cache, source, key.peer, payload).await?;
                state.stats.add_down(payload.len() as u64);
                state.backends.lock().add_bytes(backend, payload.len() as u64);
                received_any = true;
                response_deadline = None;
                touch_session(&mut idle_sleep);
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

async fn prepare_direct_session(
    state: AppState,
    key: UdpSessionKey,
    id: u64,
) -> Result<PreparedUdpSession> {
    let outbound = tokio::net::UdpSocket::bind(if key.original_dst.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    })
    .await
    .context("bind direct udp outbound")?;
    Ok(PreparedUdpSession::Direct {
        handle: new_session_handle(id),
        state,
        key,
        id,
        outbound,
    })
}

async fn direct_session_loop(
    state: AppState,
    spoof_cache: Arc<SpoofSocketCache>,
    key: UdpSessionKey,
    outbound: tokio::net::UdpSocket,
    queue: Arc<UdpSessionQueue>,
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
            maybe_data = queue.recv() => {
                let Some(data) = maybe_data else { break; };
                outbound.send_to(data.as_slice(), key.original_dst).await.context("send direct udp")?;
                state.stats.add_up(data.len() as u64);
                touch_session(&mut idle_sleep);
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
                send_spoofed_udp(&spoof_cache, source, key.peer, &buf[..n]).await?;
                state.stats.add_down(n as u64);
                received_any = true;
                response_deadline = None;
                touch_session(&mut idle_sleep);
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

fn touch_session(idle_sleep: &mut std::pin::Pin<Box<tokio::time::Sleep>>) {
    idle_sleep
        .as_mut()
        .reset(tokio::time::Instant::now() + UDP_SESSION_IDLE);
}

async fn send_spoofed_udp(
    cache: &SpoofSocketCache,
    source: SocketAddr,
    peer: SocketAddr,
    data: &[u8],
) -> Result<()> {
    cache.send(source, peer, data).await
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
