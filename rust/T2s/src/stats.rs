use crate::cli::Args;
use crate::socks5::TargetAddr;
use anyhow::{anyhow, Context, Result};
use parking_lot::Mutex;
use rand::RngCore;
use serde::Serialize;
use serde_json::Value;
use std::collections::{HashMap, VecDeque};
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use once_cell::sync::Lazy;
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Ingress {
    Internal,
    External,
}

#[derive(Default)]
pub struct PortStats {
    pub bytes_up: AtomicU64,
    pub bytes_down: AtomicU64,
}

#[derive(Default)]
pub struct Stats {
    pub bytes_up: AtomicU64,
    pub bytes_down: AtomicU64,
    pub errors: AtomicU64,
    pub socks_ok: AtomicU64,
    pub socks_fail: AtomicU64,
    pub policy_drop: AtomicU64,

    /// Traffic counters split by ingress port.
    pub internal: PortStats,
    pub external: PortStats,
}

impl Stats {
    pub fn add_up(&self, n: u64) { self.bytes_up.fetch_add(n, Ordering::Relaxed); }
    pub fn add_down(&self, n: u64) { self.bytes_down.fetch_add(n, Ordering::Relaxed); }

    pub fn add_up_ingress(&self, ingress: Ingress, n: u64) {
        match ingress {
            Ingress::Internal => { self.internal.bytes_up.fetch_add(n, Ordering::Relaxed); }
            Ingress::External => { self.external.bytes_up.fetch_add(n, Ordering::Relaxed); }
        }
    }
    pub fn add_down_ingress(&self, ingress: Ingress, n: u64) {
        match ingress {
            Ingress::Internal => { self.internal.bytes_down.fetch_add(n, Ordering::Relaxed); }
            Ingress::External => { self.external.bytes_down.fetch_add(n, Ordering::Relaxed); }
        }
    }

    pub fn inc_error(&self) { self.errors.fetch_add(1, Ordering::Relaxed); }
    pub fn inc_socks_ok(&self) { self.socks_ok.fetch_add(1, Ordering::Relaxed); }
    pub fn inc_socks_fail(&self) { self.socks_fail.fetch_add(1, Ordering::Relaxed); }
    pub fn inc_policy_drop(&self) -> u64 { self.policy_drop.fetch_add(1, Ordering::Relaxed) + 1 }
}

pub struct RuntimeConfig {
    /// 0 = unlimited
    pub download_limit_bps: AtomicU64,
    /// Connected Web UI clients (SSE/WS).
    pub ui_clients: std::sync::atomic::AtomicU64,

    /// Wakes UI-related loops when UI clients open/close.
    pub ui_wakeup: tokio::sync::Notify,
    /// Wakes backend-recovery waiters when backend health actually changes.
    pub backend_wakeup: tokio::sync::Notify,

    /// While this timestamp is in the future, direct fallback is temporarily blocked
    /// because recent direct attempts failed with timeouts or unreachable errors.
    pub direct_cooldown_until_ts: AtomicU64,

    /// Deduplicate forced health refreshes triggered by new connections.
    pub refresh_lock: tokio::sync::Mutex<()>,

    /// Last time we attempted a best-effort ICMP TTL sample for UI diagnostics.
    pub last_ttl_ping_ts: AtomicU64,

    /// Global throttle for forced recovery refreshes requested by hot-path connection handling.
    pub next_forced_refresh_after_ms: AtomicU64,

    /// Only one recovery waiter should actively drive forced refreshes at a time.
    pub recovery_waiter_active: AtomicU64,

    /// Coalesce UI wakeups.
    pub next_ui_wakeup_after_ms: AtomicU64,
    /// Coalesce backend-state wakeups.
    pub next_backend_wakeup_after_ms: AtomicU64,

    /// Recent accepted-connection timestamps used to detect small bursts when all backends are yellow.
    pub recent_conn_arrivals_ms: Mutex<VecDeque<u64>>,
    /// Throttle burst-triggered full Internet rechecks.
    pub next_burst_recheck_after_ms: AtomicU64,
    /// Only one burst-triggered backend recheck may run at a time.
    pub burst_recheck_active: AtomicU64,
    /// Only one accelerated recovery ladder may run at a time.
    pub burst_recovery_ladder_active: AtomicU64,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            download_limit_bps: AtomicU64::new(0),
            ui_clients: std::sync::atomic::AtomicU64::new(0),
            ui_wakeup: tokio::sync::Notify::new(),
            backend_wakeup: tokio::sync::Notify::new(),
            direct_cooldown_until_ts: AtomicU64::new(0),
            refresh_lock: tokio::sync::Mutex::new(()),
            last_ttl_ping_ts: AtomicU64::new(0),
            next_forced_refresh_after_ms: AtomicU64::new(0),
            recovery_waiter_active: AtomicU64::new(0),
            next_ui_wakeup_after_ms: AtomicU64::new(0),
            next_backend_wakeup_after_ms: AtomicU64::new(0),
            recent_conn_arrivals_ms: Mutex::new(VecDeque::with_capacity(16)),
            next_burst_recheck_after_ms: AtomicU64::new(0),
            burst_recheck_active: AtomicU64::new(0),
            burst_recovery_ladder_active: AtomicU64::new(0),
        }
    }
}

impl RuntimeConfig {
    fn notify_throttled(slot: &AtomicU64, notify: &tokio::sync::Notify, min_interval_ms: u64) {
        let min_interval_ms = min_interval_ms.max(1);
        let now = now_ms();
        loop {
            let next_allowed = slot.load(Ordering::Relaxed);
            if next_allowed > now {
                return;
            }
            let new_next = now.saturating_add(min_interval_ms);
            match slot.compare_exchange(
                next_allowed,
                new_next,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => {
                    notify.notify_waiters();
                    return;
                }
                Err(_) => continue,
            }
        }
    }

    pub fn ui_wake_throttled(&self, min_interval_ms: u64) {
        Self::notify_throttled(&self.next_ui_wakeup_after_ms, &self.ui_wakeup, min_interval_ms);
    }

    pub fn backend_wake(&self) {
        self.backend_wakeup.notify_waiters();
    }

    pub fn backend_wake_throttled(&self, min_interval_ms: u64) {
        Self::notify_throttled(&self.next_backend_wakeup_after_ms, &self.backend_wakeup, min_interval_ms);
    }

    pub fn direct_allowed(&self) -> bool {
        now_ts() >= self.direct_cooldown_until_ts.load(Ordering::Relaxed)
    }

    pub fn note_direct_failure(&self, seconds: u64) {
        self.direct_cooldown_until_ts.store(now_ts().saturating_add(seconds), Ordering::Relaxed);
    }

    pub fn clear_direct_cooldown(&self) {
        self.direct_cooldown_until_ts.store(0, Ordering::Relaxed);
    }

    pub fn try_begin_forced_refresh(&self, min_interval_ms: u64) -> bool {
        let now = now_ms();
        loop {
            let next_allowed = self.next_forced_refresh_after_ms.load(Ordering::Relaxed);
            if next_allowed > now {
                return false;
            }
            let new_next = now.saturating_add(min_interval_ms.max(1));
            match self.next_forced_refresh_after_ms.compare_exchange(
                next_allowed,
                new_next,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return true,
                Err(_) => continue,
            }
        }
    }

    pub fn try_enter_recovery_waiter(&self) -> bool {
        self.recovery_waiter_active
            .compare_exchange(0, 1, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    }

    pub fn leave_recovery_waiter(&self) {
        self.recovery_waiter_active.store(0, Ordering::Relaxed);
    }

    pub fn note_new_connection_spike(&self, threshold: usize, window: Duration) -> bool {
        let threshold = threshold.max(1);
        let now = now_ms();
        let cutoff = now.saturating_sub(window.as_millis() as u64);
        let mut q = self.recent_conn_arrivals_ms.lock();
        q.push_back(now);
        while let Some(front) = q.front().copied() {
            if front < cutoff {
                q.pop_front();
            } else {
                break;
            }
        }
        q.len() >= threshold
    }

    pub fn try_begin_burst_recheck(&self, min_interval_ms: u64) -> bool {
        let now = now_ms();
        loop {
            let next_allowed = self.next_burst_recheck_after_ms.load(Ordering::Relaxed);
            if next_allowed > now {
                return false;
            }
            let new_next = now.saturating_add(min_interval_ms.max(1));
            match self.next_burst_recheck_after_ms.compare_exchange(
                next_allowed,
                new_next,
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return true,
                Err(_) => continue,
            }
        }
    }

    pub fn try_enter_burst_recheck(&self) -> bool {
        self.burst_recheck_active
            .compare_exchange(0, 1, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    }

    pub fn leave_burst_recheck(&self) {
        self.burst_recheck_active.store(0, Ordering::Relaxed);
    }

    pub fn try_enter_burst_recovery_ladder(&self) -> bool {
        self.burst_recovery_ladder_active
            .compare_exchange(0, 1, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    }

    pub fn leave_burst_recovery_ladder(&self) {
        self.burst_recovery_ladder_active.store(0, Ordering::Relaxed);
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct PortStatsSnapshot {
    pub bytes_up: u64,
    pub bytes_down: u64,
}

#[derive(Clone, Debug, Serialize)]
pub struct StatsSnapshot {
    pub bytes_up: u64,
    pub bytes_down: u64,
    pub errors: u64,
    pub socks_ok: u64,
    pub socks_fail: u64,
    pub policy_drop: u64,

    pub internal: PortStatsSnapshot,
    pub external: PortStatsSnapshot,
}

impl Stats {
    pub fn snapshot(&self) -> StatsSnapshot {
        StatsSnapshot {
            bytes_up: self.bytes_up.load(Ordering::Relaxed),
            bytes_down: self.bytes_down.load(Ordering::Relaxed),
            errors: self.errors.load(Ordering::Relaxed),
            socks_ok: self.socks_ok.load(Ordering::Relaxed),
            socks_fail: self.socks_fail.load(Ordering::Relaxed),
            policy_drop: self.policy_drop.load(Ordering::Relaxed),

            internal: PortStatsSnapshot {
                bytes_up: self.internal.bytes_up.load(Ordering::Relaxed),
                bytes_down: self.internal.bytes_down.load(Ordering::Relaxed),
            },
            external: PortStatsSnapshot {
                bytes_up: self.external.bytes_up.load(Ordering::Relaxed),
                bytes_down: self.external.bytes_down.load(Ordering::Relaxed),
            },
        }
    }
}

#[derive(Clone, Debug)]
pub enum Target {
    SockAddr(SocketAddr),
    HostPort(String, u16),
}

impl Target {
    pub fn to_host_port_string(&self) -> (String, u16) {
        match self {
            Target::SockAddr(sa) => (sa.ip().to_string(), sa.port()),
            Target::HostPort(h, p) => (h.clone(), *p),
        }
    }

    pub async fn resolve_socket_addr(&self) -> Result<SocketAddr> {
        match self {
            Target::SockAddr(sa) => Ok(*sa),
            Target::HostPort(host, port) => {
                // Prefer IPv4 (many users disable IPv6 on-device).
                let addrs = (host.as_str(), *port)
                    .to_socket_addrs()
                    .context("resolve target")?;
                let mut first: Option<SocketAddr> = None;
                for sa in addrs {
                    if first.is_none() {
                        first = Some(sa);
                    }
                    if sa.is_ipv4() {
                        return Ok(sa);
                    }
                }
                first.ok_or_else(|| anyhow!("no addr for target"))
            }
        }
    }

    pub async fn to_socks_target(&self) -> Result<TargetAddr> {
        match self {
            Target::SockAddr(sa) => Ok(TargetAddr::Ip(*sa)),
            Target::HostPort(host, port) => Ok(TargetAddr::Domain(host.clone(), *port)),
        }
    }
}


#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum BackendState {
    Green,
    Yellow,
    Red,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RuntimeFailureClass {
    Soft,
    Hard,
    Auth,
}

#[derive(Clone, Debug, Serialize)]
pub struct BackendStatus {
    pub addr: String,
    pub state: BackendState,
    pub healthy: bool,
    pub last_check: u64,
    pub last_error: Option<String>,

    /// SOCKS5 RTT (connect + greeting), ms.
    pub socks_ping_ms: Option<f64>,
    /// A cheap "internet through backend" latency check (SOCKS CONNECT to 1.1.1.1:443), ms.
    pub internet_ping_ms: Option<f64>,
    /// Percent of recent RTT samples considered "stable" (0..100).
    pub rtt_integrity: Option<f64>,

    /// Internet TTL observed (best-effort). If min==max, show single value; otherwise show a range.
    pub ttl_min: Option<u32>,
    pub ttl_max: Option<u32>,

    /// Total bytes proxied through this backend (TCP, both directions).
    pub total_bytes: u64,
}

#[derive(Clone)]
pub struct SocksBackends {
    addrs: Vec<SocketAddr>,
    auth_override: Vec<Option<(String, String)>>,
    status: Vec<BackendStatus>,
    ttl_hist: Vec<VecDeque<u32>>,
    rtt_hist: Vec<VecDeque<f64>>,
    runtime_fail_streak: Vec<u8>,
    last_runtime_fail_ts: Vec<u64>,
    last_full_probe: Vec<u64>,
    last_green_ts: Vec<u64>,
    last_activity_ts: Vec<u64>,
    inflight_connects: Vec<u32>,
    backend_cooldown_until_ts: Vec<u64>,
    internet_probe_fail_streak: Vec<u8>,
    next_internet_probe_after_ts: Vec<u64>,
    rr: usize,
    check_rr: usize,
    active_check_rr: usize,
    audit_check_rr: usize,
    burst_check_rr: usize,
    check_cycle: u64,
}

impl SocksBackends {
    pub fn new(args: &Args) -> Result<Self> {
        let hosts = args.socks_hosts();
        let ports = args.socks_ports();
        if hosts.is_empty() || ports.is_empty() {
            return Err(anyhow!("socks-host/socks-port parse produced empty list"));
        }
        let mut addrs = vec![];
        for h in hosts {
            for p in &ports {
                let it = (h.as_str(), *p)
                    .to_socket_addrs()
                    .with_context(|| format!("resolve socks backend {}:{}", h, p))?;
                let mut first: Option<SocketAddr> = None;
                let mut chosen: Option<SocketAddr> = None;
                for sa in it {
                    if first.is_none() {
                        first = Some(sa);
                    }
                    if sa.is_ipv4() {
                        chosen = Some(sa);
                        break;
                    }
                }
                if chosen.is_none() {
                    chosen = first;
                }
                if let Some(sa) = chosen {
                    addrs.push(sa);
                }
            }
        }
        if addrs.is_empty() {
            return Err(anyhow!("no SOCKS backends after resolution"));
        }
        let now = now_ts();
        let auth_override = vec![None; addrs.len()];

        let status = addrs.iter().map(|sa| BackendStatus{
            addr: sa.to_string(),
            state: BackendState::Red,
            healthy: false,
            last_check: now,
            last_error: Some("not checked yet".to_string()),
            socks_ping_ms: None,
            internet_ping_ms: None,
            rtt_integrity: None,
            ttl_min: None,
            ttl_max: None,
            total_bytes: 0,
        }).collect();

        let ttl_hist = vec![VecDeque::new(); addrs.len()];
        let rtt_hist = vec![VecDeque::new(); addrs.len()];
        let runtime_fail_streak = vec![0; addrs.len()];
        let last_runtime_fail_ts = vec![0; addrs.len()];
        let last_full_probe = vec![0; addrs.len()];
        let last_green_ts = vec![0; addrs.len()];
        let last_activity_ts = vec![0; addrs.len()];
        let inflight_connects = vec![0; addrs.len()];
        let backend_cooldown_until_ts = vec![0; addrs.len()];
        let internet_probe_fail_streak = vec![0; addrs.len()];
        let next_internet_probe_after_ts = vec![0; addrs.len()];

        Ok(Self{
            addrs,
            auth_override,
            status,
            ttl_hist,
            rtt_hist,
            runtime_fail_streak,
            last_runtime_fail_ts,
            last_full_probe,
            last_green_ts,
            last_activity_ts,
            inflight_connects,
            backend_cooldown_until_ts,
            internet_probe_fail_streak,
            next_internet_probe_after_ts,
            rr: 0,
            check_rr: 0,
            active_check_rr: 0,
            audit_check_rr: 0,
            burst_check_rr: 0,
            check_cycle: 0,
        })
    }

    pub fn len(&self) -> usize { self.addrs.len() }
    pub fn addr_at(&self, idx: usize) -> Option<SocketAddr> { self.addrs.get(idx).copied() }

    pub fn effective_auth_at(&self, idx: usize, global_auth: Option<&(String, String)>) -> Option<(String, String)> {
        if let Some(Some((u, p))) = self.auth_override.get(idx) {
            return Some((u.clone(), p.clone()));
        }
        global_auth.map(|(u, p)| (u.clone(), p.clone()))
    }

    pub fn next_check_index(&mut self) -> Option<usize> {
        if self.addrs.is_empty() {
            return None;
        }
        let idx = self.check_rr % self.addrs.len();
        self.check_rr = (self.check_rr + 1) % self.addrs.len();
        Some(idx)
    }

    fn pick_from_bucket(indices: &[usize], cursor: &mut usize) -> Option<usize> {
        if indices.is_empty() {
            return None;
        }
        let idx = indices[*cursor % indices.len()];
        *cursor = (*cursor + 1) % indices.len();
        Some(idx)
    }

    fn touch_backend_activity_idx(&mut self, idx: usize, now: u64, force: bool) {
        if idx >= self.last_activity_ts.len() {
            return;
        }
        let prev = self.last_activity_ts[idx];
        if force || prev == 0 || now.saturating_sub(prev) >= BACKEND_ACTIVITY_TOUCH_SECS {
            self.last_activity_ts[idx] = now;
        }
    }

    pub fn note_backend_selected(&mut self, addr: SocketAddr) {
        if let Some(idx) = self.addrs.iter().position(|a| *a == addr) {
            self.touch_backend_activity_idx(idx, now_ts(), true);
        }
    }

    pub fn next_check_index_active_first(&mut self, active_window_secs: u64, audit_every: u64) -> Option<usize> {
        if self.addrs.is_empty() {
            return None;
        }

        let now = now_ts();
        let mut active = Vec::new();
        let mut audit = Vec::new();
        for idx in 0..self.addrs.len() {
            let last = self.last_activity_ts.get(idx).copied().unwrap_or(0);
            if last != 0 && now.saturating_sub(last) <= active_window_secs {
                active.push(idx);
            } else {
                audit.push(idx);
            }
        }

        self.check_cycle = self.check_cycle.wrapping_add(1);
        let should_audit = !audit.is_empty() && audit_every > 0 && self.check_cycle % audit_every == 0;

        if should_audit {
            if let Some(idx) = Self::pick_from_bucket(&audit, &mut self.audit_check_rr) {
                return Some(idx);
            }
        }

        if let Some(idx) = Self::pick_from_bucket(&active, &mut self.active_check_rr) {
            return Some(idx);
        }

        if let Some(idx) = Self::pick_from_bucket(&audit, &mut self.audit_check_rr) {
            return Some(idx);
        }

        self.next_check_index()
    }

    fn backend_in_cooldown_idx(&self, idx: usize, now: u64) -> bool {
        self.backend_cooldown_until_ts.get(idx).copied().unwrap_or(0) > now
    }

    fn set_backend_cooldown_idx(&mut self, idx: usize, seconds: u64) {
        if idx < self.backend_cooldown_until_ts.len() {
            self.backend_cooldown_until_ts[idx] = now_ts().saturating_add(seconds);
        }
    }

    fn internet_probe_due_idx(&self, idx: usize, now: u64) -> bool {
        self.next_internet_probe_after_ts.get(idx).copied().unwrap_or(0) <= now
    }

    fn note_internet_probe_result(&mut self, idx: usize, ok: bool) {
        if idx >= self.internet_probe_fail_streak.len() || idx >= self.next_internet_probe_after_ts.len() {
            return;
        }
        if ok {
            self.internet_probe_fail_streak[idx] = 0;
            self.next_internet_probe_after_ts[idx] = 0;
            return;
        }
        let streak = self.internet_probe_fail_streak[idx].saturating_add(1);
        self.internet_probe_fail_streak[idx] = streak;
        let backoff_secs = match streak {
            0 | 1 => 30,
            2 => 60,
            3 => 120,
            4 => 300,
            5 => 600,
            _ => 900,
        };
        self.next_internet_probe_after_ts[idx] = now_ts().saturating_add(backoff_secs);
    }

    pub fn state_at(&self, idx: usize) -> Option<BackendState> {
        if idx >= self.status.len() {
            return None;
        }
        if protector_mode_forced_green() {
            return Some(BackendState::Green);
        }
        self.status.get(idx).map(|s| s.state)
    }

    pub fn last_full_probe_at(&self, idx: usize) -> Option<u64> {
        self.last_full_probe.get(idx).copied()
    }

    pub fn any_yellow(&self) -> bool {
        self.status.iter().any(|s| s.state == BackendState::Yellow)
    }

    pub fn choose_burst_recheck_index(&mut self, global_auth: Option<&(String, String)>, ignore_normal_due: bool) -> Option<(usize, Option<(String, String)>)> {
        if protector_mode_forced_green() || self.any_green() {
            return None;
        }
        let now = now_ts();
        let mut candidates: Vec<usize> = self.status
            .iter()
            .enumerate()
            .filter(|(idx, s)| {
                s.state == BackendState::Yellow
                    && (ignore_normal_due || self.internet_probe_due_idx(*idx, now))
            })
            .map(|(idx, _)| idx)
            .collect();
        if candidates.is_empty() {
            return None;
        }

        let latest_green = candidates
            .iter()
            .filter_map(|idx| self.last_green_ts.get(*idx).copied())
            .max()
            .unwrap_or(0);
        if latest_green > 0 {
            candidates.retain(|idx| self.last_green_ts.get(*idx).copied().unwrap_or(0) == latest_green);
        }

        let best_rtt = candidates
            .iter()
            .filter_map(|idx| self.status.get(*idx).and_then(|s| s.internet_ping_ms.or(s.socks_ping_ms)))
            .min_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        if let Some(best_rtt) = best_rtt {
            candidates.retain(|idx| {
                self.status
                    .get(*idx)
                    .and_then(|s| s.internet_ping_ms.or(s.socks_ping_ms))
                    .map(|rtt| (rtt - best_rtt).abs() <= 0.5)
                    .unwrap_or(false)
            });
        }

        let idx = Self::pick_from_bucket(&candidates, &mut self.burst_check_rr)?;
        Some((idx, self.effective_auth_at(idx, global_auth)))
    }

    pub fn any_healthy(&self) -> bool {
        if protector_mode_forced_green() {
            return !self.addrs.is_empty();
        }
        self.status.iter().any(|s| s.healthy)
    }

    /// Returns true if there is at least one GREEN backend (responding + Internet OK).
    pub fn any_green(&self) -> bool {
        if protector_mode_forced_green() {
            return !self.addrs.is_empty();
        }
        self.any_healthy()
    }

    /// Returns true if there is at least one backend that responds (GREEN or YELLOW).
    pub fn any_reachable(&self) -> bool {
        self.status.iter().any(|s| s.state != BackendState::Red)
    }

    pub fn mark_backend_failed(&mut self, addr: SocketAddr, err: String) -> bool {
        if let Some(idx) = self.addrs.iter().position(|a| *a == addr) {
            if idx < self.status.len() {
                let before_any_healthy = self.any_healthy();
                let before_any_green = self.any_green();
                let now = now_ts();
                let class = Self::classify_runtime_failure(&err);
                if idx < self.last_runtime_fail_ts.len() && idx < self.runtime_fail_streak.len() {
                    let prev = self.last_runtime_fail_ts[idx];
                    if prev == 0 || now.saturating_sub(prev) > 8 {
                        self.runtime_fail_streak[idx] = 0;
                    }
                    self.runtime_fail_streak[idx] = self.runtime_fail_streak[idx].saturating_add(1);
                    self.last_runtime_fail_ts[idx] = now;
                }

                self.status[idx].last_check = now;
                self.status[idx].last_error = Some(err);

                match class {
                    RuntimeFailureClass::Soft => self.set_backend_cooldown_idx(idx, 3),
                    RuntimeFailureClass::Hard => self.set_backend_cooldown_idx(idx, 6),
                    RuntimeFailureClass::Auth => self.set_backend_cooldown_idx(idx, 15),
                }

                let streak = self.runtime_fail_streak.get(idx).copied().unwrap_or(1);
                let threshold = Self::runtime_fail_tolerate_threshold(self.status[idx].state, class);
                if streak < threshold {
                    return false;
                }

                match class {
                    RuntimeFailureClass::Soft => {
                        self.status[idx].state = BackendState::Yellow;
                        self.status[idx].healthy = false;
                        self.status[idx].internet_ping_ms = None;
                    }
                    RuntimeFailureClass::Hard | RuntimeFailureClass::Auth => {
                        self.status[idx].state = BackendState::Red;
                        self.status[idx].healthy = false;
                        self.status[idx].socks_ping_ms = None;
                        self.status[idx].internet_ping_ms = None;
                    }
                }

                let after_any_healthy = self.any_healthy();
                let after_any_green = self.any_green();
                return should_wake_backend_loops(before_any_healthy, after_any_healthy, before_any_green, after_any_green);
            }
        }
        false
    }


    pub fn snapshot(&self) -> Vec<BackendStatus> {
        if protector_mode_forced_green() {
            return self.status.iter().cloned().map(|mut s| {
                s.state = BackendState::Green;
                s.healthy = true;
                s
            }).collect();
        }
        self.status.clone()
    }

    pub fn add(&mut self, addr: SocketAddr, auth: Option<(String, String)>) {
        if self.addrs.iter().any(|a| *a == addr) { return; }
        self.addrs.push(addr);
        self.auth_override.push(auth);
        self.status.push(BackendStatus{
            addr: addr.to_string(),
            state: BackendState::Red,
            healthy: false,
            last_check: now_ts(),
            last_error: Some("added (not checked yet)".to_string()),
            socks_ping_ms: None,
            internet_ping_ms: None,
            rtt_integrity: None,
            ttl_min: None,
            ttl_max: None,
            total_bytes: 0,
        });
        self.ttl_hist.push(VecDeque::new());
        self.rtt_hist.push(VecDeque::new());
        self.runtime_fail_streak.push(0);
        self.last_runtime_fail_ts.push(0);
        self.last_full_probe.push(0);
        self.last_green_ts.push(0);
        self.last_activity_ts.push(0);
        self.inflight_connects.push(0);
        self.backend_cooldown_until_ts.push(0);
        self.internet_probe_fail_streak.push(0);
        self.next_internet_probe_after_ts.push(0);
    }

    pub fn remove(&mut self, addr: SocketAddr) {
        if let Some(pos) = self.addrs.iter().position(|a| *a == addr) {
            self.addrs.remove(pos);
            if pos < self.auth_override.len() { self.auth_override.remove(pos); }
            if pos < self.status.len() { self.status.remove(pos); }
            if pos < self.ttl_hist.len() { self.ttl_hist.remove(pos); }
            if pos < self.rtt_hist.len() { self.rtt_hist.remove(pos); }
            if pos < self.runtime_fail_streak.len() { self.runtime_fail_streak.remove(pos); }
            if pos < self.last_runtime_fail_ts.len() { self.last_runtime_fail_ts.remove(pos); }
            if pos < self.last_full_probe.len() { self.last_full_probe.remove(pos); }
            if pos < self.last_green_ts.len() { self.last_green_ts.remove(pos); }
            if pos < self.last_activity_ts.len() { self.last_activity_ts.remove(pos); }
            if pos < self.inflight_connects.len() { self.inflight_connects.remove(pos); }
            if pos < self.backend_cooldown_until_ts.len() { self.backend_cooldown_until_ts.remove(pos); }
            if pos < self.internet_probe_fail_streak.len() { self.internet_probe_fail_streak.remove(pos); }
            if pos < self.next_internet_probe_after_ts.len() { self.next_internet_probe_after_ts.remove(pos); }
            if self.rr >= self.addrs.len() { self.rr = 0; }
            if self.check_rr >= self.addrs.len() { self.check_rr = 0; }
            if self.active_check_rr >= self.addrs.len() { self.active_check_rr = 0; }
            if self.audit_check_rr >= self.addrs.len() { self.audit_check_rr = 0; }
            if self.burst_check_rr >= self.addrs.len() { self.burst_check_rr = 0; }
        }
    }
pub fn select_rr_with_auth(&mut self, global_auth: Option<&(String, String)>) -> Result<(usize, SocketAddr, Option<(String, String)>)> {
        let now = now_ts();
        let mut candidates: Vec<usize> = if protector_mode_forced_green() {
            (0..self.addrs.len()).collect()
        } else {
            self.status
                .iter()
                .enumerate()
                .filter(|(i, s)| s.healthy && !self.backend_in_cooldown_idx(*i, now))
                .map(|(i, _)| i)
                .collect()
        };
        if candidates.is_empty() {
            candidates = if protector_mode_forced_green() {
                (0..self.addrs.len()).collect()
            } else {
                self.status.iter().enumerate().filter(|(_, s)| s.healthy).map(|(i, _)| i).collect()
            };
        }
        if candidates.is_empty() {
            return Err(anyhow!("no GREEN backends"));
        }
        self.rr = (self.rr + 1) % candidates.len();
        let idx = candidates[self.rr];
        Ok((idx, self.addrs[idx], self.effective_auth_at(idx, global_auth)))
    }

    pub fn backend_addr_at(&self, idx: usize) -> Option<SocketAddr> {
        self.addrs.get(idx).copied()
    }

    pub fn try_acquire_connect_slot(&mut self, idx: usize, limit: u32) -> bool {
        if idx >= self.addrs.len() {
            return false;
        }
        let lim = limit.max(1);
        let cur = self.inflight_connects.get(idx).copied().unwrap_or(0);
        if cur >= lim {
            let now = now_ts();
            if idx < self.status.len() {
                self.status[idx].last_check = now;
                self.status[idx].last_error = Some(format!("connect queue saturated ({}/{})", cur, lim));
            }
            self.set_backend_cooldown_idx(idx, 2);
            return false;
        }
        if idx < self.inflight_connects.len() {
            self.inflight_connects[idx] = cur.saturating_add(1);
        }
        true
    }

    pub fn release_connect_slot(&mut self, idx: usize) {
        if idx < self.inflight_connects.len() && self.inflight_connects[idx] > 0 {
            self.inflight_connects[idx] -= 1;
        }
    }

    pub fn inflight_connects(&self, addr: SocketAddr) -> Option<u32> {
        self.addrs.iter().position(|a| *a == addr).and_then(|idx| self.inflight_connects.get(idx).copied())
    }

    fn classify_runtime_failure(err: &str) -> RuntimeFailureClass {
        let e = err.to_ascii_lowercase();
        if e.contains("auth") || e.contains("username/password") || e.contains("requires auth") {
            RuntimeFailureClass::Auth
        } else if e.contains("handshake timeout")
            || e.contains("saturated")
            || e.contains("queue full")
            || e.contains("too many in-flight")
            || e.contains("operation timed out")
            || e.contains("timed out")
            || e.contains("resource temporarily unavailable")
            || e.contains("soft runtime failure")
        {
            RuntimeFailureClass::Soft
        } else {
            RuntimeFailureClass::Hard
        }
    }

    fn runtime_fail_tolerate_threshold(state: BackendState, class: RuntimeFailureClass) -> u8 {
        match (state, class) {
            (BackendState::Green, RuntimeFailureClass::Soft) => 8,
            (BackendState::Green, RuntimeFailureClass::Hard) => 3,
            (BackendState::Green, RuntimeFailureClass::Auth) => 1,
            (BackendState::Yellow, RuntimeFailureClass::Soft) => 5,
            (BackendState::Yellow, RuntimeFailureClass::Hard) => 2,
            (BackendState::Yellow, RuntimeFailureClass::Auth) => 1,
            (BackendState::Red, _) => 0,
        }
    }

    pub fn update(
        &mut self,
        idx: usize,
        _healthy: bool,
        err: Option<String>,
        socks_ping_ms: Option<f64>,
        internet_ping_ms: Option<f64>,
        internet_ttl: Option<u32>,
        full_probe: bool,
    ) -> bool {
        if idx >= self.status.len() { return false; }
        let prev = self.status[idx].clone();
        let now = now_ts();

        let state = if socks_ping_ms.is_none() {
            BackendState::Red
        } else if full_probe {
            if internet_ping_ms.is_some() {
                BackendState::Green
            } else {
                BackendState::Yellow
            }
        } else if prev.state == BackendState::Green {
            BackendState::Green
        } else {
            BackendState::Yellow
        };

        self.status[idx].state = state;
        self.status[idx].healthy = state == BackendState::Green;
        self.status[idx].last_check = now;
        if state == BackendState::Green && idx < self.last_green_ts.len() {
            self.last_green_ts[idx] = now;
        }
        if idx < self.runtime_fail_streak.len() {
            self.runtime_fail_streak[idx] = 0;
        }
        if idx < self.last_runtime_fail_ts.len() {
            self.last_runtime_fail_ts[idx] = 0;
        }

        self.status[idx].socks_ping_ms = socks_ping_ms;
        if full_probe {
            if idx < self.last_full_probe.len() {
                self.last_full_probe[idx] = now;
            }
            self.note_internet_probe_result(idx, internet_ping_ms.is_some());
            self.status[idx].internet_ping_ms = internet_ping_ms;
            self.status[idx].last_error = err;
        } else {
            if state == BackendState::Red {
                self.status[idx].internet_ping_ms = None;
                self.status[idx].last_error = err;
            } else {
                // Keep last heavy-probe result for stable green backends during light checks.
                self.status[idx].last_error = if prev.state == BackendState::Green { prev.last_error.clone() } else { None };
            }
        }

        // RTT integrity based on recent internet RTT samples.
        if idx < self.rtt_hist.len() {
            if let Some(rtt) = internet_ping_ms {
                let h = &mut self.rtt_hist[idx];
                h.push_back(rtt);
                while h.len() > 30 { h.pop_front(); }
                if !h.is_empty() {
                    // Use median as baseline; stable = within ±10%.
                    let mut v: Vec<f64> = h.iter().copied().collect();
                    v.sort_by(|a,b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
                    let med = v[v.len()/2].max(0.1);
                    let lo = med * 0.90;
                    let hi = med * 1.10;
                    let stable = h.iter().filter(|x| **x >= lo && **x <= hi).count();
                    let pct = (stable as f64) * 100.0 / (h.len() as f64);
                    self.status[idx].rtt_integrity = Some(pct);
                }
            }
        }

        if idx < self.ttl_hist.len() {
            if let Some(t) = internet_ttl {
                let h = &mut self.ttl_hist[idx];
                h.push_back(t);
                while h.len() > 30 { h.pop_front(); }

                let mut min_t = t;
                let mut max_t = t;
                for v in h.iter() {
                    if *v < min_t { min_t = *v; }
                    if *v > max_t { max_t = *v; }
                }
                self.status[idx].ttl_min = Some(min_t);
                self.status[idx].ttl_max = Some(max_t);
            }
        }

        prev.state != self.status[idx].state
            || prev.healthy != self.status[idx].healthy
            || prev.last_error != self.status[idx].last_error
            || prev.socks_ping_ms != self.status[idx].socks_ping_ms
            || prev.internet_ping_ms != self.status[idx].internet_ping_ms
            || prev.ttl_min != self.status[idx].ttl_min
            || prev.ttl_max != self.status[idx].ttl_max
    }

    /// Adds proxied bytes for a backend (TCP, both directions).
    pub fn add_bytes(&mut self, addr: SocketAddr, n: u64) {
        if let Some(pos) = self.addrs.iter().position(|a| *a == addr) {
            if pos < self.status.len() {
                self.status[pos].total_bytes = self.status[pos].total_bytes.saturating_add(n);
            }
            self.touch_backend_activity_idx(pos, now_ts(), false);
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct ConnInfo {
    pub cid: u64,
    pub peer: String,
    #[serde(skip_serializing)]
    pub peer_ip_addr: IpAddr,
    pub peer_ip: String,
    /// Which local listener accepted this connection (internal 127.0.0.1:* or external 0.0.0.0:*).
    pub ingress: String,
    /// Best-effort domain (HTTP Host / CONNECT host / TLS SNI).
    pub domain: Option<String>,
    /// Best-effort destination IP (when known).
    pub dst_ip: Option<String>,
    pub target: Option<String>,
    pub mode: Option<String>,
    /// Selected SOCKS backend (when in SOCKS mode).
    pub backend: Option<String>,
    pub started_ts: u64,
    pub last_progress_ts: u64,
    pub bytes_up: u64,
    pub bytes_down: u64,
}

#[derive(Default)]
pub struct ConnRegistry {
    inner: Mutex<HashMap<u64, (ConnInfo, CancellationToken)>>,
    source_counts: Mutex<HashMap<IpAddr, u32>>,
    active_total: AtomicU64,
    active_internal: AtomicU64,
    active_external: AtomicU64,
    mode_pending: AtomicU64,
    mode_wait_backend: AtomicU64,
    mode_socks_connecting: AtomicU64,
}

impl ConnRegistry {
    fn inc_ingress(&self, ingress: Ingress) {
        match ingress {
            Ingress::Internal => { self.active_internal.fetch_add(1, Ordering::Relaxed); }
            Ingress::External => { self.active_external.fetch_add(1, Ordering::Relaxed); }
        }
        self.active_total.fetch_add(1, Ordering::Relaxed);
    }

    fn dec_ingress(&self, ingress_name: &str) {
        match ingress_name {
            "external" => { self.active_external.fetch_sub(1, Ordering::Relaxed); }
            _ => { self.active_internal.fetch_sub(1, Ordering::Relaxed); }
        }
        self.active_total.fetch_sub(1, Ordering::Relaxed);
    }

    fn inc_mode_counter(&self, mode: &str) {
        match mode {
            "pending" => { self.mode_pending.fetch_add(1, Ordering::Relaxed); }
            "wait_backend" => { self.mode_wait_backend.fetch_add(1, Ordering::Relaxed); }
            "socks_connecting" => { self.mode_socks_connecting.fetch_add(1, Ordering::Relaxed); }
            _ => {}
        }
    }

    fn dec_mode_counter(&self, mode: &str) {
        match mode {
            "pending" => { self.mode_pending.fetch_sub(1, Ordering::Relaxed); }
            "wait_backend" => { self.mode_wait_backend.fetch_sub(1, Ordering::Relaxed); }
            "socks_connecting" => { self.mode_socks_connecting.fetch_sub(1, Ordering::Relaxed); }
            _ => {}
        }
    }

    fn update_mode_counters(&self, old_mode: Option<&str>, new_mode: Option<&str>) {
        if old_mode == new_mode {
            return;
        }
        if let Some(m) = old_mode {
            self.dec_mode_counter(m);
        }
        if let Some(m) = new_mode {
            self.inc_mode_counter(m);
        }
    }

    pub fn try_new_conn(&self, peer: SocketAddr, ingress: Ingress, external_per_source_limit: Option<u32>) -> Option<u64> {
        let cid = rand_u64();
        let now = now_ts();
        let peer_ip_addr = peer.ip();
        let peer_ip = peer_ip_addr.to_string();
        let info = ConnInfo{
            cid,
            peer: peer.to_string(),
            peer_ip_addr,
            peer_ip: peer_ip.clone(),
            ingress: match ingress { Ingress::Internal => "internal".into(), Ingress::External => "external".into() },
            domain: None,
            dst_ip: None,
            target: None,
            mode: Some("pending".to_string()),
            backend: None,
            started_ts: now,
            last_progress_ts: now,
            bytes_up: 0,
            bytes_down: 0,
        };
        let mut inner = self.inner.lock();
        let mut source_counts = self.source_counts.lock();
        if ingress == Ingress::External {
            if let Some(limit) = external_per_source_limit {
                let current = source_counts.get(&peer_ip_addr).copied().unwrap_or(0);
                if current >= limit.max(1) {
                    return None;
                }
            }
            let entry = source_counts.entry(peer_ip_addr).or_insert(0);
            *entry = entry.saturating_add(1);
        }
        inner.insert(cid, (info, CancellationToken::new()));
        drop(source_counts);
        drop(inner);
        self.inc_ingress(ingress);
        self.inc_mode_counter("pending");
        Some(cid)
    }

    pub fn len(&self) -> usize {
        self.active_total.load(Ordering::Relaxed) as usize
    }

    pub fn ingress_counts(&self) -> (usize, usize) {
        (
            self.active_internal.load(Ordering::Relaxed) as usize,
            self.active_external.load(Ordering::Relaxed) as usize,
        )
    }

    pub fn count_modes(&self, modes: &[&str]) -> usize {
        let mut total = 0usize;
        let mut all_fast = true;
        for mode in modes {
            match *mode {
                "pending" => total += self.mode_pending.load(Ordering::Relaxed) as usize,
                "wait_backend" => total += self.mode_wait_backend.load(Ordering::Relaxed) as usize,
                "socks_connecting" => total += self.mode_socks_connecting.load(Ordering::Relaxed) as usize,
                _ => {
                    all_fast = false;
                    break;
                }
            }
        }
        if all_fast {
            return total;
        }
        self.inner
            .lock()
            .values()
            .filter(|(info, _)| info.mode.as_deref().map(|m| modes.iter().any(|want| *want == m)).unwrap_or(false))
            .count()
    }

    pub fn has_mode(&self, mode: &str) -> bool {
        match mode {
            "pending" => self.mode_pending.load(Ordering::Relaxed) > 0,
            "wait_backend" => self.mode_wait_backend.load(Ordering::Relaxed) > 0,
            "socks_connecting" => self.mode_socks_connecting.load(Ordering::Relaxed) > 0,
            _ => self
                .inner
                .lock()
                .values()
                .any(|(info, _)| info.mode.as_deref() == Some(mode)),
        }
    }

    pub fn set_cancel_token(&self, cid: u64) -> CancellationToken {
        let token = CancellationToken::new();
        if let Some((_info, t)) = self.inner.lock().get_mut(&cid) {
            *t = token.clone();
        }
        token
    }

    pub fn kill(&self, cid: u64) -> bool {
        if let Some((_info, token)) = self.inner.lock().get(&cid) {
            token.cancel();
            return true;
        }
        false
    }

    pub fn set_target(&self, cid: u64, target: &str, mode: &str) {
        let mut old_mode: Option<String> = None;
        let mut changed = false;
        {
            let mut guard = self.inner.lock();
            if let Some((info, _)) = guard.get_mut(&cid) {
                old_mode = info.mode.clone();
                let new_mode = Some(mode.to_string());
                changed = info.mode != new_mode;
                info.target = Some(target.to_string());
                info.mode = new_mode;
                info.last_progress_ts = now_ts();
            }
        }
        if changed {
            self.update_mode_counters(old_mode.as_deref(), Some(mode));
        }
    }

    pub fn set_mode(&self, cid: u64, mode: &str) {
        let mut old_mode: Option<String> = None;
        let mut changed = false;
        {
            let mut guard = self.inner.lock();
            if let Some((info, _)) = guard.get_mut(&cid) {
                old_mode = info.mode.clone();
                let new_mode = Some(mode.to_string());
                changed = info.mode != new_mode;
                info.mode = new_mode;
                info.last_progress_ts = now_ts();
            }
        }
        if changed {
            self.update_mode_counters(old_mode.as_deref(), Some(mode));
        }
    }

    pub fn set_domain(&self, cid: u64, domain: Option<String>) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            if domain.as_ref().map(|s| !s.is_empty()).unwrap_or(false) {
                info.domain = domain;
            }
        }
    }

    pub fn set_dst_ip(&self, cid: u64, dst_ip: Option<String>) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.dst_ip = dst_ip;
        }
    }

    pub fn set_backend(&self, cid: u64, backend: Option<SocketAddr>) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.backend = backend.map(|b| b.to_string());
        }
    }

    pub fn add_bytes_up(&self, cid: u64, n: u64) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.bytes_up += n;
            info.last_progress_ts = now_ts();
        }
    }
    pub fn add_bytes_down(&self, cid: u64, n: u64) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.bytes_down += n;
            info.last_progress_ts = now_ts();
        }
    }



    /// Cancels all active connections whose mode matches the given string (e.g. "direct").
    /// Returns number of cancelled connections.
    pub fn kill_mode(&self, mode: &str) -> usize {
        let mut n = 0usize;
        let g = self.inner.lock();
        for (info, token) in g.values() {
            if info.mode.as_deref() == Some(mode) {
                token.cancel();
                n += 1;
            }
        }
        n
    }

    /// Cancels connections that are stuck in SOCKS mode and have not transferred any bytes.
    /// Useful after connectivity blips where some clients keep a half-open socket.
    pub fn kill_stuck_socks_zero_traffic(&self, older_than_secs: u64) -> usize {
        let now = now_ts();
        let mut n = 0usize;
        let g = self.inner.lock();
        for (info, token) in g.values() {
            if info.mode.as_deref() == Some("socks")
                && info.bytes_up == 0
                && info.bytes_down == 0
                && now.saturating_sub(info.started_ts) >= older_than_secs
            {
                token.cancel();
                n += 1;
            }
        }
        n
    }

    /// Cancels connections that are still pending/connecting and have not made progress.
    pub fn kill_stuck_connecting(&self, older_than_secs: u64) -> usize {
        let now = now_ts();
        let mut n = 0usize;
        let g = self.inner.lock();
        for (info, token) in g.values() {
            let is_connecting = matches!(info.mode.as_deref(), Some("pending") | Some("socks_connecting") | Some("wait_backend"));
            if is_connecting
                && info.bytes_up == 0
                && info.bytes_down == 0
                && now.saturating_sub(info.last_progress_ts) >= older_than_secs
            {
                token.cancel();
                n += 1;
            }
        }
        n
    }
    pub fn finish_conn(&self, cid: u64) {
        let removed = self.inner.lock().remove(&cid);
        if let Some((info, _)) = removed {
            self.dec_ingress(&info.ingress);
            self.update_mode_counters(info.mode.as_deref(), None);
            if info.ingress == "external" {
                let mut source_counts = self.source_counts.lock();
                if let Some(cur) = source_counts.get_mut(&info.peer_ip_addr) {
                    if *cur > 1 {
                        *cur -= 1;
                    } else {
                        source_counts.remove(&info.peer_ip_addr);
                    }
                }
            }
        }
    }

    pub fn list(&self) -> Vec<ConnInfo> {
        self.inner.lock().values().map(|(i,_)| i.clone()).collect()
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct Event {
    pub ts: u64,
    pub kind: String,
    pub cid: Option<u64>,
    pub peer: Option<String>,
    pub target: Option<String>,
    pub mode: Option<String>,
}

impl Event {
    pub fn conn_open(cid: u64, peer: SocketAddr) -> Self {
        Self{ ts: now_ts(), kind: "conn_open".into(), cid: Some(cid), peer: Some(peer.to_string()), target: None, mode: None }
    }
    pub fn conn_close(cid: u64) -> Self {
        Self{ ts: now_ts(), kind: "conn_close".into(), cid: Some(cid), peer: None, target: None, mode: None }
    }
    pub fn conn_target(cid: u64, host: String, port: u16, mode: String) -> Self {
        Self{ ts: now_ts(), kind: "conn_target".into(), cid: Some(cid), peer: None, target: Some(format!("{}:{}", host, port)), mode: Some(mode) }
    }
}


const ZDTD_SETTING_JSON: &str = "/data/adb/modules/ZDT-D/setting/setting.json";
const PROTECTOR_MODE_CACHE_TTL_SECS: u64 = 2;
static PROTECTOR_MODE_CACHE: Lazy<Mutex<(u64, bool)>> = Lazy::new(|| Mutex::new((0, false)));

static PING_TTL_TIME_RE: Lazy<regex::Regex> = Lazy::new(|| regex::Regex::new(r"ttl=(\d+).*time=([0-9.]+)\s*ms").unwrap());
static PING_TIME_RE: Lazy<regex::Regex> = Lazy::new(|| regex::Regex::new(r"time=([0-9.]+)\s*ms").unwrap());
static PING_TTL_RE: Lazy<regex::Regex> = Lazy::new(|| regex::Regex::new(r"ttl=(\d+)").unwrap());

fn read_protector_mode_forced_green_uncached() -> bool {
    let content = match std::fs::read_to_string(ZDTD_SETTING_JSON) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let v: Value = match serde_json::from_str(&content) {
        Ok(v) => v,
        Err(_) => return false,
    };
    v.get("protector_mode")
        .and_then(|x| x.as_str())
        .map(|s| s.eq_ignore_ascii_case("on"))
        .unwrap_or(false)
}

fn protector_mode_forced_green() -> bool {
    let now = now_ts();
    let mut cache = PROTECTOR_MODE_CACHE.lock();
    if cache.0 != 0 && now.saturating_sub(cache.0) < PROTECTOR_MODE_CACHE_TTL_SECS {
        return cache.1;
    }
    let forced = read_protector_mode_forced_green_uncached();
    *cache = (now, forced);
    forced
}

pub fn now_ts() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

fn rand_u64() -> u64 {
    let mut rng = rand::thread_rng();
    rng.next_u64()
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HealthRefreshMode {
    FullSweep,
    RoundRobinOne,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ProbeMode {
    Light,
    Full,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TrafficCadence {
    High,
    Normal,
    Quiet60,
    Quiet120,
}

const ACTIVE_BACKEND_WINDOW_SECS: u64 = 10 * 60;
const AUDIT_BACKEND_EVERY: u64 = 4;
const BACKEND_ACTIVITY_TOUCH_SECS: u64 = 5;

impl TrafficCadence {
    fn interval(self) -> Duration {
        match self {
            TrafficCadence::High => Duration::from_secs(45),
            TrafficCadence::Normal => Duration::from_secs(60),
            TrafficCadence::Quiet60 => Duration::from_secs(120),
            TrafficCadence::Quiet120 => Duration::from_secs(180),
        }
    }
}

async fn probe_backend_once(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
    internet_ttl: Option<u32>,
    probe_mode: ProbeMode,
) -> (Option<String>, Option<f64>, Option<f64>, Option<u32>) {
    let socks_ping_ms = check_backend_rtt(backend, timeout, auth.clone()).await;
    let mut err = if socks_ping_ms.is_some() { None } else { Some("connect/greeting/auth failed".to_string()) };
    let internet_ping_ms = if socks_ping_ms.is_some() && probe_mode == ProbeMode::Full {
        let summary = check_internet_via_backend(backend, timeout, auth).await;
        if summary.ok {
            summary.best_ping_ms
        } else {
            err = Some(summary.error_summary);
            None
        }
    } else {
        None
    };
    let ttl = if probe_mode == ProbeMode::Full { internet_ttl } else { None };
    (err, socks_ping_ms, internet_ping_ms, ttl)
}

fn global_backend_auth(args: &Args) -> Option<(String, String)> {
    match (args.socks_user.clone(), args.socks_pass.clone()) {
        (Some(u), Some(p)) => Some((u, p)),
        _ => None,
    }
}

pub fn health_timeout(state: &crate::AppState) -> Duration {
    Duration::from_millis(((state.args.connect_timeout as u64) * 1000).clamp(1200, 3000))
}

fn choose_probe_mode(
    b: &SocksBackends,
    idx: usize,
    now: u64,
    ui_forced_full: bool,
) -> ProbeMode {
    if ui_forced_full {
        return ProbeMode::Full;
    }
    let state_at = b.state_at(idx).unwrap_or(BackendState::Red);
    let last_full_probe = b.last_full_probe_at(idx).unwrap_or(0);
    let full_probe_due = last_full_probe == 0 || now.saturating_sub(last_full_probe) >= 15 * 60;
    let probe_allowed = b.internet_probe_due_idx(idx, now);
    if state_at == BackendState::Green && !full_probe_due {
        ProbeMode::Light
    } else if full_probe_due && probe_allowed {
        ProbeMode::Full
    } else {
        ProbeMode::Light
    }
}

fn total_traffic_bytes(state: &crate::AppState) -> u64 {
    state.stats.bytes_up.load(Ordering::Relaxed)
        .saturating_add(state.stats.bytes_down.load(Ordering::Relaxed))
}

fn should_wake_backend_loops(
    before_any_healthy: bool,
    after_any_healthy: bool,
    before_any_green: bool,
    after_any_green: bool,
) -> bool {
    before_any_healthy != after_any_healthy || before_any_green != after_any_green
}

pub async fn maybe_sample_internet_ttl_for_ui(state: &crate::AppState) -> Option<u32> {
    let now = now_ts();
    let ui_open = state.runtime.ui_clients.load(Ordering::Relaxed) > 0;
    let min_gap_secs = if ui_open { 5 * 60 } else { 20 * 60 };
    let last = state.runtime.last_ttl_ping_ts.load(Ordering::Relaxed);
    if last != 0 && now.saturating_sub(last) < min_gap_secs {
        return None;
    }

    // Throttle attempts as well, not only successful samples.
    state.runtime.last_ttl_ping_ts.store(now, Ordering::Relaxed);

    match ping_once("1.1.1.1".to_string()).await {
        Ok(Some((_ms, ttl))) if ttl > 0 => Some(ttl),
        _ => None,
    }
}

async fn refresh_backend_index_once(
    state: crate::AppState,
    idx: usize,
    timeout: Duration,
    internet_ttl: Option<u32>,
    auth: Option<(String, String)>,
    probe_mode: ProbeMode,
) -> bool {
    let backend = {
        let b = state.backends.lock();
        b.addr_at(idx)
    };

    let Some(backend) = backend else { return false; };

    let (err, socks_ping_ms, internet_ping_ms, ttl) =
        probe_backend_once(backend, timeout, auth, internet_ttl, probe_mode).await;

    let mut b = state.backends.lock();
    let before_any_healthy = b.any_healthy();
    let before_any_green = b.any_green();
    let changed = b.update(idx, socks_ping_ms.is_some(), err, socks_ping_ms, internet_ping_ms, ttl, probe_mode == ProbeMode::Full);
    let after_any_healthy = b.any_healthy();
    let after_any_green = b.any_green();
    let after_any_yellow = b.any_yellow();
    if after_any_green {
        state.runtime.clear_direct_cooldown();
    }
    // If Internet just dropped across all backends but SOCKS is still reachable
    // (Yellow/no-GREEN state), immediately start the accelerated recovery ladder.
    // This avoids waiting for a later burst of new connections before we begin
    // trying to recover Internet availability again.
    let should_start_recovery_ladder = changed
        && !after_any_green
        && after_any_yellow
        && (before_any_green || !before_any_healthy || probe_mode == ProbeMode::Full);
    let wake_backend = changed && should_wake_backend_loops(before_any_healthy, after_any_healthy, before_any_green, after_any_green);
    drop(b);
    if wake_backend {
        state.runtime.backend_wake_throttled(750);
    }
    if should_start_recovery_ladder && state.runtime.try_enter_burst_recovery_ladder() {
        let st_recover = state.clone();
        spawn_burst_recovery_ladder(st_recover);
    }
    true
}

pub async fn refresh_backends_once(state: crate::AppState, timeout: Duration) {
    let _guard = state.runtime.refresh_lock.lock().await;
    let global_auth = global_backend_auth(&state.args);
    let refresh_plan: Vec<(usize, Option<(String, String)>, ProbeMode)> = {
        let b = state.backends.lock();
        let now = now_ts();
        let mut full_idx: Option<usize> = None;
        let mut plan = Vec::with_capacity(b.len());
        for idx in 0..b.len() {
            if b.addr_at(idx).is_none() {
                continue;
            }
            let probe_mode = if full_idx.is_none() {
                let pm = choose_probe_mode(&b, idx, now, false);
                if pm == ProbeMode::Full {
                    full_idx = Some(idx);
                }
                pm
            } else {
                ProbeMode::Light
            };
            plan.push((idx, b.effective_auth_at(idx, global_auth.as_ref()), probe_mode));
        }
        plan
    };

    for (idx, auth, probe_mode) in refresh_plan {
        let _ = refresh_backend_index_once(
            state.clone(),
            idx,
            timeout,
            None,
            auth,
            probe_mode,
        ).await;
    }
}

pub async fn refresh_one_backend_once_rr(state: crate::AppState, timeout: Duration) -> bool {
    let _guard = state.runtime.refresh_lock.lock().await;
    let global_auth = global_backend_auth(&state.args);
    let (idx, probe_mode, auth) = {
        let mut b = state.backends.lock();
        let idx = b.next_check_index_active_first(ACTIVE_BACKEND_WINDOW_SECS, AUDIT_BACKEND_EVERY);
        let Some(idx) = idx else { return false; };
        let now = now_ts();
        let probe_mode = choose_probe_mode(&b, idx, now, false);
        let auth = b.effective_auth_at(idx, global_auth.as_ref());
        (Some(idx), probe_mode, auth)
    };

    let Some(idx) = idx else { return false; };
    refresh_backend_index_once(state.clone(), idx, timeout, None, auth, probe_mode).await
}

pub async fn burst_recheck_one_backend(state: crate::AppState) -> bool {
    if !state.runtime.try_enter_burst_recheck() {
        return false;
    }

    let res = async {
        let _guard = state.runtime.refresh_lock.lock().await;
        let global_auth = global_backend_auth(&state.args);
        let choice = {
            let mut b = state.backends.lock();
            // Recovery ladder is intentionally more aggressive than normal background
            // probes. It bypasses the regular per-backend internet-probe due/backoff
            // gate so a recently-yellow backend can be revalidated quickly after the
            // network comes back. The ladder cadence itself (2s / 5s / 15s) remains
            // the safety limiter against overly frequent probing.
            b.choose_burst_recheck_index(global_auth.as_ref(), true)
        };
        let Some((idx, auth)) = choice else { return false; };
        let timeout = health_timeout(&state);
        refresh_backend_index_once(state.clone(), idx, timeout, None, auth, ProbeMode::Full).await
    }.await;

    state.runtime.leave_burst_recheck();
    res
}



pub fn spawn_burst_recovery_ladder(state: crate::AppState) {
    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(_) => {
                state.runtime.leave_burst_recovery_ladder();
                return;
            }
        };
        rt.block_on(async move {
            burst_recovery_ladder_loop(state).await;
        });
    });
}

pub async fn burst_recovery_ladder_loop(state: crate::AppState) {
    let started = tokio::time::Instant::now();
    let mut attempt = 0u32;

    loop {
        if state.backends.lock().any_green() {
            break;
        }

        let elapsed = started.elapsed();
        if elapsed >= Duration::from_secs(30 + 60 + 5 * 60) {
            break;
        }

        let _ = burst_recheck_one_backend(state.clone()).await;
        attempt = attempt.saturating_add(1);

        if state.backends.lock().any_green() {
            break;
        }

        let elapsed = started.elapsed();
        let sleep_for = if elapsed < Duration::from_secs(30) {
            Duration::from_secs(2)
        } else if elapsed < Duration::from_secs(30 + 60) {
            Duration::from_secs(5)
        } else {
            Duration::from_secs(15)
        };

        tokio::select! {
            _ = tokio::time::sleep(sleep_for) => {},
            _ = state.runtime.backend_wakeup.notified() => {
                if state.backends.lock().any_green() {
                    break;
                }
            },
        }
    }

    let _ = attempt;
    state.runtime.leave_burst_recovery_ladder();
}

pub async fn refresh_backends_for_ui(state: crate::AppState, timeout: Duration) {
    let _guard = state.runtime.refresh_lock.lock().await;
    let internet_ttl = maybe_sample_internet_ttl_for_ui(&state).await;
    let global_auth = global_backend_auth(&state.args);
    let refresh_plan: Vec<(usize, Option<(String, String)>)> = {
        let b = state.backends.lock();
        (0..b.len())
            .filter(|idx| b.addr_at(*idx).is_some())
            .map(|idx| (idx, b.effective_auth_at(idx, global_auth.as_ref())))
            .collect()
    };

    for (idx, auth) in refresh_plan {
        let _ = refresh_backend_index_once(
            state.clone(),
            idx,
            timeout,
            internet_ttl,
            auth,
            ProbeMode::Full,
        ).await;
    }
}

// --- background tasks

pub async fn backend_health_loop(state: crate::AppState) {
    let mut last_total_bytes = total_traffic_bytes(&state);
    let mut last_sample_at = tokio::time::Instant::now();
    let mut no_traffic_since: Option<tokio::time::Instant> = None;
    let mut force_full_sweep = true;
    let mut recent_bps: VecDeque<f64> = VecDeque::with_capacity(3);
    let mut desired_cadence = TrafficCadence::Normal;
    let mut applied_cadence = TrafficCadence::Normal;
    let mut cadence_streak: u8 = 0;

    loop {
        let now = tokio::time::Instant::now();
        let total_bytes = total_traffic_bytes(&state);
        let delta_bytes = total_bytes.saturating_sub(last_total_bytes);
        let elapsed = now.saturating_duration_since(last_sample_at);
        let elapsed_secs = elapsed.as_secs_f64().max(0.001);
        let bytes_per_sec = (delta_bytes as f64) / elapsed_secs;
        let had_traffic = delta_bytes > 0;

        recent_bps.push_back(bytes_per_sec);
        while recent_bps.len() > 3 { recent_bps.pop_front(); }
        let avg_bps = if recent_bps.is_empty() {
            0.0
        } else {
            recent_bps.iter().copied().sum::<f64>() / (recent_bps.len() as f64)
        };

        let woke_from_quiet = if had_traffic {
            let quiet = no_traffic_since
                .map(|ts| now.saturating_duration_since(ts) >= Duration::from_secs(60))
                .unwrap_or(false);
            no_traffic_since = None;
            quiet
        } else {
            if no_traffic_since.is_none() {
                no_traffic_since = Some(now);
            }
            false
        };

        let active = state.conns.len();
        let ui_open = state.runtime.ui_clients.load(Ordering::Relaxed) > 0;
        let quiet_for = no_traffic_since
            .map(|ts| now.saturating_duration_since(ts))
            .unwrap_or_default();
        let has_direct = state.conns.has_mode("direct");
        let connecting_now = state.conns.count_modes(&["pending", "wait_backend", "socks_connecting"]);
        let urgent_work = has_direct || connecting_now > 0;
        let idle_without_work = !ui_open && !urgent_work && quiet_for >= Duration::from_secs(60);
        let deep_idle = !ui_open && active == 0 && quiet_for >= Duration::from_secs(3 * 60);
        let quiet_lingering_only = idle_without_work && active > 0;

        let current_desired = if had_traffic {
            if avg_bps >= 100.0 * 1024.0 {
                TrafficCadence::High
            } else {
                TrafficCadence::Normal
            }
        } else if urgent_work {
            TrafficCadence::Normal
        } else if quiet_for >= Duration::from_secs(10 * 60) {
            TrafficCadence::Quiet120
        } else {
            TrafficCadence::Quiet60
        };

        if current_desired == desired_cadence {
            cadence_streak = cadence_streak.saturating_add(1);
        } else {
            desired_cadence = current_desired;
            cadence_streak = 1;
        }
        if current_desired == applied_cadence || cadence_streak >= 2 {
            applied_cadence = current_desired;
        }

        let allow_refresh = ui_open || had_traffic || urgent_work || quiet_for < Duration::from_secs(2 * 60);
        if allow_refresh {
            let green_available = state.backends.lock().any_green();
            let refresh_mode = if force_full_sweep || !green_available || woke_from_quiet {
                HealthRefreshMode::FullSweep
            } else {
                HealthRefreshMode::RoundRobinOne
            };

            let timeout = health_timeout(&state);
            match refresh_mode {
                HealthRefreshMode::FullSweep => refresh_backends_once(state.clone(), timeout).await,
                HealthRefreshMode::RoundRobinOne => {
                    let _ = refresh_one_backend_once_rr(state.clone(), timeout).await;
                }
            }
            force_full_sweep = false;
        }

        let interval = if deep_idle {
            // In deep idle, avoid periodic network probes entirely.
            // Wake on backend-state changes / first new connection, and keep only
            // a very rare safety tick so phones can actually sleep.
            Duration::from_secs(45 * 60)
        } else if quiet_lingering_only {
            if quiet_for >= Duration::from_secs(45 * 60) {
                Duration::from_secs(45 * 60)
            } else if quiet_for >= Duration::from_secs(20 * 60) {
                Duration::from_secs(20 * 60)
            } else if quiet_for >= Duration::from_secs(10 * 60) {
                Duration::from_secs(10 * 60)
            } else if quiet_for >= Duration::from_secs(5 * 60) {
                Duration::from_secs(5 * 60)
            } else {
                Duration::from_secs(2 * 60)
            }
        } else if idle_without_work {
            if quiet_for >= Duration::from_secs(20 * 60) {
                Duration::from_secs(20 * 60)
            } else if quiet_for >= Duration::from_secs(5 * 60) {
                Duration::from_secs(5 * 60)
            } else {
                applied_cadence.interval()
            }
        } else {
            applied_cadence.interval()
        };

        last_total_bytes = total_bytes;
        last_sample_at = now;

        tokio::select! {
            _ = tokio::time::sleep(interval) => {},
            _ = state.runtime.backend_wakeup.notified() => {
                force_full_sweep = true;
            }
        }
    }
}


#[derive(Clone, Debug)]
struct InternetProbeSummary {
    ok: bool,
    best_ping_ms: Option<f64>,
    error_summary: String,
}

async fn probe_one_target(
    backend: SocketAddr,
    target: TargetAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
) -> Option<f64> {
    let start = Instant::now();
    if crate::socks5::connect_via_socks5(backend, target, auth, timeout).await.is_ok() {
        Some(start.elapsed().as_secs_f64() * 1000.0)
    } else {
        None
    }
}

async fn first_successful_probe(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
    targets: Vec<TargetAddr>,
    stop_after: usize,
) -> (usize, Option<f64>) {
    let mut ok_count = 0usize;
    let mut best: Option<f64> = None;

    for target in targets {
        if let Some(ping_ms) = probe_one_target(backend, target, timeout, auth.clone()).await {
            ok_count += 1;
            best = Some(match best {
                Some(prev) => prev.min(ping_ms),
                None => ping_ms,
            });
            if ok_count >= stop_after {
                break;
            }
        }
    }

    (ok_count, best)
}

pub async fn proxy_enforce_loop(state: crate::AppState) {
    // Kill stale connections that got established in DIRECT mode while there were no GREEN backends,
    // so clients will reconnect through SOCKS as soon as backends recover.
    let mut had_green = false;

    let mut idle_since: Option<tokio::time::Instant> = None;
    let mut last_total_bytes = total_traffic_bytes(&state);
    let mut no_traffic_since: Option<tokio::time::Instant> = None;

    loop {
        let now = tokio::time::Instant::now();
        let total_bytes = total_traffic_bytes(&state);
        let had_traffic = total_bytes.saturating_sub(last_total_bytes) > 0;
        if had_traffic {
            no_traffic_since = None;
        } else if no_traffic_since.is_none() {
            no_traffic_since = Some(now);
        }
        let quiet_for = no_traffic_since
            .map(|ts| now.saturating_duration_since(ts))
            .unwrap_or_default();

        let active = state.conns.len();
        let ui = state.runtime.ui_clients.load(Ordering::Relaxed);
        let has_direct = state.conns.has_mode("direct");
        let connecting_now = state.conns.count_modes(&["pending", "wait_backend", "socks_connecting"]);
        let quiet_lingering_only = ui == 0
            && active > 0
            && !has_direct
            && connecting_now == 0
            && quiet_for >= Duration::from_secs(60);
        let deep_idle = ui == 0 && active == 0 && quiet_for >= Duration::from_secs(3 * 60);
        let green = if deep_idle { false } else { state.backends.lock().any_green() };
        let recovery_edge = !had_green && green;
        let needs_enforce = !deep_idle && (has_direct || connecting_now > 0 || recovery_edge);

        if needs_enforce {
            // Safety net: if any DIRECT connections exist while GREEN backends are available,
            // cancel them. This enforces the "no bypass" rule.
            if green && has_direct {
                let killed_direct = state.conns.kill_mode("direct");
                if killed_direct > 0 {
                    tracing::info!("proxy enforce: cancelled {} DIRECT connections (GREEN available)", killed_direct);
                }
            }

            // On recovery edge (no green -> green), also cancel SOCKS connections that never transferred any data.
            if recovery_edge {
                let killed_stuck = state.conns.kill_stuck_socks_zero_traffic(15);
                if killed_stuck > 0 {
                    tracing::info!("proxy enforce: cancelled {} stuck SOCKS connections after recovery", killed_stuck);
                }
            }

            if connecting_now > 0 {
                let killed_connecting = state.conns.kill_stuck_connecting(12);
                if killed_connecting > 0 {
                    tracing::info!("proxy enforce: cancelled {} stuck pending/connecting connections", killed_connecting);
                }
            }
        }

        had_green = green;
        last_total_bytes = total_bytes;

        let interval = if deep_idle {
            if idle_since.is_none() {
                idle_since = Some(tokio::time::Instant::now());
            }
            let idle_for = idle_since.unwrap().elapsed();
            if idle_for < Duration::from_secs(15 * 60) {
                Duration::from_secs(20 * 60)
            } else {
                Duration::from_secs(30 * 60)
            }
        } else if needs_enforce {
            idle_since = None;
            Duration::from_secs(40)
        } else if quiet_lingering_only {
            if quiet_for >= Duration::from_secs(45 * 60) {
                Duration::from_secs(45 * 60)
            } else if quiet_for >= Duration::from_secs(20 * 60) {
                Duration::from_secs(20 * 60)
            } else if quiet_for >= Duration::from_secs(10 * 60) {
                Duration::from_secs(10 * 60)
            } else {
                Duration::from_secs(5 * 60)
            }
        } else {
            idle_since = None;
            Duration::from_secs(10 * 60)
        };

        tokio::select! {
            _ = tokio::time::sleep(interval) => {},
            _ = state.runtime.backend_wakeup.notified() => {
                idle_since = None;
            }
        }
    }
}


/// Best-effort RTT to SOCKS backend: TCP connect + SOCKS greeting.
///
/// Returns RTT in ms.
async fn check_backend_rtt(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
) -> Option<f64> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let start = Instant::now();
    let hs_timeout = timeout.min(Duration::from_secs(3)).max(Duration::from_millis(800));
    let res = tokio::time::timeout(timeout, TcpStream::connect(backend)).await;
    if let Ok(Ok(mut s)) = res {
        let mut methods = vec![0x00u8];
        if auth.is_some() {
            methods.push(0x02u8);
        }
        if tokio::time::timeout(hs_timeout, s.write_all(&[0x05u8, methods.len() as u8])).await.is_err() { return None; }
        if tokio::time::timeout(hs_timeout, s.write_all(&methods)).await.is_err() { return None; }
        let mut resp = [0u8; 2];
        match tokio::time::timeout(hs_timeout, s.read_exact(&mut resp)).await {
            Ok(Ok(_)) => {}
            _ => return None,
        }
        if resp[0] != 0x05 { return None; }
        match resp[1] {
            0x00 => Some(start.elapsed().as_secs_f64() * 1000.0),
            0x02 => {
                let (u, p) = auth?;
                match tokio::time::timeout(hs_timeout, crate::socks5::do_userpass_auth_for_healthcheck(&mut s, &u, &p)).await {
                    Ok(Ok(())) => Some(start.elapsed().as_secs_f64() * 1000.0),
                    _ => None,
                }
            }
            _ => None,
        }
    } else {
        None
    }
}

async fn check_internet_via_backend(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
) -> InternetProbeSummary {
    // Keep Internet validation cheap: one raw IP target + one domain target.
    // This is enough to distinguish a merely reachable SOCKS backend from
    // one that can really pass Internet traffic without the old fan-out burst.
    let per_probe_timeout = timeout.min(Duration::from_millis(1500));

    let ip_targets = vec![
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)), 443)),
    ];
    let domain_targets = vec![
        TargetAddr::Domain("cloudflare.com".to_string(), 443),
    ];

    let (ip_ok, ip_best) = first_successful_probe(backend, per_probe_timeout, auth.clone(), ip_targets, 1).await;
    let (domain_ok, domain_best) = if ip_ok >= 1 {
        first_successful_probe(backend, per_probe_timeout, auth, domain_targets, 1).await
    } else {
        (0usize, None)
    };

    let ok = ip_ok >= 1 && domain_ok >= 1;
    let best_ping_ms = match (ip_best, domain_best) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };

    let error_summary = if ok {
        if ip_ok >= 2 {
            format!("probe ok (ip={}/1, domain=skipped)", ip_ok)
        } else {
            format!("probe ok (ip={}/1, domain={}/1)", ip_ok, domain_ok)
        }
    } else if ip_ok >= 1 && domain_ok == 0 {
        format!("internet probes weak: raw IP works (ip={}/1) but domain/DNS failed (domain=0/1)", ip_ok)
    } else if ip_ok == 0 && domain_ok >= 1 {
        format!("internet probes weak: domain worked (domain={}/1) but raw IP failed (ip=0/1)", domain_ok)
    } else {
        format!("internet probes failed (ip={}/1, domain={}/1)", ip_ok, domain_ok)
    };

    InternetProbeSummary { ok, best_ping_ms, error_summary }
}



/// Best-effort one-shot ICMP ping used only for occasional Internet TTL sampling.
/// Returns (rtt_ms, ttl).
async fn ping_once(host: String) -> Result<Option<(f64, u32)>> {
    tokio::task::spawn_blocking(move || {
        let out = std::process::Command::new("ping")
            .args(["-n", "-c", "1", "-W", "1", &host])
            .output();

        let Ok(out) = out else { return Ok(None); };
        if !out.status.success() {
            return Ok(None);
        }

        let s = String::from_utf8_lossy(&out.stdout);
        if let Some(c) = PING_TTL_TIME_RE.captures(&s) {
            let ttl: u32 = c.get(1).unwrap().as_str().parse().unwrap_or(0);
            let ms: f64 = c.get(2).unwrap().as_str().parse().unwrap_or(0.0);
            return Ok(Some((ms, ttl)));
        }

        let ms = PING_TIME_RE
            .captures(&s)
            .and_then(|c| c.get(1))
            .and_then(|m| m.as_str().parse().ok());
        let ttl = PING_TTL_RE
            .captures(&s)
            .and_then(|c| c.get(1))
            .and_then(|m| m.as_str().parse().ok());
        Ok(ms.zip(ttl))
    })
    .await
    .context("spawn_blocking ping")?
}

pub async fn wait_for_backend_recovery(state: crate::AppState, max_wait: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + max_wait;
    let refresh_timeout = Duration::from_millis(1500);
    let quiet_wait = Duration::from_millis(1500);
    let recovery_leader = state.runtime.try_enter_recovery_waiter();

    while tokio::time::Instant::now() < deadline {
        if state.backends.lock().any_healthy() {
            if recovery_leader {
                state.runtime.leave_recovery_waiter();
            }
            return true;
        }

        if recovery_leader && state.runtime.try_begin_forced_refresh(4000) {
            refresh_backends_once(state.clone(), refresh_timeout).await;
            if state.backends.lock().any_healthy() {
                state.runtime.leave_recovery_waiter();
                return true;
            }
        }

        let now = tokio::time::Instant::now();
        if now >= deadline {
            break;
        }
        let sleep_for = (deadline - now).min(quiet_wait);
        tokio::select! {
            _ = tokio::time::sleep(sleep_for) => {},
            _ = state.runtime.backend_wakeup.notified() => {},
        }
    }
    if recovery_leader {
        state.runtime.leave_recovery_waiter();
    }
    false
}