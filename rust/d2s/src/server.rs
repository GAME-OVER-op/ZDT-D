use crate::{
    backend::BackendPool,
    config::Config,
    router::Router,
    socks5::{read_client_request, send_failure, send_success},
    status::{status_writer, RuntimeStats},
};
use anyhow::{Context, Result};
use std::{
    net::SocketAddr,
    sync::{
        atomic::Ordering,
        Arc, Mutex as StdMutex,
    },
    time::{Duration, Instant},
};
use tokio::{
    io::copy_bidirectional,
    net::{TcpListener, TcpStream},
    sync::{watch, Notify, Semaphore},
    task::{JoinHandle, JoinSet},
};
use tracing::{debug, error, info, warn};

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

struct ActivityTracker {
    last_client: StdMutex<Instant>,
    notify: Notify,
}

impl ActivityTracker {
    fn new(_idle_after: Option<Duration>) -> Self {
        let now = Instant::now();
        let last_client = now;
        Self {
            last_client: StdMutex::new(last_client),
            notify: Notify::new(),
        }
    }

    fn touch(&self) {
        *self
            .last_client
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Instant::now();
        // A new DNSCrypt connection must wake the idle health scheduler now,
        // not only after that client connection eventually closes.
        self.notify.notify_one();
    }

    fn wake(&self) {
        self.notify.notify_one();
    }

    fn last_client(&self) -> Instant {
        *self
            .last_client
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

pub async fn start(mut config: Config) -> Result<RunningServer> {
    config.validate()?;
    let requested_listen = config.listen;
    let listener = TcpListener::bind(requested_listen)
        .await
        .with_context(|| format!("bind D2S listener {requested_listen}"))?;
    let listen_addr = listener
        .local_addr()
        .context("read D2S listener address")?;
    config.listen = listen_addr;
    let config = Arc::new(config);

    let pool = BackendPool::new(config.clone())?;
    if config.backends.is_empty() {
        info!("no SOCKS5 backends configured; D2S will use DIRECT fallback");
    } else {
        info!(
            backends = config.backends.len(),
            "SOCKS5 backends configured; health checks are traffic-aware"
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

    Ok(RunningServer {
        listen_addr,
        pool,
        stats,
        shutdown_tx,
        task,
    })
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
    let activity = Arc::new(ActivityTracker::new(config.idle_after()));
    let mut clients = JoinSet::new();

    let health_task = tokio::spawn(health_loop(
        config.clone(),
        pool.clone(),
        stats.clone(),
        activity.clone(),
        shutdown.clone(),
    ));

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
                        stats.accepted_connections.fetch_add(1, Ordering::Relaxed);
                        activity.touch();
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
                        let activity = activity.clone();
                        clients.spawn(async move {
                            let _permit = permit;
                            stats.active_connections.fetch_add(1, Ordering::Relaxed);
                            let result = handle_client(stream, peer, config, router, stats.clone()).await;
                            stats.active_connections.fetch_sub(1, Ordering::Relaxed);
                            activity.wake();
                            match result {
                                Ok(()) => {
                                    stats.completed_connections.fetch_add(1, Ordering::Relaxed);
                                }
                                Err(error) => {
                                    stats.failed_connections.fetch_add(1, Ordering::Relaxed);
                                    debug!(%peer, %error, "D2S client connection ended with an error");
                                }
                            }
                        });
                    }
                    Err(error) => {
                        error!(%error, "D2S accept failed");
                        tokio::time::sleep(Duration::from_millis(100)).await;
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
    let (client_to_remote, remote_to_client) = copy_bidirectional(&mut client, &mut routed.stream)
        .await
        .with_context(|| format!("relay traffic for {target}"))?;
    stats
        .client_to_remote_bytes
        .fetch_add(client_to_remote, Ordering::Relaxed);
    stats
        .remote_to_client_bytes
        .fetch_add(remote_to_client, Ordering::Relaxed);
    Ok(())
}

/// Traffic-aware health scheduler. While D2S is active it keeps the proven
/// one-second scheduler with MissedTickBehavior::Skip. After the configured
/// idle period and with no active clients it sleeps on Notify with no polling.
/// A newly accepted DNSCrypt connection wakes it immediately; routing itself
/// never waits for a health probe.
async fn health_loop(
    config: Arc<Config>,
    pool: BackendPool,
    stats: Arc<RuntimeStats>,
    activity: Arc<ActivityTracker>,
    mut shutdown: watch::Receiver<bool>,
) {
    if config.backends.is_empty() {
        if !*shutdown.borrow() {
            let _ = shutdown.changed().await;
        }
        return;
    }

    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut idle_logged = false;

    loop {
        if *shutdown.borrow() {
            break;
        }

        let active = stats.active_connections.load(Ordering::Relaxed);
        let should_idle = active == 0
            && config
                .idle_after()
                .map(|idle| Instant::now() >= activity.last_client() + idle)
                .unwrap_or(false);

        if should_idle {
            if !idle_logged {
                debug!("D2S health scheduler entered idle sleep");
                idle_logged = true;
            }
            tokio::select! {
                _ = activity.notify.notified() => {
                    idle_logged = false;
                    ticker.reset();
                    continue;
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        break;
                    }
                }
            }
            continue;
        }

        idle_logged = false;
        tokio::select! {
            _ = ticker.tick() => {
                let due = pool.claim_due_backends().await;
                if !due.is_empty() {
                    pool.probe_many(due).await;
                }
            }
            _ = activity.notify.notified() => {
                // New client activity can change the idle state immediately.
            }
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
            }
        }
    }
}
