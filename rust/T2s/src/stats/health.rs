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
    wrapper: Option<SocketAddr>,
    wrapper_auth: Option<(String, String)>,
) -> (Option<String>, Option<f64>, Option<f64>, Option<u32>) {
    let socks_ping_ms = check_backend_rtt(backend, timeout, auth.clone(), wrapper, wrapper_auth.clone()).await;
    let mut err = if socks_ping_ms.is_some() { None } else { Some("connect/greeting/auth failed".to_string()) };
    let internet_ping_ms = if socks_ping_ms.is_some() && probe_mode == ProbeMode::Full {
        let summary = check_internet_via_backend(backend, timeout, auth, wrapper, wrapper_auth).await;
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

fn direct_internet_enabled(state: &crate::AppState) -> bool {
    matches!(
        state.args.priority_zero_mode(),
        crate::cli::PriorityZeroMode::DirectOnly | crate::cli::PriorityZeroMode::DirectFirst
    )
}

pub async fn refresh_direct_internet_once(state: crate::AppState, timeout: Duration) -> bool {
    let st = state.clone();
    let _guard = state.runtime.refresh_lock.lock().await;
    refresh_direct_internet_once_unlocked(st, timeout).await
}

async fn refresh_direct_internet_once_unlocked(state: crate::AppState, timeout: Duration) -> bool {
    if !direct_internet_enabled(&state) {
        return state.runtime.update_direct_internet(false, None, None);
    }

    let summary = check_direct_internet(timeout).await;
    let changed = if summary.ok {
        state.runtime.update_direct_internet(true, summary.best_ping_ms, None)
    } else {
        state.runtime.update_direct_internet(true, None, Some(summary.error_summary))
    };
    if changed {
        state.runtime.backend_wake_throttled(750);
    }
    changed
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

    let wrapper = state.args.wrapped_socks_addr().ok().flatten();
    let wrapper_auth = state.args.wrapped_socks_auth();
    let (err, socks_ping_ms, internet_ping_ms, ttl) =
        probe_backend_once(backend, timeout, auth, internet_ttl, probe_mode, wrapper, wrapper_auth).await;

    let mut b = state.backends.lock();
    let before_any_healthy = b.any_healthy();
    let before_any_green = b.any_green();
    let before_state = b.raw_state_at(idx);
    let changed = b.update(idx, socks_ping_ms.is_some(), err, socks_ping_ms, internet_ping_ms, ttl, probe_mode == ProbeMode::Full);
    let after_state = b.raw_state_at(idx);
    let kill_unhealthy_backend = changed
        && before_state == Some(BackendState::Green)
        && after_state != Some(BackendState::Green);
    let lower_priority_backends_to_kill = if changed {
        // Strict priority safety net: after any backend state change, if there is
        // a higher-priority GREEN group, established connections pinned to lower
        // groups must be cancelled. This also covers races where a lower backend
        // was selected while the preferred group recovered.
        b.lower_priority_backend_addrs_below_current_green_group()
    } else {
        Vec::new()
    };
    let after_any_healthy = b.any_healthy();
    let after_any_green = b.any_green();
    let after_has_backends = b.len() > 0;
    if after_any_green {
        state.runtime.clear_direct_cooldown();
    }
    // If all backends have no confirmed Internet route (no GREEN), immediately
    // start the accelerated recovery ladder. The ladder full-probes every
    // non-GREEN backend, including Red, because Red can recover directly to Green.
    let should_start_recovery_ladder = changed
        && !after_any_green
        && after_has_backends
        && (before_any_green || !before_any_healthy || probe_mode == ProbeMode::Full);
    let wake_backend = changed && should_wake_backend_loops(before_any_healthy, after_any_healthy, before_any_green, after_any_green);
    drop(b);
    if kill_unhealthy_backend {
        let killed = state.conns.kill_backend(backend);
        if killed > 0 {
            tracing::info!(
                "backend {} became unhealthy ({:?} -> {:?}); cancelled {} pinned SOCKS connections",
                backend,
                before_state,
                after_state,
                killed
            );
        }
    }
    for lower_backend in lower_priority_backends_to_kill {
        let killed = state.conns.kill_backend_socks_and_connecting(lower_backend);
        if killed > 0 {
            tracing::info!(
                "priority backend {} recovered ({:?} -> {:?}); cancelled {} lower-priority SOCKS/connecting connections on {}",
                backend,
                before_state,
                after_state,
                killed,
                lower_backend
            );
        }
    }
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
        let no_green_recovery = !b.any_green();
        let mut full_idx: Option<usize> = None;
        let mut plan = Vec::with_capacity(b.len());
        for idx in 0..b.len() {
            if b.addr_at(idx).is_none() {
                continue;
            }
            let probe_mode = if no_green_recovery {
                ProbeMode::Full
            } else if full_idx.is_none() {
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

    let _ = refresh_direct_internet_once_unlocked(state.clone(), timeout).await;
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

pub fn spawn_all_green_failure_recheck(state: crate::AppState, reason: String) {
    // A single target/site failure is not enough evidence. The caller only invokes
    // this after a short aggregate failure window is exceeded; this function then
    // coalesces storms so thousands of failed connects become one full sweep.
    if !state.runtime.try_enter_all_green_failure_recheck(5000) {
        return;
    }
    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(e) => {
                tracing::warn!("all-GREEN failure recheck runtime create failed: {:#}", e);
                state.runtime.leave_all_green_failure_recheck();
                return;
            }
        };
        rt.block_on(async move {
            all_green_failure_recheck_once(state, reason).await;
        });
    });
}

async fn all_green_failure_recheck_once(state: crate::AppState, reason: String) {
    let _guard = state.runtime.refresh_lock.lock().await;
    let timeout = health_timeout(&state);
    let global_auth = global_backend_auth(&state.args);
    let plan = {
        let b = state.backends.lock();
        b.green_recheck_plan(global_auth.as_ref())
    };

    if plan.is_empty() {
        state.runtime.leave_all_green_failure_recheck();
        return;
    }

    tracing::debug!(
        "aggregate all-GREEN connect failures; forcing full recheck of {} GREEN backend(s): {}",
        plan.len(),
        reason
    );

    for (idx, backend, auth) in plan {
        let before_state = state.backends.lock().raw_state_at(idx);
        let _ = refresh_backend_index_once(state.clone(), idx, timeout, None, auth, ProbeMode::Full).await;
        let after_state = state.backends.lock().raw_state_at(idx);
        if before_state == Some(BackendState::Green) && after_state != Some(BackendState::Green) {
            tracing::debug!(
                "aggregate all-GREEN connect failure confirmed backend {} stale ({:?} -> {:?})",
                backend,
                before_state,
                after_state
            );
        }
    }

    // If the sweep confirms that all GREEN routes were stale, immediately start the
    // existing accelerated recovery ladder over non-GREEN backends (Yellow and Red).
    if !state.backends.lock().any_green() && state.runtime.try_enter_burst_recovery_ladder() {
        spawn_burst_recovery_ladder(state.clone());
    }

    state.runtime.leave_all_green_failure_recheck();
}

pub fn spawn_suspect_backend_recheck(state: crate::AppState, backend: SocketAddr, reason: String) {
    if !state.runtime.try_enter_suspect_recheck(750) {
        return;
    }
    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(e) => {
                tracing::warn!("suspect backend recheck runtime create failed: {:#}", e);
                state.runtime.leave_suspect_recheck();
                return;
            }
        };
        rt.block_on(async move {
            suspect_backend_recheck_once(state, backend, reason).await;
        });
    });
}

async fn suspect_backend_recheck_once(state: crate::AppState, backend: SocketAddr, reason: String) {
    let _guard = state.runtime.refresh_lock.lock().await;
    let timeout = health_timeout(&state);
    let global_auth = global_backend_auth(&state.args);
    let choice = {
        let b = state.backends.lock();
        b.index_for_addr(backend).map(|idx| (idx, b.effective_auth_at(idx, global_auth.as_ref())))
    };
    let Some((idx, auth)) = choice else {
        state.runtime.leave_suspect_recheck();
        return;
    };

    tracing::debug!("backend {} suspect runtime failure; forcing full recheck: {}", backend, reason);
    let _ = refresh_backend_index_once(state.clone(), idx, timeout, None, auth, ProbeMode::Full).await;

    // If this backend was the only GREEN route and the full recheck confirmed the
    // loss, sweep other backends immediately so failover does not wait for the
    // normal quiet/idle health cadence.
    if !state.backends.lock().any_green() {
        let followup: Vec<(usize, Option<(String, String)>)> = {
            let b = state.backends.lock();
            (0..b.len())
                .filter(|other_idx| *other_idx != idx && b.addr_at(*other_idx).is_some())
                .map(|other_idx| (other_idx, b.effective_auth_at(other_idx, global_auth.as_ref())))
                .collect()
        };
        for (other_idx, other_auth) in followup {
            let _ = refresh_backend_index_once(state.clone(), other_idx, timeout, None, other_auth, ProbeMode::Full).await;
            if state.backends.lock().any_green() {
                break;
            }
        }
    }

    state.runtime.leave_suspect_recheck();
}

pub async fn burst_recheck_one_backend(state: crate::AppState) -> bool {
    if !state.runtime.try_enter_burst_recheck() {
        return false;
    }

    let res = async {
        let _guard = state.runtime.refresh_lock.lock().await;
        let global_auth = global_backend_auth(&state.args);
        let plan = {
            let mut b = state.backends.lock();
            // Recovery ladder is intentionally more aggressive than normal background
            // probes. When there is no GREEN backend, sweep every non-GREEN backend
            // with a full probe. This includes Red: a Red backend may recover
            // directly to Green when SOCKS and Internet return.
            // The ladder cadence itself (2s / 5s / 15s) remains the safety limiter.
            b.choose_burst_recheck_indices(global_auth.as_ref(), true)
        };
        if plan.is_empty() {
            return false;
        }
        let timeout = health_timeout(&state);
        let mut checked_any = false;
        for (idx, auth) in plan {
            if state.backends.lock().any_green() {
                break;
            }
            checked_any |= refresh_backend_index_once(state.clone(), idx, timeout, None, auth, ProbeMode::Full).await;
        }
        checked_any
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

    let _ = refresh_direct_internet_once_unlocked(state.clone(), timeout).await;
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
        let priority_active_refresh = state.args.backend_mode == BackendMode::Priority && active > 0;
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

        let allow_refresh = ui_open
            || had_traffic
            || urgent_work
            || quiet_for < Duration::from_secs(2 * 60)
            || priority_active_refresh;
        if allow_refresh {
            let green_available = state.backends.lock().any_green();
            let refresh_mode = if priority_active_refresh || force_full_sweep || !green_available || woke_from_quiet {
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

        let priority_active_interval = if priority_active_refresh {
            // Priority mode needs quicker recovery detection while clients are active:
            // a higher-priority backend coming back should move traffic back promptly.
            // Keep the active polling bounded between 15 and 60 seconds.
            if urgent_work || had_traffic || quiet_for < Duration::from_secs(30) {
                Some(Duration::from_secs(15))
            } else if quiet_for < Duration::from_secs(2 * 60) {
                Some(Duration::from_secs(30))
            } else {
                Some(Duration::from_secs(60))
            }
        } else {
            None
        };

        let interval = if let Some(priority_interval) = priority_active_interval {
            priority_interval
        } else if deep_idle {
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
    wrapper: Option<SocketAddr>,
    wrapper_auth: Option<(String, String)>,
) -> Option<f64> {
    let start = Instant::now();
    let connect = if let Some(wrapper) = wrapper {
        crate::socks5::connect_via_socks5_wrapped(wrapper, backend, target.clone(), wrapper_auth, auth, timeout).await
    } else {
        crate::socks5::connect_via_socks5(backend, target.clone(), auth, timeout).await
    };
    let mut stream = match connect {
        Ok(stream) => stream,
        Err(_) => return None,
    };

    // A SOCKS5 CONNECT success only means the local proxy accepted the request.
    // Some backends (notably sing-box with a dead upstream) can return
    // "request granted" and then immediately close/drop the data plane.  Treat
    // Internet as available only after the remote side answers real TLS data.
    if verify_backend_data_plane(&mut stream, &target, timeout).await {
        Some(start.elapsed().as_secs_f64() * 1000.0)
    } else {
        None
    }
}

async fn verify_backend_data_plane(
    stream: &mut TcpStream,
    target: &TargetAddr,
    timeout: Duration,
) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let probe_timeout = timeout.min(Duration::from_millis(1500)).max(Duration::from_millis(700));
    let sni = match target {
        TargetAddr::Domain(host, _) => Some(host.as_str()),
        TargetAddr::Ip(_) => None,
    };
    let hello = build_tls_client_hello(sni);

    if tokio::time::timeout(probe_timeout, stream.write_all(&hello)).await.is_err() {
        return false;
    }

    let mut first = [0u8; 1];
    match tokio::time::timeout(probe_timeout, stream.read_exact(&mut first)).await {
        Ok(Ok(_)) => true,
        _ => false,
    }
}

fn build_tls_client_hello(sni: Option<&str>) -> Vec<u8> {
    let mut random = [0u8; 32];
    for (idx, chunk) in random.chunks_mut(8).enumerate() {
        let v = rand_u64().wrapping_add(idx as u64).to_be_bytes();
        chunk.copy_from_slice(&v[..chunk.len()]);
    }

    let mut extensions = Vec::new();

    if let Some(host) = sni.map(str::trim).filter(|h| !h.is_empty() && h.len() <= 253) {
        let host_bytes = host.as_bytes();
        if host_bytes.len() <= 255 {
            let server_name_len = 1 + 2 + host_bytes.len();
            let list_len = server_name_len;
            let ext_len = 2 + list_len;
            extensions.extend_from_slice(&0x0000u16.to_be_bytes());
            extensions.extend_from_slice(&(ext_len as u16).to_be_bytes());
            extensions.extend_from_slice(&(list_len as u16).to_be_bytes());
            extensions.push(0x00);
            extensions.extend_from_slice(&(host_bytes.len() as u16).to_be_bytes());
            extensions.extend_from_slice(host_bytes);
        }
    }

    // supported_groups: x25519, secp256r1
    extensions.extend_from_slice(&0x000au16.to_be_bytes());
    extensions.extend_from_slice(&6u16.to_be_bytes());
    extensions.extend_from_slice(&4u16.to_be_bytes());
    extensions.extend_from_slice(&0x001du16.to_be_bytes());
    extensions.extend_from_slice(&0x0017u16.to_be_bytes());

    // signature_algorithms: rsa_pss_rsae_sha256, ecdsa_secp256r1_sha256, rsa_pkcs1_sha256
    extensions.extend_from_slice(&0x000du16.to_be_bytes());
    extensions.extend_from_slice(&8u16.to_be_bytes());
    extensions.extend_from_slice(&6u16.to_be_bytes());
    extensions.extend_from_slice(&0x0804u16.to_be_bytes());
    extensions.extend_from_slice(&0x0403u16.to_be_bytes());
    extensions.extend_from_slice(&0x0401u16.to_be_bytes());

    // supported_versions: TLS 1.3, TLS 1.2
    extensions.extend_from_slice(&0x002bu16.to_be_bytes());
    extensions.extend_from_slice(&5u16.to_be_bytes());
    extensions.push(4);
    extensions.extend_from_slice(&0x0304u16.to_be_bytes());
    extensions.extend_from_slice(&0x0303u16.to_be_bytes());

    let cipher_suites: [u16; 6] = [0x1301, 0x1302, 0x1303, 0xc02f, 0xc02b, 0x009c];

    let mut body = Vec::new();
    body.extend_from_slice(&0x0303u16.to_be_bytes()); // legacy_version
    body.extend_from_slice(&random);
    body.push(0); // legacy_session_id length
    body.extend_from_slice(&((cipher_suites.len() * 2) as u16).to_be_bytes());
    for cs in cipher_suites {
        body.extend_from_slice(&cs.to_be_bytes());
    }
    body.push(1);
    body.push(0); // null compression
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);

    let mut handshake = Vec::new();
    handshake.push(0x01); // ClientHello
    let body_len = body.len() as u32;
    handshake.push(((body_len >> 16) & 0xff) as u8);
    handshake.push(((body_len >> 8) & 0xff) as u8);
    handshake.push((body_len & 0xff) as u8);
    handshake.extend_from_slice(&body);

    let mut record = Vec::new();
    record.push(0x16); // handshake
    record.extend_from_slice(&0x0301u16.to_be_bytes()); // TLS record legacy version
    record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
    record.extend_from_slice(&handshake);
    record
}

async fn first_successful_probe(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
    targets: Vec<TargetAddr>,
    stop_after: usize,
    wrapper: Option<SocketAddr>,
    wrapper_auth: Option<(String, String)>,
) -> (usize, Option<f64>) {
    let mut ok_count = 0usize;
    let mut best: Option<f64> = None;

    for target in targets {
        if let Some(ping_ms) = probe_one_target(backend, target, timeout, auth.clone(), wrapper, wrapper_auth.clone()).await {
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
    // and cancel SOCKS connections that are still pinned to non-GREEN backends.
    // This makes clients reconnect through the current live backend set.
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
        let (green, non_green_backends, lower_priority_backends, speed_recycle_pair) = if deep_idle {
            (false, Vec::new(), Vec::new(), None)
        } else {
            let b = state.backends.lock();
            let green = b.any_green();
            let non_green_backends = if active > 0 { b.non_green_backend_addrs() } else { Vec::new() };
            let lower_priority_backends = if active > 0 {
                b.lower_priority_backend_addrs_below_current_green_group()
            } else {
                Vec::new()
            };
            let speed_recycle_pair = if active > 0 {
                b.priority_speed_stream_recycle_pair()
            } else {
                None
            };
            (green, non_green_backends, lower_priority_backends, speed_recycle_pair)
        };
        let stalled_suspect_backends = if !deep_idle && green && active > 0 {
            state.conns.suspect_stalled_socks_backends(12, 8, 1024)
        } else {
            Vec::new()
        };
        let has_non_green_backends = !non_green_backends.is_empty();
        let has_lower_priority_backends = !lower_priority_backends.is_empty();
        let has_speed_recycle_pair = speed_recycle_pair.is_some();
        let has_stalled_suspects = !stalled_suspect_backends.is_empty();
        let recovery_edge = !had_green && green;
        let needs_enforce = !deep_idle
            && (has_direct
                || connecting_now > 0
                || recovery_edge
                || has_non_green_backends
                || has_lower_priority_backends
                || has_stalled_suspects);

        if needs_enforce {
            // Safety net: if any DIRECT connections exist while GREEN backends are available,
            // cancel them. This enforces the "no bypass" rule.
            if green && has_direct
                && !matches!(
                    state.args.priority_zero_mode(),
                    crate::cli::PriorityZeroMode::DirectFirst | crate::cli::PriorityZeroMode::DirectOnly
                )
            {
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

            if has_non_green_backends {
                for backend in &non_green_backends {
                    let killed_backend = state.conns.kill_backend(*backend);
                    if killed_backend > 0 {
                        tracing::info!(
                            "proxy enforce: cancelled {} SOCKS connections pinned to non-GREEN backend {}",
                            killed_backend,
                            backend
                        );
                    }
                }
            }

            if has_lower_priority_backends {
                for backend in &lower_priority_backends {
                    let killed_backend = state.conns.kill_backend_socks_and_connecting(*backend);
                    if killed_backend > 0 {
                        tracing::info!(
                            "proxy enforce: cancelled {} lower-priority SOCKS/connecting connections on {} while a higher priority group is GREEN",
                            killed_backend,
                            backend
                        );
                    }
                }
            }

            if connecting_now > 0 {
                let killed_connecting = state.conns.kill_stuck_connecting(12);
                if killed_connecting > 0 {
                    tracing::info!("proxy enforce: cancelled {} stuck pending/connecting connections", killed_connecting);
                }
            }

            for (backend, reason) in &stalled_suspect_backends {
                spawn_suspect_backend_recheck(state.clone(), *backend, reason.clone());
            }
        }

        if let Some((slow_backend, shift_backend)) = speed_recycle_pair {
            if state.runtime.priority_stream_recycle_allowed() {
                let candidates = state
                    .conns
                    .stable_low_socks_streams_on_backend(slow_backend, PRIORITY_STREAM_RECYCLE_MAX_PER_PASS);
                if let Some(first) = candidates.first() {
                    if state
                        .runtime
                        .try_begin_priority_stream_recycle(PRIORITY_STREAM_RECYCLE_COOLDOWN_SECS)
                    {
                        let activated = {
                            let mut b = state.backends.lock();
                            b.activate_priority_stream_speed_shift(slow_backend, shift_backend, first.avg_bps)
                        };
                        if activated {
                            let cids: Vec<u64> = candidates.iter().map(|c| c.cid).collect();
                            let killed = state.conns.cancel_streams(&cids);
                            if killed > 0 {
                                let target = first
                                    .domain
                                    .as_deref()
                                    .or(first.target.as_deref())
                                    .unwrap_or("unknown");
                                tracing::info!(
                                    "priority speed-aware: recycled {} stable-low stream(s) on {} -> {} (first cid={}, target={}, avg={:.0} B/s, min={:.0}, max={:.0}, age={}s, down={} bytes)",
                                    killed,
                                    slow_backend,
                                    shift_backend,
                                    first.cid,
                                    target,
                                    first.avg_bps,
                                    first.min_bps,
                                    first.max_bps,
                                    first.age_secs,
                                    first.bytes_down
                                );
                            }
                        }
                    }
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
            if has_stalled_suspects {
                Duration::from_secs(15)
            } else if has_lower_priority_backends {
                Duration::from_secs(15)
            } else {
                Duration::from_secs(40)
            }
        } else if has_speed_recycle_pair {
            idle_since = None;
            Duration::from_secs(2)
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
    wrapper: Option<SocketAddr>,
    wrapper_auth: Option<(String, String)>,
) -> Option<f64> {
    let start = Instant::now();
    let res = if let Some(wrapper) = wrapper {
        crate::socks5::connect_to_socks5_server_wrapped(wrapper, backend, wrapper_auth, auth, timeout).await
    } else {
        crate::socks5::connect_to_socks5_server(backend, auth, timeout).await
    };
    res.ok().map(|_| start.elapsed().as_secs_f64() * 1000.0)
}
async fn check_internet_via_backend(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
    wrapper: Option<SocketAddr>,
    wrapper_auth: Option<(String, String)>,
) -> InternetProbeSummary {
    // Keep detailed validation energy-efficient: one strict data-plane probe.
    // SOCKS CONNECT alone is not enough; the remote side must answer our TLS
    // ClientHello.  Stability is handled by the Green hysteresis in update(), not
    // by running multiple external probes per backend.
    let per_probe_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));

    let targets = vec![
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)), 443)),
    ];

    let (ok_count, best_ping_ms) = first_successful_probe(
        backend,
        per_probe_timeout,
        auth,
        targets,
        1,
        wrapper,
        wrapper_auth,
    ).await;

    let ok = ok_count >= 1;
    let error_summary = if ok {
        format!("data-plane probe ok ({}/1)", ok_count)
    } else {
        format!("data-plane probe failed ({}/1)", ok_count)
    };

    InternetProbeSummary { ok, best_ping_ms, error_summary }
}

async fn probe_direct_target(target: TargetAddr, timeout: Duration) -> Option<f64> {
    let start = Instant::now();
    let connect_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));
    let addr = match target.clone() {
        TargetAddr::Ip(sa) => sa,
        TargetAddr::Domain(_, _) => return None,
    };
    let mut stream = match tokio::time::timeout(connect_timeout, TcpStream::connect(addr)).await {
        Ok(Ok(stream)) => stream,
        _ => return None,
    };

    if verify_backend_data_plane(&mut stream, &target, timeout).await {
        Some(start.elapsed().as_secs_f64() * 1000.0)
    } else {
        None
    }
}

pub async fn check_direct_target_data_plane(
    target: &Target,
    domain_hint: Option<&str>,
    timeout: Duration,
) -> bool {
    let addr = match target.resolve_socket_addr().await {
        Ok(addr) => addr,
        Err(_) => return false,
    };

    let (_, port) = target.to_host_port_string();
    let verify_target = if let Some(host) = domain_hint
        .map(str::trim)
        .filter(|h| !h.is_empty() && h.parse::<std::net::IpAddr>().is_err())
    {
        TargetAddr::Domain(host.to_string(), port)
    } else {
        match target {
            Target::HostPort(host, port) if host.parse::<std::net::IpAddr>().is_err() => {
                TargetAddr::Domain(host.clone(), *port)
            }
            _ => TargetAddr::Ip(addr),
        }
    };

    let per_probe_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));
    probe_direct_socket_target(addr, verify_target, per_probe_timeout).await.is_some()
}

async fn probe_direct_socket_target(addr: SocketAddr, verify_target: TargetAddr, timeout: Duration) -> Option<f64> {
    let start = Instant::now();
    let connect_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));
    let mut stream = match tokio::time::timeout(connect_timeout, TcpStream::connect(addr)).await {
        Ok(Ok(stream)) => stream,
        _ => return None,
    };

    if verify_backend_data_plane(&mut stream, &verify_target, timeout).await {
        Some(start.elapsed().as_secs_f64() * 1000.0)
    } else {
        None
    }
}

async fn check_direct_internet(timeout: Duration) -> InternetProbeSummary {
    // Same strict data-plane rule as SOCKS5 Internet health: a TCP connect is not
    // enough; the remote side must answer our TLS ClientHello.
    let per_probe_timeout = timeout
        .min(Duration::from_millis(1500))
        .max(Duration::from_millis(700));
    let targets = vec![
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)), 443)),
    ];

    let mut ok_count = 0usize;
    let mut best_ping_ms: Option<f64> = None;
    for target in targets {
        if let Some(ping_ms) = probe_direct_target(target, per_probe_timeout).await {
            ok_count += 1;
            best_ping_ms = Some(match best_ping_ms {
                Some(prev) => prev.min(ping_ms),
                None => ping_ms,
            });
            break;
        }
    }

    let ok = ok_count >= 1;
    let error_summary = if ok {
        format!("direct data-plane probe ok ({}/1)", ok_count)
    } else {
        format!("direct data-plane probe failed ({}/1)", ok_count)
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
