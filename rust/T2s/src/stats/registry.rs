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
    #[serde(skip_serializing)]
    recent_down: VecDeque<(u64, u64)>,
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

#[derive(Clone, Debug)]
pub struct StableLowStream {
    pub cid: u64,
    pub avg_bps: f64,
    pub min_bps: f64,
    pub max_bps: f64,
    pub age_secs: u64,
    pub bytes_down: u64,
    pub domain: Option<String>,
    pub target: Option<String>,
}

fn stable_low_downstream_window(samples: &VecDeque<(u64, u64)>, now: u64) -> Option<(u64, f64, f64, f64)> {
    let window = PRIORITY_STREAM_DEGRADED_WINDOW_SECS.max(2);
    let end = now.saturating_sub(1);
    let start = end.saturating_sub(window.saturating_sub(1));
    let mut buckets = vec![0u64; window as usize];

    for (ts, bytes) in samples {
        if *ts < start || *ts > end {
            continue;
        }
        let pos = ts.saturating_sub(start) as usize;
        if let Some(bucket) = buckets.get_mut(pos) {
            *bucket = bucket.saturating_add(*bytes);
        }
    }

    let total: u64 = buckets.iter().copied().sum();
    if total < PRIORITY_STREAM_DEGRADED_MIN_WINDOW_BYTES {
        return None;
    }

    let nonzero = buckets.iter().filter(|bytes| **bytes > 0).count();
    if nonzero + 2 < buckets.len() {
        return None;
    }

    let avg_bps = total as f64 / (window as f64);
    if avg_bps <= 0.0 || avg_bps > PRIORITY_STREAM_DEGRADED_MAX_BPS {
        return None;
    }

    let min_bps = buckets.iter().copied().min().unwrap_or(0) as f64;
    let max_bps = buckets.iter().copied().max().unwrap_or(0) as f64;
    let low = avg_bps * (1.0 - PRIORITY_STREAM_DEGRADED_JITTER_RATIO);
    let high = avg_bps * (1.0 + PRIORITY_STREAM_DEGRADED_JITTER_RATIO);
    if min_bps < low || max_bps > high {
        return None;
    }

    Some((total, avg_bps, min_bps, max_bps))
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
            recent_down: VecDeque::new(),
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
            let now = now_ts();
            info.bytes_down = info.bytes_down.saturating_add(n);
            info.last_progress_ts = now;
            if let Some((ts, bytes)) = info.recent_down.back_mut() {
                if *ts == now {
                    *bytes = bytes.saturating_add(n);
                } else {
                    info.recent_down.push_back((now, n));
                }
            } else {
                info.recent_down.push_back((now, n));
            }
            let cutoff = now.saturating_sub(PRIORITY_STREAM_DEGRADED_WINDOW_SECS.saturating_mul(3));
            while info.recent_down.front().map(|(ts, _)| *ts < cutoff).unwrap_or(false) {
                info.recent_down.pop_front();
            }
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

    fn kill_backend_by_modes(&self, backend: SocketAddr, modes: &[&str]) -> usize {
        let backend = backend.to_string();
        let mut n = 0usize;
        let g = self.inner.lock();
        for (info, token) in g.values() {
            let mode_matches = info
                .mode
                .as_deref()
                .map(|mode| modes.iter().any(|want| *want == mode))
                .unwrap_or(false);
            if mode_matches && info.backend.as_deref() == Some(backend.as_str()) {
                token.cancel();
                n += 1;
            }
        }
        n
    }

    /// Cancels established SOCKS connections pinned to a backend that just became unhealthy.
    /// Pending/connecting attempts are intentionally left alone so they can retry another backend.
    pub fn kill_backend(&self, backend: SocketAddr) -> usize {
        self.kill_backend_by_modes(backend, &["socks"])
    }

    /// Cancels established and in-flight SOCKS connections pinned to a lower-priority backend.
    /// This closes the race where a fallback backend is still connecting while a higher-priority
    /// GREEN group has already recovered.
    pub fn kill_backend_socks_and_connecting(&self, backend: SocketAddr) -> usize {
        self.kill_backend_by_modes(backend, &["socks", "socks_connecting"])
    }

    pub fn stable_low_socks_streams_on_backend(
        &self,
        backend: SocketAddr,
        max_count: usize,
    ) -> Vec<StableLowStream> {
        let backend = backend.to_string();
        let now = now_ts();
        let mut candidates = Vec::new();
        let g = self.inner.lock();
        for (info, _token) in g.values() {
            if info.mode.as_deref() != Some("socks") || info.backend.as_deref() != Some(backend.as_str()) {
                continue;
            }
            let age_secs = now.saturating_sub(info.started_ts);
            if age_secs < PRIORITY_STREAM_DEGRADED_MIN_AGE_SECS {
                continue;
            }
            if info.bytes_down < PRIORITY_STREAM_DEGRADED_MIN_BYTES {
                continue;
            }
            let Some((_window_bytes, avg_bps, min_bps, max_bps)) = stable_low_downstream_window(&info.recent_down, now) else {
                continue;
            };
            candidates.push(StableLowStream {
                cid: info.cid,
                avg_bps,
                min_bps,
                max_bps,
                age_secs,
                bytes_down: info.bytes_down,
                domain: info.domain.clone(),
                target: info.target.clone(),
            });
        }
        candidates.sort_by(|a, b| a.avg_bps.partial_cmp(&b.avg_bps).unwrap_or(std::cmp::Ordering::Equal));
        candidates.truncate(max_count.max(1));
        candidates
    }

    pub fn cancel_streams(&self, cids: &[u64]) -> usize {
        let mut n = 0usize;
        let g = self.inner.lock();
        for cid in cids {
            if let Some((_info, token)) = g.get(cid) {
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

    pub fn get(&self, cid: u64) -> Option<ConnInfo> {
        self.inner.lock().get(&cid).map(|(info, _)| info.clone())
    }

    /// Returns backends with established SOCKS streams that uploaded data, then
    /// stalled without any downstream bytes. This does not change health by
    /// itself; callers use it only as a signal to force a full backend probe.
    pub fn suspect_stalled_socks_backends(
        &self,
        min_age_secs: u64,
        min_idle_secs: u64,
        min_up_bytes: u64,
    ) -> Vec<(SocketAddr, String)> {
        let now = now_ts();
        let mut out: Vec<(SocketAddr, String)> = Vec::new();
        let g = self.inner.lock();
        for (info, _) in g.values() {
            if info.mode.as_deref() != Some("socks") {
                continue;
            }
            if info.bytes_up < min_up_bytes || info.bytes_down != 0 {
                continue;
            }
            let age = now.saturating_sub(info.started_ts);
            let idle = now.saturating_sub(info.last_progress_ts);
            if age < min_age_secs || idle < min_idle_secs {
                continue;
            }
            let Some(backend_str) = info.backend.as_deref() else { continue; };
            let Ok(backend) = backend_str.parse::<SocketAddr>() else { continue; };
            if out.iter().any(|(existing, _)| *existing == backend) {
                continue;
            }
            out.push((
                backend,
                format!(
                    "stalled SOCKS data-plane: cid={}, up={}, down={}, age={}s, idle={}s",
                    info.cid, info.bytes_up, info.bytes_down, age, idle
                ),
            ));
        }
        out
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

