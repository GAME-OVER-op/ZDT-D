use crate::{
    config::Config,
    socks5::connect_via_socks5,
    status::BackendSnapshot,
    target::TargetAddr,
};
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
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
    probe_in_flight: bool,
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

#[derive(Clone, Copy, Debug)]
pub(crate) struct ProbeClaim {
    addr: SocketAddr,
    revision: u64,
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
                probe_in_flight: false,
                selected_connections: 0,
                successful_connections: 0,
                failed_connections: 0,
                revision: 0,
            })
            .collect();
        let index = entries
            .iter()
            .enumerate()
            .map(|(i, entry)| (entry.addr, i))
            .collect();
        Ok(Self {
            inner: Arc::new(Mutex::new(PoolInner {
                entries,
                index,
                rr: 0,
            })),
            config,
            probe_targets,
        })
    }

    /// Explicit one-shot probing used by the CLI. The normal server does not
    /// wait for this before accepting traffic.
    pub async fn initial_probe(&self) {
        let claims = self.claim_all_for_probe().await;
        self.probe_many(claims).await;
    }

    /// Primary routing candidates. Healthy backends are preferred, but
    /// unchecked backends are immediately usable so D2S never blocks DNSCrypt
    /// startup waiting for synthetic probes.
    pub async fn candidate_order(&self) -> Vec<SocketAddr> {
        let mut inner = self.inner.lock().await;
        let seed = inner.rr;
        inner.rr = inner.rr.wrapping_add(1);

        let mut ordered = rotated_state(&inner.entries, BackendState::Green, seed);
        ordered.extend(rotated_state(
            &inner.entries,
            BackendState::Unknown,
            seed,
        ));
        ordered
    }

    /// Claim one degraded backend for a single half-open recovery attempt.
    /// Yellow is preferred over Red. A claimed backend cannot simultaneously
    /// be probed or claimed by another recovery attempt.
    pub async fn claim_degraded_candidate(
        &self,
        excluded: &HashSet<SocketAddr>,
    ) -> Option<SocketAddr> {
        let mut inner = self.inner.lock().await;
        let now = Instant::now();
        let seed = inner.rr;
        inner.rr = inner.rr.wrapping_add(1);

        for wanted in [BackendState::Yellow, BackendState::Red] {
            let candidates: Vec<usize> = inner
                .entries
                .iter()
                .enumerate()
                .filter(|(_, entry)| {
                    entry.state == wanted
                        && !entry.probe_in_flight
                        && entry.next_probe <= now
                        && !excluded.contains(&entry.addr)
                })
                .map(|(index, _)| index)
                .collect();
            if candidates.is_empty() {
                continue;
            }
            let selected = candidates[seed % candidates.len()];
            inner.entries[selected].probe_in_flight = true;
            return Some(inner.entries[selected].addr);
        }
        None
    }

    pub async fn mark_attempt(&self, addr: SocketAddr) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else {
            return;
        };
        inner.entries[index].selected_connections = inner.entries[index]
            .selected_connections
            .saturating_add(1);
    }

    pub async fn mark_runtime_success(&self, addr: SocketAddr, latency: Duration) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else {
            return;
        };
        let entry = &mut inner.entries[index];
        let old = entry.state;
        let now_unix = unix_now();
        entry.state = BackendState::Green;
        entry.consecutive_failures = 0;
        entry.last_error = None;
        entry.last_check_unix = Some(now_unix);
        entry.last_success_unix = Some(now_unix);
        entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
        entry.next_probe = Instant::now() + self.config.healthy_probe_interval();
        entry.probe_in_flight = false;
        entry.successful_connections = entry.successful_connections.saturating_add(1);
        entry.revision = entry.revision.wrapping_add(1);
        if old != BackendState::Green {
            info!(backend = %addr, old_state = ?old, "backend recovered during real traffic");
        }
    }

    /// Record a transport/protocol failure that reflects backend health.
    pub async fn mark_runtime_failure(&self, addr: SocketAddr, error: &str) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else {
            return;
        };
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
        entry.probe_in_flight = false;
        entry.failed_connections = entry.failed_connections.saturating_add(1);
        entry.next_probe = Instant::now()
            + if entry.state == BackendState::Red {
                recovery_delay(&self.config, entry.consecutive_failures)
            } else {
                self.config.runtime_cooldown()
            };
        entry.revision = entry.revision.wrapping_add(1);

        if old != entry.state {
            warn!(
                backend = %addr,
                old_state = ?old,
                new_state = ?entry.state,
                failures = entry.consecutive_failures,
                error = %error,
                "backend health degraded during real traffic"
            );
        } else {
            debug!(
                backend = %addr,
                state = ?entry.state,
                failures = entry.consecutive_failures,
                error = %error,
                "backend transport failure during real traffic"
            );
        }
    }

    /// A SOCKS5 server may be perfectly healthy while reporting that a
    /// particular destination is unreachable. Do not poison global backend
    /// health for destination-specific reply codes.
    pub async fn mark_target_failure(&self, addr: SocketAddr, error: &str) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&addr).copied() else {
            return;
        };
        let entry = &mut inner.entries[index];
        entry.probe_in_flight = false;
        entry.failed_connections = entry.failed_connections.saturating_add(1);
        if matches!(entry.state, BackendState::Yellow | BackendState::Red) {
            entry.next_probe = Instant::now() + self.config.recovery_probe_interval();
        }
        debug!(backend = %addr, state = ?entry.state, error = %error, "backend rejected or could not reach this target; health unchanged");
    }

    pub async fn next_probe_deadline(&self) -> Option<Instant> {
        let inner = self.inner.lock().await;
        inner
            .entries
            .iter()
            .filter(|entry| !entry.probe_in_flight)
            .map(|entry| entry.next_probe)
            .min()
    }

    pub(crate) async fn claim_due_backends(&self) -> Vec<ProbeClaim> {
        let mut inner = self.inner.lock().await;
        let now = Instant::now();
        let mut due = Vec::new();
        for entry in &mut inner.entries {
            if !entry.probe_in_flight && entry.next_probe <= now {
                entry.probe_in_flight = true;
                due.push(ProbeClaim {
                    addr: entry.addr,
                    revision: entry.revision,
                });
            }
        }
        due
    }

    async fn claim_all_for_probe(&self) -> Vec<ProbeClaim> {
        let mut inner = self.inner.lock().await;
        let mut claims = Vec::new();
        for entry in &mut inner.entries {
            if !entry.probe_in_flight {
                entry.probe_in_flight = true;
                claims.push(ProbeClaim {
                    addr: entry.addr,
                    revision: entry.revision,
                });
            }
        }
        claims
    }

    pub(crate) async fn probe_many(&self, claims: Vec<ProbeClaim>) {
        let mut set = JoinSet::new();
        for claim in claims {
            let pool = self.clone();
            set.spawn(async move { pool.probe_one(claim).await });
        }
        while let Some(result) = set.join_next().await {
            if let Err(error) = result {
                warn!(%error, "health probe task failed");
            }
        }
    }

    async fn probe_one(&self, claim: ProbeClaim) {
        let started = Instant::now();
        let mut errors = Vec::new();
        for target in self.probe_targets.iter() {
            let probe = tokio::time::timeout(
                self.config.probe_timeout(),
                connect_via_socks5(
                    claim.addr,
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
                    self.mark_probe_success(claim, started.elapsed()).await;
                    return;
                }
                Ok(Err(error)) => {
                    let hard_failure = error.affects_backend_health();
                    errors.push(format!("{target}: {error}"));
                    // A dead/broken local SOCKS transport will fail every
                    // target in the same way; avoid redundant probe traffic.
                    if hard_failure {
                        break;
                    }
                }
                Err(_) => {
                    errors.push(format!(
                        "{target}: probe exceeded {} ms",
                        self.config.probe_timeout_ms
                    ));
                    break;
                }
            }
        }
        self.mark_probe_failure(claim, errors.join("; ")).await;
    }

    async fn mark_probe_success(&self, claim: ProbeClaim, latency: Duration) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&claim.addr).copied() else {
            return;
        };
        let entry = &mut inner.entries[index];
        entry.probe_in_flight = false;
        if entry.revision != claim.revision {
            debug!(
                backend = %claim.addr,
                expected_revision = claim.revision,
                current_revision = entry.revision,
                "discarding stale successful health probe"
            );
            return;
        }
        let old = entry.state;
        let now_unix = unix_now();
        entry.state = BackendState::Green;
        entry.consecutive_failures = 0;
        entry.last_error = None;
        entry.last_check_unix = Some(now_unix);
        entry.last_success_unix = Some(now_unix);
        entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
        entry.next_probe = Instant::now() + self.config.healthy_probe_interval();
        entry.revision = entry.revision.wrapping_add(1);
        if old != BackendState::Green {
            info!(backend = %claim.addr, old_state = ?old, latency_ms = ?entry.last_latency_ms, "backend is GREEN");
        } else {
            debug!(backend = %claim.addr, latency_ms = ?entry.last_latency_ms, "backend health probe succeeded");
        }
    }

    async fn mark_probe_failure(&self, claim: ProbeClaim, error: String) {
        let mut inner = self.inner.lock().await;
        let Some(index) = inner.index.get(&claim.addr).copied() else {
            return;
        };
        let entry = &mut inner.entries[index];
        entry.probe_in_flight = false;
        if entry.revision != claim.revision {
            debug!(
                backend = %claim.addr,
                expected_revision = claim.revision,
                current_revision = entry.revision,
                "discarding stale failed health probe"
            );
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
        entry.next_probe = Instant::now()
            + if entry.state == BackendState::Red {
                recovery_delay(&self.config, entry.consecutive_failures)
            } else {
                self.config.recovery_probe_interval()
            };
        entry.revision = entry.revision.wrapping_add(1);
        if old != entry.state || old == BackendState::Unknown {
            warn!(
                backend = %claim.addr,
                old_state = ?old,
                new_state = ?entry.state,
                failures = entry.consecutive_failures,
                %error,
                "backend health probe failed"
            );
        } else {
            debug!(
                backend = %claim.addr,
                state = ?entry.state,
                failures = entry.consecutive_failures,
                %error,
                "backend is still unavailable"
            );
        }
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

fn rotated_state(entries: &[BackendEntry], state: BackendState, seed: usize) -> Vec<SocketAddr> {
    let candidates: Vec<_> = entries
        .iter()
        .filter(|entry| entry.state == state)
        .map(|entry| entry.addr)
        .collect();
    if candidates.is_empty() {
        return Vec::new();
    }
    let start = seed % candidates.len();
    (0..candidates.len())
        .map(|offset| candidates[(start + offset) % candidates.len()])
        .collect()
}

fn recovery_delay(config: &Config, failures: u32) -> Duration {
    let base = config.recovery_probe_interval();
    let exponent = failures
        .saturating_sub(config.failure_threshold)
        .min(6);
    let factor = 1u32 << exponent;
    let cap = config.healthy_probe_interval().max(base);
    base.saturating_mul(factor).min(cap)
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
