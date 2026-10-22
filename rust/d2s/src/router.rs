use crate::{
    backend::BackendPool,
    config::Config,
    coord::DialPriority,
    socks5::{connect_via_socks5, RuntimeFailureClass, SocksClientError},
    status::RuntimeStats,
    target::TargetAddr,
};
use anyhow::{anyhow, Context, Result};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::SocketAddr,
    os::unix::io::AsRawFd,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::{
    net::TcpStream,
    sync::Mutex as TokioMutex,
    task::JoinSet,
    time::Instant as TokioInstant,
};
use tracing::{debug, info, warn};

const DIRECT_FAILURE_THRESHOLD: u32 = 3;
const DIRECT_FAILURE_COOLDOWN_MS: u64 = 5_000;

/// Cached pre-connected tunnels per target. Each warm hit consumes one tunnel
/// and schedules one background replacement. A separate bounded proactive
/// refresher may replace tunnels for the hottest recently-used resolver targets
/// before TTL expiry, but stops after the recent-activity window.
const WARM_TUNNELS_PER_TARGET: usize = 4;

/// How many recently used DNS targets are remembered as warmup candidates.
const HOT_TARGETS_CAP: usize = 8;

struct WarmTunnel {
    created: Instant,
    stream: TcpStream,
    backend: Option<SocketAddr>,
}

/// One hedged dial attempt, resolved by its own timeout window inside the
/// spawned task so the coordinator never blocks on a single backend.
struct AttemptOutcome {
    backend: SocketAddr,
    started: Instant,
    window_ms: u64,
    result: Result<TcpStream, SocksClientError>,
}

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
enum DialPurpose {
    ForegroundDns,
    Background,
}

impl DialPurpose {
    fn is_foreground(self) -> bool {
        self == Self::ForegroundDns
    }

    fn priority(self) -> DialPriority {
        if self.is_foreground() {
            DialPriority::ForegroundDns
        } else {
            DialPriority::Background
        }
    }

    fn coordination_wait(self, config: &Config) -> Duration {
        if self.is_foreground() {
            config.foreground_coordination_wait()
        } else {
            config.background_coordination_wait()
        }
    }

    fn attempt_timeout(self, config: &Config) -> Duration {
        config.coordinated_attempt_timeout(self.is_foreground())
    }
}

#[derive(Debug, Clone, Copy, Eq, PartialEq)]
pub enum RouteKind {
    Socks,
    Direct,
}

pub struct RoutedStream {
    pub stream: TcpStream,
    pub route: RouteKind,
    pub backend: Option<SocketAddr>,
}

#[derive(Default)]
struct DirectHealth {
    failures: AtomicU32,
    cooldown_until_ms: AtomicU64,
}

impl DirectHealth {
    fn allowed(&self) -> bool {
        now_ms() >= self.cooldown_until_ms.load(Ordering::Relaxed)
    }

    fn note_failure(&self) {
        let failures = self.failures.fetch_add(1, Ordering::Relaxed).saturating_add(1);
        if failures >= DIRECT_FAILURE_THRESHOLD {
            self.cooldown_until_ms
                .store(now_ms().saturating_add(DIRECT_FAILURE_COOLDOWN_MS), Ordering::Relaxed);
        }
    }

    fn note_success(&self) {
        self.failures.store(0, Ordering::Relaxed);
        self.cooldown_until_ms.store(0, Ordering::Relaxed);
    }
}

#[derive(Clone)]
pub struct Router {
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    direct_fallback_active: Arc<AtomicBool>,
    direct_health: Arc<DirectHealth>,
    warm_cache: Arc<TokioMutex<HashMap<TargetAddr, VecDeque<WarmTunnel>>>>,
    /// Recently used DNS targets, most recent last. The warmup preconnect
    /// establishes a tunnel to the hottest one right after a backend turns
    /// GREEN, so the first real query does not pay route establishment.
    hot_targets: Arc<std::sync::Mutex<HashMap<TargetAddr, Instant>>>,
    /// At most one proactive refresh loop per recently-used resolver target.
    warm_refresh_tasks: Arc<std::sync::Mutex<HashSet<TargetAddr>>>,
    /// Stops proactive network work as soon as server shutdown begins.
    warm_shutdown: Arc<AtomicBool>,
}

impl Router {
    pub fn new(config: Arc<Config>, pool: BackendPool, stats: Arc<RuntimeStats>) -> Self {
        Self {
            config,
            pool,
            stats,
            direct_fallback_active: Arc::new(AtomicBool::new(false)),
            direct_health: Arc::new(DirectHealth::default()),
            warm_cache: Arc::new(TokioMutex::new(HashMap::new())),
            hot_targets: Arc::new(std::sync::Mutex::new(HashMap::new())),
            warm_refresh_tasks: Arc::new(std::sync::Mutex::new(HashSet::new())),
            warm_shutdown: Arc::new(AtomicBool::new(false)),
        }
    }

    fn note_hot_target(&self, target: &TargetAddr) {
        let mut hot = self.hot_targets.lock().expect("hot targets lock poisoned");
        hot.insert(target.clone(), Instant::now());
        if hot.len() > HOT_TARGETS_CAP {
            if let Some(oldest) = hot
                .iter()
                .min_by_key(|(_, at)| **at)
                .map(|(target, _)| target.clone())
            {
                hot.remove(&oldest);
            }
        }
    }

    fn hottest_target(&self) -> Option<TargetAddr> {
        let hot = self.hot_targets.lock().expect("hot targets lock poisoned");
        hot.iter()
            .max_by_key(|(_, at)| **at)
            .map(|(target, _)| target.clone())
    }

    /// Keep only the two most recently used resolver targets proactively warm.
    /// dnscrypt-proxy can rotate resolvers, so maintaining just one target can
    /// still leave the next query cold; limiting this to two bounds background
    /// sockets/network work even when many resolvers were seen historically.
    fn is_recent_top_warm_target(&self, target: &TargetAddr) -> bool {
        let hot = self.hot_targets.lock().expect("hot targets lock poisoned");
        let Some(target_at) = hot.get(target).copied() else { return false };
        if target_at.elapsed() > self.config.warm_active_window() {
            return false;
        }
        let newer = hot.values().filter(|at| **at > target_at).count();
        newer < 2
    }

    pub fn begin_shutdown(&self) {
        self.warm_shutdown.store(true, Ordering::Release);
    }

    fn schedule_proactive_warm_refresh(&self, target: TargetAddr) {
        if !self.config.warm_tunnels || self.warm_shutdown.load(Ordering::Acquire) {
            return;
        }
        {
            let mut tasks = self
                .warm_refresh_tasks
                .lock()
                .expect("warm refresh task lock poisoned");
            if !tasks.insert(target.clone()) {
                return;
            }
        }

        let router = self.clone();
        tokio::spawn(async move {
            router.proactive_warm_refresh_loop(target.clone()).await;
            router
                .warm_refresh_tasks
                .lock()
                .expect("warm refresh task lock poisoned")
                .remove(&target);
            // Close the tiny exit/remove race: a foreground DNS request may
            // have made this target hot again while the old task was exiting.
            // Re-arm maintenance if it is still eligible after releasing the
            // task guard.
            if !router.warm_shutdown.load(Ordering::Acquire)
                && router.is_recent_top_warm_target(&target)
            {
                router.schedule_proactive_warm_refresh(target);
            }
        });
    }

    async fn proactive_warm_refresh_loop(&self, target: TargetAddr) {
        const RETRY_AFTER_FAILURE: Duration = Duration::from_secs(2);
        loop {
            if self.warm_shutdown.load(Ordering::Acquire)
                || !self.is_recent_top_warm_target(&target)
            {
                return;
            }

            let delay = self.time_until_warm_refresh(&target).await;
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }

            if self.warm_shutdown.load(Ordering::Acquire)
                || !self.is_recent_top_warm_target(&target)
            {
                return;
            }

            // A normal foreground query may have consumed/replaced the tunnel
            // while this task slept. Recalculate before dialing so proactive
            // maintenance never creates duplicate connections unnecessarily.
            if !self.warm_refresh_due(&target).await {
                continue;
            }

            if !self.proactive_refresh_warm_tunnel(&target).await {
                // Backend/coordination can be temporarily busy. Retry soon
                // while the target is still active, but never change health or
                // fall back DIRECT from this background task.
                tokio::time::sleep(RETRY_AFTER_FAILURE).await;
            }
        }
    }

    async fn time_until_warm_refresh(&self, target: &TargetAddr) -> Duration {
        const EMPTY_CACHE_RETRY: Duration = Duration::from_millis(750);
        let refresh_after = self.config.warm_refresh_interval();
        let cache = self.warm_cache.lock().await;
        let Some(newest) = cache.get(target).and_then(|queue| queue.back()) else {
            return EMPTY_CACHE_RETRY;
        };
        refresh_after.saturating_sub(newest.created.elapsed())
    }

    async fn warm_refresh_due(&self, target: &TargetAddr) -> bool {
        let refresh_after = self.config.warm_refresh_interval();
        let cache = self.warm_cache.lock().await;
        cache
            .get(target)
            .and_then(|queue| queue.back())
            .map(|newest| newest.created.elapsed() >= refresh_after)
            .unwrap_or(true)
    }

    async fn proactive_refresh_warm_tunnel(&self, target: &TargetAddr) -> bool {
        let candidates = self.pool.candidate_order().await;
        if candidates.is_empty() {
            return false;
        }
        let deadline = TokioInstant::now() + self.config.route_budget();
        let routed = if candidates.len() > 1 {
            self.connect_hedged(candidates, target, deadline, DialPurpose::Background).await
        } else {
            self.connect_sequential(candidates, target, deadline, DialPurpose::Background).await
        };
        let Ok(routed) = routed else { return false };
        let Some(backend) = routed.backend else { return false };
        if self.warm_shutdown.load(Ordering::Acquire) {
            return false;
        }
        self.replace_warm_tunnel(target.clone(), backend, routed.stream)
            .await;
        debug!(%target, %backend, "proactively refreshed hot DNS tunnel");
        true
    }

    pub async fn connect(&self, target: &TargetAddr) -> Result<RoutedStream> {
        self.reject_recursive_target(target)?;

        if let Some(routed) = self.try_take_warm_tunnel(target).await {
            self.schedule_warm_refill(target.clone());
            self.schedule_proactive_warm_refresh(target.clone());
            return Ok(routed);
        }

        // dnscrypt-proxy uses a plain SOCKS Dialer in several paths and that
        // dial can outlive the caller context. Keep route establishment inside
        // DNSCrypt's own query timeout.
        let deadline = TokioInstant::now() + self.config.route_budget();
        let candidates = self.pool.candidate_order().await;
        let routed = if candidates.len() > 1 {
            self.connect_hedged(candidates, target, deadline, DialPurpose::ForegroundDns).await?
        } else {
            self.connect_sequential(candidates, target, deadline, DialPurpose::ForegroundDns).await?
        };

        if self.warm_eligible(&routed) {
            self.schedule_warm_refill(target.clone());
            self.schedule_proactive_warm_refresh(target.clone());
        }
        Ok(routed)
    }

    /// A cached tunnel is served only while it is younger than
    /// `warm_tunnel_ttl_secs` and its backend is still selectable; older
    /// entries and tunnels of degraded backends are closed instead of reused
    /// because a half-open upstream session would otherwise turn the first
    /// DNS request after a quiet period into a guaranteed stall.
    fn warm_stream_looks_alive(stream: &TcpStream) -> bool {
        let mut byte = 0u8;
        let rc = unsafe {
            libc::recv(
                stream.as_raw_fd(),
                &mut byte as *mut u8 as *mut libc::c_void,
                1,
                libc::MSG_PEEK | libc::MSG_DONTWAIT,
            )
        };
        if rc > 0 {
            return true;
        }
        if rc == 0 {
            return false;
        }
        let error = std::io::Error::last_os_error();
        let code = error.raw_os_error();
        code == Some(libc::EAGAIN)
            || code == Some(libc::EWOULDBLOCK)
            || code == Some(libc::EINTR)
    }

    async fn try_take_warm_tunnel(&self, target: &TargetAddr) -> Option<RoutedStream> {
        if !self.config.warm_tunnels {
            return None;
        }
        let ttl = self.config.warm_tunnel_ttl();

        loop {
            let tunnel = {
                let mut cache = self.warm_cache.lock().await;
                let queue = cache.get_mut(target)?;

                // Drop expired entries one-by-one. The old implementation
                // cleared the entire queue when the oldest entry expired,
                // which could throw away a fresh replacement behind it and
                // recreate the exact cold-start pause we are trying to avoid.
                while queue
                    .front()
                    .is_some_and(|tunnel| tunnel.created.elapsed() > ttl)
                {
                    queue.pop_front();
                }
                let tunnel = queue.pop_front();
                if queue.is_empty() {
                    cache.remove(target);
                }
                tunnel
            }?;

            let Some(backend) = tunnel.backend else { continue };
            if !Self::warm_stream_looks_alive(&tunnel.stream) {
                debug!(%target, %backend, "discarded closed warm tunnel before serving it");
                continue;
            }
            if !self.pool.backend_selectable(backend).await {
                debug!(%target, %backend, "discarded warm tunnel of a degraded backend");
                continue;
            }

            self.note_hot_target(target);
            self.stats.warm_tunnel_hits.fetch_add(1, Ordering::Relaxed);
            self.note_socks_restored();
            debug!(%target, %backend, "served request from a warm pre-connected tunnel");
            return Some(RoutedStream {
                stream: tunnel.stream,
                route: RouteKind::Socks,
                backend: Some(backend),
            });
        }
    }

    fn warm_eligible(&self, routed: &RoutedStream) -> bool {
        // DNSCrypt reconnects to the same resolver addresses repeatedly (native
        // DNSCrypt on 443/8443, DoH keep-alive pools), so any SOCKS-routed
        // target is worth one pre-connected replacement tunnel. DIRECT streams
        // are never cached: they skip SOCKS accounting and have their own
        // cooldown semantics.
        self.config.warm_tunnels && routed.backend.is_some()
    }

    /// Replace a consumed (or newly created) warm tunnel in the background so
    /// the request path never waits for the reactive replacement dial. The spawned task
    /// owns its sockets and every dial step is timeout-bounded, so a dropped
    /// or cancelled refill simply closes its half-open connection; it cannot
    /// leak relay workers the way a detached relay copy could.
    fn schedule_warm_refill(&self, target: TargetAddr) {
        let router = self.clone();
        tokio::spawn(async move {
            router.refill_warm_tunnel(&target).await;
        });
    }

    async fn refill_warm_tunnel(&self, target: &TargetAddr) {
        let candidates = self.pool.candidate_order().await;
        if candidates.is_empty() {
            // No GREEN backend: a background refill must never manufacture a
            // DIRECT connection on its own.
            return;
        }
        let deadline = TokioInstant::now() + self.config.route_budget();
        let routed = if candidates.len() > 1 {
            self.connect_hedged(candidates, target, deadline, DialPurpose::Background).await
        } else {
            self.connect_sequential(candidates, target, deadline, DialPurpose::Background).await
        };
        let Ok(routed) = routed else { return };
        // Never cache DIRECT streams: they skip SOCKS accounting and the
        // direct path has its own cooldown semantics.
        let Some(backend) = routed.backend else { return };
        self.store_warm_tunnel(target.clone(), backend, routed.stream)
            .await;
    }

    /// Store one pre-connected tunnel, sweeping expired entries of every
    /// target first and capping the queue length.
    async fn store_warm_tunnel(&self, target: TargetAddr, backend: SocketAddr, stream: TcpStream) {
        let ttl = self.config.warm_tunnel_ttl();
        let mut cache = self.warm_cache.lock().await;
        // Opportunistic sweep: drop expired tunnels of other targets that no
        // request has touched since their TTL elapsed.
        for queue in cache.values_mut() {
            while queue.front().is_some_and(|tunnel| tunnel.created.elapsed() > ttl) {
                queue.pop_front();
            }
        }
        let queue = cache.entry(target).or_default();
        queue.push_back(WarmTunnel {
            created: Instant::now(),
            stream,
            backend: Some(backend),
        });
        while queue.len() > WARM_TUNNELS_PER_TARGET {
            queue.pop_front();
        }
    }

    /// A proactive refresh supersedes older idle tunnels for the same target.
    /// Install the new connection atomically and drop old sockets afterwards so
    /// the request path always sees the freshest possible warm candidate.
    async fn replace_warm_tunnel(
        &self,
        target: TargetAddr,
        backend: SocketAddr,
        stream: TcpStream,
    ) {
        let mut cache = self.warm_cache.lock().await;
        let queue = cache.entry(target).or_default();
        queue.clear();
        queue.push_back(WarmTunnel {
            created: Instant::now(),
            stream,
            backend: Some(backend),
        });
    }

    async fn warm_cache_has_fresh(&self, target: &TargetAddr) -> bool {
        let ttl = self.config.warm_tunnel_ttl();
        let cache = self.warm_cache.lock().await;
        cache
            .get(target)
            .map(|queue| queue.iter().any(|tunnel| tunnel.created.elapsed() <= ttl))
            .unwrap_or(false)
    }

    /// Proactive warmup for a freshly (re-)GREEN backend: pre-establish one
    /// tunnel to the hottest known DNS target so the first real query does not
    /// pay the full route establishment cost. Without this, every recovery
    /// made the first DNS requests slow until the transport session warmed up.
    async fn preconnect_warm_tunnel(&self, backend: SocketAddr) {
        let Some(target) = self.hottest_target() else {
            // No DNS target observed yet (fresh start): the first real request
            // warms the cache the usual way.
            return;
        };
        if !self.is_recent_top_warm_target(&target) {
            return;
        }
        if self.warm_cache_has_fresh(&target).await {
            return;
        }
        if !self.pool.backend_selectable(backend).await {
            return;
        }
        let attempt = tokio::time::timeout(
            DialPurpose::Background.attempt_timeout(&self.config),
            connect_via_socks5(
                backend,
                &target,
                self.config.connect_timeout(),
                self.config.upstream_handshake_timeout(),
                self.config.tcp_nodelay,
                DialPurpose::Background.priority(),
                DialPurpose::Background.coordination_wait(&self.config),
                self.config.backend_attempt_timeout(),
            ),
        )
        .await;
        let Ok(Ok(stream)) = attempt else {
            debug!(%backend, "warmup preconnect failed; the next real request warms the cache as before");
            return;
        };
        // Re-check after the dial: the backend may have degraded meanwhile.
        if !self.pool.backend_selectable(backend).await {
            return;
        }
        self.store_warm_tunnel(target.clone(), backend, stream).await;
        self.schedule_proactive_warm_refresh(target);
        debug!(%backend, "warm tunnel pre-connected after GREEN transition");
    }

    /// Hedged route establishment (Happy-Eyeballs style), used when several
    /// GREEN candidates exist. The first candidate starts immediately; if it
    /// is still unresolved after the stagger window (widened by the
    /// candidate's own measured runtime latency, so healthy mobile RTT jitter
    /// never spawns duplicate connects), the next selectable candidate dials
    /// in parallel. At most two attempts run at once and the first winner is
    /// used. A losing attempt is never aborted mid-handshake: it is handed to
    /// a bounded janitor that applies the original per-attempt health
    /// bookkeeping (success refreshes the runtime EWMA, timeout marks a soft
    /// failure and schedules the strict Full recheck), so backend health
    /// semantics are identical to the sequential dialer.
    async fn connect_hedged(
        &self,
        candidates: Vec<SocketAddr>,
        target: &TargetAddr,
        deadline: TokioInstant,
        purpose: DialPurpose,
    ) -> Result<RoutedStream> {
        const BUDGET_EXHAUSTED: &str =
            "dnscrypt route budget exhausted before trying all backends";

        let mut failures = Vec::new();
        if TokioInstant::now() >= deadline {
            failures.push(BUDGET_EXHAUSTED.to_string());
            return self.finish_with_direct(target, failures, deadline, purpose.is_foreground()).await;
        }

        let mut next_candidate = 1usize;
        let mut in_flight: JoinSet<AttemptOutcome> = JoinSet::new();
        let head = candidates[0];
        self.start_hedged_attempt(&mut in_flight, head, target, deadline, purpose)
            .await;

        // One hedge window per request, anchored to the first dial.
        let stagger = self.hedge_window(head).await;
        let stagger_at = TokioInstant::now() + stagger;
        let mut stagger_armed = true;

        // The join_next() future borrows `in_flight` for the whole select, so
        // every mutation of the set (spawning the hedge, the sequential tail,
        // the janitor handoff) happens after the select resolves.
        enum Step {
            Joined(Option<Result<AttemptOutcome, tokio::task::JoinError>>),
            Staggered,
        }

        loop {
            let step = tokio::select! {
                joined = in_flight.join_next() => Step::Joined(joined),
                _ = tokio::time::sleep_until(stagger_at), if stagger_armed => Step::Staggered,
            };

            match step {
                Step::Joined(Some(Ok(outcome))) => {
                    // Any completion closes the single hedge window.
                    stagger_armed = false;
                    let AttemptOutcome { backend, started, window_ms, result } = outcome;
                    match result {
                        Ok(stream) => {
                            self.pool
                                .mark_runtime_success(backend, started.elapsed())
                                .await;
                            self.stats
                                .upstream_connections
                                .fetch_add(1, Ordering::Relaxed);
                            self.note_socks_restored();
                            if purpose.is_foreground() {
                                self.note_hot_target(target);
                            }
                            debug!(backend = %backend, %target, "hedged dial won");

                            if !in_flight.is_empty() {
                                // Losing attempts keep their own timeout window
                                // and original health bookkeeping; the janitor
                                // outlives this request by at most one window.
                                let mut leftover =
                                    std::mem::replace(&mut in_flight, JoinSet::new());
                                let router = self.clone();
                                tokio::spawn(async move {
                                    while let Some(joined) = leftover.join_next().await {
                                        if let Ok(AttemptOutcome {
                                            backend,
                                            started,
                                            window_ms,
                                            result,
                                        }) = joined
                                        {
                                            router
                                                .settle_attempt(backend, started, window_ms, result, purpose)
                                                .await;
                                        }
                                    }
                                });
                            }
                            return Ok(RoutedStream {
                                stream,
                                route: RouteKind::Socks,
                                backend: Some(backend),
                            });
                        }
                        Err(error) => {
                            if let Some(message) = self
                                .settle_attempt(backend, started, window_ms, Err(error), purpose)
                                .await
                            {
                                failures.push(message);
                            }
                        }
                    }
                }
                Step::Joined(Some(Err(error))) => {
                    failures.push(format!("attempt task failed: {error}"));
                }
                // Defensive: the sequential tail below always keeps one attempt
                // in flight or exits the loop.
                Step::Joined(None) => break,
                Step::Staggered => {
                    stagger_armed = false;
                    if let Some(&candidate) = candidates.get(next_candidate) {
                        if self.pool.backend_selectable(candidate).await {
                            self.start_hedged_attempt(&mut in_flight, candidate, target, deadline, purpose)
                                .await;
                            next_candidate += 1;
                        }
                    }
                }
            }

            // Sequential failover tail: whenever no attempt is pending, start
            // the next candidate immediately. This preserves the original
            // behavior of exhausting the GREEN candidate list within the
            // dnscrypt route budget.
            if in_flight.is_empty() {
                if let Some(&candidate) = candidates.get(next_candidate) {
                    if TokioInstant::now() >= deadline {
                        failures.push(BUDGET_EXHAUSTED.to_string());
                        break;
                    }
                    self.start_hedged_attempt(&mut in_flight, candidate, target, deadline, purpose)
                        .await;
                    next_candidate += 1;
                } else {
                    break;
                }
            }
        }

        self.finish_with_direct(target, failures, deadline, purpose.is_foreground()).await
    }

    async fn start_hedged_attempt(
        &self,
        in_flight: &mut JoinSet<AttemptOutcome>,
        backend: SocketAddr,
        target: &TargetAddr,
        deadline: TokioInstant,
        purpose: DialPurpose,
    ) {
        let now = TokioInstant::now();
        if now >= deadline {
            return;
        }
        let window = purpose.attempt_timeout(&self.config).min(deadline - now);
        self.pool.mark_attempt(backend).await;
        let connect_timeout = self.config.connect_timeout();
        let handshake_timeout = self.config.upstream_handshake_timeout();
        let tcp_nodelay = self.config.tcp_nodelay;
        let priority = purpose.priority();
        let coordination_wait = purpose.coordination_wait(&self.config);
        let backend_attempt_timeout = self.config.backend_attempt_timeout();
        let window_ms = window.as_millis() as u64;
        let target = target.clone();
        in_flight.spawn(async move {
            let started = Instant::now();
            let result = match tokio::time::timeout(
                window,
                connect_via_socks5(
                    backend,
                    &target,
                    connect_timeout,
                    handshake_timeout,
                    tcp_nodelay,
                    priority,
                    coordination_wait,
                    backend_attempt_timeout,
                ),
            )
            .await
            {
                Ok(inner) => inner,
                Err(_) => Err(SocksClientError::Timeout("hedged backend attempt")),
            };
            AttemptOutcome { backend, started, window_ms, result }
        });
    }

    /// Stagger delay before a second candidate is dialed in parallel. The base
    /// window is widened to twice the first candidate's runtime EWMA so a
    /// merely slow-but-healthy route is not raced by redundant connects.
    async fn hedge_window(&self, backend: SocketAddr) -> Duration {
        let base = self.config.hedge_stagger();
        match self.pool.runtime_ewma_ms(backend).await {
            Some(ewma) if ewma.is_finite() && ewma > 0.0 => {
                let widened = Duration::from_secs_f64(ewma / 1000.0).saturating_mul(2);
                base.max(widened)
            }
            _ => base,
        }
    }

    /// Health/stat bookkeeping for one finished attempt, shared by the request
    /// loop and the janitor that settles losing attempts. Returns the failure
    /// summary line when the attempt failed.
    async fn settle_attempt(
        &self,
        backend: SocketAddr,
        started: Instant,
        window_ms: u64,
        result: Result<TcpStream, SocksClientError>,
        purpose: DialPurpose,
    ) -> Option<String> {
        match result {
            Ok(stream) => {
                // A slow loser that eventually connected is real evidence: its
                // slower sample naturally raises the runtime EWMA.
                drop(stream);
                self.pool.mark_runtime_success(backend, started.elapsed()).await;
                self.stats.upstream_connections.fetch_add(1, Ordering::Relaxed);
                None
            }
            Err(error) => {
                if error.is_coordination_busy() {
                    let message = error.to_string();
                    debug!(%backend, %message, "backend dial deferred by shared T2S/D2S coordination");
                    return Some(format!("{backend}: {message}"));
                }
                if !purpose.is_foreground() {
                    let message = error.to_string();
                    debug!(%backend, %message, "background warm dial failed without changing backend health");
                    return Some(format!("{backend}: {message}"));
                }
                let class = error.runtime_failure_class();
                let message = match &error {
                    SocksClientError::Timeout("hedged backend attempt") => {
                        format!("backend attempt exceeded {window_ms} ms")
                    }
                    other => other.to_string(),
                };
                self.pool
                    .mark_runtime_failure(backend, class, &message)
                    .await;
                Some(format!("{backend}: {message}"))
            }
        }
    }

    /// Sequential dialer for zero/one candidate. Foreground DNS uses the
    /// shared coordinator and keeps the existing one-shot transient retry;
    /// coordination contention is retried once without poisoning backend health.
    async fn connect_sequential(
        &self,
        candidates: Vec<SocketAddr>,
        target: &TargetAddr,
        deadline: TokioInstant,
        purpose: DialPurpose,
    ) -> Result<RoutedStream> {
        let single_backend_mode = candidates.len() == 1;
        let mut failures = Vec::new();

        for backend in candidates {
            let now = TokioInstant::now();
            if now >= deadline {
                failures.push("dnscrypt route budget exhausted before trying all backends".to_string());
                break;
            }
            let remaining = deadline - now;
            let attempt_timeout = purpose.attempt_timeout(&self.config).min(remaining);

            self.pool.mark_attempt(backend).await;
            let started = Instant::now();
            let attempt = tokio::time::timeout(
                attempt_timeout,
                connect_via_socks5(
                    backend,
                    target,
                    self.config.connect_timeout(),
                    self.config.upstream_handshake_timeout(),
                    self.config.tcp_nodelay,
                    purpose.priority(),
                    purpose.coordination_wait(&self.config),
                    self.config.backend_attempt_timeout(),
                ),
            )
            .await;
            match attempt {
                Ok(Ok(stream)) => {
                    // Runtime success does not promote an unverified backend to
                    // GREEN; only the strict Full health probe may do that.
                    self.pool.mark_runtime_success(backend, started.elapsed()).await;
                    self.stats
                        .upstream_connections
                        .fetch_add(1, Ordering::Relaxed);
                    self.note_socks_restored();
                    if purpose.is_foreground() {
                        self.note_hot_target(target);
                    }
                    debug!(%backend, %target, "routed connection through SOCKS5 backend");
                    return Ok(RoutedStream {
                        stream,
                        route: RouteKind::Socks,
                        backend: Some(backend),
                    });
                }
                Ok(Err(mut error)) => {
                    // A busy coordination slot is not a backend failure. In the
                    // important single-backend DNS case give the priority lease
                    // one extra short attempt within the existing route budget.
                    // Keep the existing Android-route retry for network/host
                    // unreachable replies as well.
                    let coordination_busy = error.is_coordination_busy();
                    let should_retry = single_backend_mode
                        && purpose.is_foreground()
                        && (coordination_busy || error.should_retry_once_on_single_backend());
                    if should_retry {
                        let retry_delay = if coordination_busy {
                            Duration::from_millis(20)
                        } else {
                            Duration::from_millis(75)
                        };
                        let now = TokioInstant::now();
                        if now < deadline && deadline - now > retry_delay {
                            tokio::time::sleep(retry_delay).await;
                            let now = TokioInstant::now();
                            if now < deadline {
                                let remaining = deadline - now;
                                let retry_timeout = purpose.attempt_timeout(&self.config).min(remaining);
                                let retry_started = Instant::now();
                                match tokio::time::timeout(
                                    retry_timeout,
                                    connect_via_socks5(
                                        backend,
                                        target,
                                        self.config.connect_timeout(),
                                        self.config.upstream_handshake_timeout(),
                                        self.config.tcp_nodelay,
                                        purpose.priority(),
                                        purpose.coordination_wait(&self.config),
                                        self.config.backend_attempt_timeout(),
                                    ),
                                )
                                .await
                                {
                                    Ok(Ok(stream)) => {
                                        self.pool.mark_runtime_success(backend, retry_started.elapsed()).await;
                                        self.stats
                                            .upstream_connections
                                            .fetch_add(1, Ordering::Relaxed);
                                        self.note_socks_restored();
                                        if purpose.is_foreground() {
                                            self.note_hot_target(target);
                                        }
                                        debug!(%backend, %target, "single-backend retry succeeded");
                                        return Ok(RoutedStream {
                                            stream,
                                            route: RouteKind::Socks,
                                            backend: Some(backend),
                                        });
                                    }
                                    Ok(Err(retry_error)) => error = retry_error,
                                    Err(_) => {
                                        let message = format!(
                                            "single-backend retry exceeded {} ms",
                                            retry_timeout.as_millis()
                                        );
                                        self.pool
                                            .mark_runtime_failure(
                                                backend,
                                                RuntimeFailureClass::Soft,
                                                &message,
                                            )
                                            .await;
                                        failures.push(format!("{backend}: {message}"));
                                        continue;
                                    }
                                }
                            }
                        }
                    }

                    let message = error.to_string();
                    if error.is_coordination_busy() {
                        // Another local T2S/D2S handshake owned the backend
                        // slot. Do not poison health or cooldown for contention
                        // that says nothing about backend reachability.
                        debug!(%backend, %message, "SOCKS backend busy in shared dial coordinator");
                        failures.push(format!("{backend}: {message}"));
                        continue;
                    }
                    if purpose.is_foreground() {
                        let class = error.runtime_failure_class();
                        self.pool.mark_runtime_failure(backend, class, &message).await;
                    } else {
                        debug!(%backend, %message, "background warm dial failed without changing backend health");
                    }
                    failures.push(format!("{backend}: {message}"));
                }
                Err(_) => {
                    let message = format!(
                        "backend attempt exceeded {} ms",
                        attempt_timeout.as_millis()
                    );
                    if purpose.is_foreground() {
                        self.pool
                            .mark_runtime_failure(backend, RuntimeFailureClass::Soft, &message)
                            .await;
                    } else {
                        debug!(%backend, %message, "background warm timeout ignored for backend health");
                    }
                    failures.push(format!("{backend}: {message}"));
                }
            }
        }

        self.finish_with_direct(target, failures, deadline, purpose.is_foreground()).await
    }

    async fn finish_with_direct(
        &self,
        target: &TargetAddr,
        failures: Vec<String>,
        deadline: TokioInstant,
        allow_direct: bool,
    ) -> Result<RoutedStream> {
        if !allow_direct {
            return Err(anyhow!(
                "background SOCKS route deferred/failed for {target}; failures: {}",
                failures.join(" | ")
            ));
        }
        if !self.config.direct_fallback {
            return Err(anyhow!(
                "no SOCKS5 backend could reach {target}; direct fallback is disabled; failures: {}",
                failures.join(" | ")
            ));
        }

        // T2S tracks DIRECT independently from SOCKS health. D2S keeps a small,
        // DNS-specific version: after repeated direct failures, skip the same
        // doomed path briefly instead of making every DNS query wait for it.
        if !self.direct_health.allowed() {
            return Err(anyhow!(
                "DIRECT fallback temporarily suppressed after repeated failures; SOCKS failures: {}",
                failures.join(" | ")
            ));
        }

        self.note_direct_fallback(target, &failures);
        let stream = match connect_direct(target, &self.config, deadline).await {
            Ok(stream) => stream,
            Err(error) => {
                self.direct_health.note_failure();
                return Err(error);
            }
        };
        self.stats.direct_connections.fetch_add(1, Ordering::Relaxed);
        Ok(RoutedStream {
            stream,
            route: RouteKind::Direct,
            backend: None,
        })
    }

    pub async fn report_relay_failure(
        &self,
        route: RouteKind,
        backend: Option<SocketAddr>,
        error: &str,
    ) {
        match (route, backend) {
            (RouteKind::Socks, Some(addr)) => self.pool.mark_relay_suspect(addr, error).await,
            (RouteKind::Direct, _) => self.direct_health.note_failure(),
            _ => {}
        }
    }

    pub fn report_relay_success(
        &self,
        route: RouteKind,
        backend: Option<SocketAddr>,
        remote_to_client: u64,
    ) {
        // For DIRECT, receiving actual payload is stronger evidence than TCP
        // connect alone and clears the failure cooldown.
        if route == RouteKind::Direct && remote_to_client > 0 {
            self.direct_health.note_success();
        }
        // A relayed DNS exchange that delivered downstream bytes is the
        // strongest DNS-fitness proof: clear any accumulated DNS-path
        // exclusion for the backend that served it.
        if route == RouteKind::Socks && remote_to_client > 0 {
            if let Some(backend) = backend {
                let pool = self.pool.clone();
                tokio::spawn(async move {
                    pool.mark_dns_path_ok(backend).await;
                });
            }
        }
    }

    fn note_direct_fallback(&self, target: &TargetAddr, failures: &[String]) {
        if !self.direct_fallback_active.swap(true, Ordering::Relaxed) {
            if failures.is_empty() {
                info!(%target, "no GREEN SOCKS5 backends; entering DIRECT fallback");
            } else {
                warn!(%target, failures = %failures.join(" | "), "SOCKS5 backends failed; entering DIRECT fallback");
            }
        } else {
            debug!(%target, "using DIRECT fallback");
        }
    }

    fn note_socks_restored(&self) {
        if self.direct_fallback_active.swap(false, Ordering::Relaxed) {
            info!("SOCKS5 routing restored; leaving DIRECT fallback");
        }
    }

    fn reject_recursive_target(&self, target: &TargetAddr) -> Result<()> {
        match target {
            TargetAddr::Ip(addr) => {
                let listener_loop = addr.port() == self.config.listen.port()
                    && addr.ip().is_loopback()
                    && (self.config.listen.ip().is_loopback() || self.config.listen.ip().is_unspecified());
                if listener_loop || self.config.backends.contains(addr) {
                    return Err(anyhow!("refusing recursive D2S target {addr}"));
                }
            }
            TargetAddr::Domain(host, port) => {
                if *port == self.config.listen.port()
                    && (host.eq_ignore_ascii_case("localhost") || host == "127.0.0.1" || host == "::1")
                {
                    return Err(anyhow!("refusing recursive D2S target {host}:{port}"));
                }
            }
        }
        Ok(())
    }
}

impl crate::backend::GreenTransitionHook for Router {
    fn on_backend_green(&self, backend: SocketAddr) {
        if !self.config.warm_tunnels {
            return;
        }
        let router = self.clone();
        tokio::spawn(async move {
            router.preconnect_warm_tunnel(backend).await;
        });
    }
}

async fn connect_direct(target: &TargetAddr, config: &Config, deadline: TokioInstant) -> Result<TcpStream> {
    if TokioInstant::now() >= deadline {
        return Err(anyhow!("DIRECT connect to {target} skipped: dnscrypt route budget exhausted"));
    }

    let addresses = tokio::time::timeout_at(deadline, target.resolve())
        .await
        .map_err(|_| anyhow!("DIRECT target resolution for {target} exceeded dnscrypt route budget"))??;
    let mut errors = Vec::new();
    for addr in addresses {
        let now = TokioInstant::now();
        if now >= deadline {
            errors.push("dnscrypt route budget exhausted".to_string());
            break;
        }
        let remaining = deadline - now;
        let attempt_timeout = config.direct_connect_timeout().min(remaining);
        match tokio::time::timeout(attempt_timeout, TcpStream::connect(addr)).await {
            Ok(Ok(stream)) => {
                let _ = stream.set_nodelay(config.tcp_nodelay);
                return Ok(stream);
            }
            Ok(Err(error)) => errors.push(format!("{addr}: {error}")),
            Err(_) => errors.push(format!("{addr}: timeout after {} ms", attempt_timeout.as_millis())),
        }
    }
    Err(anyhow!("DIRECT connect to {target} failed: {}", errors.join(" | ")))
        .with_context(|| format!("direct fallback failed for {target}"))
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn direct_health_cools_down_after_repeated_failures_and_recovers_on_payload() {
        let health = DirectHealth::default();
        assert!(health.allowed());
        health.note_failure();
        health.note_failure();
        assert!(health.allowed());
        health.note_failure();
        assert!(!health.allowed());
        health.note_success();
        assert!(health.allowed());
    }
}
