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
    /// Recent throughput estimate for this backend, bytes/sec over a short rolling window.
    pub recent_bps: Option<f64>,
    /// True when priority speed-aware mode is temporarily avoiding this GREEN backend.
    pub speed_degraded: bool,
    /// True when this backend is the current temporary target selected by priority speed-aware mode.
    pub speed_shift_target: bool,
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
    recent_speed: Vec<VecDeque<(u64, u64)>>,
    priority_speed_aware: bool,
    priority_speed_shift_from: Option<SocketAddr>,
    priority_speed_shift_to: Option<SocketAddr>,
    priority_speed_hold_until_ts: u64,
    priority_speed_last_probe_ts: u64,
    priority_speed_probe_rr: usize,
    backend_mode: BackendMode,
    /// Priority groups by SOCKS5 port. In priority mode each inner Vec is one priority level.
    /// Multiple ports in one level are balanced with round-robin; the next level is used only
    /// when the previous level has no selectable GREEN backend.
    priority_groups: Vec<Vec<u16>>,
    priority_rr: Vec<usize>,
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
        if addrs.iter().any(|sa| sa.port() == args.listen_port) {
            return Err(anyhow!("t2s backend points to its own listen port {}", args.listen_port));
        }
        let priority_groups = Self::build_priority_groups(args, &addrs)?;
        let priority_rr = vec![0; priority_groups.len()];
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
            recent_bps: None,
            speed_degraded: false,
            speed_shift_target: false,
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
        let recent_speed = vec![VecDeque::new(); addrs.len()];

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
            recent_speed,
            priority_speed_aware: args.priority_speed_aware && args.backend_mode == BackendMode::Priority,
            priority_speed_shift_from: None,
            priority_speed_shift_to: None,
            priority_speed_hold_until_ts: 0,
            priority_speed_last_probe_ts: 0,
            priority_speed_probe_rr: 0,
            backend_mode: args.backend_mode,
            priority_groups,
            priority_rr,
            rr: 0,
            check_rr: 0,
            active_check_rr: 0,
            audit_check_rr: 0,
            burst_check_rr: 0,
            check_cycle: 0,
        })
    }

    fn build_priority_groups(args: &Args, addrs: &[SocketAddr]) -> Result<Vec<Vec<u16>>> {
        if args.backend_mode != BackendMode::Priority {
            return Ok(Vec::new());
        }

        let configured_ports = args.socks_ports();
        let mut groups: Vec<Vec<u16>> = Vec::new();
        let mut seen: Vec<u16> = Vec::new();

        if let Some(spec) = args.backend_priority.as_ref().map(|s| s.trim()).filter(|s| !s.is_empty()) {
            for raw_group in spec.split(';') {
                let mut group: Vec<u16> = Vec::new();
                for raw_port in raw_group.split(',') {
                    let port_s = raw_port.trim();
                    if port_s.is_empty() {
                        continue;
                    }
                    let port = port_s
                        .parse::<u16>()
                        .with_context(|| format!("invalid --backend-priority port: {}", port_s))?;
                    if !addrs.iter().any(|sa| sa.port() == port) {
                        return Err(anyhow!(
                            "--backend-priority port {} does not match any configured SOCKS backend",
                            port
                        ));
                    }
                    if !seen.contains(&port) {
                        seen.push(port);
                        group.push(port);
                    }
                }
                if !group.is_empty() {
                    groups.push(group);
                }
            }

            if groups.is_empty() {
                return Err(anyhow!("--backend-priority did not contain any valid port groups"));
            }
        }

        // If the priority list is omitted, use the --socks-port order as separate
        // priority levels: 1145,1146,1147 -> 1145;1146;1147.
        // If an explicit list omits a configured port, keep that port as a lower
        // priority fallback instead of silently making it unreachable.
        for port in configured_ports {
            if addrs.iter().any(|sa| sa.port() == port) && !seen.contains(&port) {
                seen.push(port);
                groups.push(vec![port]);
            }
        }

        if groups.is_empty() {
            for sa in addrs {
                let port = sa.port();
                if !seen.contains(&port) {
                    seen.push(port);
                    groups.push(vec![port]);
                }
            }
        }

        Ok(groups)
    }

    fn protector_mode_enabled(&self) -> bool {
        self.backend_mode != BackendMode::Priority && protector_mode_forced_green()
    }

    fn healthy_indices_by_ports(&self, ports: &[u16], now: u64, respect_cooldown: bool) -> Vec<usize> {
        self.addrs
            .iter()
            .enumerate()
            .filter(|(idx, sa)| {
                ports.contains(&sa.port())
                    && self.status.get(*idx).map(|s| s.healthy).unwrap_or(false)
                    && (!respect_cooldown || !self.backend_in_cooldown_idx(*idx, now))
            })
            .map(|(idx, _)| idx)
            .collect()
    }

    fn first_priority_green_group_index(&self) -> Option<usize> {
        let now = now_ts();
        self.priority_groups
            .iter()
            .position(|group| !self.healthy_indices_by_ports(group, now, false).is_empty())
    }

    fn priority_select_index(&mut self) -> Option<usize> {
        let now = now_ts();
        for group_idx in 0..self.priority_groups.len() {
            let all_green = self.healthy_indices_by_ports(&self.priority_groups[group_idx], now, false);
            if all_green.is_empty() {
                continue;
            }

            // Priority must be strict by group: a GREEN backend in a higher group
            // prevents falling through to lower groups. Runtime cooldown may change
            // which backend is selected inside the current group, but must not make
            // traffic jump to a lower priority level while this group is still GREEN.
            let ready = self.healthy_indices_by_ports(&self.priority_groups[group_idx], now, true);
            let candidates = if ready.is_empty() { all_green } else { ready };
            let cursor = self.priority_rr.get_mut(group_idx)?;
            return Self::pick_from_bucket(&candidates, cursor);
        }
        None
    }

    fn clear_priority_speed_shift(&mut self) {
        self.priority_speed_shift_from = None;
        self.priority_speed_shift_to = None;
        self.priority_speed_hold_until_ts = 0;
        for s in &mut self.status {
            s.speed_degraded = false;
            s.speed_shift_target = false;
        }
    }

    fn recent_speed_stats_idx(&self, idx: usize, now: u64) -> (u64, f64) {
        let Some(samples) = self.recent_speed.get(idx) else {
            return (0, 0.0);
        };
        let cutoff = now.saturating_sub(PRIORITY_SPEED_WINDOW_SECS);
        let mut bytes: u64 = 0;
        let mut first_ts: Option<u64> = None;
        let mut last_ts: Option<u64> = None;
        for (ts, n) in samples.iter().filter(|(ts, _)| *ts >= cutoff) {
            bytes = bytes.saturating_add(*n);
            first_ts = Some(first_ts.map(|v| v.min(*ts)).unwrap_or(*ts));
            last_ts = Some(last_ts.map(|v| v.max(*ts)).unwrap_or(*ts));
        }
        let span_secs = match (first_ts, last_ts) {
            (Some(first), Some(last)) => last.saturating_sub(first).saturating_add(1).min(PRIORITY_SPEED_WINDOW_SECS),
            _ => PRIORITY_SPEED_WINDOW_SECS,
        }.max(1);
        let bps = bytes as f64 / (span_secs as f64);
        (bytes, bps)
    }

    fn priority_next_lower_green_candidate(&mut self, primary_idx: usize, now: u64) -> Option<usize> {
        let primary_port = self.addrs.get(primary_idx)?.port();
        let primary_group_idx = self.priority_groups
            .iter()
            .position(|group| group.contains(&primary_port))?;

        let mut candidates: Vec<usize> = Vec::new();
        for group in self.priority_groups.iter().skip(primary_group_idx + 1) {
            let ready = self.healthy_indices_by_ports(group, now, true);
            let all_green = self.healthy_indices_by_ports(group, now, false);
            let bucket = if ready.is_empty() { all_green } else { ready };
            if !bucket.is_empty() {
                candidates.extend(bucket);
                break;
            }
        }

        if candidates.is_empty() {
            return None;
        }
        Self::pick_from_bucket(&candidates, &mut self.priority_speed_probe_rr)
    }

    fn priority_first_green_index_by_ports(&self, ports: &[u16], now: u64) -> Option<usize> {
        let ready = self.healthy_indices_by_ports(ports, now, true);
        if let Some(idx) = ready.first().copied() {
            return Some(idx);
        }
        self.healthy_indices_by_ports(ports, now, false).first().copied()
    }

    fn priority_current_primary_index_for_speed(&self, now: u64) -> Option<usize> {
        for group in &self.priority_groups {
            if let Some(idx) = self.priority_first_green_index_by_ports(group, now) {
                return Some(idx);
            }
        }
        None
    }

    fn priority_first_lower_green_candidate(&self, primary_idx: usize, now: u64) -> Option<usize> {
        let primary_port = self.addrs.get(primary_idx)?.port();
        let primary_group_idx = self.priority_groups
            .iter()
            .position(|group| group.contains(&primary_port))?;

        for group in self.priority_groups.iter().skip(primary_group_idx + 1) {
            if let Some(idx) = self.priority_first_green_index_by_ports(group, now) {
                return Some(idx);
            }
        }
        None
    }

    pub fn priority_speed_stream_recycle_pair(&self) -> Option<(SocketAddr, SocketAddr)> {
        if self.backend_mode != BackendMode::Priority || !self.priority_speed_aware {
            return None;
        }

        let now = now_ts();
        if let (Some(from), Some(to)) = (self.priority_speed_shift_from, self.priority_speed_shift_to) {
            let _from_idx = self.addrs.iter().position(|addr| *addr == from)?;
            let to_idx = self.addrs.iter().position(|addr| *addr == to)?;
            if self.status.get(to_idx).map(|s| s.healthy).unwrap_or(false) {
                return Some((from, to));
            }
        }

        let primary_idx = self.priority_current_primary_index_for_speed(now)?;
        let candidate_idx = self.priority_first_lower_green_candidate(primary_idx, now)?;
        Some((self.addrs[primary_idx], self.addrs[candidate_idx]))
    }

    pub fn activate_priority_stream_speed_shift(
        &mut self,
        from: SocketAddr,
        to: SocketAddr,
        avg_bps: f64,
    ) -> bool {
        if self.backend_mode != BackendMode::Priority || !self.priority_speed_aware {
            return false;
        }
        let Some(from_idx) = self.addrs.iter().position(|addr| *addr == from) else {
            return false;
        };
        let Some(to_idx) = self.addrs.iter().position(|addr| *addr == to) else {
            return false;
        };
        if !self.status.get(to_idx).map(|s| s.healthy).unwrap_or(false) {
            return false;
        }

        let now = now_ts();
        let changed = self.priority_speed_shift_from != Some(from) || self.priority_speed_shift_to != Some(to);
        self.priority_speed_shift_from = Some(from);
        self.priority_speed_shift_to = Some(to);
        self.priority_speed_hold_until_ts = now.saturating_add(PRIORITY_SPEED_HOLD_SECS);
        self.priority_speed_last_probe_ts = now;
        for s in &mut self.status {
            s.speed_degraded = false;
            s.speed_shift_target = false;
        }
        if let Some(s) = self.status.get_mut(from_idx) {
            s.speed_degraded = true;
        }
        if let Some(s) = self.status.get_mut(to_idx) {
            s.speed_shift_target = true;
        }
        if changed {
            tracing::info!(
                "priority speed-aware: stream recycle shifting new connections from {} to {} (stable low stream {:.0} B/s)",
                from,
                to,
                avg_bps
            );
        }
        true
    }

    fn priority_speed_candidate_is_better(&self, primary_idx: usize, candidate_idx: usize, now: u64) -> bool {
        let (primary_bytes, primary_bps) = self.recent_speed_stats_idx(primary_idx, now);
        let (candidate_bytes, candidate_bps) = self.recent_speed_stats_idx(candidate_idx, now);
        primary_bytes >= PRIORITY_SPEED_PRIMARY_MIN_BYTES
            && candidate_bytes >= PRIORITY_SPEED_PROBE_MIN_BYTES
            && primary_bps < PRIORITY_SPEED_MIN_BPS
            && candidate_bps >= primary_bps * PRIORITY_SPEED_SWITCH_RATIO
            && candidate_bps >= primary_bps + PRIORITY_SPEED_SWITCH_DELTA_BPS
    }

    fn priority_primary_recovered(&self, primary_idx: usize, shifted_idx: usize, now: u64) -> bool {
        let (primary_bytes, primary_bps) = self.recent_speed_stats_idx(primary_idx, now);
        let (_, shifted_bps) = self.recent_speed_stats_idx(shifted_idx, now);
        primary_bytes >= PRIORITY_SPEED_PROBE_MIN_BYTES
            && primary_bps >= PRIORITY_SPEED_MIN_BPS
            && primary_bps + PRIORITY_SPEED_SWITCH_DELTA_BPS >= shifted_bps
    }

    fn priority_speed_aware_select_index(&mut self) -> Option<usize> {
        let now = now_ts();
        let primary_idx = self.priority_select_index()?;
        let primary_addr = self.addrs.get(primary_idx).copied()?;
        let primary_is_green = self.status.get(primary_idx).map(|s| s.healthy).unwrap_or(false);
        if !primary_is_green {
            self.clear_priority_speed_shift();
            return Some(primary_idx);
        }

        let shifted_idx = self.priority_speed_shift_to
            .and_then(|addr| self.addrs.iter().position(|a| *a == addr));

        if let Some(shifted_idx) = shifted_idx {
            let shifted_addr = self.addrs[shifted_idx];
            let shifted_green = self.status.get(shifted_idx).map(|s| s.healthy).unwrap_or(false);
            if !shifted_green {
                self.clear_priority_speed_shift();
                return Some(primary_idx);
            }

            if now >= self.priority_speed_hold_until_ts
                && self.priority_primary_recovered(primary_idx, shifted_idx, now)
            {
                tracing::info!(
                    "priority speed-aware: primary backend {} recovered; returning new connections from {}",
                    primary_addr,
                    shifted_addr
                );
                self.clear_priority_speed_shift();
                return Some(primary_idx);
            }

            if now.saturating_sub(self.priority_speed_last_probe_ts) >= PRIORITY_SPEED_PROBE_INTERVAL_SECS {
                self.priority_speed_last_probe_ts = now;
                if let Some(s) = self.status.get_mut(primary_idx) {
                    s.speed_degraded = false;
                }
                tracing::debug!(
                    "priority speed-aware: probing primary backend {} while shifted to {}",
                    primary_addr,
                    shifted_addr
                );
                return Some(primary_idx);
            }

            if let Some(s) = self.status.get_mut(primary_idx) {
                s.speed_degraded = true;
            }
            if let Some(s) = self.status.get_mut(shifted_idx) {
                s.speed_shift_target = true;
            }
            return Some(shifted_idx);
        }

        let (primary_bytes, primary_bps) = self.recent_speed_stats_idx(primary_idx, now);
        let primary_suspect = primary_bytes >= PRIORITY_SPEED_PRIMARY_MIN_BYTES
            && primary_bps < PRIORITY_SPEED_MIN_BPS;
        if !primary_suspect {
            self.clear_priority_speed_shift();
            return Some(primary_idx);
        }

        let Some(candidate_idx) = self.priority_next_lower_green_candidate(primary_idx, now) else {
            return Some(primary_idx);
        };
        let candidate_addr = self.addrs[candidate_idx];

        if self.priority_speed_candidate_is_better(primary_idx, candidate_idx, now) {
            self.priority_speed_shift_from = Some(primary_addr);
            self.priority_speed_shift_to = Some(candidate_addr);
            self.priority_speed_hold_until_ts = now.saturating_add(PRIORITY_SPEED_HOLD_SECS);
            self.priority_speed_last_probe_ts = now;
            for s in &mut self.status {
                s.speed_degraded = false;
                s.speed_shift_target = false;
            }
            if let Some(s) = self.status.get_mut(primary_idx) {
                s.speed_degraded = true;
            }
            if let Some(s) = self.status.get_mut(candidate_idx) {
                s.speed_shift_target = true;
            }
            tracing::info!(
                "priority speed-aware: shifting new connections from {} to {} (primary {:.0} B/s, candidate {:.0} B/s)",
                primary_addr,
                candidate_addr,
                primary_bps,
                self.recent_speed_stats_idx(candidate_idx, now).1
            );
            return Some(candidate_idx);
        }

        if now.saturating_sub(self.priority_speed_last_probe_ts) >= PRIORITY_SPEED_PROBE_INTERVAL_SECS {
            self.priority_speed_last_probe_ts = now;
            tracing::debug!(
                "priority speed-aware: probing lower backend {} because primary {} is slow ({:.0} B/s)",
                candidate_addr,
                primary_addr,
                primary_bps
            );
            return Some(candidate_idx);
        }

        Some(primary_idx)
    }

    fn priority_capacity_backend_count(&self, respect_cooldown: bool) -> usize {
        let now = now_ts();
        for group in &self.priority_groups {
            let all_green = self.healthy_indices_by_ports(group, now, false);
            if all_green.is_empty() {
                continue;
            }
            if respect_cooldown {
                let ready = self.healthy_indices_by_ports(group, now, true);
                return if ready.is_empty() { all_green.len() } else { ready.len() };
            }
            return all_green.len();
        }
        0
    }

    /// In priority mode, returns all configured backends from priority groups below
    /// the group that contains `restored_backend`.
    ///
    /// This is used when a higher-priority backend recovers: existing connections
    /// pinned to lower-priority fallback backends must be cancelled so clients
    /// reconnect through the preferred group.
    pub fn lower_priority_backend_addrs_after(&self, restored_backend: SocketAddr) -> Vec<SocketAddr> {
        if self.backend_mode != BackendMode::Priority || self.priority_speed_aware {
            return Vec::new();
        }

        let restored_port = restored_backend.port();
        let Some(group_idx) = self.priority_groups
            .iter()
            .position(|group| group.contains(&restored_port))
        else {
            return Vec::new();
        };

        self.lower_priority_backend_addrs_after_group(group_idx)
    }

    fn lower_priority_backend_addrs_after_group(&self, group_idx: usize) -> Vec<SocketAddr> {
        let lower_ports: Vec<u16> = self.priority_groups
            .iter()
            .skip(group_idx + 1)
            .flat_map(|group| group.iter().copied())
            .collect();

        if lower_ports.is_empty() {
            return Vec::new();
        }

        self.addrs
            .iter()
            .copied()
            .filter(|addr| lower_ports.contains(&addr.port()))
            .collect()
    }

    /// In strict priority mode, returns every backend from groups below the first
    /// currently GREEN priority group. Established SOCKS connections pinned to
    /// these lower groups should be cancelled so clients reconnect through the
    /// preferred live group.
    pub fn lower_priority_backend_addrs_below_current_green_group(&self) -> Vec<SocketAddr> {
        if self.backend_mode != BackendMode::Priority || self.priority_speed_aware {
            return Vec::new();
        }

        let Some(group_idx) = self.first_priority_green_group_index() else {
            return Vec::new();
        };

        self.lower_priority_backend_addrs_after_group(group_idx)
    }

    /// Returns all configured backends that are currently not effectively GREEN.
    ///
    /// This is a safety net for established SOCKS connections: even if a backend
    /// health transition was missed or happened before a connection was fully
    /// registered as established, proxy_enforce_loop can still cancel connections
    /// pinned to non-working backends. In balance mode this respects the existing
    /// protector_mode behavior; in priority mode protector_mode is already ignored
    /// by protector_mode_enabled().
    pub fn non_green_backend_addrs(&self) -> Vec<SocketAddr> {
        if self.protector_mode_enabled() {
            return Vec::new();
        }

        self.addrs
            .iter()
            .enumerate()
            .filter(|(idx, _)| self.status.get(*idx).map(|s| s.state != BackendState::Green).unwrap_or(true))
            .map(|(_, addr)| *addr)
            .collect()
    }

    pub fn len(&self) -> usize { self.addrs.len() }
    pub fn addr_at(&self, idx: usize) -> Option<SocketAddr> { self.addrs.get(idx).copied() }

    /// Number of currently usable GREEN backends for distributing local connect capacity.
    /// Prefer backends that are not in runtime cooldown, mirroring select_rr_with_auth().
    /// If every GREEN backend is cooling down, fall back to all GREEN backends because
    /// select_rr_with_auth() does the same instead of reporting no backend.
    pub fn connect_capacity_backend_count(&self) -> usize {
        if self.protector_mode_enabled() {
            return self.addrs.len();
        }

        if self.backend_mode == BackendMode::Priority {
            return self.priority_capacity_backend_count(true);
        }

        let now = now_ts();
        let selectable = self.status
            .iter()
            .enumerate()
            .filter(|(idx, s)| s.healthy && !self.backend_in_cooldown_idx(*idx, now))
            .count();
        if selectable > 0 {
            selectable
        } else {
            self.status.iter().filter(|s| s.healthy).count()
        }
    }

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
        if self.protector_mode_enabled() {
            return Some(BackendState::Green);
        }
        self.status.get(idx).map(|s| s.state)
    }

    /// Returns the real probed backend state without protector-mode overrides.
    pub fn raw_state_at(&self, idx: usize) -> Option<BackendState> {
        self.status.get(idx).map(|s| s.state)
    }

    pub fn raw_state_for_addr(&self, addr: SocketAddr) -> Option<BackendState> {
        self.addrs
            .iter()
            .position(|a| *a == addr)
            .and_then(|idx| self.raw_state_at(idx))
    }

    pub fn last_full_probe_at(&self, idx: usize) -> Option<u64> {
        self.last_full_probe.get(idx).copied()
    }

    pub fn any_yellow(&self) -> bool {
        self.status.iter().any(|s| s.state == BackendState::Yellow)
    }

    pub fn choose_burst_recheck_index(&mut self, global_auth: Option<&(String, String)>, ignore_normal_due: bool) -> Option<(usize, Option<(String, String)>)> {
        if self.protector_mode_enabled() || self.any_green() {
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
        if self.protector_mode_enabled() {
            return !self.addrs.is_empty();
        }
        self.status.iter().any(|s| s.healthy)
    }

    /// Returns true if there is at least one GREEN backend (responding + Internet OK).
    pub fn any_green(&self) -> bool {
        if self.protector_mode_enabled() {
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
        if self.protector_mode_enabled() {
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
            recent_bps: None,
            speed_degraded: false,
            speed_shift_target: false,
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
        self.recent_speed.push(VecDeque::new());
        if self.backend_mode == BackendMode::Priority
            && !self.priority_groups.iter().any(|group| group.contains(&addr.port()))
        {
            self.priority_groups.push(vec![addr.port()]);
            self.priority_rr.push(0);
        }
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
            if pos < self.recent_speed.len() { self.recent_speed.remove(pos); }
            if self.priority_speed_shift_from == Some(addr) || self.priority_speed_shift_to == Some(addr) {
                self.clear_priority_speed_shift();
            }
            if self.rr >= self.addrs.len() { self.rr = 0; }
            if self.check_rr >= self.addrs.len() { self.check_rr = 0; }
            if self.active_check_rr >= self.addrs.len() { self.active_check_rr = 0; }
            if self.audit_check_rr >= self.addrs.len() { self.audit_check_rr = 0; }
            if self.burst_check_rr >= self.addrs.len() { self.burst_check_rr = 0; }
        }
    }
    pub fn select_rr_with_auth(&mut self, global_auth: Option<&(String, String)>) -> Result<(usize, SocketAddr, Option<(String, String)>)> {
        if self.backend_mode == BackendMode::Priority {
            let selected = if self.priority_speed_aware {
                self.priority_speed_aware_select_index()
            } else {
                self.priority_select_index()
            };
            if let Some(idx) = selected {
                return Ok((idx, self.addrs[idx], self.effective_auth_at(idx, global_auth)));
            }
            return Err(anyhow!("no GREEN backends"));
        }

        let now = now_ts();
        let mut candidates: Vec<usize> = if self.protector_mode_enabled() {
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
            candidates = if self.protector_mode_enabled() {
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

    pub fn index_for_addr(&self, addr: SocketAddr) -> Option<usize> {
        self.addrs.iter().position(|a| *a == addr)
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
            // This is local T2S pressure, not a SOCKS5 runtime failure.
            // Do not put the backend into cooldown here: with a single live backend that
            // would temporarily remove the only usable route and make bursty clients fail.
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
            // Stage 1/simple check failed: the SOCKS backend itself is not reachable.
            BackendState::Red
        } else if full_probe {
            // Stage 2/detailed check: SOCKS is reachable; the strict single
            // data-plane probe decides Green vs Yellow immediately.
            if internet_ping_ms.is_some() {
                BackendState::Green
            } else {
                BackendState::Yellow
            }
        } else if prev.state == BackendState::Green {
            // Stage 1/simple success keeps an already verified backend Green.
            BackendState::Green
        } else {
            // Stage 1/simple success for an unverified backend means reachable, but
            // Internet is not confirmed until the detailed probe succeeds.
            BackendState::Yellow
        };

        self.status[idx].state = state;
        self.status[idx].healthy = state == BackendState::Green;
        self.status[idx].last_check = now;
        if state == BackendState::Green && idx < self.last_green_ts.len() {
            self.last_green_ts[idx] = now;
        }
        if socks_ping_ms.is_none() && idx < self.internet_probe_fail_streak.len() {
            self.internet_probe_fail_streak[idx] = 0;
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
            let now = now_ts();
            if pos < self.status.len() {
                self.status[pos].total_bytes = self.status[pos].total_bytes.saturating_add(n);
            }
            if pos < self.recent_speed.len() {
                let q = &mut self.recent_speed[pos];
                if let Some((ts, bytes)) = q.back_mut() {
                    if *ts == now {
                        *bytes = bytes.saturating_add(n);
                    } else {
                        q.push_back((now, n));
                    }
                } else {
                    q.push_back((now, n));
                }
                let cutoff = now.saturating_sub(PRIORITY_SPEED_WINDOW_SECS.saturating_mul(2));
                while q.front().map(|(ts, _)| *ts < cutoff).unwrap_or(false) {
                    q.pop_front();
                }
                let recent_bytes: u64 = q
                    .iter()
                    .filter(|(ts, _)| *ts >= now.saturating_sub(PRIORITY_SPEED_WINDOW_SECS))
                    .map(|(_, bytes)| *bytes)
                    .sum();
                if let Some(status) = self.status.get_mut(pos) {
                    status.recent_bps = Some(recent_bytes as f64 / PRIORITY_SPEED_WINDOW_SECS.max(1) as f64);
                }
            }
            self.touch_backend_activity_idx(pos, now, false);
        }
    }
}

#[derive(Clone, Debug, Serialize)]
