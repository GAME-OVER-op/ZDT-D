use crate::{rules, socks5, stats, AppState};
use anyhow::{Context, Result};
use socket2::{Domain, Protocol, Socket, Type};
use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    os::unix::io::{AsRawFd, RawFd},
    sync::Arc,
    time::Duration,
};
use tokio::io::unix::AsyncFd;

const IP_TRANSPARENT_OPT: libc::c_int = 19;
const IP_RECVORIGDSTADDR_OPT: libc::c_int = 20;
const IPV6_TRANSPARENT_OPT: libc::c_int = 75;

#[derive(Debug)]
struct UdpPacket {
    peer: SocketAddr,
    original_dst: SocketAddr,
    data: Vec<u8>,
}

struct AsyncUdpSocket {
    inner: std::net::UdpSocket,
}

impl AsRawFd for AsyncUdpSocket {
    fn as_raw_fd(&self) -> RawFd { self.inner.as_raw_fd() }
}

pub async fn run_udp_tproxy(state: AppState) -> Result<()> {
    let addr: SocketAddr = format!("{}:{}", state.args.listen_addr, state.args.listen_port)
        .parse()
        .context("udp listen addr parse")?;
    let udp = Arc::new(bind_udp_tproxy(addr).context("bind udp tproxy")?);
    tracing::info!("UDP TPROXY listening on 0.0.0.0:{}", addr.port());

    loop {
        let pkt = recv_udp_packet(udp.clone()).await?;
        let st = state.clone();
        let udp_send = udp.clone();
        tokio::spawn(async move {
            if let Err(e) = handle_udp_packet(st, udp_send, pkt).await {
                tracing::debug!("udp packet handling failed: {:#}", e);
            }
        });
    }
}

fn set_transparent(fd: RawFd, ipv6: bool) -> Result<()> {
    unsafe {
        let one: libc::c_int = 1;
        let (level, opt) = if ipv6 { (libc::SOL_IPV6, IPV6_TRANSPARENT_OPT) } else { (libc::SOL_IP, IP_TRANSPARENT_OPT) };
        let rc = libc::setsockopt(
            fd,
            level,
            opt,
            &one as *const _ as *const libc::c_void,
            std::mem::size_of_val(&one) as libc::socklen_t,
        );
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
        let rc = libc::setsockopt(
            socket.as_raw_fd(),
            libc::SOL_IP,
            IP_RECVORIGDSTADDR_OPT,
            &one as *const _ as *const libc::c_void,
            std::mem::size_of_val(&one) as libc::socklen_t,
        );
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
            Ok(res) => return Ok(res),
            Err(_would_block) => continue,
        }
    }
}

fn recv_udp_packet_once(fd: RawFd) -> std::io::Result<UdpPacket> {
    let mut data = vec![0u8; 65535];
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

async fn handle_udp_packet(state: AppState, udp_sock: Arc<AsyncFd<AsyncUdpSocket>>, pkt: UdpPacket) -> Result<()> {
    let target = stats::Target::SockAddr(pkt.original_dst);
    let (target_host, target_port) = target.to_host_port_string();
    let proto = rules::classify_protocol(target_port);
    let udp_socks_available = state.backends.lock().udp_available();
    let action = state.rules.decide(&proto, &target_host, target_port, udp_socks_available, true);

    match action {
        Some(rules::Action::Drop) | Some(rules::Action::Reset) | Some(rules::Action::Wait) => {
            state.stats.inc_policy_drop();
            return Ok(());
        }
        Some(rules::Action::Direct) => return udp_direct(udp_sock, pkt).await,
        Some(rules::Action::Socks) | None => {}
    }

    if let Some((idx, backend, auth)) = {
        let mut b = state.backends.lock();
        b.select_udp_with_auth(global_auth(&state), true)
    } {
        match udp_socks_once(&state, udp_sock.clone(), &pkt, idx, backend, auth).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                tracing::debug!("udp via socks backend {} failed, falling back direct: {:#}", backend, e);
            }
        }
    }

    udp_direct(udp_sock, pkt).await
}

fn global_auth(state: &AppState) -> Option<(String, String)> {
    match (state.args.socks_user.clone(), state.args.socks_pass.clone()) {
        (Some(u), Some(p)) => Some((u, p)),
        _ => None,
    }
}

async fn udp_socks_once(
    state: &AppState,
    udp_sock: Arc<AsyncFd<AsyncUdpSocket>>,
    pkt: &UdpPacket,
    _idx: usize,
    backend: SocketAddr,
    auth: Option<(String, String)>,
) -> Result<()> {
    if state.args.wrapped_socks_addr()?.is_some() {
        return Err(anyhow::anyhow!("UDP ASSOCIATE through wrapped SOCKS is unsupported"));
    }
    let timeout = Duration::from_secs(state.args.connect_timeout as u64).min(Duration::from_secs(5)).max(Duration::from_millis(800));
    let (_control, relay) = socks5::udp_associate(backend, auth, timeout).await?;
    let udp = tokio::net::UdpSocket::bind(if relay.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" }).await.context("bind socks udp client")?;
    let target = socks5::TargetAddr::Ip(pkt.original_dst);
    let enc = socks5::encode_udp_packet(target, &pkt.data)?;
    udp.send_to(&enc, relay).await.context("send socks udp packet")?;
    let mut buf = vec![0u8; 65535];
    let (n, _from) = tokio::time::timeout(Duration::from_secs(3), udp.recv_from(&mut buf))
        .await
        .context("socks udp response timeout")?
        .context("recv socks udp response")?;
    let (_src, payload) = socks5::decode_udp_packet(&buf[..n])?;
    send_spoofed_udp(udp_sock, pkt.original_dst, pkt.peer, payload).await?;
    state.stats.add_up(pkt.data.len() as u64);
    state.stats.add_down(payload.len() as u64);
    state.backends.lock().add_bytes(backend, (pkt.data.len() + payload.len()) as u64);
    Ok(())
}

async fn udp_direct(udp_sock: Arc<AsyncFd<AsyncUdpSocket>>, pkt: UdpPacket) -> Result<()> {
    let outbound = tokio::net::UdpSocket::bind(if pkt.original_dst.is_ipv4() { "0.0.0.0:0" } else { "[::]:0" })
        .await
        .context("bind direct udp outbound")?;
    outbound.send_to(&pkt.data, pkt.original_dst).await.context("send direct udp")?;
    let mut buf = vec![0u8; 65535];
    let (n, from) = tokio::time::timeout(Duration::from_secs(3), outbound.recv_from(&mut buf))
        .await
        .context("direct udp response timeout")?
        .context("recv direct udp response")?;
    let source = if from.port() == pkt.original_dst.port() { from } else { pkt.original_dst };
    send_spoofed_udp(udp_sock, source, pkt.peer, &buf[..n]).await
}

async fn send_spoofed_udp(_base: Arc<AsyncFd<AsyncUdpSocket>>, source: SocketAddr, peer: SocketAddr, data: &[u8]) -> Result<()> {
    let payload = data.to_vec();
    tokio::task::spawn_blocking(move || -> Result<()> {
        let socket = Socket::new(if source.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 }, Type::DGRAM, Some(Protocol::UDP))
            .context("create spoof udp socket")?;
        socket.set_reuse_address(true).ok();
        set_transparent(socket.as_raw_fd(), source.is_ipv6())?;
        socket.bind(&source.into()).with_context(|| format!("bind spoof udp source {}", source))?;
        let std_sock: std::net::UdpSocket = socket.into();
        std_sock.send_to(&payload, peer).context("send spoofed udp response")?;
        Ok(())
    }).await.context("join spoof udp send")??;
    Ok(())
}
