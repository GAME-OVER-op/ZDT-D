use crate::cli::Args;
use crate::socks5::TargetAddr;
use anyhow::{anyhow, Context, Result};
use parking_lot::Mutex;
use rand::RngCore;
use serde::Serialize;
use std::collections::{HashMap, VecDeque};
use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
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
    pub fn inc_policy_drop(&self) { self.policy_drop.fetch_add(1, Ordering::Relaxed); }
}

pub struct RuntimeConfig {
    /// 0 = unlimited
    pub download_limit_bps: AtomicU64,
    /// Connected Web UI clients (SSE/WS).
    pub ui_clients: std::sync::atomic::AtomicU64,

    /// Wakes up background loops (health checks, enforce loop, etc.) when activity happens.
    pub wakeup: tokio::sync::Notify,

    /// While this timestamp is in the future, direct fallback is temporarily blocked
    /// because recent direct attempts failed with timeouts or unreachable errors.
    pub direct_cooldown_until_ts: AtomicU64,

    /// Deduplicate forced health refreshes triggered by new connections.
    pub refresh_lock: tokio::sync::Mutex<()>,
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            download_limit_bps: AtomicU64::new(0),
            ui_clients: std::sync::atomic::AtomicU64::new(0),
            wakeup: tokio::sync::Notify::new(),
            direct_cooldown_until_ts: AtomicU64::new(0),
            refresh_lock: tokio::sync::Mutex::new(()),
        }
    }
}

impl RuntimeConfig {
    pub fn wake(&self) {
        self.wakeup.notify_waiters();
    }

    pub fn direct_allowed(&self) -> bool {
        now_ts() >= self.direct_cooldown_until_ts.load(Ordering::Relaxed)
    }

    pub fn note_direct_failure(&self, seconds: u64) {
        self.direct_cooldown_until_ts.store(now_ts().saturating_add(seconds), Ordering::Relaxed);
        self.wake();
    }

    pub fn clear_direct_cooldown(&self) {
        self.direct_cooldown_until_ts.store(0, Ordering::Relaxed);
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
    status: Vec<BackendStatus>,
    ttl_hist: Vec<VecDeque<u32>>,
    rtt_hist: Vec<VecDeque<f64>>,
    runtime_fail_streak: Vec<u8>,
    last_runtime_fail_ts: Vec<u64>,
    rr: usize,
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

        Ok(Self{ addrs, status, ttl_hist, rtt_hist, runtime_fail_streak, last_runtime_fail_ts, rr: 0 })
    }

    pub fn len(&self) -> usize { self.addrs.len() }
    pub fn addr_at(&self, idx: usize) -> Option<SocketAddr> { self.addrs.get(idx).copied() }

    pub fn any_healthy(&self) -> bool {
        self.status.iter().any(|s| s.healthy)
    }

    /// Returns true if there is at least one GREEN backend (responding + Internet OK).
    pub fn any_green(&self) -> bool {
        self.any_healthy()
    }

    /// Returns true if there is at least one backend that responds (GREEN or YELLOW).
    pub fn any_reachable(&self) -> bool {
        self.status.iter().any(|s| s.state != BackendState::Red)
    }

    pub fn mark_backend_failed(&mut self, addr: SocketAddr, err: String) {
        if let Some(idx) = self.addrs.iter().position(|a| *a == addr) {
            if idx < self.status.len() {
                let now = now_ts();
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

                let streak = self.runtime_fail_streak.get(idx).copied().unwrap_or(1);
                let tolerate = match self.status[idx].state {
                    BackendState::Green => streak < 3,
                    BackendState::Yellow => streak < 2,
                    BackendState::Red => false,
                };

                if tolerate {
                    return;
                }

                self.status[idx].state = BackendState::Red;
                self.status[idx].healthy = false;
                self.status[idx].socks_ping_ms = None;
                self.status[idx].internet_ping_ms = None;
            }
        }
    }


    pub fn snapshot(&self) -> Vec<BackendStatus> {
        self.status.clone()
    }

    pub fn add(&mut self, addr: SocketAddr) {
        if self.addrs.iter().any(|a| *a == addr) { return; }
        self.addrs.push(addr);
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
    }

    pub fn remove(&mut self, addr: SocketAddr) {
        if let Some(pos) = self.addrs.iter().position(|a| *a == addr) {
            self.addrs.remove(pos);
            if pos < self.status.len() { self.status.remove(pos); }
            if pos < self.ttl_hist.len() { self.ttl_hist.remove(pos); }
            if pos < self.rtt_hist.len() { self.rtt_hist.remove(pos); }
            if pos < self.runtime_fail_streak.len() { self.runtime_fail_streak.remove(pos); }
            if pos < self.last_runtime_fail_ts.len() { self.last_runtime_fail_ts.remove(pos); }
            if self.rr >= self.addrs.len() { self.rr = 0; }
        }
    }
pub fn select_rr(&mut self) -> Result<SocketAddr> {
        let healthy: Vec<usize> = self.status.iter().enumerate().filter(|(_,s)| s.healthy).map(|(i,_)| i).collect();
        if healthy.is_empty() {
            return Err(anyhow!("no GREEN backends"));
        }
        self.rr = (self.rr + 1) % healthy.len();
        Ok(self.addrs[healthy[self.rr]])
    }

    pub fn update(
        &mut self,
        idx: usize,
        _healthy: bool,
        err: Option<String>,
        socks_ping_ms: Option<f64>,
        internet_ping_ms: Option<f64>,
        internet_ttl: Option<u32>,
    ) {
        if idx >= self.status.len() { return; }
        // Derive tri-state health:
        // - Red: backend not responding (SOCKS connect/greeting failed)
        // - Yellow: backend responds, but cannot reach the Internet (probe failed)
        // - Green: backend responds and Internet probe succeeds
        let state = if socks_ping_ms.is_none() {
            BackendState::Red
        } else if internet_ping_ms.is_some() {
            BackendState::Green
        } else {
            BackendState::Yellow
        };
        self.status[idx].state = state;
        self.status[idx].healthy = state == BackendState::Green;
        self.status[idx].last_check = now_ts();
        if idx < self.runtime_fail_streak.len() {
            self.runtime_fail_streak[idx] = 0;
        }
        if idx < self.last_runtime_fail_ts.len() {
            self.last_runtime_fail_ts[idx] = 0;
        }
        self.status[idx].last_error = err;

        self.status[idx].socks_ping_ms = socks_ping_ms;
        self.status[idx].internet_ping_ms = internet_ping_ms;

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
    }

    /// Adds proxied bytes for a backend (TCP, both directions).
    pub fn add_bytes(&mut self, addr: SocketAddr, n: u64) {
        if let Some(pos) = self.addrs.iter().position(|a| *a == addr) {
            if pos < self.status.len() {
                self.status[pos].total_bytes = self.status[pos].total_bytes.saturating_add(n);
            }
        }
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct ConnInfo {
    pub cid: u64,
    pub peer: String,
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
    pub bytes_up: u64,
    pub bytes_down: u64,
}

#[derive(Default)]
pub struct ConnRegistry {
    inner: Mutex<HashMap<u64, (ConnInfo, CancellationToken)>>,
}

impl ConnRegistry {
    pub fn new_conn(&self, peer: SocketAddr, ingress: Ingress) -> u64 {
        let cid = rand_u64();
        let info = ConnInfo{
            cid,
            peer: peer.to_string(),
            ingress: match ingress { Ingress::Internal => "internal".into(), Ingress::External => "external".into() },
            domain: None,
            dst_ip: None,
            target: None,
            mode: None,
            backend: None,
            started_ts: now_ts(),
            bytes_up: 0,
            bytes_down: 0,
        };
        self.inner.lock().insert(cid, (info, CancellationToken::new()));
        cid
    }

    pub fn len(&self) -> usize {
        self.inner.lock().len()
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
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.target = Some(target.to_string());
            info.mode = Some(mode.to_string());
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
        }
    }
    pub fn add_bytes_down(&self, cid: u64, n: u64) {
        if let Some((info, _)) = self.inner.lock().get_mut(&cid) {
            info.bytes_down += n;
        }
    }



    /// Cancels all active connections whose mode matches the given string (e.g. "direct").
    /// Returns number of cancelled connections.
    pub fn kill_mode(&self, mode: &str) -> usize {
        let mut n = 0usize;
        let mut g = self.inner.lock();
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
        let mut g = self.inner.lock();
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
    pub fn finish_conn(&self, cid: u64) {
        self.inner.lock().remove(&cid);
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

pub fn now_ts() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

fn rand_u64() -> u64 {
    let mut rng = rand::thread_rng();
    rng.next_u64()
}

async fn probe_backend_once(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
    internet_ttl: Option<u32>,
) -> (Option<String>, Option<f64>, Option<f64>, Option<u32>) {
    let socks_ping_ms = check_backend_rtt(backend, timeout, auth.clone()).await;
    let mut err = if socks_ping_ms.is_some() { None } else { Some("connect/greeting/auth failed".to_string()) };
    let internet_ping_ms = if socks_ping_ms.is_some() {
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
    (err, socks_ping_ms, internet_ping_ms, internet_ttl)
}

pub async fn refresh_backends_once(state: crate::AppState, timeout: Duration) {
    let _guard = state.runtime.refresh_lock.lock().await;
    let internet_ttl = match ping_once("1.1.1.1".to_string()).await {
        Ok(Some((_ms, ttl))) => Some(ttl as u32),
        _ => None,
    };
    let auth = match (state.args.socks_user.clone(), state.args.socks_pass.clone()) {
        (Some(u), Some(p)) => Some((u, p)),
        _ => None,
    };
    let addrs: Vec<(usize, SocketAddr)> = {
        let b = state.backends.lock();
        (0..b.len()).filter_map(|idx| b.addr_at(idx).map(|a| (idx, a))).collect()
    };
    let futs = addrs.iter().map(|(_, backend)| probe_backend_once(*backend, timeout, auth.clone(), internet_ttl));
    let results = futures::future::join_all(futs).await;
    let mut b = state.backends.lock();
    for ((idx, _backend), (err, socks_ping_ms, internet_ping_ms, ttl)) in addrs.into_iter().zip(results.into_iter()) {
        b.update(idx, socks_ping_ms.is_some(), err, socks_ping_ms, internet_ping_ms, ttl);
    }
    if b.any_green() {
        state.runtime.clear_direct_cooldown();
    }
}

// --- background tasks

pub async fn backend_health_loop(state: crate::AppState) {
    let mut idle_since: Option<tokio::time::Instant> = None;

    loop {
        let timeout = Duration::from_millis(((state.args.connect_timeout as u64) * 1000).clamp(1200, 3000));
        refresh_backends_once(state.clone(), timeout).await;

        let ui = state.runtime.ui_clients.load(Ordering::Relaxed);
        let active = state.conns.len();
        let is_idle = ui == 0 && active == 0;

        let interval = if is_idle {
            if idle_since.is_none() {
                idle_since = Some(tokio::time::Instant::now());
            }
            let idle_for = idle_since.unwrap().elapsed();
            if idle_for < Duration::from_secs(8 * 60) {
                Duration::from_secs(20)
            } else if idle_for < Duration::from_secs(25 * 60) {
                Duration::from_secs(45)
            } else {
                Duration::from_secs(90)
            }
        } else {
            idle_since = None;
            Duration::from_secs(2)
        };

        tokio::select! {
            _ = tokio::time::sleep(interval) => {},
            _ = state.runtime.wakeup.notified() => { idle_since = None; }
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
) -> (usize, Option<f64>) {
    let futs = targets.into_iter().map(|target| probe_one_target(backend, target, timeout, auth.clone()));
    let results = futures::future::join_all(futs).await;
    let ok_count = results.iter().filter(|r| r.is_some()).count();
    let best = results.into_iter().flatten().min_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    (ok_count, best)
}

pub async fn proxy_enforce_loop(state: crate::AppState) {
    // Kill stale connections that got established in DIRECT mode while there were no GREEN backends,
    // so clients will reconnect through SOCKS as soon as backends recover.
    let mut had_green = false;

    let mut idle_since: Option<tokio::time::Instant> = None;

    loop {
        let green = state.backends.lock().any_green();

        if green {
            // Safety net: if any DIRECT connections exist while GREEN backends are available,
            // cancel them. This enforces the "no bypass" rule.
            let killed_direct = state.conns.kill_mode("direct");
            if killed_direct > 0 {
                tracing::info!("proxy enforce: cancelled {} DIRECT connections (GREEN available)", killed_direct);
            }

            // On recovery edge (no green -> green), also cancel SOCKS connections that never transferred any data.
            if !had_green {
                let killed_stuck = state.conns.kill_stuck_socks_zero_traffic(15);
                if killed_stuck > 0 {
                    tracing::info!("proxy enforce: cancelled {} stuck SOCKS connections after recovery", killed_stuck);
                }
            }
        }

        had_green = green;

        // When idle (no UI + no conns), slow down enforcement checks.
        let ui = state.runtime.ui_clients.load(Ordering::Relaxed);
        let active = state.conns.len();
        let is_idle = ui == 0 && active == 0;

        let interval = if is_idle {
            if idle_since.is_none() {
                idle_since = Some(tokio::time::Instant::now());
            }
            // While sleeping, enforce rarely (there should be no conns anyway).
            let idle_for = idle_since.unwrap().elapsed();
            if idle_for < Duration::from_secs(10 * 60) {
                Duration::from_secs(15)
            } else {
                Duration::from_secs(30)
            }
        } else {
            idle_since = None;
            Duration::from_secs(2)
        };

        tokio::select! {
            _ = tokio::time::sleep(interval) => {},
            _ = state.runtime.wakeup.notified() => {
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
    let start = Instant::now();
    let res = tokio::time::timeout(timeout, TcpStream::connect(backend)).await;
    if let Ok(Ok(mut s)) = res {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut methods = vec![0x00u8];
        if auth.is_some() {
            methods.push(0x02u8);
        }
        if s.write_all(&[0x05u8, methods.len() as u8]).await.is_err() { return None; }
        if s.write_all(&methods).await.is_err() { return None; }
        let mut resp = [0u8; 2];
        if s.read_exact(&mut resp).await.is_err() { return None; }
        if resp[0] != 0x05 { return None; }
        match resp[1] {
            0x00 => Some(start.elapsed().as_secs_f64() * 1000.0),
            0x02 => {
                let (u, p) = auth?;
                if crate::socks5::do_userpass_auth_for_healthcheck(&mut s, &u, &p).await.is_ok() {
                    Some(start.elapsed().as_secs_f64() * 1000.0)
                } else { None }
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
    // Several parallel checks reduce false positives and false negatives:
    // 1) raw IP connectivity via SOCKS CONNECT
    // 2) remote-DNS/domain connectivity via SOCKS CONNECT with DOMAIN target
    // A backend is considered internet-usable only if either:
    // - at least one IP probe AND at least one domain probe succeed, or
    // - at least two different IP probes succeed
    // This avoids "GREEN" on backends that only answer SOCKS locally or only reach a single target.
    let per_probe_timeout = timeout.min(Duration::from_millis(1500));

    let ip_targets = vec![
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)), 443)),
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 0, 0, 1)), 443)),
        TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(8, 8, 8, 8)), 53)),
    ];
    let domain_targets = vec![
        TargetAddr::Domain("example.com".to_string(), 80),
        TargetAddr::Domain("cloudflare.com".to_string(), 443),
    ];

    let ((ip_ok, ip_best), (domain_ok, domain_best)) = tokio::join!(
        first_successful_probe(backend, per_probe_timeout, auth.clone(), ip_targets),
        first_successful_probe(backend, per_probe_timeout, auth, domain_targets),
    );

    let ok = (ip_ok >= 1 && domain_ok >= 1) || ip_ok >= 2;
    let best_ping_ms = match (ip_best, domain_best) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };

    let error_summary = if ok {
        format!("probe ok (ip={}/3, domain={}/2)", ip_ok, domain_ok)
    } else if ip_ok >= 1 && domain_ok == 0 {
        format!("internet probes weak: raw IP works (ip={}/3) but domain/DNS failed (domain=0/2)", ip_ok)
    } else if ip_ok == 0 && domain_ok >= 1 {
        format!("internet probes weak: domain worked (domain={}/2) but raw IP failed (ip=0/3)", domain_ok)
    } else {
        format!("internet probes failed (ip={}/3, domain={}/2)", ip_ok, domain_ok)
    };

    InternetProbeSummary { ok, best_ping_ms, error_summary }
}

/// Best-effort one-shot ICMP ping.
/// Returns (rtt_ms, ttl).
async fn ping_once(host: String) -> Result<Option<(f64, u32)>> {
    // ping is blocking; run it off the core.
    tokio::task::spawn_blocking(move || {
        // On Android, `ping` usually supports: ping -n -c 1 -W 1 <host>
        let out = std::process::Command::new("ping")
            .args(["-n", "-c", "1", "-W", "1", &host])
            .output();

        let Ok(out) = out else { return Ok(None); };
        if !out.status.success() {
            return Ok(None);
        }
        let s = String::from_utf8_lossy(&out.stdout);
        // Example: "ttl=117 time=14.0 ms"
        let re = regex::Regex::new(r"ttl=(\d+).*time=([0-9.]+)\s*ms").unwrap();
        if let Some(c) = re.captures(&s) {
            let ttl: u32 = c.get(1).unwrap().as_str().parse().unwrap_or(0);
            let ms: f64 = c.get(2).unwrap().as_str().parse().unwrap_or(0.0);
            return Ok(Some((ms, ttl)));
        }
        // Some ping versions use "time<1ms" and may not include ttl in same order.
        let re2 = regex::Regex::new(r"time=([0-9.]+)\s*ms").unwrap();
        let ms = re2.captures(&s).and_then(|c| c.get(1)).and_then(|m| m.as_str().parse().ok());
        let re3 = regex::Regex::new(r"ttl=(\d+)").unwrap();
        let ttl = re3.captures(&s).and_then(|c| c.get(1)).and_then(|m| m.as_str().parse().ok());
        Ok(ms.zip(ttl))
    }).await.context("spawn_blocking ping")?
}

pub async fn wait_for_backend_recovery(state: crate::AppState, max_wait: Duration) -> bool {
    let deadline = tokio::time::Instant::now() + max_wait;
    while tokio::time::Instant::now() < deadline {
        if state.backends.lock().any_healthy() {
            return true;
        }
        refresh_backends_once(state.clone(), Duration::from_millis(1500)).await;
        if state.backends.lock().any_healthy() {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    false
}