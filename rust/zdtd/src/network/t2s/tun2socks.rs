use std::{path::Path, process::Command};

/// Build the shared tun2socks command line used by VPN-capable proxy backends.
///
/// The caller keeps ownership of process liveness checks, log policy, error
/// contexts and post-spawn readiness. Only the identical argv/cwd construction
/// lives here.
pub fn build_tun2socks_command(
    bin: &Path,
    tun: &str,
    proxy: &str,
    loglevel: &str,
    mtu: Option<u16>,
    work_dir: &Path,
) -> Command {
    let mut cmd = Command::new(bin);
    cmd.arg("--device")
        .arg(format!("tun://{tun}"))
        .arg("--proxy")
        .arg(proxy)
        .arg("--loglevel")
        .arg(loglevel);

    if let Some(mtu) = mtu {
        cmd.arg("--mtu").arg(mtu.to_string());
    }

    cmd.current_dir(work_dir);
    cmd
}

/// Check whether a live tun2socks process already owns the exact tun/proxy
/// pair. This preserves the historical `ps -ef` substring rule used by the
/// sing-box family, Mihomo and Mieru.
pub fn tun2socks_process_running(binary: &str, tun: &str, proxy: &str) -> bool {
    let pattern = format!("{binary} --device tun://{tun} --proxy {proxy}");
    crate::common::process::any_live_ps_ef_where(|line| line.contains(&pattern))
}
