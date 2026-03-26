use crate::{stats, system, AppState};
use anyhow::{Context, Result};
use axum::{
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{hash::{Hash, Hasher}, net::SocketAddr, time::Duration};
use tokio::time::Instant;

const INDEX_HTML: &str = include_str!("web_ui.html");
const BUILD_TAG: &str = "tcp-only-green-only-smart-energy-v2";

#[derive(Debug, Deserialize)]
struct DownloadLimitReq {
    /// 0 = unlimited
    mbit: f64,
}

#[derive(Debug, Deserialize)]
struct BackendReq {
    host: String,
    port: u16,
}

#[derive(Debug, Deserialize)]
struct KillReq {
    cid: String,
}

#[derive(Clone, Debug, Serialize)]
struct ConnView {
    cid: String,
    ingress: String,
    domain: String,
    peer: String,
    dst_ip: String,
    mode: String,
    bytes_up: u64,
    bytes_down: u64,
    server: String,
}

#[derive(Clone, Debug, Serialize)]
struct PortView {
    label: String,
    listen: String,
    active_connections: usize,
    bytes_up: u64,
    bytes_down: u64,
}

#[derive(Clone, Debug, Serialize)]
struct PortsView {
    internal: PortView,
    external: Option<PortView>,
}

#[derive(Clone, Debug, Serialize)]
struct ApiState {
    ts: u64,
    stats: stats::StatsSnapshot,
    active_connections: usize,
    ports: PortsView,
    conns: Vec<ConnView>,
    backends: Vec<stats::BackendStatus>,
    system: system::SystemStats,
    download_limit_mbit: f64,
}

pub async fn serve(state: AppState) -> Result<()> {
    let app = Router::new()
        .route("/", get(index))
        .route("/ws", get(ws_upgrade))
        .route("/api/version", get(api_version))
        .route("/api/state", get(api_state))
        .route("/api/download_limit", post(api_download_limit))
        .route("/api/backends/add", post(api_backend_add))
        .route("/api/backends/remove", post(api_backend_remove))
        .route("/api/kill", post(api_kill_post).get(api_kill_get))
        .with_state(state.clone());

    let addr: SocketAddr = format!("{}:{}", state.args.web_addr, state.args.web_port)
        .parse()
        .context("web addr parse")?;
    tracing::info!("Web UI listening on http://{}", addr);

    axum::serve(
        tokio::net::TcpListener::bind(addr).await.context("bind web")?,
        app,
    )
    .await
    .context("web serve")?;

    Ok(())
}

async fn index() -> impl IntoResponse {
    // Single-page UI. All data comes from /ws or /api/state.
    let mut headers = HeaderMap::new();
    headers.insert(axum::http::header::CONTENT_TYPE, "text/html; charset=utf-8".parse().unwrap());
    // Avoid browser caching across rebuilds (HTML is embedded into the binary).
    headers.insert(axum::http::header::CACHE_CONTROL, "no-store, max-age=0".parse().unwrap());
    headers.insert(axum::http::header::PRAGMA, "no-cache".parse().unwrap());
    (StatusCode::OK, headers, Html(INDEX_HTML))
}

async fn api_version() -> impl IntoResponse {
    (StatusCode::OK, Json(serde_json::json!({"build": BUILD_TAG})))
}

async fn api_state(State(state): State<AppState>) -> impl IntoResponse {
    Json(build_state(&state))
}

async fn api_download_limit(State(state): State<AppState>, Json(req): Json<DownloadLimitReq>) -> impl IntoResponse {
    let mbit = if req.mbit.is_finite() && req.mbit > 0.0 { req.mbit } else { 0.0 };
    let bps = if mbit > 0.0 { (mbit * 1024.0 * 1024.0 / 8.0) as u64 } else { 0u64 };
    state.runtime.download_limit_bps.store(bps, std::sync::atomic::Ordering::Relaxed);
    (StatusCode::OK, Json(serde_json::json!({"result":"ok","mbit":mbit})))
}

async fn api_backend_add(State(state): State<AppState>, Json(req): Json<BackendReq>) -> impl IntoResponse {
    let addr_str = format!("{}:{}", req.host, req.port);
    match addr_str.parse::<SocketAddr>() {
        Ok(sa) => {
            let mut b = state.backends.lock();
            // avoid duplicates
            if b.snapshot().iter().any(|s| s.addr == sa.to_string()) {
                return (StatusCode::OK, Json(serde_json::json!({"result":"ok","note":"already exists"})));
            }
            b.add(sa);
            (StatusCode::OK, Json(serde_json::json!({"result":"ok"})))
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error":"invalid host:port"}))),
    }
}

async fn api_backend_remove(State(state): State<AppState>, Json(req): Json<BackendReq>) -> impl IntoResponse {
    let addr_str = format!("{}:{}", req.host, req.port);
    match addr_str.parse::<SocketAddr>() {
        Ok(sa) => {
            state.backends.lock().remove(sa);
            (StatusCode::OK, Json(serde_json::json!({"result":"ok"})))
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error":"invalid host:port"}))),
    }
}

async fn api_kill_get(State(state): State<AppState>, axum::extract::Query(q): axum::extract::Query<KillReq>) -> impl IntoResponse {
    let cid = match parse_cid(&q.cid) {
        Some(v) => v,
        None => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error":"invalid cid"}))),
    };
    let ok = state.conns.kill(cid);
    if ok {
        (StatusCode::OK, Json(serde_json::json!({"result":"ok"})))
    } else {
        (StatusCode::NOT_FOUND, Json(serde_json::json!({"error":"unknown cid"})))
    }
}

async fn api_kill_post(State(state): State<AppState>, Json(req): Json<KillReq>) -> impl IntoResponse {
    let cid = match parse_cid(&req.cid) {
        Some(v) => v,
        None => return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error":"invalid cid"}))),
    };
    let ok = state.conns.kill(cid);
    if ok {
        (StatusCode::OK, Json(serde_json::json!({"result":"ok"})))
    } else {
        (StatusCode::NOT_FOUND, Json(serde_json::json!({"error":"unknown cid"})))
    }
}

fn parse_cid(s: &str) -> Option<u64> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    if let Some(hex) = t.strip_prefix("0x") {
        return u64::from_str_radix(hex, 16).ok();
    }
    t.parse::<u64>().ok()
}

fn state_fingerprint(st: &ApiState) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    st.active_connections.hash(&mut hasher);
    st.stats.bytes_up.hash(&mut hasher);
    st.stats.bytes_down.hash(&mut hasher);
    st.stats.errors.hash(&mut hasher);
    st.stats.socks_ok.hash(&mut hasher);
    st.stats.socks_fail.hash(&mut hasher);
    st.stats.policy_drop.hash(&mut hasher);
    st.stats.internal.bytes_up.hash(&mut hasher);
    st.stats.internal.bytes_down.hash(&mut hasher);
    st.stats.external.bytes_up.hash(&mut hasher);
    st.stats.external.bytes_down.hash(&mut hasher);
    st.system.cpu_percent.to_bits().hash(&mut hasher);
    st.system.mem_total_kb.hash(&mut hasher);
    st.system.mem_avail_kb.hash(&mut hasher);
    st.system.mem_used_percent.to_bits().hash(&mut hasher);
    st.system.proc_rss_kb.hash(&mut hasher);
    st.system.net_rx_bytes.hash(&mut hasher);
    st.system.net_tx_bytes.hash(&mut hasher);
    for b in &st.backends {
        b.addr.hash(&mut hasher);
        (b.state as u8).hash(&mut hasher);
        b.healthy.hash(&mut hasher);
        b.last_error.hash(&mut hasher);
        b.socks_ping_ms.map(f64::to_bits).hash(&mut hasher);
        b.internet_ping_ms.map(f64::to_bits).hash(&mut hasher);
        b.rtt_integrity.map(f64::to_bits).hash(&mut hasher);
        b.ttl_min.hash(&mut hasher);
        b.ttl_max.hash(&mut hasher);
        b.total_bytes.hash(&mut hasher);
    }
    for c in &st.conns {
        c.cid.hash(&mut hasher);
        c.ingress.hash(&mut hasher);
        c.domain.hash(&mut hasher);
        c.peer.hash(&mut hasher);
        c.dst_ip.hash(&mut hasher);
        c.mode.hash(&mut hasher);
        c.bytes_up.hash(&mut hasher);
        c.bytes_down.hash(&mut hasher);
        c.server.hash(&mut hasher);
    }
    hasher.finish()
}

async fn ws_upgrade(ws: WebSocketUpgrade, State(state): State<AppState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| ws_handle(socket, state))
}

async fn ws_handle(mut socket: WebSocket, state: AppState) {
    // Track UI clients for "sleep"/idle optimizations.
    struct UiGuard {
        rt: std::sync::Arc<stats::RuntimeConfig>,
    }
    impl Drop for UiGuard {
        fn drop(&mut self) {
            self.rt.ui_clients.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
            self.rt.wake();
        }
    }
    state.runtime.ui_clients.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    state.runtime.wake();
    let _guard = UiGuard { rt: state.runtime.clone() };

    // Event-driven updates with slow polling for backend/system changes and a rare keepalive.
    let mut poll_tick = tokio::time::interval(Duration::from_secs(15));
    let mut keepalive_tick = tokio::time::interval(Duration::from_secs(30));
    let mut events_rx = state.events.subscribe();
    let mut last_sent = Instant::now() - Duration::from_secs(31);
    let mut last_fp: Option<u64> = None;

    let initial = build_state(&state);
    let initial_fp = state_fingerprint(&initial);
    if let Ok(txt) = serde_json::to_string(&initial) {
        if socket.send(Message::Text(txt)).await.is_err() { return; }
    }
    last_sent = Instant::now();
    last_fp = Some(initial_fp);

    loop {
        tokio::select! {
            _ = poll_tick.tick() => {
                let st = build_state(&state);
                let fp = state_fingerprint(&st);
                let changed = last_fp.map(|prev| prev != fp).unwrap_or(true);
                if changed || last_sent.elapsed() >= Duration::from_secs(30) {
                    if let Ok(txt) = serde_json::to_string(&st) {
                        if socket.send(Message::Text(txt)).await.is_err() { break; }
                    }
                    last_sent = Instant::now();
                    last_fp = Some(fp);
                }
            }
            _ = keepalive_tick.tick() => {
                if last_sent.elapsed() >= Duration::from_secs(30) {
                    let st = build_state(&state);
                    let fp = state_fingerprint(&st);
                    if let Ok(txt) = serde_json::to_string(&st) {
                        if socket.send(Message::Text(txt)).await.is_err() { break; }
                    }
                    last_sent = Instant::now();
                    last_fp = Some(fp);
                }
            }
            evt = events_rx.recv() => {
                match evt {
                    Ok(_evt) => {
                        if last_sent.elapsed() < Duration::from_millis(300) { continue; }
                        let st = build_state(&state);
                        let fp = state_fingerprint(&st);
                        let changed = last_fp.map(|prev| prev != fp).unwrap_or(true);
                        if changed {
                            if let Ok(txt) = serde_json::to_string(&st) {
                                if socket.send(Message::Text(txt)).await.is_err() { break; }
                            }
                            last_sent = Instant::now();
                            last_fp = Some(fp);
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
            msg = socket.recv() => {
                match msg {
                    Some(Ok(Message::Text(_t))) => {
                        // reserved for future commands over WS
                    }
                    Some(Ok(Message::Ping(v))) => {
                        let _ = socket.send(Message::Pong(v)).await;
                    }
                    Some(Ok(Message::Close(_))) | None => break,
                    _ => {}
                }
            }
        }
    }
}

fn build_state(state: &AppState) -> ApiState {
    let stats = state.stats.snapshot();
    let conns = state.conns.list();
    let backends = state.backends.lock().snapshot();

    let mut int_active = 0usize;
    let mut ext_active = 0usize;
    for c in conns.iter() {
        match c.ingress.as_str() {
            "external" => ext_active += 1,
            _ => int_active += 1,
        }
    }

    let ports = PortsView {
        internal: PortView {
            label: "internal".into(),
            listen: format!("{}:{}", state.args.listen_addr, state.args.listen_port),
            active_connections: int_active,
            bytes_up: stats.internal.bytes_up,
            bytes_down: stats.internal.bytes_down,
        },
        external: if state.args.external_port != 0 {
            Some(PortView {
                label: "external".into(),
                listen: format!("0.0.0.0:{}", state.args.external_port),
                active_connections: ext_active,
                bytes_up: stats.external.bytes_up,
                bytes_down: stats.external.bytes_down,
            })
        } else {
            None
        },
    };
    let conn_views: Vec<ConnView> = conns
        .into_iter()
        .map(|c| {
            let domain = c.domain.clone().filter(|d| !d.is_empty()).unwrap_or_else(|| "Domain not resolved".to_string());
            let dst_ip = c.dst_ip.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "—".to_string());

            let mode = match c.mode.as_deref() {
                _ => {
                    if c.backend.is_some() { "socks5" } else { "transparent" }
                }
            };

            let server = if let Some(be) = &c.backend {
                if let Some(pos) = backends.iter().position(|b| b.addr == *be) {
                    format!("#{}", pos + 1)
                } else {
                    // backend list might have changed; still keep a compact marker
                    "#?".to_string()
                }
            } else {
                "—".to_string()
            };

            ConnView {
                cid: c.cid.to_string(),
                ingress: c.ingress,
                domain,
                peer: c.peer,
                dst_ip,
                mode: mode.to_string(),
                bytes_up: c.bytes_up,
                bytes_down: c.bytes_down,
                server,
            }
        })
        .collect();
    let system = state.system.lock().clone();
    let bps = state.runtime.download_limit_bps.load(std::sync::atomic::Ordering::Relaxed);
    let download_limit_mbit = if bps > 0 { (bps as f64) * 8.0 / (1024.0 * 1024.0) } else { 0.0 };

    ApiState {
        ts: stats::now_ts(),
        stats,
        active_connections: conn_views.len(),
        ports,
        conns: conn_views,
        backends,
        system,
        download_limit_mbit,
    }
}
