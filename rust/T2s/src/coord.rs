//! Cross-instance backend dial serialization.
//!
//! Some local SOCKS5 engines cannot accept two clients at the same moment:
//! two overlapping TCP+SOCKS handshakes make the proxy break the requests.
//! When several t2s instances (from different ZDT-D profiles) forward to the
//! same backend, their handshakes must be staggered. This module implements a
//! per-backend, cross-process mutex on the filesystem:
//!
//! * before dialing a backend the caller acquires an exclusive `flock` on
//!   `<api-dir>/t2s/locks/backend-<ip>-<port>.lock`;
//! * the lock is held for the whole TCP connect + SOCKS handshake and released
//!   afterwards (optionally `--connect-stagger-ms` later, so consecutive
//!   handshakes never overlap even approximately);
//! * established relay traffic is never serialized — only the "proxy accepts a
//!   client" phase is;
//! * real D2S DNS requests hold a shared flock on
//!   `backend-<ip>-<port>.dns-priority.lock`; t2s probes that lock once per
//!   acquisition loop and briefly yields new handshakes while DNS is active,
//!   but only for a bounded window so sustained DNS load can never starve t2s;
//! * `flock` is automatically released by the kernel if a process dies; the
//!   priority lock file can remain on disk because an unlocked file is inert;
//! * every filesystem/coordination failure fails open (proceeding
//!   unsynchronized) so coordination can never cause a routing outage.
//!
//! Because the lock is keyed per backend address, different backends are still
//! dialed in parallel, and because `flock` conflicts are per open file
//! description, it also serializes concurrent dials inside one t2s process —
//! exactly the burst case that breaks fragile engines.

use once_cell::sync::OnceCell;
use std::{
    fs,
    io,
    net::SocketAddr,
    os::unix::io::AsRawFd,
    path::PathBuf,
    time::{Duration, Instant},
};

#[derive(Clone)]
pub struct DialCoordination {
    enabled: bool,
    locks_dir: PathBuf,
    stagger: Duration,
}

impl DialCoordination {
    fn disabled() -> Self {
        Self {
            enabled: false,
            locks_dir: PathBuf::new(),
            stagger: Duration::ZERO,
        }
    }
}

static DIAL_COORDINATION: OnceCell<DialCoordination> = OnceCell::new();

/// Prefer foreground D2S DNS handshakes for this long before normal t2s dials
/// are allowed to contend again. Existing relays are never affected. The cap
/// prevents a continuous DNS stream from starving new t2s connections.
const DNS_PRIORITY_YIELD_MAX: Duration = Duration::from_millis(750);
const DNS_PRIORITY_POLL: Duration = Duration::from_millis(10);

fn backend_key(backend: SocketAddr) -> String {
    format!("backend-{}-{}", backend.ip(), backend.port())
}

fn priority_path(locks_dir: &PathBuf, backend: SocketAddr) -> PathBuf {
    locks_dir.join(format!("{}.dns-priority.lock", backend_key(backend)))
}

fn is_would_block(error: &io::Error) -> bool {
    let code = error.raw_os_error();
    code == Some(libc::EWOULDBLOCK) || code == Some(libc::EAGAIN)
}

fn try_exclusive_lock(file: &fs::File) -> io::Result<bool> {
    let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
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

fn open_lock_file(path: &PathBuf) -> io::Result<fs::File> {
    fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(path)
}

/// Detect a live D2S foreground-DNS priority lease with one nonblocking flock.
/// An unlocked file, including one left after a D2S crash, is simply inactive.
fn has_active_dns_priority(locks_dir: &PathBuf, backend: SocketAddr) -> bool {
    let file = match open_lock_file(&priority_path(locks_dir, backend)) {
        Ok(file) => file,
        Err(_) => return false,
    };
    match try_exclusive_lock(&file) {
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

/// Called once from main() before any listener is spawned.
pub fn init_dial_coordination(api_dir: &str, enabled: bool, stagger_ms: u64) {
    if !enabled {
        return;
    }
    let stagger = Duration::from_millis(stagger_ms.min(5_000));
    let _ = DIAL_COORDINATION.set(DialCoordination {
        enabled: true,
        locks_dir: PathBuf::from(api_dir.trim()).join("t2s").join("locks"),
        stagger,
    });
}

fn coordination() -> DialCoordination {
    DIAL_COORDINATION.get().cloned().unwrap_or_else(DialCoordination::disabled)
}

/// Exclusive handle for one backend handshake phase.
pub struct DialLock {
    file: fs::File,
}

impl Drop for DialLock {
    fn drop(&mut self) {
        // The kernel releases flock on close anyway; the explicit unlock keeps
        // the intent obvious and is harmless if the fd is already closed.
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

/// Acquire the per-backend dial mutex across cooperating t2s processes.
///
/// Returns `None` when coordination is disabled, the filesystem is unusable
/// (fail open), or another holder kept the lock longer than the 4s wait budget
/// (healthy local handshakes normally release well inside that window; if a
/// peer is wedged, proceeding unsynchronized after 4s is safer than making the
/// client request depend forever on coordination).
pub async fn acquire_dial_lock(backend: SocketAddr) -> Option<DialLock> {
    let coordination = coordination();
    if !coordination.enabled {
        return None;
    }
    if fs::create_dir_all(&coordination.locks_dir).is_err() {
        return None;
    }
    let path = coordination
        .locks_dir
        .join(format!("{}.lock", backend_key(backend)));

    let started = Instant::now();
    let deadline = started + Duration::from_secs(4);
    let priority_deadline = started + DNS_PRIORITY_YIELD_MAX;
    loop {
        // D2S holds a live shared priority lease before a real DNS request
        // waits for this backend. Yield new t2s handshakes for a bounded window
        // so DNS gets first access without allowing sustained DNS traffic to
        // starve t2s forever.
        if Instant::now() < priority_deadline
            && has_active_dns_priority(&coordination.locks_dir, backend)
        {
            tokio::time::sleep(DNS_PRIORITY_POLL).await;
            continue;
        }

        let file = match open_lock_file(&path) {
            Ok(file) => file,
            Err(_) => return None,
        };
        // Recheck after opening the main lock file to narrow the race where a
        // real DNS request announces priority between the loop-top probe and
        // the actual backend-lock attempt.
        if Instant::now() < priority_deadline
            && has_active_dns_priority(&coordination.locks_dir, backend)
        {
            drop(file);
            tokio::time::sleep(DNS_PRIORITY_POLL).await;
            continue;
        }
        match try_exclusive_lock(&file) {
            Ok(true) => return Some(DialLock { file }),
            Ok(false) => {}
            Err(_) => return None,
        }
        if Instant::now() >= deadline {
            tracing::debug!(
                %backend,
                "backend dial lock wait budget exhausted; proceeding unsynchronized"
            );
            return None;
        }
        // Once the DNS-priority yield window has elapsed, poll at the same
        // cadence as D2S so a sustained stream of DNS handshakes cannot win
        // every release race and starve T2S. Before that, keep the original
        // lower-frequency contention polling.
        let poll = if Instant::now() >= priority_deadline {
            DNS_PRIORITY_POLL
        } else {
            Duration::from_millis(25)
        };
        tokio::time::sleep(poll).await;
    }
}

/// Release a dial lock after the configured stagger window so the next
/// handshake (here, in a peer T2S instance, or in D2S) starts only after the
/// backend has fully digested the previous one. The delay is detached: the
/// established client stream starts relaying immediately and does not pay the
/// stagger as user-visible latency.
pub async fn release_dial_lock(lock: Option<DialLock>) {
    let Some(lock) = lock else { return };
    let stagger = coordination().stagger;
    if stagger.is_zero() {
        drop(lock);
        return;
    }
    let _ = tokio::spawn(async move {
        tokio::time::sleep(stagger).await;
        drop(lock);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> PathBuf {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("t2s-coord-{}-{nonce}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn d2s_priority_path_contract_matches_shared_name() {
        let dir = PathBuf::from("/tmp/example");
        let backend: SocketAddr = "127.0.0.1:1080".parse().unwrap();
        assert_eq!(
            priority_path(&dir, backend),
            dir.join("backend-127.0.0.1-1080.dns-priority.lock")
        );
    }

    #[test]
    fn shared_d2s_priority_lease_is_detected_and_clears_on_unlock() {
        let dir = temp_dir();
        let backend: SocketAddr = "127.0.0.1:1080".parse().unwrap();
        let path = priority_path(&dir, backend);
        let d2s = open_lock_file(&path).unwrap();
        let rc = unsafe { libc::flock(d2s.as_raw_fd(), libc::LOCK_SH | libc::LOCK_NB) };
        assert_eq!(rc, 0);
        assert!(has_active_dns_priority(&dir, backend));

        unsafe { libc::flock(d2s.as_raw_fd(), libc::LOCK_UN); }
        assert!(!has_active_dns_priority(&dir, backend));
        let _ = fs::remove_dir_all(dir);
    }
}
