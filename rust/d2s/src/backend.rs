use crate::{
    config::Config,
    socks5::{
        connect_to_socks5_server, connect_via_socks5, verify_tls_data_plane,
        RuntimeFailureClass,
    },
    status::BackendSnapshot,
    target::TargetAddr,
};
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{sync::{Mutex, Notify}, task::JoinSet};
use tracing::{debug, info, warn};

const FULL_PROBE_INTERVAL: Duration = Duration::from_secs(15 * 60);
const RUNTIME_FAILURE_WINDOW: Duration = Duration::from_secs(8);
const NO_GREEN_FAST_RECOVERY: Duration = Duration::from_secs(2);
const NO_GREEN_MEDIUM_RECOVERY: Duration = Duration::from_secs(5);
const NO_GREEN_SLOW_RECOVERY: Duration = Duration::from_secs(15);

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum BackendState {
    Unknown,
    Green,
    Yellow,
    Red,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ProbeMode {
    Light,
    Full,
}

#[derive(Clone, Debug)]
struct BackendEntry {
    addr: SocketAddr,
    state: BackendState,
    consecutive_failures: u32,
    runtime_failure_streak: u32,
    last_runtime_failure: Option<Instant>,
    last_error: Option<String>,
    last_check_unix: Option<u64>,
    last_success_unix: Option<u64>,
    last_full_probe_unix: Option<u64>,
    last_latency_ms: Option<f64>,
    internet_latency_ms: Option<f64>,
    internet_probe_fail_streak: u8,
    next_internet_probe_after: Instant,
    next_probe: Instant,
    selected_connections: u64,
    successful_connections: u64,
    failed_connections: u64,
    revision: u64,
    probe_in_flight: bool,
    force_full_probe: bool,
    next_forced_probe_after: Instant,
    runtime_cooldown_until: Instant,
}

#[derive(Debug)]
struct PoolInner {
    entries: Vec<BackendEntry>,
    index: HashMap<SocketAddr, usize>,
    rr: usize,
    no_green_since: Option<Instant>,
}

#[derive(Clone)]
pub struct BackendPool {
    inner: Arc<Mutex<PoolInner>>,
    config: Arc<Config>,
    probe_targets: Arc<Vec<TargetAddr>>,
    health_wake: Arc<Notify>,
}

#[derive(Debug)]
enum ProbeOutcome {
    SocksUnavailable(String),
    LightReachable { latency: Duration },
    InternetUnavailable { socks_latency: Duration, error: String },
    InternetVerified { latency: Duration },
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
                runtime_failure_streak: 0,
                last_runtime_failure: None,
                last_error: Some("not checked yet".to_string()),
                last_check_unix: None,
                last_success_unix: None,
                last_full_probe_unix: None,
                last_latency_ms: None,
                internet_latency_ms: None,
                internet_probe_fail_streak: 0,
                next_internet_probe_after: now,
                next_probe: now,
                selected_connections: 0,
                successful_connections: 0,
                failed_connections: 0,
                revision: 0,
                probe_in_flight: false,
                force_full_probe: true,
                next_forced_probe_after: now,
                runtime_cooldown_until: now,
            })
            .collect();
        let index = entries
            .iter()
            .enumerate()
            .map(|(i, entry)| (entry.addr, i))
            .collect();
        let no_green_since = (!entries.is_empty()).then_some(now);
        Ok(Self {
            inner: Arc::new(Mutex::new(PoolInner {
                entries,
                index,
                rr: 0,
                no_green_since,
            })),
            config,
            probe_targets,
            health_wake: Arc::new(Notify::new()),
        })
    }

    pub async fn initial_probe(&self) {
        let addresses = self.addresses().await;
        let plan = addresses.into_iter().map(|addr| (addr, ProbeMode::Full)).collect();
        self.probe_many(plan).await;
    }

    pub async fn candidate_order(&self) -> Vec<SocketAddr> {
        let mut inner = self.inner.lock().await;
        let now = Instant::now();
        let all_green: Vec<_> = inner
            .entries
            .iter()
            .filter(|entry| entry.state == BackendState::Green)
            .map(|entry| entry.addr)
            .collect();
        if all_green.is_empty() {
            return Vec::new();
        }

        // T2S-style runtime cooldown: temporarily prefer other verified GREEN
        // backends after a suspicious runtime error. If every GREEN backend is
        // cooling down (especially the single-backend case), fall back to the
        // full GREEN set instead of manufacturing an outage.
        let ready_green: Vec<_> = inner
            .entries
            .iter()
            .filter(|entry| entry.state == BackendState::Green && entry.runtime_cooldown_until <= now)
            .map(|entry| entry.addr)
            .collect();
        let healthy = if ready_green.is_empty() { all_green } else { ready_green };
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
        inner.entries[index].selected_connections = inner.entries[index]
            .selected_connections
            .saturating_add(1);
    }

    /// A successful DNSCrypt SOCKS CONNECT is useful runtime evidence, but it is
    /// not allowed to promote YELLOW/RED to GREEN. T2S uses a strict Full probe
    /// as the authority for confirmed Internet health, and D2S now does the same.
    pub async fn mark_runtime_success(&self, addr: SocketAddr, latency: Duration) {
        let mut wake = false;
        {
            let mut inner = self.inner.lock().await;
            let Some(index) = inner.index.get(&addr).copied() else { return; };
            let entry = &mut inner.entries[index];
            entry.runtime_failure_streak = 0;
            entry.last_runtime_failure = None;
            entry.last_error = None;
            entry.last_check_unix = Some(unix_now());
            entry.last_success_unix = Some(unix_now());
            entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
            entry.successful_connections = entry.successful_connections.saturating_add(1);
            if entry.state == BackendState::Green {
                entry.next_probe = entry.next_probe.max(Instant::now() + Duration::from_secs(1));
            } else {
                // A race can finish a real connection just after a health probe
                // downgraded the backend. Do not self-promote; request proof.
                let now = Instant::now();
                if now >= entry.next_forced_probe_after {
                    entry.force_full_probe = true;
                    entry.next_probe = now;
                    entry.next_forced_probe_after = now + self.config.runtime_cooldown();
                    wake = true;
                }
            }
        }
        if wake {
            self.health_wake.notify_one();
        }
    }

    /// Runtime failures are signals, not final health verdicts. Target/path
    /// failures only trigger a Full recheck. Soft/hard backend failures use T2S-
    /// style hysteresis, while the forced Full probe remains the source of truth.
    pub async fn mark_runtime_failure(
        &self,
        addr: SocketAddr,
        class: RuntimeFailureClass,
        error: &str,
    ) {
        let mut state_change = None;
        let mut wake_health = false;
        {
            let mut inner = self.inner.lock().await;
            let Some(index) = inner.index.get(&addr).copied() else { return; };
            let entry = &mut inner.entries[index];
            let old = entry.state;
            entry.failed_connections = entry.failed_connections.saturating_add(1);
            entry.last_error = Some(error.to_string());
            entry.last_check_unix = Some(unix_now());
            let now = Instant::now();
            let wake = now >= entry.next_forced_probe_after;
            if wake {
                wake_health = true;
                entry.force_full_probe = true;
                entry.next_probe = now;
                entry.next_forced_probe_after = now + self.config.runtime_cooldown();
            }

            if class != RuntimeFailureClass::TargetPath {
                // Mirror T2S selection cooldowns without removing the backend
                // from GREEN. Multiple backends will prefer a clean peer; a
                // single GREEN backend remains selectable by candidate_order().
                let cooldown = match class {
                    RuntimeFailureClass::Soft => Duration::from_secs(3),
                    RuntimeFailureClass::Hard => Duration::from_secs(6),
                    RuntimeFailureClass::TargetPath => Duration::ZERO,
                };
                entry.runtime_cooldown_until = now + cooldown;
                let now = Instant::now();
                if entry
                    .last_runtime_failure
                    .map(|last| now.duration_since(last) > RUNTIME_FAILURE_WINDOW)
                    .unwrap_or(true)
                {
                    entry.runtime_failure_streak = 0;
                }
                entry.runtime_failure_streak = entry.runtime_failure_streak.saturating_add(1);
                entry.last_runtime_failure = Some(now);

                let hard_threshold = self.config.failure_threshold.max(1);
                let soft_threshold = hard_threshold.saturating_mul(2).saturating_add(2);
                let threshold = match class {
                    RuntimeFailureClass::Soft => soft_threshold,
                    RuntimeFailureClass::Hard => hard_threshold,
                    RuntimeFailureClass::TargetPath => unreachable!(),
                };

                if entry.runtime_failure_streak >= threshold {
                    entry.state = match class {
                        RuntimeFailureClass::Soft => BackendState::Yellow,
                        RuntimeFailureClass::Hard => BackendState::Red,
                        RuntimeFailureClass::TargetPath => entry.state,
                    };
                }
            }

            if entry.state != old {
                state_change = Some((old, entry.state, entry.runtime_failure_streak));
            }
            refresh_no_green_epoch(&mut inner);
        }

        if wake_health {
            self.health_wake.notify_one();
        }
        if let Some((old, new, streak)) = state_change {
            warn!(
                backend = %addr,
                old_state = ?old,
                new_state = ?new,
                runtime_failures = streak,
                error = %error,
                "runtime failure crossed hysteresis threshold; Full health recheck requested"
            );
        } else {
            debug!(backend = %addr, class = ?class, error = %error, "runtime failure marked backend suspect; Full health recheck requested");
        }
    }

    /// Relay failures happen after SOCKS CONNECT has already succeeded. They are
    /// therefore treated exactly like T2S suspect data-plane events: keep the
    /// current state and immediately ask a strict Full probe to arbitrate it.
    pub async fn mark_relay_suspect(&self, addr: SocketAddr, error: &str) {
        let mut wake = false;
        {
            let mut inner = self.inner.lock().await;
            let Some(index) = inner.index.get(&addr).copied() else { return; };
            let entry = &mut inner.entries[index];
            entry.last_error = Some(format!("relay suspect: {error}"));
            entry.last_check_unix = Some(unix_now());
            let now = Instant::now();
            if now >= entry.next_forced_probe_after {
                entry.force_full_probe = true;
                entry.next_probe = now;
                entry.next_forced_probe_after = now + self.config.runtime_cooldown();
                wake = true;
            }
        }
        if wake {
            self.health_wake.notify_one();
            debug!(backend = %addr, error = %error, "relay error triggered Full backend recheck");
        }
    }

    pub async fn request_full_probe(&self, addr: SocketAddr, reason: &str) {
        let mut wake = false;
        {
            let mut inner = self.inner.lock().await;
            let Some(index) = inner.index.get(&addr).copied() else { return; };
            let entry = &mut inner.entries[index];
            entry.last_error = Some(reason.to_string());
            let now = Instant::now();
            if now >= entry.next_forced_probe_after {
                entry.force_full_probe = true;
                entry.next_probe = now;
                entry.next_forced_probe_after = now + self.config.runtime_cooldown();
                wake = true;
            }
        }
        if wake {
            self.health_wake.notify_one();
        }
    }

    pub async fn due_probes(&self) -> Vec<(SocketAddr, ProbeMode)> {
        let mut inner = self.inner.lock().await;
        refresh_no_green_epoch(&mut inner);
        let now = Instant::now();
        let any_green = inner.entries.iter().any(|entry| entry.state == BackendState::Green);
        let recovery_age = inner.no_green_since.map(|since| now.duration_since(since));
        let mut due = Vec::new();

        for entry in &mut inner.entries {
            if entry.probe_in_flight || (entry.next_probe > now && !entry.force_full_probe) {
                continue;
            }

            let full_due = entry
                .last_full_probe_unix
                .map(|last| unix_now().saturating_sub(last) >= FULL_PROBE_INTERVAL.as_secs())
                .unwrap_or(true);
            let forced_full = entry.force_full_probe;
            let internet_probe_allowed = now >= entry.next_internet_probe_after;
            let mode = if forced_full
                || (!any_green && entry.state != BackendState::Green)
                || (entry.state != BackendState::Green && internet_probe_allowed)
                || (full_due && internet_probe_allowed)
            {
                ProbeMode::Full
            } else {
                // SOCKS reachability can still be checked cheaply while a failed
                // Internet probe is in T2S-style backoff. Forced suspect checks
                // and the no-GREEN recovery ladder deliberately bypass backoff.
                ProbeMode::Light
            };
            entry.force_full_probe = false;

            entry.next_probe = if entry.state == BackendState::Green {
                now + self.config.healthy_probe_interval()
            } else if !any_green {
                now + no_green_recovery_interval(recovery_age.unwrap_or_default())
            } else {
                now + self.config.recovery_probe_interval()
            };
            due.push((entry.addr, mode));
        }
        due
    }

    pub async fn probe_many(&self, plan: Vec<(SocketAddr, ProbeMode)>) {
        let mut set = JoinSet::new();
        for (addr, mode) in plan {
            let pool = self.clone();
            set.spawn(async move { pool.probe_one(addr, mode).await });
        }
        while let Some(result) = set.join_next().await {
            if let Err(error) = result {
                warn!(%error, "health probe task failed");
            }
        }
    }

    pub async fn probe_one(&self, addr: SocketAddr, mode: ProbeMode) {
        let Some(revision) = self.begin_probe(addr).await else {
            debug!(backend = %addr, "health probe already in flight; skipping duplicate");
            return;
        };

        let stage1_started = Instant::now();
        let stage1 = tokio::time::timeout(
            self.config.probe_timeout(),
            connect_to_socks5_server(
                addr,
                self.config.connect_timeout(),
                self.config.upstream_handshake_timeout(),
                self.config.tcp_nodelay,
            ),
        )
        .await;

        let socks_latency = match stage1 {
            Ok(Ok(stream)) => {
                drop(stream);
                stage1_started.elapsed()
            }
            Ok(Err(error)) => {
                self.finish_probe(
                    addr,
                    revision,
                    mode,
                    ProbeOutcome::SocksUnavailable(error.to_string()),
                )
                .await;
                return;
            }
            Err(_) => {
                self.finish_probe(
                    addr,
                    revision,
                    mode,
                    ProbeOutcome::SocksUnavailable(format!(
                        "SOCKS reachability probe exceeded {} ms",
                        self.config.probe_timeout_ms
                    )),
                )
                .await;
                return;
            }
        };

        if mode == ProbeMode::Light {
            self.finish_probe(
                addr,
                revision,
                mode,
                ProbeOutcome::LightReachable { latency: socks_latency },
            )
            .await;
            return;
        }

        if self.probe_targets.is_empty() {
            self.finish_probe(
                addr,
                revision,
                mode,
                ProbeOutcome::InternetUnavailable {
                    socks_latency,
                    error: "no TLS Internet probe target configured".to_string(),
                },
            )
            .await;
            return;
        }

        // Unlike generic T2S, D2S already exposes a small ordered probe target
        // list. Keep the strict T2S data-plane proof, but accept the first target
        // that actually returns TLS data. This avoids a false YELLOW when a
        // mobile operator blocks one public probe endpoint but the SOCKS route
        // itself still has working Internet access.
        let mut failures = Vec::new();
        for target in self.probe_targets.iter().cloned() {
            let attempt_started = Instant::now();
            let connect = tokio::time::timeout(
                self.config.probe_timeout(),
                connect_via_socks5(
                    addr,
                    &target,
                    self.config.connect_timeout(),
                    self.config.upstream_handshake_timeout(),
                    self.config.tcp_nodelay,
                ),
            )
            .await;

            let mut stream = match connect {
                Ok(Ok(stream)) => stream,
                Ok(Err(error)) => {
                    failures.push(format!("{target}: {error}"));
                    continue;
                }
                Err(_) => {
                    failures.push(format!(
                        "{target}: Internet CONNECT probe exceeded {} ms",
                        self.config.probe_timeout_ms
                    ));
                    continue;
                }
            };

            if verify_tls_data_plane(&mut stream, &target, self.config.probe_timeout()).await {
                self.finish_probe(
                    addr,
                    revision,
                    mode,
                    ProbeOutcome::InternetVerified { latency: attempt_started.elapsed() },
                )
                .await;
                return;
            }
            failures.push(format!("{target}: TLS data-plane probe received no response"));
        }

        self.finish_probe(
            addr,
            revision,
            mode,
            ProbeOutcome::InternetUnavailable {
                socks_latency,
                error: failures.join("; "),
            },
        )
        .await;
    }

    async fn finish_probe(
        &self,
        addr: SocketAddr,
        expected_revision: u64,
        mode: ProbeMode,
        outcome: ProbeOutcome,
    ) {
        let mut transition = None;
        let mut log_success = None;
        {
            let mut inner = self.inner.lock().await;
            let Some(index) = inner.index.get(&addr).copied() else { return; };
            let entry = &mut inner.entries[index];
            entry.probe_in_flight = false;
            if entry.revision != expected_revision {
                debug!(backend = %addr, expected_revision, current_revision = entry.revision, "discarding stale health probe");
                return;
            }

            let old = entry.state;
            let now_unix = unix_now();
            entry.last_check_unix = Some(now_unix);
            entry.runtime_failure_streak = 0;
            entry.last_runtime_failure = None;
            if mode == ProbeMode::Full {
                entry.last_full_probe_unix = Some(now_unix);
            }

            match outcome {
                ProbeOutcome::SocksUnavailable(error) => {
                    entry.state = BackendState::Red;
                    entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
                    entry.last_error = Some(error);
                    entry.last_latency_ms = None;
                    entry.internet_latency_ms = None;
                    // The local SOCKS itself is down; Internet-probe backoff is
                    // irrelevant until Stage 1 becomes reachable again.
                    entry.internet_probe_fail_streak = 0;
                    entry.next_internet_probe_after = Instant::now();
                }
                ProbeOutcome::LightReachable { latency } => {
                    entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
                    if old == BackendState::Green {
                        entry.state = BackendState::Green;
                        entry.consecutive_failures = 0;
                        entry.last_error = None;
                    } else {
                        // A light SOCKS check can prove reachability but cannot
                        // prove Internet for an unverified local backend.
                        entry.state = BackendState::Yellow;
                    }
                }
                ProbeOutcome::InternetUnavailable { socks_latency, error } => {
                    entry.state = BackendState::Yellow;
                    entry.consecutive_failures = entry.consecutive_failures.saturating_add(1);
                    entry.last_error = Some(error);
                    entry.last_latency_ms = Some(socks_latency.as_secs_f64() * 1000.0);
                    entry.internet_latency_ms = None;
                    entry.internet_probe_fail_streak = entry.internet_probe_fail_streak.saturating_add(1);
                    entry.next_internet_probe_after =
                        Instant::now() + internet_probe_backoff(entry.internet_probe_fail_streak);
                }
                ProbeOutcome::InternetVerified { latency } => {
                    entry.state = BackendState::Green;
                    entry.consecutive_failures = 0;
                    entry.internet_probe_fail_streak = 0;
                    entry.next_internet_probe_after = Instant::now();
                    entry.last_error = None;
                    entry.last_check_unix = Some(now_unix);
                    entry.last_success_unix = Some(now_unix);
                    entry.last_latency_ms = Some(latency.as_secs_f64() * 1000.0);
                    entry.internet_latency_ms = entry.last_latency_ms;
                    log_success = entry.last_latency_ms;
                }
            }

            entry.revision = entry.revision.wrapping_add(1);
            refresh_no_green_epoch(&mut inner);
            let any_green = inner.entries.iter().any(|item| item.state == BackendState::Green);
            let recovery_age = inner.no_green_since.map(|since| Instant::now().duration_since(since));
            let entry = &mut inner.entries[index];
            entry.next_probe = if entry.state == BackendState::Green {
                Instant::now() + self.config.healthy_probe_interval()
            } else if !any_green {
                Instant::now() + no_green_recovery_interval(recovery_age.unwrap_or_default())
            } else {
                Instant::now() + self.config.recovery_probe_interval()
            };

            if old != entry.state {
                transition = Some((old, entry.state, entry.last_error.clone(), entry.consecutive_failures));
            }
        }

        if let Some((old, new, error, failures)) = transition {
            match new {
                BackendState::Green => info!(backend = %addr, old_state = ?old, latency_ms = ?log_success, "backend is GREEN after Full Internet probe"),
                BackendState::Yellow => warn!(backend = %addr, old_state = ?old, new_state = ?new, failures, error = ?error, "SOCKS reachable but Internet data-plane is not confirmed"),
                BackendState::Red => warn!(backend = %addr, old_state = ?old, new_state = ?new, failures, error = ?error, "SOCKS backend itself is unreachable"),
                BackendState::Unknown => {}
            }
        } else if mode == ProbeMode::Full {
            debug!(backend = %addr, "Full backend health probe completed without state change");
        }
    }

    async fn begin_probe(&self, addr: SocketAddr) -> Option<u64> {
        let mut inner = self.inner.lock().await;
        let index = inner.index.get(&addr).copied()?;
        let entry = &mut inner.entries[index];
        if entry.probe_in_flight {
            return None;
        }
        entry.probe_in_flight = true;
        Some(entry.revision)
    }

    pub async fn addresses(&self) -> Vec<SocketAddr> {
        let inner = self.inner.lock().await;
        inner.entries.iter().map(|entry| entry.addr).collect()
    }

    pub async fn any_green(&self) -> bool {
        let inner = self.inner.lock().await;
        inner.entries.iter().any(|entry| entry.state == BackendState::Green)
    }

    pub async fn wait_for_health_wake(&self) {
        self.health_wake.notified().await;
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
                runtime_failure_streak: entry.runtime_failure_streak,
                last_error: entry.last_error.clone(),
                last_check_unix: entry.last_check_unix,
                last_success_unix: entry.last_success_unix,
                last_full_probe_unix: entry.last_full_probe_unix,
                latency_ms: entry.last_latency_ms,
                internet_latency_ms: entry.internet_latency_ms,
                selected_connections: entry.selected_connections,
                successful_connections: entry.successful_connections,
                failed_connections: entry.failed_connections,
            })
            .collect()
    }
}

fn refresh_no_green_epoch(inner: &mut PoolInner) {
    let any_green = inner.entries.iter().any(|entry| entry.state == BackendState::Green);
    if any_green || inner.entries.is_empty() {
        inner.no_green_since = None;
    } else if inner.no_green_since.is_none() {
        inner.no_green_since = Some(Instant::now());
    }
}

fn no_green_recovery_interval(age: Duration) -> Duration {
    if age < Duration::from_secs(30) {
        NO_GREEN_FAST_RECOVERY
    } else if age < Duration::from_secs(90) {
        NO_GREEN_MEDIUM_RECOVERY
    } else {
        NO_GREEN_SLOW_RECOVERY
    }
}

fn internet_probe_backoff(streak: u8) -> Duration {
    Duration::from_secs(match streak {
        0 | 1 => 30,
        2 => 60,
        3 => 120,
        4 => 300,
        5 => 600,
        _ => 900,
    })
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

    #[test]
    fn no_green_recovery_ladder_matches_t2s_shape() {
        assert_eq!(no_green_recovery_interval(Duration::from_secs(0)), Duration::from_secs(2));
        assert_eq!(no_green_recovery_interval(Duration::from_secs(29)), Duration::from_secs(2));
        assert_eq!(no_green_recovery_interval(Duration::from_secs(30)), Duration::from_secs(5));
        assert_eq!(no_green_recovery_interval(Duration::from_secs(89)), Duration::from_secs(5));
        assert_eq!(no_green_recovery_interval(Duration::from_secs(90)), Duration::from_secs(15));
        assert_eq!(internet_probe_backoff(1), Duration::from_secs(30));
        assert_eq!(internet_probe_backoff(2), Duration::from_secs(60));
        assert_eq!(internet_probe_backoff(3), Duration::from_secs(120));
        assert_eq!(internet_probe_backoff(4), Duration::from_secs(300));
        assert_eq!(internet_probe_backoff(5), Duration::from_secs(600));
        assert_eq!(internet_probe_backoff(6), Duration::from_secs(900));
    }

    #[tokio::test]
    async fn runtime_target_failure_requests_full_probe_without_immediate_eviction() {
        let backend: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![backend])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            inner.entries[0].state = BackendState::Green;
            inner.entries[0].force_full_probe = false;
            inner.entries[0].next_probe = Instant::now() + Duration::from_secs(60);
        }
        pool.mark_runtime_failure(
            backend,
            RuntimeFailureClass::TargetPath,
            "SOCKS5 CONNECT failed with reply code 0x04",
        )
        .await;
        let snapshot = pool.snapshots().await.into_iter().next().unwrap();
        assert_eq!(snapshot.state, BackendState::Green);
        let plan = pool.due_probes().await;
        assert_eq!(plan, vec![(backend, ProbeMode::Full)]);
    }

    #[tokio::test]
    async fn runtime_success_does_not_promote_unverified_backend() {
        let backend: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![backend])).unwrap();
        pool.mark_runtime_success(backend, Duration::from_millis(10)).await;
        let snapshot = pool.snapshots().await.into_iter().next().unwrap();
        assert_eq!(snapshot.state, BackendState::Unknown);
    }

    #[tokio::test]
    async fn runtime_cooldown_prefers_other_green_but_never_hides_the_only_green() {
        let first: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let second: SocketAddr = "127.0.0.1:2084".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![first, second])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            for entry in &mut inner.entries {
                entry.state = BackendState::Green;
                entry.force_full_probe = false;
            }
        }
        pool.mark_runtime_failure(first, RuntimeFailureClass::Soft, "handshake timeout").await;
        assert_eq!(pool.candidate_order().await, vec![second]);

        {
            let mut inner = pool.inner.lock().await;
            inner.entries[1].state = BackendState::Yellow;
        }
        assert_eq!(pool.candidate_order().await, vec![first]);
    }
}
