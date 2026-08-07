use crate::{backend::BackendPool, config::Config, router::Router, socks5::{read_client_request, send_failure, send_success}, status::{status_writer, RuntimeStats}};
use anyhow::{Context, Result};
use std::{net::SocketAddr, sync::{Arc, Mutex as StdMutex, atomic::Ordering}, time::{Duration, Instant}};
use tokio::{io::copy_bidirectional, net::{TcpListener, TcpStream}, sync::{Notify, Semaphore, watch}, task::{JoinHandle, JoinSet}};
use tracing::{debug, error, info, warn};

#[derive(Clone)]
struct ActivityTracker {
    last_client: Arc<StdMutex<Instant>>,
    wake: Arc<Notify>,
}

impl ActivityTracker {
    fn new() -> Self {
        Self {
            last_client: Arc::new(StdMutex::new(Instant::now())),
            wake: Arc::new(Notify::new()),
        }
    }

    fn touch(&self) {
        if let Ok(mut last) = self.last_client.lock() {
            *last = Instant::now();
        }
        self.wake.notify_one();
    }

    fn idle_for(&self) -> Duration {
        self.last_client
            .lock()
            .map(|last| last.elapsed())
            .unwrap_or_default()
    }
}

pub struct RunningServer {
    pub listen_addr: SocketAddr,
    pub pool: BackendPool,
    pub stats: Arc<RuntimeStats>,
    shutdown_tx: watch::Sender<bool>,
    task: JoinHandle<Result<()>>,
}

impl RunningServer {
    pub async fn shutdown(self) -> Result<()> {
        let _ = self.shutdown_tx.send(true);
        self.task.await.context("D2S server task join failed")?
    }
}

pub async fn start(mut config: Config) -> Result<RunningServer> {
    config.validate()?;
    let requested_listen = config.listen;
    let listener = TcpListener::bind(requested_listen)
        .await
        .with_context(|| format!("bind D2S listener {requested_listen}"))?;
    let listen_addr = listener.local_addr().context("read D2S listener address")?;
    config.listen = listen_addr;
    let config = Arc::new(config);

    let pool = BackendPool::new(config.clone())?;
    if config.backends.is_empty() {
        info!(
            dnscrypt_timeout_ms = config.dnscrypt_timeout_ms,
            route_budget_ms = config.route_budget().as_millis() as u64,
            "no SOCKS5 backends configured; D2S will use DIRECT fallback"
        );
    } else {
        info!(
            backends = config.backends.len(),
            dnscrypt_timeout_ms = config.dnscrypt_timeout_ms,
            route_budget_ms = config.route_budget().as_millis() as u64,
            "SOCKS5 backend probes scheduled"
        );
    }

    let stats = Arc::new(RuntimeStats::default());
    let router = Router::new(config.clone(), pool.clone(), stats.clone());
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let task = tokio::spawn(run_loop(
        listener,
        config,
        pool.clone(),
        stats.clone(),
        router,
        shutdown_rx,
    ));

    Ok(RunningServer { listen_addr, pool, stats, shutdown_tx, task })
}

async fn run_loop(
    listener: TcpListener,
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    router: Router,
    mut shutdown: watch::Receiver<bool>,
) -> Result<()> {
    let semaphore = Arc::new(Semaphore::new(config.max_connections));
    let mut clients = JoinSet::new();
    let activity = ActivityTracker::new();

    let health_pool = pool.clone();
    let health_config = config.clone();
    let health_stats = stats.clone();
    let health_activity = activity.clone();
    let health_shutdown = shutdown.clone();
    let health_task = tokio::spawn(async move {
        health_loop(health_pool, health_config, health_stats, health_activity, health_shutdown).await
    });

    let status_task = tokio::spawn(status_writer(
        config.clone(),
        pool.clone(),
        stats.clone(),
        shutdown.clone(),
    ));

    info!(listen = %listener.local_addr()?, "D2S SOCKS5 listener is ready");

    loop {
        tokio::select! {
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
            }
            joined = clients.join_next(), if !clients.is_empty() => {
                if let Some(Err(error)) = joined {
                    warn!(%error, "D2S client task panicked or was cancelled");
                }
            }
            accepted = listener.accept() => {
                match accepted {
                    Ok((stream, peer)) => {
                        activity.touch();
                        stats.accepted_connections.fetch_add(1, Ordering::Relaxed);
                        let permit = match semaphore.clone().try_acquire_owned() {
                            Ok(permit) => permit,
                            Err(_) => {
                                warn!(%peer, max_connections = config.max_connections, "connection limit reached; dropping client");
                                drop(stream);
                                continue;
                            }
                        };
                        let config = config.clone();
                        let router = router.clone();
                        let stats = stats.clone();
                        clients.spawn(async move {
                            let _permit = permit;
                            stats.active_connections.fetch_add(1, Ordering::Relaxed);
                            let result = handle_client(stream, peer, config, router, stats.clone()).await;
                            stats.active_connections.fetch_sub(1, Ordering::Relaxed);
                            match result {
                                Ok(()) => { stats.completed_connections.fetch_add(1, Ordering::Relaxed); }
                                Err(error) => {
                                    stats.failed_connections.fetch_add(1, Ordering::Relaxed);
                                    debug!(%peer, %error, "D2S client connection ended with an error");
                                }
                            }
                        });
                    }
                    Err(error) => {
                        error!(%error, "D2S accept failed");
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                }
            }
        }
    }

    info!(active_clients = clients.len(), "D2S shutdown requested");
    let grace = tokio::time::sleep(config.shutdown_grace_period());
    tokio::pin!(grace);
    while !clients.is_empty() {
        tokio::select! {
            joined = clients.join_next() => {
                if let Some(Err(error)) = joined {
                    warn!(%error, "D2S client task panicked during shutdown");
                }
            }
            _ = &mut grace => {
                warn!(remaining_clients = clients.len(), "shutdown grace period expired; aborting remaining connections");
                clients.abort_all();
                while clients.join_next().await.is_some() {}
                break;
            }
        }
    }
    let _ = health_task.await;
    let _ = status_task.await;
    Ok(())
}

async fn handle_client(
    mut client: TcpStream,
    peer: SocketAddr,
    config: Arc<Config>,
    router: Router,
    stats: Arc<RuntimeStats>,
) -> Result<()> {
    let _ = client.set_nodelay(config.tcp_nodelay);
    let target = match read_client_request(&mut client, config.client_handshake_timeout()).await {
        Ok(target) => target,
        Err(error) => {
            warn!(%peer, %error, "invalid SOCKS5 client request");
            return Err(error);
        }
    };

    let mut routed = match router.connect(&target).await {
        Ok(routed) => routed,
        Err(error) => {
            send_failure(&mut client, 0x04).await.ok();
            return Err(error);
        }
    };

    debug!(%peer, %target, route = ?routed.route, backend = ?routed.backend, "D2S route established");
    send_success(&mut client).await?;
    let relay = copy_bidirectional(&mut client, &mut routed.stream).await;
    match relay {
        Ok((client_to_remote, remote_to_client)) => {
            stats.client_to_remote_bytes.fetch_add(client_to_remote, Ordering::Relaxed);
            stats.remote_to_client_bytes.fetch_add(remote_to_client, Ordering::Relaxed);

            // DNSCrypt/DoH traffic always expects a response. If the client sent
            // bytes through an established route but the remote side returned
            // nothing before the relay closed, treat this as a suspect data-plane
            // event. As in T2S, this only triggers a strict Full recheck; it does
            // not directly rewrite backend health.
            if client_to_remote > 0 && remote_to_client == 0 {
                let message = format!(
                    "relay closed after {client_to_remote} upstream bytes with zero downstream bytes for {target}"
                );
                router.report_relay_failure(routed.route, routed.backend, &message).await;
            } else {
                router.report_relay_success(routed.route, routed.backend, remote_to_client);
            }
            Ok(())
        }
        Err(error) => {
            let message = format!("relay traffic for {target}: {error}");
            router.report_relay_failure(routed.route, routed.backend, &message).await;
            Err(anyhow::Error::new(error).context(format!("relay traffic for {target}")))
        }
    }
}

async fn health_loop(
    pool: BackendPool,
    config: Arc<Config>,
    stats: Arc<RuntimeStats>,
    activity: ActivityTracker,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        if let Some(idle_after) = config.idle_after() {
            let active = stats.active_connections.load(Ordering::Relaxed);
            let has_routable_backend = config.backends.is_empty() || pool.any_green().await;
            if active == 0 && has_routable_backend && activity.idle_for() >= idle_after {
                debug!(idle_secs = idle_after.as_secs(), "health probes sleeping while D2S is idle");
                tokio::select! {
                    _ = activity.wake.notified() => {
                        debug!("health probes woke on new client activity");
                    }
                    _ = pool.wait_for_health_wake() => {
                        debug!("health probes woke for forced backend recheck");
                    }
                    changed = shutdown.changed() => {
                        if changed.is_err() || *shutdown.borrow() {
                            break;
                        }
                    }
                }
                continue;
            }
        }

        tokio::select! {
            _ = ticker.tick() => {}
            _ = pool.wait_for_health_wake() => {
                // Runtime/relay failures schedule a forced Full probe and wake
                // the loop immediately instead of waiting for the 1s ticker.
            }
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
                continue;
            }
        }

        let due = pool.due_probes().await;
        if !due.is_empty() {
            pool.probe_many(due).await;
        }
    }
}
