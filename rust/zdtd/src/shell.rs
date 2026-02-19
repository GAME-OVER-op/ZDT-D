use anyhow::{anyhow, Result};
use log::{debug, info};
use std::{
    fs,
    io::Read,
    path::PathBuf,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

#[derive(Clone, Copy)]
pub enum Capture { Stdout, Stderr, Both, None }

fn preferred_tmp_dir() -> PathBuf {
    let p = PathBuf::from("/data/local/tmp");
    if p.is_dir() {
        return p;
    }
    std::env::temp_dir()
}

fn unique_tmp_prefix(cmd: &str) -> String {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_nanos();
    let pid = std::process::id();
    let safe = cmd.replace('/', "_");
    format!("zdtd_{safe}_{pid}_{ts}")
}

/// Run a command with a timeout.
///
/// Notes:
/// - Uses temp files for stdout/stderr capture to avoid deadlocks with large outputs.
/// - On timeout, kills the process and returns an error.
pub fn run_timeout(cmd: &str, args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    info!("exec(timeout={:?}): {} {}", timeout, cmd, args.join(" "));

    let tmp_dir = preferred_tmp_dir();
    let prefix = unique_tmp_prefix(cmd);

    let mut stdout_path: Option<PathBuf> = None;
    let mut stderr_path: Option<PathBuf> = None;

    let mut c = Command::new(cmd);
    c.args(args);

    match capture {
        Capture::Stdout => {
            let p = tmp_dir.join(format!("{prefix}.out"));
            let f = fs::File::create(&p)
                .map_err(|e| anyhow!("failed to create stdout temp file {p:?}: {e}"))?;
            c.stdout(Stdio::from(f));
            c.stderr(Stdio::null());
            stdout_path = Some(p);
        }
        Capture::Stderr => {
            let p = tmp_dir.join(format!("{prefix}.err"));
            let f = fs::File::create(&p)
                .map_err(|e| anyhow!("failed to create stderr temp file {p:?}: {e}"))?;
            c.stdout(Stdio::null());
            c.stderr(Stdio::from(f));
            stderr_path = Some(p);
        }
        Capture::Both => {
            let po = tmp_dir.join(format!("{prefix}.out"));
            let pe = tmp_dir.join(format!("{prefix}.err"));
            let fo = fs::File::create(&po)
                .map_err(|e| anyhow!("failed to create stdout temp file {po:?}: {e}"))?;
            let fe = fs::File::create(&pe)
                .map_err(|e| anyhow!("failed to create stderr temp file {pe:?}: {e}"))?;
            c.stdout(Stdio::from(fo));
            c.stderr(Stdio::from(fe));
            stdout_path = Some(po);
            stderr_path = Some(pe);
        }
        Capture::None => {
            c.stdout(Stdio::null());
            c.stderr(Stdio::null());
        }
    }

    let mut child = c.spawn().map_err(|e| anyhow!("failed to spawn {cmd}: {e}"))?;
    let start = Instant::now();

    loop {
        if let Some(st) = child.try_wait().map_err(|e| anyhow!("failed to wait {cmd}: {e}"))? {
            let code = st.code().unwrap_or(-1);
            let mut s = String::new();
            if let Some(p) = &stdout_path {
                let mut buf = Vec::new();
                if let Ok(mut f) = fs::File::open(p) {
                    let _ = f.read_to_end(&mut buf);
                }
                s.push_str(&String::from_utf8_lossy(&buf));
                let _ = fs::remove_file(p);
            }
            if let Some(p) = &stderr_path {
                let mut buf = Vec::new();
                if let Ok(mut f) = fs::File::open(p) {
                    let _ = f.read_to_end(&mut buf);
                }
                s.push_str(&String::from_utf8_lossy(&buf));
                let _ = fs::remove_file(p);
            }
            debug!("exit={code} output_len={} (timeout wrapper)", s.len());
            return Ok((code, s));
        }

        if start.elapsed() >= timeout {
            let _ = child.kill();
            let _ = child.wait();

            let mut s = String::new();
            if let Some(p) = &stdout_path {
                let mut buf = Vec::new();
                if let Ok(mut f) = fs::File::open(p) {
                    let _ = f.read_to_end(&mut buf);
                }
                s.push_str(&String::from_utf8_lossy(&buf));
                let _ = fs::remove_file(p);
            }
            if let Some(p) = &stderr_path {
                let mut buf = Vec::new();
                if let Ok(mut f) = fs::File::open(p) {
                    let _ = f.read_to_end(&mut buf);
                }
                s.push_str(&String::from_utf8_lossy(&buf));
                let _ = fs::remove_file(p);
            }

            return Err(anyhow!(
                "command timeout after {:?}: {cmd} {}\n{}",
                timeout,
                args.join(" "),
                s
            ));
        }

        thread::sleep(Duration::from_millis(50));
    }
}

pub fn run(cmd: &str, args: &[&str], capture: Capture) -> Result<(i32, String)> {
    info!("exec: {} {}", cmd, args.join(" "));
    let mut c = Command::new(cmd);
    c.args(args);

    match capture {
        Capture::Stdout => { c.stderr(Stdio::null()); }
        Capture::Stderr => { c.stdout(Stdio::null()); }
        Capture::Both => {}
        Capture::None => { c.stdout(Stdio::null()); c.stderr(Stdio::null()); }
    }

    let out = c.output().map_err(|e| anyhow!("failed to run {cmd}: {e}"))?;
    let code = out.status.code().unwrap_or(-1);

    let mut s = String::new();
    if matches!(capture, Capture::Stdout | Capture::Both) {
        s.push_str(&String::from_utf8_lossy(&out.stdout));
    }
    if matches!(capture, Capture::Stderr | Capture::Both) {
        s.push_str(&String::from_utf8_lossy(&out.stderr));
    }
    debug!("exit={code} output_len={}", s.len());
    Ok((code, s))
}

pub fn ok(cmd: &str, args: &[&str]) -> Result<String> {
    let (code, out) = run(cmd, args, Capture::Both)?;
    if code != 0 {
        return Err(anyhow!("command failed ({code}): {cmd} {} | out={}", args.join(" "), out));
    }
    Ok(out)
}

pub fn ok_timeout(cmd: &str, args: &[&str], timeout: Duration) -> Result<String> {
    let (code, out) = run_timeout(cmd, args, Capture::Both, timeout)?;
    if code != 0 {
        return Err(anyhow!("command failed ({code}): {cmd} {} | out={}", args.join(" "), out));
    }
    Ok(out)
}

pub fn runv(cmd: &str, args: &[String], capture: Capture) -> Result<(i32, String)> {
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    run(cmd, &refs, capture)
}

pub fn runv_timeout(
    cmd: &str,
    args: &[String],
    capture: Capture,
    timeout: Duration,
) -> Result<(i32, String)> {
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    run_timeout(cmd, &refs, capture, timeout)
}

pub fn okv(cmd: &str, args: &[String]) -> Result<String> {
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    ok(cmd, &refs)
}

pub fn okv_timeout(cmd: &str, args: &[String], timeout: Duration) -> Result<String> {
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    ok_timeout(cmd, &refs, timeout)
}

/// Run a full shell line via `sh -c <line>` and require exit code 0.
/// Thin wrapper for call-sites that already operate on a single command line.
pub fn ok_sh(line: &str) -> Result<String> {
    ok("sh", &["-c", line])
}

pub fn ok_sh_timeout(line: &str, timeout: Duration) -> Result<String> {
    ok_timeout("sh", &["-c", line], timeout)
}

/// Run a full shell line via `sh -c <line>` and capture stdout.
/// Returns an error if the command exits with non-zero status.
pub fn capture(line: &str) -> Result<String> {
    let (rc, out) = run("sh", &["-c", line], Capture::Stdout)?;
    if rc == 0 {
        Ok(out)
    } else {
        Err(anyhow!("command failed rc={rc}: {line}\n{out}"))
    }
}

pub fn capture_timeout(line: &str, timeout: Duration) -> Result<String> {
    let (rc, out) = run_timeout("sh", &["-c", line], Capture::Stdout, timeout)?;
    if rc == 0 {
        Ok(out)
    } else {
        Err(anyhow!("command failed rc={rc}: {line}\n{out}"))
    }
}
