use crate::{backend::BackendState, config::Config};
use anyhow::{Context, Result};
use serde::Serialize;
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Arc, Mutex as StdMutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::watch;
use tracing::warn;

#[derive(Default)]
pub struct RuntimeStats {
    pub accepted_connections: AtomicU64,
    pub active_connections: AtomicU64,
    pub completed_connections: AtomicU64,
    pub failed_connections: AtomicU64,
    pub connection_limit_drops: AtomicU64,
    pub peak_active_connections: AtomicU64,
    pub upstream_connections: AtomicU64,
    pub direct_connections: AtomicU64,
    pub client_to_remote_bytes: AtomicU64,
    pub remote_to_client_bytes: AtomicU64,
    pub relay_stalled: AtomicU64,
    pub relay_half_close_timeouts: AtomicU64,
    pub relay_forced_closes: AtomicU64,
    pub relay_client_eof: AtomicU64,
    pub relay_remote_eof: AtomicU64,
    pub relay_client_io_errors: AtomicU64,
    pub relay_remote_io_errors: AtomicU64,
    next_connection_id: AtomicU64,
    active_started: StdMutex<HashMap<u64, Instant>>,
}

pub struct ActiveConnectionGuard {
    stats: Arc<RuntimeStats>,
    id: u64,
}

impl Drop for ActiveConnectionGuard {
    fn drop(&mut self) {
        if let Ok(mut active) = self.stats.active_started.lock() {
            active.remove(&self.id);
        }
        self.stats.active_connections.fetch_sub(1, Ordering::Relaxed);
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct BackendSnapshot {
    pub address: String,
    pub state: BackendState,
    pub consecutive_failures: u32,
    pub runtime_failure_streak: u32,
    pub last_error: Option<String>,
    pub last_check_unix: Option<u64>,
    pub last_success_unix: Option<u64>,
    pub last_full_probe_unix: Option<u64>,
    pub latency_ms: Option<f64>,
    pub internet_latency_ms: Option<f64>,
    pub runtime_latency_ewma_ms: Option<f64>,
    pub runtime_warm: bool,
    pub selected_connections: u64,
    pub successful_connections: u64,
    pub failed_connections: u64,
}

#[derive(Debug, Serialize)]
pub struct StatusSnapshot {
    pub name: &'static str,
    pub version: &'static str,
    pub generated_unix: u64,
    pub running: bool,
    pub listen: String,
    pub direct_fallback: bool,
    pub accepted_connections: u64,
    pub active_connections: u64,
    pub oldest_active_connection_ms: Option<u64>,
    pub peak_active_connections: u64,
    pub completed_connections: u64,
    pub failed_connections: u64,
    pub connection_limit_drops: u64,
    pub upstream_connections: u64,
    pub direct_connections: u64,
    pub client_to_remote_bytes: u64,
    pub remote_to_client_bytes: u64,
    pub relay_stalled: u64,
    pub relay_half_close_timeouts: u64,
    pub relay_forced_closes: u64,
    pub relay_client_eof: u64,
    pub relay_remote_eof: u64,
    pub relay_client_io_errors: u64,
    pub relay_remote_io_errors: u64,
    pub backends: Vec<BackendSnapshot>,
}

impl RuntimeStats {
    pub fn begin_connection(self: &Arc<Self>) -> ActiveConnectionGuard {
        let id = self.next_connection_id.fetch_add(1, Ordering::Relaxed);
        let active = self.active_connections.fetch_add(1, Ordering::Relaxed) + 1;
        update_max(&self.peak_active_connections, active);
        if let Ok(mut started) = self.active_started.lock() {
            started.insert(id, Instant::now());
        }
        ActiveConnectionGuard { stats: self.clone(), id }
    }

    fn oldest_active_connection_ms(&self) -> Option<u64> {
        self.active_started
            .lock()
            .ok()
            .and_then(|active| {
                active
                    .values()
                    .map(|started| started.elapsed().as_millis() as u64)
                    .max()
            })
    }

    pub fn snapshot(&self, config: &Config, backends: Vec<BackendSnapshot>, running: bool) -> StatusSnapshot {
        StatusSnapshot {
            name: "D2S",
            version: env!("CARGO_PKG_VERSION"),
            generated_unix: unix_now(),
            running,
            listen: config.listen.to_string(),
            direct_fallback: config.direct_fallback,
            accepted_connections: self.accepted_connections.load(Ordering::Relaxed),
            active_connections: self.active_connections.load(Ordering::Relaxed),
            oldest_active_connection_ms: self.oldest_active_connection_ms(),
            peak_active_connections: self.peak_active_connections.load(Ordering::Relaxed),
            completed_connections: self.completed_connections.load(Ordering::Relaxed),
            failed_connections: self.failed_connections.load(Ordering::Relaxed),
            connection_limit_drops: self.connection_limit_drops.load(Ordering::Relaxed),
            upstream_connections: self.upstream_connections.load(Ordering::Relaxed),
            direct_connections: self.direct_connections.load(Ordering::Relaxed),
            client_to_remote_bytes: self.client_to_remote_bytes.load(Ordering::Relaxed),
            remote_to_client_bytes: self.remote_to_client_bytes.load(Ordering::Relaxed),
            relay_stalled: self.relay_stalled.load(Ordering::Relaxed),
            relay_half_close_timeouts: self.relay_half_close_timeouts.load(Ordering::Relaxed),
            relay_forced_closes: self.relay_forced_closes.load(Ordering::Relaxed),
            relay_client_eof: self.relay_client_eof.load(Ordering::Relaxed),
            relay_remote_eof: self.relay_remote_eof.load(Ordering::Relaxed),
            relay_client_io_errors: self.relay_client_io_errors.load(Ordering::Relaxed),
            relay_remote_io_errors: self.relay_remote_io_errors.load(Ordering::Relaxed),
            backends,
        }
    }
}

fn update_max(target: &AtomicU64, value: u64) {
    let mut current = target.load(Ordering::Relaxed);
    while value > current {
        match target.compare_exchange_weak(current, value, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => break,
            Err(observed) => current = observed,
        }
    }
}

pub async fn status_writer(
    config: Arc<Config>,
    pool: crate::backend::BackendPool,
    stats: Arc<RuntimeStats>,
    mut shutdown: watch::Receiver<bool>,
) {
    let Some(path) = config.status_file.clone() else { return; };
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(config.status_interval_secs));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        tokio::select! {
            _ = interval.tick() => {
                let snapshot = stats.snapshot(&config, pool.snapshots().await, true);
                if let Err(error) = write_atomic_json(&path, &snapshot).await {
                    warn!(path = %path.display(), %error, "unable to update D2S status file");
                }
            }
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    let snapshot = stats.snapshot(&config, pool.snapshots().await, false);
                    if let Err(error) = write_atomic_json(&path, &snapshot).await {
                        warn!(path = %path.display(), %error, "unable to write final D2S status file");
                    }
                    break;
                }
            }
        }
    }
}

async fn write_atomic_json(path: &Path, value: &impl Serialize) -> Result<()> {
    if let Some(parent) = path.parent().filter(|parent| !parent.as_os_str().is_empty()) {
        tokio::fs::create_dir_all(parent)
            .await
            .with_context(|| format!("create status directory {}", parent.display()))?;
    }
    let bytes = serde_json::to_vec_pretty(value).context("serialize status JSON")?;
    let tmp = path.with_extension(format!("tmp.{}", std::process::id()));
    tokio::fs::write(&tmp, bytes)
        .await
        .with_context(|| format!("write temporary status file {}", tmp.display()))?;
    tokio::fs::rename(&tmp, path)
        .await
        .with_context(|| format!("replace status file {}", path.display()))?;
    Ok(())
}

fn unix_now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}
