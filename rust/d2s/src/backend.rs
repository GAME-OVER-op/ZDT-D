use crate::{config::Config, socks5::connect_via_socks5, status::BackendSnapshot, target::TargetAddr};
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, net::SocketAddr, sync::Arc, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
use tokio::{sync::Mutex, task::JoinSet};
use tracing::{debug, info, warn};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum BackendState {
    Unknown,
    Green,
    Yellow,
    Red,
}

#[derive(Clone, Debug)]
struct BackendEntry {
    addr: SocketAddr,
    state: BackendState,
    consecutive_failures: u32,
    last_error: Option<String>,
    last_check_unix: Option<u64>,
    last_success_unix: Option<u64>,
    last_latency_ms: Option<f64>,
    next_probe: Instant,
    selected_connections: u64,
    successful_connections: u64,
    failed_connections: u64,
    revision: u64,
}

#[derive(Debug)]
struct PoolInner {
    entries: Vec<BackendEntry>,
    index: HashMap<SocketAddr, usize>,
    rr: usize,
}

#[derive(Clone)]
pub struct BackendPool {
    inner: Arc<Mutex<PoolInner>>,
    config: Arc<Config>,
    probe_targets: Arc<Vec<TargetAddr>>,
}

impl BackendPool {
    pub fn new(config: Arc<Config>) -> Result<Self> {
        let probe_targets = Arc::new(config.parsed_probe_targets()?);
        let now = Instant::now();
        let entries: Vec<_> = config
            .backends
            .iter()
            .copied()
            .map(|addr| BackendEntry {
                addr,
                state: BackendState::Unknown,
                consecutive_failures: 0,
                last_error: Some("not checked yet".to_string()),
                last_check_unix: None,
                last_success_unix: None,
                last_latency_ms: None,
                next_probe: now,
                selected_connections: 0,
                successful_connections: 0,
                failed_connections: 0,
                revision: 0,
            })
            .collect();
        let index = entries.iter().enumerate().map(|(i, entry)| (entry.addr, i)).collect();
        Ok(Self {
            inner: Arc::new(Mutex::new(PoolInner { entries, index, rr: 0 })),
            config,
            probe_targets,
        })
    }

    pub async fn initial_probe(&self) {
        let addresses = self.addresses().await;
        self.probe_many(addresses).await;
    }

    pub async fn candidate_order(&self) -> Vec<SocketAddr> {
        let mut inner = self.inner.lock().await;
        let healthy: Vec<_> = inner
            .entries
            .iter()
            .filter(|entry| entry.state == BackendState::Green)
            .map(|entry| entry.addr)
            .collect();
        if healthy.is_empty() {
            return Vec::new();
        }
        let start = inner.rr % healthy.len();
        inner.rr = inner.rr.wrapping_add(1);
        let mut ordered = Vec::with_capacity(healthy.len());
        for offset in 0..healthy.len() {
            ordered.push(healthy[(start + offset) % healthy.len()]);
        }
        ordered
    }

    pub async fn mark_attempt(&self, addr: SocketAddr) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        inner.entries[index].selected_connections = inner.entries[index].selected_connections.saturating_add(1);
    }

    pub async fn mark_runtime_success(&self, addr: SocketAddr, latency: Duration) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        let entry = &mut inner.entries[index];
        let old = entry.state;
        entry.state = BackendState::Green;
        entry.consecutive_failures = 0;
        entry.last_error = None;
        entry.last_check_unix = Some(unix_now());
        entry.last_success_unix = Some(unix_now());
        entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
        entry.next_probe = Instant::now() + self.config.healthy_probe_interval();
        entry.successful_connections = entry.successful_connections.saturating_add(1);
        entry.revision = entry.revision.wrapping_add(1);
        if old != BackendState::Green {
            info!(backend = %addr, old_state = ?old, "backend recovered during real traffic");
        }
    }

    pub async fn mark_runtime_failure(&self, addr: SocketAddr, error: &str) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        let entry = &mut inner.entries[index];
        let old = entry.state;
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
        entry.state = if entry.consecutive_failures >= self.config.failure_threshold {
            BackendState::Red
        } else {
            BackendState::Yellow
        };
        entry.last_error = Some(error.to_string());
        entry.last_check_unix = Some(unix_now());
        entry.last_latency_ms = None;
        entry.next_probe = Instant::now() + self.config.runtime_cooldown();
        entry.failed_connections = entry.failed_connections.saturating_add(1);
        entry.revision = entry.revision.wrapping_add(1);
        warn!(backend = %addr, old_state = ?old, new_state = ?entry.state, error = %error, "backend failed during real traffic and was removed from balancing");
    }

    pub async fn due_backends(&self) -> Vec<SocketAddr> {
        let mut inner = self.inner.lock().await;
        let now = Instant::now();
        let mut due = Vec::new();
        for entry in &mut inner.entries {
            if entry.next_probe <= now {
                due.push(entry.addr);
                let interval = if entry.state == BackendState::Green {
                    self.config.healthy_probe_interval()
                } else {
                    self.config.recovery_probe_interval()
                };
                entry.next_probe = now + interval;
            }
        }
        due
    }

    pub async fn probe_many(&self, addresses: Vec<SocketAddr>) {
        let mut set = JoinSet::new();
        for addr in addresses {
            let pool = self.clone();
            set.spawn(async move { pool.probe_one(addr).await });
        }
        while let Some(result) = set.join_next().await {
            if let Err(error) = result {
                warn!(%error, "health probe task failed");
            }
        }
    }

    pub async fn probe_one(&self, addr: SocketAddr) {
        let Some(revision) = self.revision_of(addr).await else { return; };
        let started = Instant::now();
        let mut errors = Vec::new();
        for target in self.probe_targets.iter() {
            let probe = tokio::time::timeout(
                self.config.probe_timeout(),
                connect_via_socks5(
                    addr,
                    target,
                    self.config.connect_timeout(),
                    self.config.upstream_handshake_timeout(),
                    self.config.tcp_nodelay,
                ),
            )
            .await;
            match probe {
                Ok(Ok(stream)) => {
                    drop(stream);
                    self.mark_probe_success(addr, revision, started.elapsed()).await;
                    return;
                }
                Ok(Err(error)) => errors.push(format!("{target}: {error}")),
                Err(_) => errors.push(format!(
                    "{target}: probe exceeded {} ms",
                    self.config.probe_timeout_ms
                )),
            }
        }
        self.mark_probe_failure(addr, revision, errors.join("; ")).await;
    }

    async fn mark_probe_success(&self, addr: SocketAddr, expected_revision: u64, latency: Duration) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        let entry = &mut inner.entries[index];
        if entry.revision != expected_revision {
            debug!(backend = %addr, expected_revision = expected_revision, current_revision = entry.revision, "discarding stale successful health probe");
            return;
        }
        let old = entry.state;
        entry.state = BackendState::Green;
        entry.consecutive_failures = 0;
        entry.last_error = None;
        entry.last_check_unix = Some(unix_now());
        entry.last_success_unix = Some(unix_now());
        entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
        entry.next_probe = Instant::now() + self.config.healthy_probe_interval();
        entry.revision = entry.revision.wrapping_add(1);
        if old != BackendState::Green {
            info!(backend = %addr, old_state = ?old, latency_ms = ?entry.last_latency_ms, "backend is GREEN");
        } else {
            debug!(backend = %addr, latency_ms = ?entry.last_latency_ms, "backend health probe succeeded");
        }
    }

    async fn mark_probe_failure(&self, addr: SocketAddr, expected_revision: u64, error: String) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        let entry = &mut inner.entries[index];
        if entry.revision != expected_revision {
            debug!(backend = %addr, expected_revision = expected_revision, current_revision = entry.revision, "discarding stale failed health probe");
            return;
        }
        let old = entry.state;
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
        entry.state = if entry.consecutive_failures >= self.config.failure_threshold {
            BackendState::Red
        } else {
            BackendState::Yellow
        };
        entry.last_error = Some(error.clone());
        entry.last_check_unix = Some(unix_now());
        entry.last_latency_ms = None;
        entry.next_probe = Instant::now() + self.config.recovery_probe_interval();
        entry.revision = entry.revision.wrapping_add(1);
        if old != entry.state || old == BackendState::Unknown {
            warn!(backend = %addr, old_state = ?old, new_state = ?entry.state, failures = entry.consecutive_failures, %error, "backend health probe failed");
        } else {
            debug!(backend = %addr, state = ?entry.state, failures = entry.consecutive_failures, %error, "backend is still unavailable");
        }
    }

    async fn revision_of(&self, addr: SocketAddr) -> Option<u64> {
        let inner = self.inner.lock().await;
        inner.index.get(&addr).map(|index| inner.entries[*index].revision)
    }

    pub async fn addresses(&self) -> Vec<SocketAddr> {
        let inner = self.inner.lock().await;
        inner.entries.iter().map(|entry| entry.addr).collect()
    }

    pub async fn any_green(&self) -> bool {
        let inner = self.inner.lock().await;
        inner.entries.iter().any(|entry| entry.state == BackendState::Green)
    }

    pub async fn snapshots(&self) -> Vec<BackendSnapshot> {
        let inner = self.inner.lock().await;
        inner
            .entries
            .iter()
            .map(|entry| BackendSnapshot {
                address: entry.addr.to_string(),
                state: entry.state,
                consecutive_failures: entry.consecutive_failures,
                last_error: entry.last_error.clone(),
                last_check_unix: entry.last_check_unix,
                last_success_unix: entry.last_success_unix,
                latency_ms: entry.last_latency_ms,
                selected_connections: entry.selected_connections,
                successful_connections: entry.successful_connections,
                failed_connections: entry.failed_connections,
            })
            .collect()
    }


}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
