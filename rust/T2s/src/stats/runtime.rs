const PRIORITY_SPEED_MIN_BPS: f64 = 128.0 * 1024.0;
const PRIORITY_SPEED_SWITCH_RATIO: f64 = 2.0;
const PRIORITY_SPEED_SWITCH_DELTA_BPS: f64 = 256.0 * 1024.0;
const PRIORITY_SPEED_WINDOW_SECS: u64 = 10;
const PRIORITY_SPEED_PRIMARY_MIN_BYTES: u64 = 512 * 1024;
const PRIORITY_SPEED_PROBE_MIN_BYTES: u64 = 256 * 1024;
const PRIORITY_SPEED_PROBE_INTERVAL_SECS: u64 = 45;
const PRIORITY_SPEED_HOLD_SECS: u64 = 60;
const PRIORITY_STREAM_DEGRADED_WINDOW_SECS: u64 = 10;
const PRIORITY_STREAM_DEGRADED_JITTER_RATIO: f64 = 0.15;
const PRIORITY_STREAM_DEGRADED_MAX_BPS: f64 = 96.0 * 1024.0;
const PRIORITY_STREAM_DEGRADED_MIN_AGE_SECS: u64 = 15;
const PRIORITY_STREAM_DEGRADED_MIN_BYTES: u64 = 128 * 1024;
const PRIORITY_STREAM_DEGRADED_MIN_WINDOW_BYTES: u64 = 16 * 1024;
const PRIORITY_STREAM_RECYCLE_COOLDOWN_SECS: u64 = 20;
const PRIORITY_STREAM_RECYCLE_MAX_PER_PASS: usize = 2;

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

    /// Throttle event-triggered full rechecks for a backend that failed on the hot path.
    pub next_suspect_recheck_after_ms: AtomicU64,
    /// Only one suspect-backend full recheck may run at a time.
    pub suspect_recheck_active: AtomicU64,

    /// Throttle priority speed-aware stream recycling so a flaky backend cannot
    /// cause repeated reconnect loops.
    pub next_priority_stream_recycle_after_ts: AtomicU64,
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
            next_suspect_recheck_after_ms: AtomicU64::new(0),
            suspect_recheck_active: AtomicU64::new(0),
            next_priority_stream_recycle_after_ts: AtomicU64::new(0),
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

    pub fn try_enter_suspect_recheck(&self, min_interval_ms: u64) -> bool {
        if !self.try_begin_suspect_recheck(min_interval_ms) {
            return false;
        }
        self.suspect_recheck_active
            .compare_exchange(0, 1, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    }

    fn try_begin_suspect_recheck(&self, min_interval_ms: u64) -> bool {
        let now = now_ms();
        loop {
            let next_allowed = self.next_suspect_recheck_after_ms.load(Ordering::Relaxed);
            if next_allowed > now {
                return false;
            }
            let new_next = now.saturating_add(min_interval_ms.max(1));
            match self.next_suspect_recheck_after_ms.compare_exchange(
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

    pub fn leave_suspect_recheck(&self) {
        self.suspect_recheck_active.store(0, Ordering::Relaxed);
    }

    pub fn priority_stream_recycle_allowed(&self) -> bool {
        now_ts() >= self.next_priority_stream_recycle_after_ts.load(Ordering::Relaxed)
    }

    pub fn try_begin_priority_stream_recycle(&self, cooldown_secs: u64) -> bool {
        let now = now_ts();
        loop {
            let next_allowed = self.next_priority_stream_recycle_after_ts.load(Ordering::Relaxed);
            if next_allowed > now {
                return false;
            }
            let new_next = now.saturating_add(cooldown_secs.max(1));
            match self.next_priority_stream_recycle_after_ts.compare_exchange(
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

