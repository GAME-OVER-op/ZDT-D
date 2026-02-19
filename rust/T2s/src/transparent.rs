use anyhow::Result;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use tokio::net::TcpStream;

#[cfg(any(target_os="linux", target_os="android"))]
pub fn get_original_dst(stream: &TcpStream) -> Result<SocketAddr> {
    use std::mem::MaybeUninit;
    use std::os::unix::io::AsRawFd;

    const SO_ORIGINAL_DST: libc::c_int = 80;

    let fd = stream.as_raw_fd();

    // Try IPv4
    unsafe {
        let mut addr: MaybeUninit<libc::sockaddr_in> = MaybeUninit::uninit();
        let mut len: libc::socklen_t = std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t;
        let rc = libc::getsockopt(
            fd,
            libc::SOL_IP,
            SO_ORIGINAL_DST,
            addr.as_mut_ptr() as *mut libc::c_void,
            &mut len as *mut libc::socklen_t,
        );
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
