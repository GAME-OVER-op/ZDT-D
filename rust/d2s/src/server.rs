use crate::{
    backend::BackendPool,
    config::Config,
    relay::{relay_bidirectional, RelayEndpoint, RelayTermination},
    router::Router,
    socks5::{read_client_request, send_failure, send_success},
    status::{status_writer, RuntimeStats},
};
use anyhow::{Context, Result};
use std::{net::SocketAddr, sync::{Arc, atomic::Ordering}, time::Duration};
use tokio::{
    net::{TcpListener, TcpStream},
    sync::{watch, Semaphore},
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
    let health_pool = pool.clone();
    let health_shutdown = shutdown.clone();
    let health_task = tokio::spawn(async move {
        health_loop(health_pool, health_shutdown).await
    });

    // Keep status lifetime separate from the external shutdown signal. The
    // final `running=false` snapshot is written only after client tasks have
    // drained/been aborted, so active connection diagnostics cannot be stale.
    let (status_shutdown_tx, status_shutdown_rx) = watch::channel(false);
    let status_task = tokio::spawn(status_writer(
        config.clone(),
        pool.clone(),
        stats.clone(),
        status_shutdown_rx,
    ));

    info!(listen = %config.listen, "D2S SOCKS5 listener is ready");

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
                        let permit = match semaphore.clone().try_acquire_owned() {
                            Ok(permit) => permit,
                            Err(_) => {
                                stats.connection_limit_drops.fetch_add(1, Ordering::Relaxed);
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
                            let _active = stats.begin_connection();
                            let result = handle_client(stream, peer, config, router, stats.clone()).await;
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
    let _ = status_shutdown_tx.send(true);
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

    let routed = match router.connect(&target).await {
        Ok(routed) => routed,
        Err(error) => {
            let _ = tokio::time::timeout(
                config.client_handshake_timeout(),
                send_failure(&mut client, 0x04),
            )
            .await;
            return Err(error);
        }
    };

    debug!(%peer, %target, route = ?routed.route, backend = ?routed.backend, "D2S route established");
    tokio::time::timeout(config.client_handshake_timeout(), send_success(&mut client))
        .await
        .map_err(|_| anyhow::anyhow!("SOCKS5 success reply to {peer} timed out"))??;

    let report = relay_bidirectional(
        client,
        routed.stream,
        config.relay_first_response_timeout(),
        config.relay_half_close_timeout(),
    )
    .await;

    stats
        .client_to_remote_bytes
        .fetch_add(report.client_to_remote, Ordering::Relaxed);
    stats
        .remote_to_client_bytes
        .fetch_add(report.remote_to_client, Ordering::Relaxed);
    if report.client_eof {
        stats.relay_client_eof.fetch_add(1, Ordering::Relaxed);
    }
    if report.remote_eof {
        stats.relay_remote_eof.fetch_add(1, Ordering::Relaxed);
    }

    let no_downstream_after_request =
        report.client_to_remote > 0 && report.remote_to_client == 0;

    match report.termination {
        RelayTermination::Clean => {
            if no_downstream_after_request {
                let message = format!(
                    "relay closed after {} upstream bytes with zero downstream bytes for {target}",
                    report.client_to_remote
                );
                router
                    .report_relay_failure(routed.route, routed.backend, &message)
                    .await;
            } else {
                router.report_relay_success(
                    routed.route,
                    routed.backend,
                    report.remote_to_client,
                );
            }
            Ok(())
        }
        RelayTermination::FirstResponseTimeout => {
            stats.relay_stalled.fetch_add(1, Ordering::Relaxed);
            stats.relay_forced_closes.fetch_add(1, Ordering::Relaxed);
            let message = format!(
                "relay data-plane stalled for {target}: {} upstream bytes, zero downstream bytes within {} ms",
                report.client_to_remote,
                config.relay_first_response_timeout().as_millis()
            );
            router
                .report_relay_failure(routed.route, routed.backend, &message)
                .await;
            Err(anyhow::anyhow!(message))
        }
        RelayTermination::HalfCloseTimeout { first_closed } => {
            stats
                .relay_half_close_timeouts
                .fetch_add(1, Ordering::Relaxed);
            stats.relay_forced_closes.fetch_add(1, Ordering::Relaxed);

            // A half-closed TCP connection with no request payload is usually a
            // cancelled/empty client and is not evidence against the backend.
            // If DNSCrypt did send a request and received nothing, however, the
            // remote data-plane is suspect and should receive the existing Full
            // recheck without changing the backend selection policy directly.
            if no_downstream_after_request {
                let message = format!(
                    "relay half-close drain timed out for {target} after {:?}: {} upstream bytes, zero downstream bytes",
                    first_closed,
                    report.client_to_remote
                );
                router
                    .report_relay_failure(routed.route, routed.backend, &message)
                    .await;
                return Err(anyhow::anyhow!(message));
            }

            router.report_relay_success(
                routed.route,
                routed.backend,
                report.remote_to_client,
            );
            debug!(
                %peer,
                %target,
                ?first_closed,
                client_to_remote = report.client_to_remote,
                remote_to_client = report.remote_to_client,
                "forced close of lingering half-closed relay"
            );
            Ok(())
        }
        RelayTermination::IoError {
            endpoint,
            operation,
            error,
        } => {
            match endpoint {
                RelayEndpoint::Client => {
                    stats
                        .relay_client_io_errors
                        .fetch_add(1, Ordering::Relaxed);
                    // Client-side resets/cancellations are not backend failures.
                    // Preserve any positive DIRECT evidence but do not trigger a
                    // backend Full probe solely because DNSCrypt went away.
                    router.report_relay_success(
                        routed.route,
                        routed.backend,
                        report.remote_to_client,
                    );
                    debug!(
                        %peer,
                        %target,
                        ?operation,
                        %error,
                        "client side of relay closed with I/O error"
                    );
                    Ok(())
                }
                RelayEndpoint::Remote => {
                    stats
                        .relay_remote_io_errors
                        .fetch_add(1, Ordering::Relaxed);
                    let message = format!(
                        "remote relay {operation:?} error for {target}: {error}"
                    );
                    router
                        .report_relay_failure(routed.route, routed.backend, &message)
                        .await;
                    Err(anyhow::Error::new(error).context(message))
                }
            }
        }
    }
}

async fn health_loop(
    pool: BackendPool,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut ticker = tokio::time::interval(Duration::from_secs(1));
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    // Do not suspend backend health checks when DNS traffic is idle. Sleeping
    // here made the last GREEN/latency snapshot stale, so the first DNS request
    // after a long quiet period could spend the whole backend timeout on a route
    // that had disappeared while D2S was asleep. The normal scheduler is already
    // inexpensive for healthy routes: Light checks use the configured healthy
    // interval, while strict Full Internet verification stays on its long cadence.
    loop {
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
