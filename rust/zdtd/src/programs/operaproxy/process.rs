
use anyhow::{Context, Result};

use log::{info};
use std::{fs::{OpenOptions}, io::{Read, Write}, net::{TcpStream}, os::unix::process::CommandExt, path::{Path}, process::{Command, Stdio}, time::{Duration, Instant}};

use crate::{common::process::shell::{self, Capture}};

use super::model::*;

pub(super) fn spawn_byedpi(bin: &Path, port: u16, extra_args: &[String], log_path: &Path) -> Result<u32> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let mut cmd = Command::new(bin);
    cmd.arg("-i")
        .arg("127.0.0.1")
        .arg("-p")
        .arg(port.to_string())
        .arg("-x")
        .arg("0")
        .args(extra_args)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    info!("spawned operaproxy-byedpi pid={} port={} log={}", pid, port, log_path.display());
    Ok(pid)
}

pub(super) fn spawn_opera_proxy(
    bin: &Path,
    bind_port: u16,
    fake_sni: &str,
    upstream_proxy: Option<&str>,
    override_proxy_address: Option<&str>,
    country: &str,
    bootstrap_dns: &str,
    selected_api_proxy: Option<&str>,
    opera_args: &OperaArgs,
    log_path: &Path,
) -> Result<()> {
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)
        .with_context(|| format!("open log {}", log_path.display()))?;
    let logf_err = logf.try_clone().with_context(|| "clone log file")?;

    let bind = format!("127.0.0.1:{}", bind_port);

    let mut cmd = Command::new(bin);
    cmd.arg("-bind-address")
        .arg(bind)
        .arg("-socks-mode")
        .arg("-cafile")
        .arg(OPERA_CAFILE)
        .arg("-fake-SNI")
        .arg(fake_sni)
        .arg("-bootstrap-dns")
        .arg(bootstrap_dns)
        .arg("-api-user-agent")
        .arg(&opera_args.api_user_agent)
        .arg("-country")
        .arg(country)
        .arg("-init-retry-interval")
        .arg(&opera_args.init_retry_interval)
        .arg("-server-selection")
        .arg(&opera_args.server_selection)
        .arg("-server-selection-dl-limit")
        .arg(opera_args.server_selection_dl_limit.to_string())
        .arg("-verbosity")
        .arg(opera_args.verbosity.to_string());
    if let Some(api_proxy) = selected_api_proxy.map(str::trim).filter(|s| !s.is_empty()) {
        cmd.arg("-api-proxy").arg(api_proxy);
    }
    cmd.arg("-server-selection-test-url")
        .arg(&opera_args.server_selection_test_url);
    if let Some(upstream) = upstream_proxy.map(str::trim).filter(|s| !s.is_empty()) {
        cmd.arg("-proxy").arg(upstream);
    }
    if let Some(override_proxy_address) = override_proxy_address {
        cmd.arg("-override-proxy-address").arg(override_proxy_address);
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));

    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    info!(
        "spawned opera-proxy pid={} bind_port={} sni='{}' log={}",
        child.id(),
        bind_port,
        fake_sni,
        log_path.display()
    );
    Ok(())
}

pub(super) fn wait_for_socks(
    min_ok: usize,
    ports: &[u16],
    max_wait: Duration,
    interval: Duration,
    check_timeout: Duration,
    log_path: &Path,
) -> Result<bool> {
    let start = Instant::now();
    let mut f = OpenOptions::new()
        .create(true)
        .write(true)
        .append(true)
        .open(log_path)
        .with_context(|| format!("open {}", log_path.display()))?;
    writeln!(
        f,
        "Starting SOCKS check: ports={:?} min_ok={} max_wait={:?} interval={:?} check_timeout={:?}",
        ports,
        min_ok,
        max_wait,
        interval,
        check_timeout
    )
    .ok();

    while start.elapsed() < max_wait {
        let mut working = 0usize;
        // Run checks in parallel so that total check time is closer to the per-check timeout,
        // not multiplied by number of ports.
        let host: &'static str = "127.0.0.1";
        let mut handles = Vec::with_capacity(ports.len());
        for p in ports {
            let port = *p;
            handles.push(std::thread::spawn(move || {
                let (ok, err) = check_socks5_health(host, port, check_timeout);
                (port, ok, err)
            }));
        }

        for h in handles {
            match h.join() {
                Ok((port, ok, err)) => {
                    if ok {
                        working += 1;
                    }
                    writeln!(
                        f,
                        "  {}: {}{}",
                        port,
                        if ok { "OK" } else { "FAIL" },
                        err.as_deref()
                            .map(|e| format!(" ({})", e))
                            .unwrap_or_default()
                    )
                    .ok();
                }
                Err(_) => {
                    writeln!(f, "  <thread>: FAIL (panic)").ok();
                }
            }
        }
        writeln!(f, "CHECK RESULT: working={} total={} min_ok={}", working, ports.len(), min_ok).ok();
        f.flush().ok();

        if working >= min_ok {
            writeln!(f, "Reached required working servers: {}", working).ok();
            f.flush().ok();
            return Ok(true);
        }

        std::thread::sleep(interval);
    }
    writeln!(f, "Timeout waiting for required working servers").ok();
    Ok(false)
}

pub(super) fn check_socks5_health(host: &str, port: u16, timeout: Duration) -> (bool, Option<String>) {
    // Minimal SOCKS5 handshake: NO AUTH, CONNECT 8.8.8.8:53 (IPv4)
    let addr = format!("{}:{}", host, port);
    let mut s = match TcpStream::connect_timeout(&addr.parse().unwrap(), timeout) {
        Ok(x) => x,
        Err(e) => return (false, Some(format!("connect: {}", e))),
    };
    let _ = s.set_read_timeout(Some(timeout));
    let _ = s.set_write_timeout(Some(timeout));

    // METHODS: VER=5, NMETHODS=1, METHOD=0x00
    if let Err(e) = s.write_all(&[0x05, 0x01, 0x00]) {
        return (false, Some(format!("write methods: {}", e)));
    }
    let mut resp = [0u8; 2];
    if let Err(e) = s.read_exact(&mut resp) {
        return (false, Some(format!("read methods: {}", e)));
    }
    if resp[0] != 0x05 || resp[1] == 0xFF {
        return (false, Some(format!("bad method reply: {:?}", resp)));
    }

    // CONNECT request: 8.8.8.8:53
    let req = [
        0x05, 0x01, 0x00, 0x01, // VER, CMD=CONNECT, RSV, ATYP=IPv4
        8, 8, 8, 8, // IP
        0, 53, // PORT big-endian
    ];
    if let Err(e) = s.write_all(&req) {
        return (false, Some(format!("write connect: {}", e)));
    }

    // Reply: VER, REP, RSV, ATYP
    let mut head = [0u8; 4];
    if let Err(e) = s.read_exact(&mut head) {
        return (false, Some(format!("read reply head: {}", e)));
    }
    if head[0] != 0x05 {
        return (false, Some(format!("bad reply ver: {}", head[0])));
    }
    if head[1] != 0x00 {
        return (false, Some(format!("rep=0x{:02x}", head[1])));
    }

    // Skip BND.ADDR depending on ATYP, then BND.PORT(2)
    match head[3] {
        0x01 => {
            let mut buf = [0u8; 4];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short ipv4".into()));
            }
        }
        0x03 => {
            let mut ln = [0u8; 1];
            if s.read_exact(&mut ln).is_err() {
                return (false, Some("short domain len".into()));
            }
            let mut buf = vec![0u8; ln[0] as usize];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short domain".into()));
            }
        }
        0x04 => {
            let mut buf = [0u8; 16];
            if s.read_exact(&mut buf).is_err() {
                return (false, Some("short ipv6".into()));
            }
        }
        x => return (false, Some(format!("unknown atyp {}", x))),
    }
    let mut portbuf = [0u8; 2];
    if s.read_exact(&mut portbuf).is_err() {
        return (false, Some("short port".into()));
    }

    (true, None)
}

pub(super) fn find_pid_by_listen_port(port: u16, log_path: &Path) -> Result<Option<String>> {
    let out = shell::run("ss", &["-ltnp"], Capture::Stdout).unwrap_or_default();

    let needle = format!(":{}", port);
    // shell::run returns (exit_code, stdout). Parse only stdout.
    let pid_opt = out
        .1
        .lines()
        .find(|l| l.contains(&needle))
        .and_then(extract_pid);

    let mut f = OpenOptions::new().create(true).append(true).open(log_path)?;
    if let Some(ref pid) = pid_opt {
        writeln!(f, "Found listener for port {} pid={}", port, pid)?;
    } else {
        writeln!(f, "No listener for port {}", port)?;
    }

    Ok(pid_opt)
}


pub(super) fn extract_pid(line: &str) -> Option<String> {
    // ss output often: users:(("proc",pid=1234,fd=...))
    let idx = line.find("pid=")?;
    let tail = &line[idx + 4..];
    let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

pub(super) fn sanitize_for_filename(s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else {
            out.push('_');
        }
    }
    // limit length
    if out.len() > 64 {
        out.truncate(64);
    }
    out
}


