use crate::{config::Config, socks5::connect_via_socks5, status::BackendSnapshot, target::TargetAddr};
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{collections::{HashMap, VecDeque}, net::SocketAddr, sync::Arc, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
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
    recent_targets: VecDeque<TargetAddr>,
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
            inner: Arc::new(Mutex::new(PoolInner {
                entries,
                index,
                rr: 0,
                recent_targets: VecDeque::new(),
            })),
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

    /// Remember targets that DNSCrypt actually reached successfully. Recovery
    /// probes prefer these over generic public probe addresses, making health
    /// checks representative of the real resolver set in use.
    pub async fn record_recent_target(&self, target: &TargetAddr) {
        const MAX_RECENT_TARGETS: usize = 4;
        let mut inner = self.inner.lock().await;
        if let Some(pos) = inner.recent_targets.iter().position(|item| item == target) {
            inner.recent_targets.remove(pos);
        }
        inner.recent_targets.push_front(target.clone());
        while inner.recent_targets.len() > MAX_RECENT_TARGETS {
            inner.recent_targets.pop_back();
        }
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

    /// Target/path failures (SOCKS REP 0x02..=0x06) can be specific to one
    /// resolver address. Keep a GREEN backend eligible until the configured
    /// failure threshold is actually reached. This is critical when there is
    /// only one SOCKS backend: one transient resolver failure must not create a
    /// multi-query DNS outage.
    pub async fn mark_runtime_target_failure(&self, addr: SocketAddr, error: &str) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else { return; };
        let entry = &mut inner.entries[index];
        let old = entry.state;
        entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
        let threshold_reached = entry.consecutive_failures >= self.config.failure_threshold;
        entry.state = if threshold_reached { BackendState::Red } else { BackendState::Green };
        entry.last_error = Some(error.to_string());
        entry.last_check_unix = Some(unix_now());
        entry.last_latency_ms = None;
        entry.next_probe = Instant::now() + self.config.runtime_cooldown();
        entry.failed_connections = entry.failed_connections.saturating_add(1);
        entry.revision = entry.revision.wrapping_add(1);
        if threshold_reached {
            warn!(
                backend = %addr,
                old_state = ?old,
                new_state = ?entry.state,
                failures = entry.consecutive_failures,
                error = %error,
                "backend reached target-failure threshold and was removed from balancing"
            );
        } else {
            debug!(
                backend = %addr,
                failures = entry.consecutive_failures,
                threshold = self.config.failure_threshold,
                error = %error,
                "target-specific SOCKS failure; keeping backend GREEN"
            );
        }
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
        let probe_targets = self.health_probe_targets().await;
        for target in &probe_targets {
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

    async fn health_probe_targets(&self) -> Vec<TargetAddr> {
        let recent = {
            let inner = self.inner.lock().await;
            inner.recent_targets.iter().cloned().collect::<Vec<_>>()
        };
        if !recent.is_empty() {
            return recent;
        }
        self.probe_targets.as_ref().clone()
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

        // For a backend that is already carrying real DNSCrypt traffic, one
        // failed synthetic probe is not enough evidence to evict it. Honour
        // failure_threshold while probing GREEN backends. UNKNOWN/YELLOW/RED
        // still require an actual successful probe before becoming GREEN.
        if old == BackendState::Green && entry.consecutive_failures < self.config.failure_threshold {
            entry.state = BackendState::Green;
        } else {
            entry.state = if entry.consecutive_failures >= self.config.failure_threshold {
                BackendState::Red
            } else {
                BackendState::Yellow
            };
        }
        entry.last_error = Some(error.clone());
        entry.last_check_unix = Some(unix_now());
        entry.last_latency_ms = None;
        let never_succeeded = entry.last_success_unix.is_none();
        entry.next_probe = if never_succeeded {
            // Local SOCKS processes often start just after D2S. Until the first
            // confirmed success, retry readiness on the short cooldown instead
            // of forcing long DIRECT-only windows during service startup.
            Instant::now() + self.config.runtime_cooldown()
        } else {
            Instant::now() + self.config.recovery_probe_interval()
        };
        entry.revision = entry.revision.wrapping_add(1);
        if old != entry.state || old == BackendState::Unknown {
            warn!(backend = %addr, old_state = ?old, new_state = ?entry.state, failures = entry.consecutive_failures, %error, "backend health probe failed");
        } else {
            debug!(backend = %addr, state = ?entry.state, failures = entry.consecutive_failures, %error, "backend health probe failed without crossing threshold");
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

#[cfg(test)]
mod tests {
    use super::*;

    fn test_config(backends: Vec<SocketAddr>) -> Arc<Config> {
        let mut config: Config = toml::from_str(
            r#"
direct_fallback = true
failure_threshold = 3
probe_targets = ["1.1.1.1:443", "8.8.8.8:443"]
"#,
        )
        .unwrap();
        config.listen = "127.0.0.1:11990".parse().unwrap();
        config.dnscrypt_timeout_ms = 5_000;
        config.backends = backends;
        Arc::new(config)
    }

    #[tokio::test]
    async fn target_failures_honour_failure_threshold() {
        let backend: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![backend])).unwrap();
        pool.mark_runtime_success(backend, Duration::from_millis(10)).await;

        pool.mark_runtime_target_failure(backend, "SOCKS5 CONNECT failed with reply code 0x04").await;
        let first = pool.snapshots().await.into_iter().next().unwrap();
        assert_eq!(first.state, BackendState::Green);
        assert_eq!(first.consecutive_failures, 1);

        pool.mark_runtime_target_failure(backend, "SOCKS5 CONNECT failed with reply code 0x04").await;
        let second = pool.snapshots().await.into_iter().next().unwrap();
        assert_eq!(second.state, BackendState::Green);
        assert_eq!(second.consecutive_failures, 2);

        pool.mark_runtime_target_failure(backend, "SOCKS5 CONNECT failed with reply code 0x04").await;
        let third = pool.snapshots().await.into_iter().next().unwrap();
        assert_eq!(third.state, BackendState::Red);
        assert_eq!(third.consecutive_failures, 3);
    }

    #[tokio::test]
    async fn recent_dnscrypt_targets_are_probed_before_static_targets() {
        let backend: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![backend])).unwrap();
        let actual: TargetAddr = "149.112.112.9:8443".parse().unwrap();
        pool.record_recent_target(&actual).await;

        let targets = pool.health_probe_targets().await;
        assert_eq!(targets, vec![actual]);
    }
}
