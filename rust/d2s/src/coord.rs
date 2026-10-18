//! Cross-process backend dial coordination shared with T2S.
//!
//! D2S and T2S can legitimately point at the same local SOCKS5 backend. Some
//! proxy engines are fragile when two TCP+SOCKS handshakes arrive at exactly
//! the same time, so both programs serialize only that short establishment
//! phase through the same filesystem `flock` used by T2S:
//!
//! `<coord-dir>/backend-<ip>-<port>.lock`
//!
//! A real DNS request additionally holds a shared flock on:
//!
//! `<coord-dir>/backend-<ip>-<port>.dns-priority.lock`
//!
//! Multiple DNS requests can hold that priority lease together. T2S detects an
//! active lease with a single nonblocking exclusive-lock probe and briefly
//! yields new handshakes, while established T2S relays continue untouched.
//! Background D2S work (health and warmup) never takes a priority lease and
//! yields while foreground DNS is active.
//!
//! `flock` is automatically released by the kernel if either process dies.
//! The lock files themselves may remain on disk, but an unlocked file is inert;
//! no stale-PID/marker cleanup is required. Filesystem failures always fail
//! open so coordination can never become a new routing dependency.

use std::{
    fs,
    io,
    net::SocketAddr,
    os::unix::io::AsRawFd,
    path::{Path, PathBuf},
    sync::OnceLock,
    time::{Duration, Instant},
};

const DEFAULT_COORD_DIR: &str = "/data/adb/modules/ZDT-D/api/t2s/locks";
const COORD_DIR_ENV: &str = "ZDTD_BACKEND_COORD_DIR";
const CONNECT_STAGGER_MS: u64 = 100;
const POLL_MS: u64 = 10;
const PRIORITY_ACQUIRE_POLL_MS: u64 = 1;
const PRIORITY_ACQUIRE_BUDGET_MS: u64 = 20;

static COORD_DIR: OnceLock<PathBuf> = OnceLock::new();

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DialPriority {
    /// A real DNSCrypt client route. Hold a shared priority lease before
    /// waiting for the shared backend handshake lock.
    ForegroundDns,
    /// Health probes, recovery probes and warm tunnel creation.
    Background,
}

#[derive(Debug)]
pub struct CoordinationBusy {
    pub waited: Duration,
}

struct PriorityLease {
    file: fs::File,
}

impl Drop for PriorityLease {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

/// Exclusive handle for one backend TCP+SOCKS handshake.
pub struct DialLock {
    file: fs::File,
    // Keep the foreground priority lease alive for the entire handshake. It is
    // released only after the main backend lock's post-handshake stagger.
    _priority_lease: Option<PriorityLease>,
}

impl Drop for DialLock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

fn coordination_dir() -> &'static PathBuf {
    COORD_DIR.get_or_init(|| {
        std::env::var_os(COORD_DIR_ENV)
            .filter(|value| !value.as_os_str().is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(DEFAULT_COORD_DIR))
    })
}

fn backend_key(backend: SocketAddr) -> String {
    format!("backend-{}-{}", backend.ip(), backend.port())
}

fn lock_path(backend: SocketAddr) -> PathBuf {
    coordination_dir().join(format!("{}.lock", backend_key(backend)))
}

fn priority_path(backend: SocketAddr) -> PathBuf {
    coordination_dir().join(format!("{}.dns-priority.lock", backend_key(backend)))
}

fn is_would_block(error: &io::Error) -> bool {
    let code = error.raw_os_error();
    code == Some(libc::EWOULDBLOCK) || code == Some(libc::EAGAIN)
}

fn try_lock(file: &fs::File, operation: libc::c_int) -> io::Result<bool> {
    let rc = unsafe { libc::flock(file.as_raw_fd(), operation | libc::LOCK_NB) };
    if rc == 0 {
        return Ok(true);
    }
    let error = io::Error::last_os_error();
    if is_would_block(&error) {
        Ok(false)
    } else {
        Err(error)
    }
}

fn open_lock_file(path: &Path) -> io::Result<fs::File> {
    fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path)
}

/// Take a shared foreground-DNS priority lease. T2S only takes the conflicting
/// exclusive priority lock for the instant needed to test whether DNS is
/// active, so contention here should last microseconds. If the priority file is
/// unavailable we fail open and still use the main backend lock.
async fn acquire_priority_lease(backend: SocketAddr) -> Option<PriorityLease> {
    let file = open_lock_file(&priority_path(backend)).ok()?;
    let deadline = Instant::now() + Duration::from_millis(PRIORITY_ACQUIRE_BUDGET_MS);
    loop {
        match try_lock(&file, libc::LOCK_SH) {
            Ok(true) => return Some(PriorityLease { file }),
            Ok(false) => {}
            Err(_) => return None,
        }
        if Instant::now() >= deadline {
            return None;
        }
        tokio::time::sleep(Duration::from_millis(PRIORITY_ACQUIRE_POLL_MS)).await;
    }
}

/// True while one or more real DNS requests hold the shared priority lease.
/// An unlocked file (including one left after a crash) returns false.
fn has_active_priority(backend: SocketAddr) -> bool {
    let file = match open_lock_file(&priority_path(backend)) {
        Ok(file) => file,
        Err(_) => return false,
    };
    match try_lock(&file, libc::LOCK_EX) {
        Ok(true) => {
            unsafe {
                libc::flock(file.as_raw_fd(), libc::LOCK_UN);
            }
            false
        }
        Ok(false) => true,
        Err(_) => false,
    }
}

/// Acquire the shared T2S/D2S backend handshake lock.
///
/// `Ok(None)` means coordination is unavailable and the caller should proceed
/// unsynchronized (fail open). `Err(CoordinationBusy)` means a healthy peer is
/// actively using or waiting for this backend past our caller-supplied budget;
/// callers should defer/retry without marking backend health down.
pub async fn acquire_dial_lock(
    backend: SocketAddr,
    priority: DialPriority,
    wait_budget: Duration,
) -> Result<Option<DialLock>, CoordinationBusy> {
    let dir = coordination_dir();
    if fs::create_dir_all(dir).is_err() {
        return Ok(None);
    }

    let priority_lease = if priority == DialPriority::ForegroundDns {
        acquire_priority_lease(backend).await
    } else {
        None
    };
    let started = Instant::now();
    let deadline = started + wait_budget;
    let path = lock_path(backend);

    loop {
        if priority == DialPriority::Background && has_active_priority(backend) {
            if Instant::now() >= deadline {
                return Err(CoordinationBusy {
                    waited: started.elapsed(),
                });
            }
            tokio::time::sleep(Duration::from_millis(POLL_MS)).await;
            continue;
        }

        let file = match open_lock_file(&path) {
            Ok(file) => file,
            Err(_) => return Ok(None),
        };
        // Close the tiny check->lock race for background work: a foreground
        // DNS request may have taken the priority lease after our first check
        // but before this main lock file was opened.
        if priority == DialPriority::Background && has_active_priority(backend) {
            drop(file);
            if Instant::now() >= deadline {
                return Err(CoordinationBusy {
                    waited: started.elapsed(),
                });
            }
            tokio::time::sleep(Duration::from_millis(POLL_MS)).await;
            continue;
        }
        match try_lock(&file, libc::LOCK_EX) {
            Ok(true) => {
                return Ok(Some(DialLock {
                    file,
                    _priority_lease: priority_lease,
                }));
            }
            Ok(false) => {}
            Err(_) => return Ok(None),
        }

        if Instant::now() >= deadline {
            return Err(CoordinationBusy {
                waited: started.elapsed(),
            });
        }
        tokio::time::sleep(Duration::from_millis(POLL_MS)).await;
    }
}

/// Keep the backend lock alive for the same default 100 ms stagger used by
/// T2S, but do it in a detached task. The just-established DNS tunnel can be
/// returned immediately while only the *next handshake* waits for spacing.
/// Runtime shutdown cancels the tiny task and drops the guard, so no lock can
/// be left behind.
pub async fn release_dial_lock(lock: Option<DialLock>) {
    let Some(lock) = lock else { return };
    let _ = tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(CONNECT_STAGGER_MS)).await;
        drop(lock);
    });
}

/// Exposed for startup diagnostics only; this is a filesystem coordination
/// path, not an HTTP/API endpoint.
pub fn coordination_path() -> &'static Path {
    coordination_dir().as_path()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backend_paths_match_t2s_format() {
        let backend: SocketAddr = "127.0.0.1:1080".parse().unwrap();
        assert_eq!(backend_key(backend), "backend-127.0.0.1-1080");
        assert!(lock_path(backend).ends_with("backend-127.0.0.1-1080.lock"));
        assert!(priority_path(backend).ends_with("backend-127.0.0.1-1080.dns-priority.lock"));
    }

    fn temp_lock_path(name: &str) -> PathBuf {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("d2s-coord-{}-{nonce}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir.join(name)
    }

    #[test]
    fn shared_dns_priority_lease_blocks_exclusive_probe() {
        let path = temp_lock_path("priority.lock");
        let dns_a = open_lock_file(&path).unwrap();
        let dns_b = open_lock_file(&path).unwrap();
        let probe = open_lock_file(&path).unwrap();

        assert!(try_lock(&dns_a, libc::LOCK_SH).unwrap());
        assert!(try_lock(&dns_b, libc::LOCK_SH).unwrap());
        assert!(!try_lock(&probe, libc::LOCK_EX).unwrap());

        unsafe {
            libc::flock(dns_a.as_raw_fd(), libc::LOCK_UN);
            libc::flock(dns_b.as_raw_fd(), libc::LOCK_UN);
        }
        assert!(try_lock(&probe, libc::LOCK_EX).unwrap());
        unsafe { libc::flock(probe.as_raw_fd(), libc::LOCK_UN); }
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }

    #[test]
    fn backend_handshake_lock_is_exclusive_and_reusable() {
        let path = temp_lock_path("backend.lock");
        let first = open_lock_file(&path).unwrap();
        let second = open_lock_file(&path).unwrap();

        assert!(try_lock(&first, libc::LOCK_EX).unwrap());
        assert!(!try_lock(&second, libc::LOCK_EX).unwrap());
        unsafe { libc::flock(first.as_raw_fd(), libc::LOCK_UN); }
        assert!(try_lock(&second, libc::LOCK_EX).unwrap());
        unsafe { libc::flock(second.as_raw_fd(), libc::LOCK_UN); }
        let _ = fs::remove_dir_all(path.parent().unwrap());
    }
}
