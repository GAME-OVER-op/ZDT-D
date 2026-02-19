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
}

impl Default for RuntimeConfig {
    fn default() -> Self {
        Self {
            download_limit_bps: AtomicU64::new(0),
            ui_clients: std::sync::atomic::AtomicU64::new(0),
            wakeup: tokio::sync::Notify::new(),
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

        Ok(Self{ addrs, status, ttl_hist, rtt_hist, rr: 0 })
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
                self.status[idx].state = BackendState::Red;
                self.status[idx].healthy = false;
                self.status[idx].last_check = now_ts();
                self.status[idx].last_error = Some(err);
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
    }

    pub fn remove(&mut self, addr: SocketAddr) {
        if let Some(pos) = self.addrs.iter().position(|a| *a == addr) {
            self.addrs.remove(pos);
            if pos < self.status.len() { self.status.remove(pos); }
            if pos < self.ttl_hist.len() { self.ttl_hist.remove(pos); }
            if pos < self.rtt_hist.len() { self.rtt_hist.remove(pos); }
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

// --- background tasks

pub async fn backend_health_loop(state: crate::AppState) {
    let mut idle_since: Option<tokio::time::Instant> = None;

    loop {
        // Best-effort internet TTL probe (not per-backend).
        let internet_ttl = match ping_once("1.1.1.1".to_string()).await {
            Ok(Some((_ms, ttl))) => Some(ttl as u32),
            _ => None,
        };

        let len = state.backends.lock().len();
        for idx in 0..len {
            let backend = match state.backends.lock().addr_at(idx) {
                Some(a) => a,
                None => continue,
            };

            let timeout = Duration::from_secs(state.args.connect_timeout as u64);

            // 1) SOCKS RTT/health (TCP + greeting). This is the primary availability signal.
            let socks_ping_ms = check_backend_rtt(backend, timeout).await;
            let ok = socks_ping_ms.is_some();

            // Report a useful error if the backend itself is down.
            let mut err = if ok { None } else { Some("connect/greeting failed".to_string()) };

            // 2) "Internet through server" probe (SOCKS CONNECT to 1.1.1.1:443) (best-effort)
            let internet_ping_ms = if ok {
                let auth = match (state.args.socks_user.clone(), state.args.socks_pass.clone()) {
                    (Some(u), Some(p)) => Some((u, p)),
                    _ => None,
                };
                check_internet_via_backend(backend, timeout, auth).await
            } else {
                None
            };

            if ok && internet_ping_ms.is_none() {
                err = Some("internet probe failed".to_string());
            }

            state.backends.lock().update(
                idx,
                ok,
                err,
                socks_ping_ms,
                internet_ping_ms,
                internet_ttl,
            );

            // TCP-only build: no UDP support and no UDP capability probing.
        }

        // Sleep policy: when idle (no UI + no conns), we "sleep" and check much less often.
        // During sleep we poll every 1-3 minutes depending on how long we've been idle.
        let ui = state.runtime.ui_clients.load(Ordering::Relaxed);
        let active = state.conns.len();
        let is_idle = ui == 0 && active == 0;

        let interval = if is_idle {
            if idle_since.is_none() {
                idle_since = Some(tokio::time::Instant::now());
            }
            let idle_for = idle_since.unwrap().elapsed();

            if idle_for < Duration::from_secs(8 * 60) {
                Duration::from_secs(60) // 1 min
            } else if idle_for < Duration::from_secs(25 * 60) {
                Duration::from_secs(120) // 2 min
            } else {
                Duration::from_secs(180) // 3 min
            }
        } else {
            idle_since = None;
            Duration::from_secs(5)
        };

        tokio::select! {
            _ = tokio::time::sleep(interval) => {},
            _ = state.runtime.wakeup.notified() => {
                // Activity happened (new connection / UI), wake immediately.
                idle_since = None;
            }
        }
    }
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
async fn check_backend_rtt(backend: SocketAddr, timeout: Duration) -> Option<f64> {
    let start = Instant::now();
    let res = tokio::time::timeout(timeout, TcpStream::connect(backend)).await;
    if let Ok(Ok(mut s)) = res {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        // Offer only NOAUTH here; we only need liveness + RTT.
        if s.write_all(&[0x05u8, 0x01u8, 0x00u8]).await.is_err() {
            return None;
        }
        let mut resp = [0u8; 2];
        if s.read_exact(&mut resp).await.is_err() { return None; }
        if resp[0] == 0x05 && resp[1] != 0xFF {
            return Some(start.elapsed().as_secs_f64() * 1000.0);
        }
    }
    None
}

async fn check_internet_via_backend(
    backend: SocketAddr,
    timeout: Duration,
    auth: Option<(String, String)>,
) -> Option<f64> {
    let start = Instant::now();
    // Use a stable anycast target; only connect latency matters.
    let target = TargetAddr::Ip(SocketAddr::new(std::net::IpAddr::V4(std::net::Ipv4Addr::new(1, 1, 1, 1)), 443));
    if crate::socks5::connect_via_socks5(backend, target, auth, timeout).await.is_ok() {
        Some(start.elapsed().as_secs_f64() * 1000.0)
    } else {
        None
    }
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
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    false
}