use anyhow::{Context, Result};
use serde_json::Value;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use tokio::net::{TcpListener, TcpStream};

const ZDTD_SETTING_JSON: &str = "/data/adb/modules/ZDT-D/setting/setting.json";

pub fn tproxy_enabled_from_settings() -> bool {
    let content = match std::fs::read_to_string(ZDTD_SETTING_JSON) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let v: Value = match serde_json::from_str(&content) {
        Ok(v) => v,
        Err(_) => return false,
    };
    v.get("tproxy_enabled").and_then(|x| x.as_bool()).unwrap_or(false)
}

#[cfg(any(target_os="linux", target_os="android"))]
pub async fn bind_tcp_listener(addr: SocketAddr, tproxy_enabled: bool) -> Result<TcpListener> {
    if !tproxy_enabled {
        return TcpListener::bind(addr).await.context("bind tcp listener");
    }
    use socket2::{Domain, Protocol, Socket, Type};
    use std::os::unix::io::AsRawFd;

    let bind_addr = match addr {
        SocketAddr::V4(v4) => {
            let ip = if v4.ip().is_loopback() { Ipv4Addr::UNSPECIFIED } else { *v4.ip() };
            SocketAddr::new(IpAddr::V4(ip), v4.port())
        }
        SocketAddr::V6(v6) => SocketAddr::new(IpAddr::V6(*v6.ip()), v6.port()),
    };
    let domain = if bind_addr.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).context("create transparent tcp socket")?;
    socket.set_reuse_address(true).ok();
    unsafe {
        let one: libc::c_int = 1;
        let (level, opt) = if bind_addr.is_ipv4() { (libc::SOL_IP, 19) } else { (libc::SOL_IPV6, 75) };
        let rc = libc::setsockopt(socket.as_raw_fd(), level, opt, &one as *const _ as *const libc::c_void, std::mem::size_of_val(&one) as libc::socklen_t);
        if rc != 0 { return Err(std::io::Error::last_os_error()).context("setsockopt IP_TRANSPARENT/IPV6_TRANSPARENT"); }
    }
    socket.bind(&bind_addr.into()).with_context(|| format!("bind transparent tcp listener on {}", bind_addr))?;
    socket.listen(1024).context("listen transparent tcp")?;
    socket.set_nonblocking(true).context("set transparent tcp nonblocking")?;
    let std_listener: std::net::TcpListener = socket.into();
    TcpListener::from_std(std_listener).context("tokio transparent tcp listener")
}

#[cfg(not(any(target_os="linux", target_os="android")))]
pub async fn bind_tcp_listener(addr: SocketAddr, tproxy_enabled: bool) -> Result<TcpListener> {
    if tproxy_enabled { return Err(anyhow::anyhow!("TPROXY transparent sockets are supported only on Linux/Android")); }
    TcpListener::bind(addr).await.context("bind tcp listener")
}

#[cfg(any(target_os="linux", target_os="android"))]
pub fn get_original_dst(stream: &TcpStream) -> Result<SocketAddr> {
    use std::mem::MaybeUninit;
    use std::os::unix::io::AsRawFd;
    const SO_ORIGINAL_DST: libc::c_int = 80;
    let fd = stream.as_raw_fd();
    unsafe {
        let mut addr: MaybeUninit<libc::sockaddr_in> = MaybeUninit::uninit();
        let mut len: libc::socklen_t = std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t;
        let rc = libc::getsockopt(fd, libc::SOL_IP, SO_ORIGINAL_DST, addr.as_mut_ptr() as *mut libc::c_void, &mut len as *mut libc::socklen_t);
        if rc == 0 && (len as usize) >= std::mem::size_of::<libc::sockaddr_in>() {
            let a = addr.assume_init();
            let ip = IpAddr::V4(Ipv4Addr::from(u32::from_be(a.sin_addr.s_addr)));
            let port = u16::from_be(a.sin_port);
            return Ok(SocketAddr::new(ip, port));
        }
    }
    Err(anyhow::anyhow!("SO_ORIGINAL_DST not available for this socket"))
}

#[cfg(not(any(target_os="linux", target_os="android")))]
pub fn get_original_dst(_stream: &TcpStream) -> Result<SocketAddr> {
    Err(anyhow::anyhow!("SO_ORIGINAL_DST is only supported on Linux in this port"))
}

pub fn get_transparent_dst(stream: &TcpStream, _listen_addr: &str, _listen_port: u16, tproxy_enabled: bool) -> Result<SocketAddr> {
    // Always try SO_ORIGINAL_DST first.  ZDT-D can feed the same t2s instance
    // from both true TPROXY rules and NAT REDIRECT/DNAT paths; hotspot/tethering
    // still uses PREROUTING REDIRECT even when the global tproxy_enabled setting
    // is enabled.  For REDIRECT traffic, SO_ORIGINAL_DST is the reliable real
    // destination, while local_addr() may only be the device/hotspot address and
    // the t2s listen port.
    match get_original_dst(stream) {
        Ok(dst) => return Ok(dst),
        Err(original_dst_err) => {
            if !tproxy_enabled {
                return Err(original_dst_err);
            }
            tracing::debug!("SO_ORIGINAL_DST unavailable in TPROXY mode, using accepted socket local_addr: {:#}", original_dst_err);
        }
    }

    // In true TPROXY delivery the accepted socket's local address is the
    // original destination.  This is only a fallback after SO_ORIGINAL_DST
    // failed, so NAT REDIRECT/DNAT hotspot traffic keeps working.
    stream.local_addr().context("read accepted socket local_addr for TPROXY")
}
