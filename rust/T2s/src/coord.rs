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
//! * `flock` is automatically released by the kernel if a process dies, and
//!   every failure path fails open (proceeding unsynchronized) so a
//!   coordination problem can never cause a routing outage.
//!
//! Because the lock is keyed per backend address, different backends are still
//! dialed in parallel, and because `flock` conflicts are per open file
//! description, it also serializes concurrent dials inside one t2s process —
//! exactly the burst case that breaks fragile engines.

use once_cell::sync::OnceCell;
use std::{
    fs,
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
            locks_dir: PathBuf::from("/data/local/tmp"),
            stagger: Duration::ZERO,
        }
    }
}

static DIAL_COORDINATION: OnceCell<DialCoordination> = OnceCell::new();

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
/// (the peer's own handshake timeout is bounded by ~3s, so a healthy peer
/// always releases in time; proceeding unsynchronized after that beats
/// failing the client request).
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
        .join(format!("backend-{}-{}.lock", backend.ip(), backend.port()));

    let deadline = Instant::now() + Duration::from_secs(4);
    loop {
        let file = match fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .write(true)
            .open(&path)
        {
            Ok(file) => file,
            Err(_) => return None,
        };
        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc == 0 {
            return Some(DialLock { file });
        }
        if Instant::now() >= deadline {
            tracing::debug!(
                %backend,
                "backend dial lock wait budget exhausted; proceeding unsynchronized"
            );
            return None;
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

/// Release a dial lock, optionally holding it for the configured stagger window
/// first so the next handshake (here or in a peer instance) starts only after
/// the backend has fully digested the previous one.
pub async fn release_dial_lock(lock: Option<DialLock>) {
    let Some(lock) = lock else { return };
    let stagger = coordination().stagger;
    if !stagger.is_zero() {
        tokio::time::sleep(stagger).await;
    }
    drop(lock);
}
