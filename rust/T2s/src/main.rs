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

    // Background: system stats collector (/proc), optimized for Android
    {
        let st = state.clone();
        tokio::spawn(async move {
            let mut prev = None;
            let mut idle_since: Option<tokio::time::Instant> = None;
            loop {
                let (s, cpu) = system::collect(prev);
                prev = Some(cpu);
                *st.system.lock() = s;

                // "Sleep" optimization: when there are no active connections and no UI clients,
                // collect /proc stats less frequently to reduce battery usage.
                let ui = st.runtime.ui_clients.load(std::sync::atomic::Ordering::Relaxed);
                let active = st.conns.len();
                let is_idle = ui == 0 && active == 0;

                let interval = if is_idle {
                    if idle_since.is_none() {
                        idle_since = Some(tokio::time::Instant::now());
                    }
                    let idle_for = idle_since.unwrap().elapsed();
                    if idle_for < Duration::from_secs(10 * 60) {
                        Duration::from_secs(3)
                    } else {
                        Duration::from_secs(10)
                    }
                } else {
                    idle_since = None;
                    Duration::from_secs(1)
                };

                tokio::select! {
                    _ = tokio::time::sleep(interval) => {},
                    _ = st.runtime.wakeup.notified() => { idle_since = None; }
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
        let (sock, peer) = listener.accept().await.context("accept")?;
        // Wake up background loops (health checks, etc.) on new activity.
        state.runtime.wakeup.notify_waiters();
        let st = state.clone();

        tokio::spawn(async move {
            let permit = match st.semaphore.acquire().await {
                Ok(p) => p,
                Err(_) => return,
            };

            let cid = st.conns.new_conn(peer, ingress);
            let token = st.conns.set_cancel_token(cid);

            let _ = st.events.send(stats::Event::conn_open(cid, peer));

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
    let sniffed = {
        let mut buf = vec![0u8; 4096];
        let n = tokio::time::timeout(Duration::from_millis(200), client.peek(&mut buf)).await;
        match n {
            Ok(Ok(sz)) if sz > 0 => crate::sniff::sniff_host(&buf[..sz]),
            _ => None,
        }
    };
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
            // Wait a bit for backend recovery
            let ok = stats::wait_for_backend_recovery(state.clone(), Duration::from_secs(5)).await;
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
        chosen_mode = "direct";
        connect_direct(&target, state.args.connect_timeout).await?
    } else {
        match connect_socks(&target, sniff_host.as_deref(), state.clone()).await {
            Ok((s, be)) => {
                chosen_backend = Some(be);
                s
            }
            Err(e) => {
                tracing::warn!("[cid={}] socks connect failed: {:#}", cid, e);

                // No bypass while any GREEN backend exists.
                if state.backends.lock().any_green() {
                    return Err(e);
                }

                // Bypass only when no GREEN backends are available.
                chosen_mode = "direct";
                connect_direct(&target, state.args.connect_timeout).await?
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
        let mut last = Instant::now();
        loop {
            tokio::select! {
                _ = c1.cancelled() => break,
                res = cr.read(&mut buf) => {
                    let n = res?;
                    if n == 0 { break; }
                    uw.write_all(&buf[..n]).await?;
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
                    last = Instant::now();
                }
            }
            if let Some(idle_d) = idle {
                if last.elapsed() > idle_d { break; }
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
        let mut last = Instant::now();
        let mut window_start = Instant::now();
        let mut window_bytes: u64 = 0;

        loop {
            tokio::select! {
                _ = c2.cancelled() => break,
                res = ur.read(&mut buf) => {
                    let n = res?;
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
                                    tokio::time::sleep(Duration::from_secs_f64(sleep_s.min(0.5))).await;
                                }
                            }
                        }
                        if window_start.elapsed() > Duration::from_secs(1) {
                            window_start = Instant::now();
                            window_bytes = 0;
                        }
                    }

                    cw.write_all(&buf[..n]).await?;
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
                    last = Instant::now();
                }
            }
            if let Some(idle_d) = idle {
                if last.elapsed() > idle_d { break; }
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

async fn connect_socks(
    target: &stats::Target,
    domain_hint: Option<&str>,
    state: AppState,
) -> Result<(tokio::net::TcpStream, SocketAddr)> {
    let timeout = Duration::from_secs(state.args.connect_timeout as u64);

    let auth = match (state.args.socks_user.clone(), state.args.socks_pass.clone()) {
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

    for _ in 0..max_tries {
        let backend = {
            let mut b = state.backends.lock();
            b.select_rr().context("no GREEN SOCKS5 backends")?
        };

        if !tried.insert(backend) {
            continue;
        }

        match socks5::connect_via_socks5(backend, taddr.clone(), auth.clone(), timeout).await {
            Ok(s) => {
                state.stats.inc_socks_ok();
                return Ok((s, backend));
            }
            Err(e) => {
                state.stats.inc_socks_fail();
                state.backends
                    .lock()
                    .mark_backend_failed(backend, format!("{:#}", e));
                // try next backend
            }
        }
    }

    Err(anyhow::anyhow!("all GREEN backends failed"))
}


