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

// Runtime selection is intentionally separate from health. GREEN means that a
// strict Full probe has proved the backend usable; WARM means that recent real
// DNSCrypt traffic also proved it fast. Keep the hot band deliberately broad so
// multiple good backends share load instead of pinning everything to one proxy.
const RUNTIME_EWMA_ALPHA: f64 = 0.25;
const WARM_RUNTIME_TTL: Duration = Duration::from_secs(120);
const HOT_LATENCY_MULTIPLIER: f64 = 2.0;
const HOT_LATENCY_SLACK_MS: f64 = 100.0;
const COLD_EXPLORATION_EVERY: u64 = 32;

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
    runtime_latency_ewma_ms: Option<f64>,
    last_runtime_success: Option<Instant>,
    last_preferred_pick_seq: u64,
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
    selection_seq: u64,
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
                runtime_latency_ewma_ms: None,
                last_runtime_success: None,
                last_preferred_pick_seq: 0,
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
                selection_seq: 0,
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

        let all_green: Vec<usize> = inner
            .entries
            .iter()
            .enumerate()
            .filter(|(_, entry)| entry.state == BackendState::Green)
            .map(|(index, _)| index)
            .collect();
        if all_green.is_empty() {
            return Vec::new();
        }

        // Runtime failures temporarily remove a backend from the hot path. If
        // every GREEN backend is cooling down, keep the old single-backend-safe
        // behavior and use the complete GREEN set rather than invent an outage.
        let ready_green: Vec<usize> = all_green
            .iter()
            .copied()
            .filter(|&index| inner.entries[index].runtime_cooldown_until <= now)
            .collect();
        let eligible = if ready_green.is_empty() { all_green } else { ready_green };

        inner.selection_seq = inner.selection_seq.wrapping_add(1);
        let selection_seq = inner.selection_seq;

        let mut warm: Vec<usize> = eligible
            .iter()
            .copied()
            .filter(|&index| {
                let entry = &inner.entries[index];
                entry.runtime_latency_ewma_ms.is_some()
                    && entry
                        .last_runtime_success
                        .map(|last| now.duration_since(last) <= WARM_RUNTIME_TTL)
                        .unwrap_or(false)
            })
            .collect();

        // A recovered/new GREEN backend must not immediately steal normal
        // traffic just because its health probe passed. Give cold backends a
        // sparse real request so they can prove current runtime latency and join
        // the warm pool without making every request pay the discovery cost.
        let mut cold: Vec<usize> = eligible
            .iter()
            .copied()
            .filter(|index| !warm.contains(index))
            .collect();

        let mut unseen_cold: Vec<usize> = cold
            .iter()
            .copied()
            .filter(|&index| inner.entries[index].last_preferred_pick_seq == 0)
            .collect();
        unseen_cold.sort_unstable();

        let explore_cold = unseen_cold.is_empty()
            && !cold.is_empty()
            && !warm.is_empty()
            && selection_seq % COLD_EXPLORATION_EVERY == 0;

        let preferred = if !unseen_cold.is_empty() {
            // Bootstrap every verified GREEN backend exactly once before the
            // learned warm pool is allowed to dominate selection. This avoids
            // the first successful backend monopolising traffic before peers
            // have any real runtime sample at all.
            unseen_cold[0]
        } else if explore_cold {
            cold.sort_by_key(|&index| inner.entries[index].last_preferred_pick_seq);
            cold[0]
        } else if warm.is_empty() {
            // Bootstrap: until real traffic has measured anything, distribute
            // first attempts fairly across all verified GREEN backends.
            let mut bootstrap = eligible.clone();
            bootstrap.sort_by_key(|&index| inner.entries[index].last_preferred_pick_seq);
            bootstrap[0]
        } else {
            let best_latency = warm
                .iter()
                .filter_map(|&index| inner.entries[index].runtime_latency_ewma_ms)
                .fold(f64::INFINITY, f64::min);
            let hot_limit = (best_latency * HOT_LATENCY_MULTIPLIER)
                .max(best_latency + HOT_LATENCY_SLACK_MS);

            warm.retain(|&index| {
                inner.entries[index]
                    .runtime_latency_ewma_ms
                    .map(|latency| latency <= hot_limit)
                    .unwrap_or(false)
            });
            // Balance only inside the genuinely fast band. Least-recently-picked
            // wins; latency breaks ties during bootstrap/re-entry.
            warm.sort_by(|&a, &b| {
                inner.entries[a]
                    .last_preferred_pick_seq
                    .cmp(&inner.entries[b].last_preferred_pick_seq)
                    .then_with(|| {
                        inner.entries[a]
                            .runtime_latency_ewma_ms
                            .unwrap_or(f64::INFINITY)
                            .total_cmp(
                                &inner.entries[b]
                                    .runtime_latency_ewma_ms
                                    .unwrap_or(f64::INFINITY),
                            )
                    })
            });
            warm[0]
        };

        inner.entries[preferred].last_preferred_pick_seq = selection_seq;

        // The router still needs fallback candidates for this same request. Put
        // the preferred backend first, then other warm/fast peers, and only then
        // cold GREEN candidates. A slow or newly recovered backend therefore does
        // not delay normal traffic unless it is the sparse exploration request.
        let mut rest: Vec<usize> = eligible
            .iter()
            .copied()
            .filter(|&index| index != preferred)
            .collect();
        rest.sort_by(|&a, &b| {
            let a_warm = inner.entries[a]
                .last_runtime_success
                .map(|last| now.duration_since(last) <= WARM_RUNTIME_TTL)
                .unwrap_or(false)
                && inner.entries[a].runtime_latency_ewma_ms.is_some();
            let b_warm = inner.entries[b]
                .last_runtime_success
                .map(|last| now.duration_since(last) <= WARM_RUNTIME_TTL)
                .unwrap_or(false)
                && inner.entries[b].runtime_latency_ewma_ms.is_some();
            b_warm.cmp(&a_warm).then_with(|| {
                inner.entries[a]
                    .runtime_latency_ewma_ms
                    .unwrap_or(f64::INFINITY)
                    .total_cmp(
                        &inner.entries[b]
                            .runtime_latency_ewma_ms
                            .unwrap_or(f64::INFINITY),
                    )
            })
        });

        std::iter::once(preferred)
            .chain(rest)
            .map(|index| inner.entries[index].addr)
            .collect()
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
            let runtime_latency_ms = latency.as_secs_f64() * 1000.0;
            entry.last_latency_ms = Some(runtime_latency_ms);
            entry.runtime_latency_ewma_ms = Some(match entry.runtime_latency_ewma_ms {
                Some(previous) => {
                    previous * (1.0 - RUNTIME_EWMA_ALPHA)
                        + runtime_latency_ms * RUNTIME_EWMA_ALPHA
                }
                None => runtime_latency_ms,
            });
            entry.last_runtime_success = Some(Instant::now());
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
                // A transport/backend failure must immediately remove stale
                // runtime warmth so this backend cannot return to the preferred
                // set as soon as the short cooldown expires. Full health remains
                // the authority for GREEN/YELLOW/RED; later sparse exploration
                // can warm it again after recovery.
                entry.runtime_latency_ewma_ms = None;
                entry.last_runtime_success = None;

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
                    entry.runtime_latency_ewma_ms = None;
                    entry.last_runtime_success = None;
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
                    entry.runtime_latency_ewma_ms = None;
                    entry.last_runtime_success = None;
                    entry.internet_probe_fail_streak = entry.internet_probe_fail_streak.saturating_add(1);
                    entry.next_internet_probe_after =
                        Instant::now() + internet_probe_backoff(entry.internet_probe_fail_streak);
                }
                ProbeOutcome::InternetVerified { latency } => {
                    entry.state = BackendState::Green;
                    if old != BackendState::Green {
                        entry.runtime_latency_ewma_ms = None;
                        entry.last_runtime_success = None;
                    }
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
                runtime_latency_ewma_ms: entry.runtime_latency_ewma_ms,
                runtime_warm: entry.state == BackendState::Green
                    && entry.runtime_cooldown_until <= Instant::now()
                    && entry.runtime_latency_ewma_ms.is_some()
                    && entry
                        .last_runtime_success
                        .map(|last| Instant::now().duration_since(last) <= WARM_RUNTIME_TTL)
                        .unwrap_or(false),
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
    async fn bootstrap_samples_each_verified_green_before_warm_pool_dominates() {
        let a: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let b: SocketAddr = "127.0.0.1:11591".parse().unwrap();
        let c: SocketAddr = "127.0.0.1:11592".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![a, b, c])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            for entry in &mut inner.entries {
                entry.state = BackendState::Green;
                entry.force_full_probe = false;
            }
        }

        assert_eq!(pool.candidate_order().await[0], a);
        pool.mark_runtime_success(a, Duration::from_millis(30)).await;
        assert_eq!(pool.candidate_order().await[0], b);
        pool.mark_runtime_success(b, Duration::from_millis(40)).await;
        assert_eq!(pool.candidate_order().await[0], c);
    }

    #[tokio::test]
    async fn warm_fast_backends_are_balanced_and_slow_warm_backend_is_not_preferred() {
        let a: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let b: SocketAddr = "127.0.0.1:11591".parse().unwrap();
        let c: SocketAddr = "127.0.0.1:11592".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![a, b, c])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            let now = Instant::now();
            for entry in &mut inner.entries {
                entry.state = BackendState::Green;
                entry.force_full_probe = false;
                entry.last_runtime_success = Some(now);
            }
            inner.entries[0].runtime_latency_ewma_ms = Some(40.0);
            inner.entries[1].runtime_latency_ewma_ms = Some(75.0);
            inner.entries[2].runtime_latency_ewma_ms = Some(450.0);
        }

        let first = pool.candidate_order().await[0];
        let second = pool.candidate_order().await[0];
        let third = pool.candidate_order().await[0];
        let fourth = pool.candidate_order().await[0];

        assert_eq!([first, second, third, fourth], [a, b, a, b]);
    }

    #[tokio::test]
    async fn cold_green_backend_is_only_sparse_exploration_while_warm_peer_exists() {
        let warm: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let recovered: SocketAddr = "127.0.0.1:11591".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![warm, recovered])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            let now = Instant::now();
            for entry in &mut inner.entries {
                entry.state = BackendState::Green;
                entry.force_full_probe = false;
            }
            inner.entries[0].runtime_latency_ewma_ms = Some(35.0);
            inner.entries[0].last_runtime_success = Some(now);
            // Simulate a backend that was sampled earlier, later lost warmth,
            // and has now recovered to GREEN via health probing.
            inner.entries[1].last_preferred_pick_seq = 7;
        }

        for _ in 0..(COLD_EXPLORATION_EVERY - 1) {
            assert_eq!(pool.candidate_order().await[0], warm);
        }
        assert_eq!(pool.candidate_order().await[0], recovered);
    }

    #[tokio::test]
    async fn runtime_backend_failure_removes_backend_from_warm_pool_immediately() {
        let first: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let second: SocketAddr = "127.0.0.1:11591".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![first, second])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            let now = Instant::now();
            for entry in &mut inner.entries {
                entry.state = BackendState::Green;
                entry.force_full_probe = false;
                entry.runtime_latency_ewma_ms = Some(30.0);
                entry.last_runtime_success = Some(now);
            }
        }

        pool.mark_runtime_failure(first, RuntimeFailureClass::Soft, "timeout").await;
        assert_eq!(pool.candidate_order().await[0], second);
        let inner = pool.inner.lock().await;
        assert!(inner.entries[0].runtime_latency_ewma_ms.is_none());
        assert!(inner.entries[0].last_runtime_success.is_none());
    }

    #[tokio::test]
    async fn runtime_latency_uses_ewma_instead_of_last_sample_only() {
        let backend: SocketAddr = "127.0.0.1:11590".parse().unwrap();
        let pool = BackendPool::new(test_config(vec![backend])).unwrap();
        {
            let mut inner = pool.inner.lock().await;
            inner.entries[0].state = BackendState::Green;
            inner.entries[0].force_full_probe = false;
        }

        pool.mark_runtime_success(backend, Duration::from_millis(40)).await;
        pool.mark_runtime_success(backend, Duration::from_millis(120)).await;

        let inner = pool.inner.lock().await;
        let ewma = inner.entries[0].runtime_latency_ewma_ms.unwrap();
        assert!((ewma - 60.0).abs() < 0.001);
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
