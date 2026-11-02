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
/// pair. Prefer exact process-name candidates verified through `/proc`, then
/// retain the historical `ps -ef` substring rule as a compatibility fallback.
pub fn tun2socks_process_running(binary: &str, tun: &str, proxy: &str) -> bool {
    let process_name = Path::new(binary)
        .file_name()
        .and_then(|v| v.to_str())
        .unwrap_or("tun2socks");
    let expected_device = format!("tun://{tun}");

    let mut pids = crate::common::process::pidof(process_name);
    crate::common::process::filter_live_pids(&mut pids);
    if pids.into_iter().any(|pid| {
        let cmdline = crate::common::process::read_process_cmdline(pid);
        let args: Vec<&str> = cmdline.split_whitespace().collect();
        let device = args.windows(2).find_map(|pair| (pair[0] == "--device").then_some(pair[1]));
        let actual_proxy = args.windows(2).find_map(|pair| (pair[0] == "--proxy").then_some(pair[1]));
        device == Some(expected_device.as_str()) && actual_proxy == Some(proxy)
    }) {
        return true;
    }

    let pattern = format!("{binary} --device {expected_device} --proxy {proxy}");
    crate::common::process::any_live_ps_ef_where(|line| line.contains(&pattern))
}
