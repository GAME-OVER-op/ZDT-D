mod cli;
mod socks5;
mod transparent;
mod rules;
mod stats;
mod web;
mod system;
mod sniff;

use anyhow::{Context, Result};
use cli::Args;
use parking_lot::Mutex;
use std::{net::SocketAddr, sync::Arc, time::Duration};
use tokio::{net::TcpListener, signal, sync::{broadcast, Semaphore}};
use tracing::{error, info, warn};

#[derive(Clone)]
pub struct AppState {
    pub args: Args,
    pub stats: Arc<stats::Stats>,
    pub runtime: Arc<stats::RuntimeConfig>,
    pub conns: Arc<stats::ConnRegistry>,
    pub rules: Arc<rules::Rules>,
    pub backends: Arc<Mutex<stats::SocksBackends>>,
    pub system: Arc<Mutex<system::SystemStats>>,
    pub events: broadcast::Sender<stats::Event>,
    pub semaphore: Arc<Semaphore>,
}


#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum SniffMode {
    Progressive,
    Quick80,
    Skip,
}

fn sniff_thresholds(max_conns: u32) -> (usize, usize) {
    let max_conns = usize::max(max_conns as usize, 1);
    let busy = (max_conns / 3).clamp(24, 64);
    let overload = ((max_conns * 2) / 3).clamp(48, 128).max(busy + 1);
    (busy, overload)
}

fn sniff_mode_for(state: &AppState) -> SniffMode {
    let active = state.conns.len();
    let (busy_threshold, overload_threshold) = sniff_thresholds(state.args.max_conns);

    if active >= overload_threshold {
        if state.rules.has_host_rules() {
            SniffMode::Quick80
        } else {
            SniffMode::Skip
        }
    } else if active >= busy_threshold {
        SniffMode::Quick80
    } else {
        SniffMode::Progressive
    }
}


fn accept_error_backoff(err: &std::io::Error) -> Duration {
    match err.raw_os_error() {
        Some(11) | Some(12) | Some(23) | Some(24) => Duration::from_millis(250),
        _ => Duration::from_millis(50),
    }
}

fn is_transient_accept_error(err: &std::io::Error) -> bool {
    use std::io::ErrorKind;
    matches!(
        err.kind(),
        ErrorKind::ConnectionAborted
            | ErrorKind::ConnectionReset
            | ErrorKind::Interrupted
            | ErrorKind::WouldBlock
            | ErrorKind::TimedOut
    ) || matches!(err.raw_os_error(), Some(11) | Some(12) | Some(23) | Some(24))
}

async fn sniff_client_host(client: &tokio::net::TcpStream, mode: SniffMode) -> Option<crate::sniff::SniffResult> {
    use tokio::time::{Duration, Instant};

    let budgets_ms: &[u64] = match mode {
        SniffMode::Progressive => &[80, 120, 160, 200],
        SniffMode::Quick80 => &[80],
        SniffMode::Skip => &[],
    };

    if budgets_ms.is_empty() {
        return None;
    }

    let mut buf = vec![0u8; 4096];
    let started = Instant::now();

    for budget_ms in budgets_ms {
        let budget = Duration::from_millis(*budget_ms);
        let elapsed = started.elapsed();
        if elapsed >= budget {
            continue;
        }
        let remaining = budget - elapsed;
        match tokio::time::timeout(remaining, client.peek(&mut buf)).await {
            Ok(Ok(sz)) if sz > 0 => return crate::sniff::sniff_host(&buf[..sz]),
            Ok(Ok(_)) => return None,
            Ok(Err(_)) => return None,
            Err(_) => continue,
        }
    }

    None
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(std::env::var("RUST_LOG").unwrap_or_else(|_| "info".to_string()))
        .init();

    let args = Args::parse_and_normalize().context("parse args")?;

    let rules = rules::Rules::load_from_env();
    let stats = Arc::new(stats::Stats::default());
    let runtime = Arc::new(stats::RuntimeConfig::default());
    // initialize download limit from CLI
    if args.download_limit_mbit > 0.0 {
        let bps = (args.download_limit_mbit * 1024.0 * 1024.0 / 8.0) as u64;
        runtime.download_limit_bps.store(bps, std::sync::atomic::Ordering::Relaxed);
    }
    let conns = Arc::new(stats::ConnRegistry::default());
    let (events, _rx) = broadcast::channel(1024);

    let backends = Arc::new(Mutex::new(stats::SocksBackends::new(&args)?));
    let system_stats = Arc::new(Mutex::new(system::SystemStats::default()));
    let semaphore = Arc::new(Semaphore::new(args.max_conns as usize));

    let state = AppState {
        args: args.clone(),
        stats,
        runtime,
        conns,
        rules: Arc::new(rules),
        backends,
        system: system_stats.clone(),
        events,
        semaphore,
    };

    // Background: periodic backend checks + stats tick
    {
        let st = state.clone();
        tokio::spawn(async move {
            stats::backend_health_loop(st).await;
        });
    }

    // Background: enforce "no bypass while GREEN backends exist" and auto-kill stale connections after recovery
    {
        let st = state.clone();
        tokio::spawn(async move {
            stats::proxy_enforce_loop(st).await;
        });
    }

    // Background: system stats collector (/proc), optimized for Android.
    // To save battery on weaker phones, we only collect expensive /proc stats while the Web UI is open.
    {
        let st = state.clone();
        tokio::spawn(async move {
            let mut prev = None;
            let mut idle_since: Option<tokio::time::Instant> = None;
            loop {
                let ui = st.runtime.ui_clients.load(std::sync::atomic::Ordering::Relaxed);
                if ui == 0 {
                    idle_since = None;
                    tokio::select! {
                        _ = st.runtime.wakeup.notified() => {},
                        _ = tokio::time::sleep(Duration::from_secs(60)) => {},
                    }
                    continue;
                }

                let (s, cpu) = system::collect(prev);
                prev = Some(cpu);
                *st.system.lock() = s;

                let active = st.conns.len();
                let interval = if active == 0 {
                    if idle_since.is_none() {
                        idle_since = Some(tokio::time::Instant::now());
                    }
                    let idle_for = idle_since.unwrap().elapsed();
                    if idle_for < Duration::from_secs(10 * 60) {
                        Duration::from_secs(20)
                    } else {
                        Duration::from_secs(40)
                    }
                } else {
                    idle_since = None;
                    Duration::from_secs(10)
                };

                tokio::select! {
                    _ = tokio::time::sleep(interval) => {},
                    _ = st.runtime.wakeup.notified() => {},
                }
            }
        });
    }

    // Web server (optional)
    if args.web_socket {
        let st = state.clone();
        tokio::spawn(async move {
            if let Err(e) = web::serve(st).await {
                error!("web server error: {:#}", e);
            }
        });
    }

    // TCP listener (TCP-only build)
    {
        let st = state.clone();
        tokio::spawn(async move {
            if let Err(e) = run_tcp(st).await {
                error!("tcp server error: {:#}", e);
            }
        });

        // Optional external listener on 0.0.0.0:<external_port>
        if args.external_port != 0 {
            let st = state.clone();
            let ext_port = args.external_port;
            tokio::spawn(async move {
                if let Err(e) = run_tcp_on(
                    st,
                    format!("0.0.0.0:{}", ext_port).parse().unwrap(),
                    stats::Ingress::External,
                )
                .await
                {
                    error!("external tcp server error: {:#}", e);
                }
            });
        }
    }

    info!("Started. Press Ctrl+C to stop.");
    signal::ctrl_c().await?;
    info!("Shutting down.");
    Ok(())
}

async fn run_tcp(state: AppState) -> Result<()> {
    let addr: SocketAddr = format!("{}:{}", state.args.listen_addr, state.args.listen_port)
        .parse()
        .context("listen addr parse")?;
    run_tcp_on(state, addr, stats::Ingress::Internal).await
}

async fn run_tcp_on(state: AppState, addr: SocketAddr, ingress: stats::Ingress) -> Result<()> {
    // Prevent obvious self-conflicts (e.g. internal listen_addr already 0.0.0.0 and same port).
    if ingress == stats::Ingress::External {
        if state.args.listen_port == addr.port() {
            tracing::warn!("External listener port {} matches internal listen_port; skipping external listener.", addr.port());
            return Ok(());
        }
    }

    let listener = TcpListener::bind(addr).await.context("bind tcp listener")?;
    info!("TCP ({:?}) listening on {}", ingress, addr);

    loop {
        let (sock, peer) = match listener.accept().await {
            Ok(v) => v,
            Err(e) => {
                if is_transient_accept_error(&e) {
                    let backoff = accept_error_backoff(&e);
                    warn!("TCP ({:?}) accept temporary error on {}: {} (backoff {:?})", ingress, addr, e, backoff);
                    tokio::time::sleep(backoff).await;
                    continue;
                }
                return Err(e).context("accept");
            }
        };
        // Wake up background loops (health checks, etc.) on new activity.
        state.runtime.wake();

        let permit = match state.semaphore.clone().try_acquire_owned() {
            Ok(p) => p,
            Err(_) => {
                state.stats.inc_policy_drop();
                warn!("dropping new connection from {}: connection storm protection (max_conns reached)", peer);
                drop(sock);
                continue;
            }
        };

        if ingress == stats::Ingress::External {
            let (_, ext_active) = state.conns.ingress_counts();
            let ext_limit = external_ingress_limit(&state);
            if ext_active >= ext_limit {
                state.stats.inc_policy_drop();
                warn!(
                    "dropping new external connection from {}: external fairness cap reached ({}/{})",
                    peer,
                    ext_active,
                    ext_limit,
                );
                drop(sock);
                drop(permit);
                continue;
            }
        }

        let source_limit = if ingress == stats::Ingress::External {
            Some(external_per_source_limit(&state))
        } else {
            None
        };
        let cid = match state.conns.try_new_conn(peer, ingress, source_limit) {
            Some(cid) => cid,
            None => {
                state.stats.inc_policy_drop();
                warn!(
                    "dropping new connection from {}: per-source fairness cap reached (limit={})",
                    peer,
                    source_limit.unwrap_or(0),
                );
                drop(sock);
                drop(permit);
                continue;
            }
        };
        let token = state.conns.set_cancel_token(cid);
        let _ = state.events.send(stats::Event::conn_open(cid, peer));

        let st = state.clone();
        tokio::spawn(async move {
            let res = proxy_tcp(sock, peer, cid, st.clone(), token, ingress).await;

            drop(permit);

            match res {
                Ok(()) => {}
                Err(e) => {
                    st.stats.inc_error();
                    warn!("[cid={}] connection ended with error: {:#}", cid, e);
                }
            }
            st.conns.finish_conn(cid);
            let _ = st.events.send(stats::Event::conn_close(cid));
        });
    }
}

async fn proxy_tcp(
    mut client: tokio::net::TcpStream,
    peer: SocketAddr,
    cid: u64,
    state: AppState,
    cancel: tokio_util::sync::CancellationToken,
    ingress: stats::Ingress,
) -> Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::time::Instant;

    state.conns.set_mode(cid, "pending");

    // Determine target
    let target = if let (Some(h), Some(p)) = (state.args.target_host.clone(), state.args.target_port) {
        stats::Target::HostPort(h, p)
    } else {
        let dst = transparent::get_original_dst(&client)
            .context("SO_ORIGINAL_DST failed (need iptables REDIRECT/TPROXY style setup)")?;
        stats::Target::SockAddr(dst)
    };

    let (target_host, target_port) = target.to_host_port_string();

    // Best-effort sniffing: domain from HTTP Host / CONNECT / TLS SNI.
    // Under load we shrink or skip sniffing to avoid adding avoidable latency on new connections.
    let sniffed = sniff_client_host(&client, sniff_mode_for(&state)).await;
    let sniff_host = match &sniffed {
        Some(crate::sniff::SniffResult::HttpHost(h)) => Some(h.clone()),
        Some(crate::sniff::SniffResult::ConnectHost(h)) => Some(h.clone()),
        Some(crate::sniff::SniffResult::TlsSni(h)) => Some(h.clone()),
        None => None,
    };

    // Expose best-effort domain to the UI (SNI/Host/CONNECT). If absent -> UI will show fallback.
    state.conns.set_domain(cid, sniff_host.clone());

    // Best-effort destination IP (used by the UI). For transparent mode we always know it.
    // For HostPort targets (explicit mode) we try resolving quickly, but never fail the connection.
    let dst_ip_hint: Option<String> = match &target {
        stats::Target::SockAddr(sa) => Some(sa.ip().to_string()),
        stats::Target::HostPort(host, port) => {
            let r = tokio::time::timeout(
                Duration::from_millis(250),
                tokio::net::lookup_host((host.as_str(), *port)),
            )
            .await;
            match r {
                Ok(Ok(it)) => {
                    // Prefer IPv4 when IPv6 is disabled on-device.
                    let mut first: Option<std::net::SocketAddr> = None;
                    let mut chosen: Option<std::net::SocketAddr> = None;
                    for sa in it {
                        if first.is_none() {
                            first = Some(sa);
                        }
                        if sa.is_ipv4() {
                            chosen = Some(sa);
                            break;
                        }
                    }
                    chosen.or(first).map(|sa| sa.ip().to_string())
                }
                _ => None,
            }
        }
    };
    state.conns.set_dst_ip(cid, dst_ip_hint);

    let host_for_rules = sniff_host.clone().unwrap_or_else(|| target_host.clone());

    let proto = rules::classify_protocol(target_port);
    let socks_available = state.backends.lock().any_green();
    let action = state.rules.decide(&proto, &host_for_rules, target_port, socks_available, false);

    // Resolve mode: socks vs direct
    let mut use_direct = false;
    match action {
        Some(rules::Action::Direct) => use_direct = true,
        Some(rules::Action::Drop) => {
            state.stats.inc_policy_drop();
            return Ok(());
        }
        Some(rules::Action::Reset) => {
            state.stats.inc_policy_drop();
            return Ok(());
        }
        Some(rules::Action::Wait) => {
            // Avoid turning WAIT into a permit-holding queue under overload.
            let overload_cutoff = ((state.args.max_conns as usize) * 8 / 10).max(1);
            let waiting_now = state.conns.count_modes(&["wait_backend"]);
            let setup_now = state.conns.count_modes(&["pending", "wait_backend", "socks_connecting"]);
            if state.conns.len() >= overload_cutoff
                || state.semaphore.available_permits() <= 1
                || waiting_now >= wait_phase_limit(&state)
                || setup_now >= setup_phase_limit(&state)
            {
                state.stats.inc_policy_drop();
                tracing::warn!(
                    "[cid={}] dropping WAIT action under overload (active={}, permits_left={}, waiting={}, setup={})",
                    cid,
                    state.conns.len(),
                    state.semaphore.available_permits(),
                    waiting_now,
                    setup_now,
                );
                return Ok(());
            }
            state.conns.set_mode(cid, "wait_backend");
            let ok = stats::wait_for_backend_recovery(state.clone(), Duration::from_secs(5)).await;
            state.conns.set_mode(cid, "pending");
            if !ok {
                state.stats.inc_policy_drop();
                return Ok(());
            }
        }
        None | Some(rules::Action::Socks) => {}
    }

    
    // Enforce: when there is at least one GREEN backend, do NOT allow direct connections.
    // Only bypass the proxy when no GREEN backends are available.
    if socks_available {
        use_direct = false;
    }

let mut chosen_mode = "socks";
    let mut chosen_backend: Option<SocketAddr> = None;
    let upstream = if use_direct || !socks_available {
        let refreshed = stats::wait_for_backend_recovery(state.clone(), Duration::from_millis(1200)).await;
        if refreshed {
            let (s, be) = connect_socks(&target, sniff_host.as_deref(), state.clone(), cid).await?;
            chosen_backend = Some(be);
            s
        } else {
            if !state.runtime.direct_allowed() {
                return Err(anyhow::anyhow!("direct fallback is cooling down after recent failures"));
            }
            chosen_mode = "direct";
            match connect_direct(&target, state.args.connect_timeout).await {
                Ok(s) => s,
                Err(e) => {
                    state.runtime.note_direct_failure(20);
                    return Err(e);
                }
            }
        }
    } else {
        match connect_socks(&target, sniff_host.as_deref(), state.clone(), cid).await {
            Ok((s, be)) => {
                chosen_backend = Some(be);
                s
            }
            Err(e) => {
                tracing::warn!("[cid={}] socks connect failed: {:#}", cid, e);

                if state.backends.lock().any_green() {
                    return Err(e);
                }

                let refreshed = stats::wait_for_backend_recovery(state.clone(), Duration::from_millis(1200)).await;
                if refreshed {
                    let (s, be) = connect_socks(&target, sniff_host.as_deref(), state.clone(), cid).await?;
                    chosen_backend = Some(be);
                    s
                } else {
                    if !state.runtime.direct_allowed() {
                        return Err(anyhow::anyhow!("direct fallback is cooling down after recent failures"));
                    }
                    chosen_mode = "direct";
                    match connect_direct(&target, state.args.connect_timeout).await {
                        Ok(s) => s,
                        Err(err) => {
                            state.runtime.note_direct_failure(20);
                            return Err(err);
                        }
                    }
                }
            }
        }
    };

    // Expose chosen backend (if any) to the UI.
    state.conns.set_backend(cid, chosen_backend);

    state.conns.set_target(cid, &format!("{}:{}", target_host, target_port), chosen_mode);
    let _ = state.events.send(stats::Event::conn_target(cid, target_host.clone(), target_port, chosen_mode.to_string()));

    // Proxy with simple throttling on downstream (upstream->client)
    let (mut cr, mut cw) = client.into_split();
    let (mut ur, mut uw) = upstream.into_split();
    let buf_sz = state.args.buffer_size as usize;

    // download limit is runtime-adjustable via web UI (0 = unlimited)

    let idle = if state.args.idle_timeout == 0 {
        None
    } else {
        Some(Duration::from_secs(state.args.idle_timeout as u64))
    };

    let st1 = state.clone();
    let be1 = chosen_backend;
    let c1 = cancel.clone();
    let t1 = tokio::spawn(async move {
        // client -> upstream (upload)
        let mut buf = vec![0u8; buf_sz];
        let mut be_acc: u64 = 0;
        loop {
            let n = tokio::select! {
                _ = c1.cancelled() => break,
                res = async {
                    if let Some(idle_d) = idle {
                        tokio::time::timeout(idle_d, cr.read(&mut buf)).await
                            .map_err(|_| anyhow::anyhow!("upload read idle timeout"))?
                            .map_err(anyhow::Error::from)
                    } else {
                        cr.read(&mut buf).await.map_err(anyhow::Error::from)
                    }
                } => res?,
            };
            if n == 0 { break; }

            tokio::select! {
                _ = c1.cancelled() => break,
                res = async {
                    if let Some(idle_d) = idle {
                        tokio::time::timeout(idle_d, uw.write_all(&buf[..n])).await
                            .map_err(|_| anyhow::anyhow!("upload write idle timeout"))?
                            .map_err(anyhow::Error::from)
                    } else {
                        uw.write_all(&buf[..n]).await.map_err(anyhow::Error::from)
                    }
                } => { res?; }
            }

            st1.stats.add_up(n as u64);
            st1.stats.add_up_ingress(ingress, n as u64);
            st1.conns.add_bytes_up(cid, n as u64);
            if let Some(b) = be1 {
                be_acc = be_acc.saturating_add(n as u64);
                if be_acc >= 65536 {
                    st1.backends.lock().add_bytes(b, be_acc);
                    be_acc = 0;
                }
            }
        }
        if let Some(b) = be1 {
            if be_acc > 0 {
                st1.backends.lock().add_bytes(b, be_acc);
            }
        }
        let _ = uw.shutdown().await;
        anyhow::Ok(())
    });

    let st2 = state.clone();
    let be2 = chosen_backend;
    let c2 = cancel.clone();
    let t2 = tokio::spawn(async move {
        // upstream -> client (download)
        let mut buf = vec![0u8; buf_sz];
        let mut be_acc: u64 = 0;
        let mut window_start = Instant::now();
        let mut window_bytes: u64 = 0;

        loop {
            let n = tokio::select! {
                _ = c2.cancelled() => break,
                res = async {
                    if let Some(idle_d) = idle {
                        tokio::time::timeout(idle_d, ur.read(&mut buf)).await
                            .map_err(|_| anyhow::anyhow!("download read idle timeout"))?
                            .map_err(anyhow::Error::from)
                    } else {
                        ur.read(&mut buf).await.map_err(anyhow::Error::from)
                    }
                } => res?,
            };
            if n == 0 { break; }

            let bps = st2.runtime.download_limit_bps.load(std::sync::atomic::Ordering::Relaxed);
            if bps > 0 {
                window_bytes += n as u64;
                let elapsed = window_start.elapsed().as_secs_f64();
                if elapsed > 0.0 {
                    let cur_bps = (window_bytes as f64) / elapsed;
                    if cur_bps > (bps as f64) {
                        // sleep proportional
                        let target_elapsed = (window_bytes as f64) / (bps as f64);
                        let sleep_s = target_elapsed - elapsed;
                        if sleep_s > 0.0 {
                            tokio::select! {
                                _ = c2.cancelled() => break,
                                _ = tokio::time::sleep(Duration::from_secs_f64(sleep_s.min(0.5))) => {}
                            }
                        }
                    }
                }
                if window_start.elapsed() > Duration::from_secs(1) {
                    window_start = Instant::now();
                    window_bytes = 0;
                }
            }

            tokio::select! {
                _ = c2.cancelled() => break,
                res = async {
                    if let Some(idle_d) = idle {
                        tokio::time::timeout(idle_d, cw.write_all(&buf[..n])).await
                            .map_err(|_| anyhow::anyhow!("download write idle timeout"))?
                            .map_err(anyhow::Error::from)
                    } else {
                        cw.write_all(&buf[..n]).await.map_err(anyhow::Error::from)
                    }
                } => { res?; }
            }
            st2.stats.add_down(n as u64);
            st2.stats.add_down_ingress(ingress, n as u64);
            st2.conns.add_bytes_down(cid, n as u64);
            if let Some(b) = be2 {
                be_acc = be_acc.saturating_add(n as u64);
                if be_acc >= 65536 {
                    st2.backends.lock().add_bytes(b, be_acc);
                    be_acc = 0;
                }
            }
        }
        if let Some(b) = be2 {
            if be_acc > 0 {
                st2.backends.lock().add_bytes(b, be_acc);
            }
        }
        let _ = cw.shutdown().await;
        anyhow::Ok(())
    });

    let _ = tokio::try_join!(t1, t2)?;

    Ok(())
}

async fn connect_direct(target: &stats::Target, timeout_s: u32) -> Result<tokio::net::TcpStream> {
    let timeout = Duration::from_secs(timeout_s as u64);
    let addr = target.resolve_socket_addr().await?;
    let s = tokio::time::timeout(timeout, tokio::net::TcpStream::connect(addr))
        .await
        .context("direct connect timeout")?
        .context("direct connect failed")?;
    Ok(s)
}

fn looks_like_ip(host: &str) -> bool {
    // Fast checks only; we don't want to depend on DNS here.
    host.parse::<std::net::IpAddr>().is_ok()
}

fn per_backend_connect_limit(state: &AppState) -> u32 {
    let backend_count = state.backends.lock().len().max(1) as u32;
    let share = ((state.args.max_conns.max(1) + backend_count - 1) / backend_count).max(1);
    (share / 2).clamp(4, 12)
}

fn setup_phase_limit(state: &AppState) -> usize {
    ((state.args.max_conns as usize) / 2).clamp(8, 64)
}

fn wait_phase_limit(state: &AppState) -> usize {
    ((state.args.max_conns as usize) / 8).clamp(2, 16)
}

fn internal_reserve_slots(state: &AppState) -> usize {
    let maxc = (state.args.max_conns as usize).max(1);
    if maxc <= 8 {
        1
    } else {
        ((maxc / 4).clamp(4, 32)).min(maxc.saturating_sub(1))
    }
}

fn external_ingress_limit(state: &AppState) -> usize {
    let maxc = (state.args.max_conns as usize).max(1);
    maxc.saturating_sub(internal_reserve_slots(state)).max(1)
}

fn external_per_source_limit(state: &AppState) -> u32 {
    let ingress_cap = external_ingress_limit(state);
    let base = ((state.args.max_conns as usize) / 4).clamp(8, 64);
    let limit = base.min((ingress_cap / 2).max(2));
    limit.max(2) as u32
}

fn is_soft_backend_failure(err: &str) -> bool {
    let e = err.to_ascii_lowercase();
    e.contains("socks handshake timeout")
        || e.contains("connect queue saturated")
        || e.contains("too many in-flight")
        || e.contains("timed out")
}

async fn connect_socks(
    target: &stats::Target,
    domain_hint: Option<&str>,
    state: AppState,
    cid: u64,
) -> Result<(tokio::net::TcpStream, SocketAddr)> {
    let timeout = Duration::from_secs(state.args.connect_timeout as u64);

    let setup_now = state.conns.count_modes(&["pending", "wait_backend", "socks_connecting"]);
    let setup_limit = setup_phase_limit(&state);
    if setup_now > setup_limit {
        return Err(anyhow::anyhow!("local socks setup saturated ({}/{})", setup_now, setup_limit));
    }

    let global_auth = match (state.args.socks_user.clone(), state.args.socks_pass.clone()) {
        (Some(u), Some(p)) => Some((u, p)),
        _ => None,
    };

    // IMPORTANT: in transparent mode the target is usually an IP (SO_ORIGINAL_DST).
    // If we managed to sniff a domain (HTTP Host / CONNECT / TLS SNI), prefer sending it
    // to the upstream SOCKS5 as a DOMAIN address to get "socks5h"-like remote DNS.
    let taddr = match (target, domain_hint) {
        (stats::Target::SockAddr(sa), Some(h)) if !h.is_empty() && !looks_like_ip(h) => {
            socks5::TargetAddr::Domain(h.to_string(), sa.port())
        }
        _ => target.to_socks_target().await?,
    };

    // Try multiple GREEN backends before giving up.
    let max_tries = state.backends.lock().len().max(1);
    let mut tried = std::collections::HashSet::<SocketAddr>::new();
    let inflight_limit = per_backend_connect_limit(&state);

    for _ in 0..max_tries {
        let (backend_idx, backend, auth) = {
            let mut b = state.backends.lock();
            b.select_rr_with_auth(global_auth.as_ref()).context("no GREEN SOCKS5 backends")?
        };

        if !tried.insert(backend) {
            continue;
        }

        let acquired = {
            let mut b = state.backends.lock();
            b.try_acquire_connect_slot(backend_idx, inflight_limit)
        };
        if !acquired {
            continue;
        }

        state.conns.set_mode(cid, "socks_connecting");
        state.conns.set_backend(cid, Some(backend));

        let attempt = socks5::connect_via_socks5(backend, taddr.clone(), auth, timeout).await;
        {
            let mut b = state.backends.lock();
            b.release_connect_slot(backend_idx);
        }

        match attempt {
            Ok(s) => {
                state.stats.inc_socks_ok();
                state.backends.lock().note_backend_selected(backend);
                return Ok((s, backend));
            }
            Err(e) => {
                state.stats.inc_socks_fail();
                let err_text = format!("{:#}", e);
                let mut b = state.backends.lock();
                if let Some(inflight) = b.inflight_connects(backend) {
                    let prefix = if is_soft_backend_failure(&err_text) { "soft runtime failure" } else { "runtime failure" };
                    b.mark_backend_failed(backend, format!("{}: {} (inflight={})", prefix, err_text, inflight));
                } else {
                    b.mark_backend_failed(backend, err_text);
                }
                drop(b);
                state.runtime.wake();
                state.conns.set_mode(cid, "pending");
                // try next backend
            }
        }
    }

    Err(anyhow::anyhow!("all GREEN backends failed"))
}


