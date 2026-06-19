use crate::{stats, AppState};
use anyhow::{Context, Result};
use axum::{
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, Query, State},
    http::{HeaderMap, StatusCode},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, hash::{Hash, Hasher}, net::{SocketAddr, ToSocketAddrs}, time::Duration};
use tokio::time::Instant;

const INDEX_HTML: &str = include_str!("web_ui.html");
const BUILD_TAG: &str = "tcp-only-green-only-smart-energy-v3";

#[derive(Clone, Debug, Deserialize)]
struct DownloadLimitReq {
    /// 0 = unlimited
    mbit: f64,
}

#[derive(Clone, Debug, Deserialize)]
struct BackendReq {
    host: String,
    port: u16,
    #[serde(default)]
    username: Option<String>,
    #[serde(default)]
    password: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
struct KillReq {
    cid: String,
}

#[derive(Clone, Debug, Deserialize)]
struct LegacyAuthQuery {
    #[serde(default)]
    token: Option<String>,
    #[serde(default)]
    api_key: Option<String>,
}

fn is_loopback_web_addr(addr: &str) -> bool {
    let a = addr.trim();
    if a.eq_ignore_ascii_case("localhost") {
        return true;
    }
    match a.parse::<std::net::IpAddr>() {
        Ok(ip) => ip.is_loopback(),
        Err(_) => false,
    }
}

fn legacy_api_requires_auth(state: &AppState) -> bool {
    !is_loopback_web_addr(&state.args.web_addr)
}

fn authorize_legacy_http(state: &AppState, headers: &HeaderMap) -> bool {
    !legacy_api_requires_auth(state) || state.api.is_authorized(headers)
}

fn authorize_legacy_query(state: &AppState, q: &LegacyAuthQuery) -> bool {
    if !legacy_api_requires_auth(state) {
        return true;
    }
    let Some(expected) = state.api.token.as_deref().filter(|s| !s.is_empty()) else {
        return false;
    };
    q.token.as_deref().map(str::trim) == Some(expected)
        || q.api_key.as_deref().map(str::trim) == Some(expected)
}

fn legacy_unauthorized() -> Response {
    json_response(
        StatusCode::UNAUTHORIZED,
        serde_json::json!({"error":"authorization required for public web bind"}),
    )
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
    schema_version: u32,
    api_name: String,
    api_version: u32,
    instance: crate::api_runtime::InstanceMeta,
    ts: u64,
    stats: stats::StatsSnapshot,
    active_connections: usize,
    ports: PortsView,
    conns: Vec<ConnView>,
    backends: Vec<stats::BackendStatus>,
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
        .route("/api/v1/meta", get(api_v1_meta))
        .route("/api/v1/poll", get(api_v1_poll))
        .route("/api/v1/state", get(api_v1_state))
        .route("/api/v1/connections", get(api_v1_connections))
        .route("/api/v1/backends", get(api_v1_backends))
        .route("/api/v1/limits", get(api_v1_limits).put(api_v1_limits_put))
        .route("/api/v1/download-limit", post(api_v1_download_limit))
        .route("/api/v1/connections/kill", post(api_v1_kill_connection))
        .route("/api/v1/backends/add", post(api_v1_backend_add))
        .route("/api/v1/backends/remove", post(api_v1_backend_remove))
        .route("/api/v1/backends/recheck", post(api_v1_backends_recheck))
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

async fn api_state(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    if state.runtime.try_begin_forced_refresh(30_000) {
        let st = state.clone();
        tokio::spawn(async move {
            stats::refresh_backends_for_ui(st.clone(), stats::health_timeout(&st)).await;
        });
    }
    json_response(StatusCode::OK, serde_json::to_value(build_state(&state)).unwrap_or_else(|_| serde_json::json!({"error":"state serialization failed"})))
}

async fn api_download_limit(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<DownloadLimitReq>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    let mbit = set_download_limit(&state, &req);
    (StatusCode::OK, Json(serde_json::json!({"result":"ok","mbit":mbit}))).into_response()
}


fn normalize_backend_auth(req: &BackendReq) -> std::result::Result<Option<(String, String)>, String> {
    let username = req.username.as_ref().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let password = req.password.clone().filter(|s| !s.is_empty());
    match (username, password) {
        (None, None) => Ok(None),
        (Some(u), Some(p)) => Ok(Some((u, p))),
        _ => Err("username and password must be provided together".to_string()),
    }
}

fn resolve_backend_addr(host: &str, port: u16) -> std::result::Result<SocketAddr, String> {
    let it = (host, port)
        .to_socket_addrs()
        .map_err(|_| "invalid host:port".to_string())?;
    let mut first: Option<SocketAddr> = None;
    for sa in it {
        if first.is_none() {
            first = Some(sa);
        }
        if sa.is_ipv4() {
            return Ok(sa);
        }
    }
    first.ok_or_else(|| "invalid host:port".to_string())
}

fn empty_404() -> Response {
    (StatusCode::NOT_FOUND, [(axum::http::header::CONTENT_LENGTH, "0")], "").into_response()
}

fn json_response(status: StatusCode, body: serde_json::Value) -> Response {
    (status, Json(body)).into_response()
}

fn set_download_limit(state: &AppState, req: &DownloadLimitReq) -> f64 {
    let mbit = if req.mbit.is_finite() && req.mbit > 0.0 { req.mbit } else { 0.0 };
    let bps = if mbit > 0.0 { (mbit * 1024.0 * 1024.0 / 8.0) as u64 } else { 0u64 };
    state.runtime.download_limit_bps.store(bps, std::sync::atomic::Ordering::Relaxed);
    mbit
}

fn backend_add_impl(state: &AppState, req: &BackendReq) -> Response {
    let auth = match normalize_backend_auth(req) {
        Ok(v) => v,
        Err(e) => return json_response(StatusCode::BAD_REQUEST, serde_json::json!({"error": e})),
    };

    match resolve_backend_addr(&req.host, req.port) {
        Ok(sa) => {
            if sa.port() == state.args.listen_port {
                return json_response(StatusCode::BAD_REQUEST, serde_json::json!({"error":"t2s backend points to its own listen port"}));
            }
            let mut b = state.backends.lock();
            if b.snapshot().iter().any(|s| s.addr == sa.to_string()) {
                return json_response(StatusCode::OK, serde_json::json!({"result":"ok","note":"already exists"}));
            }
            b.add(sa, auth);
            json_response(StatusCode::OK, serde_json::json!({"result":"ok"}))
        }
        Err(e) => json_response(StatusCode::BAD_REQUEST, serde_json::json!({"error": e})),
    }
}

fn backend_remove_impl(state: &AppState, req: &BackendReq) -> Response {
    match resolve_backend_addr(&req.host, req.port) {
        Ok(sa) => {
            state.backends.lock().remove(sa);
            let killed = state.conns.kill_backend_socks_and_connecting(sa);
            json_response(StatusCode::OK, serde_json::json!({"result":"ok","killed_connections":killed}))
        }
        Err(e) => json_response(StatusCode::BAD_REQUEST, serde_json::json!({"error": e})),
    }
}

fn kill_connection_impl(state: &AppState, cid_raw: &str) -> Response {
    let cid = match parse_cid(cid_raw) {
        Some(v) => v,
        None => return json_response(StatusCode::BAD_REQUEST, serde_json::json!({"error":"invalid cid"})),
    };
    let ok = state.conns.kill(cid);
    if ok {
        json_response(StatusCode::OK, serde_json::json!({"result":"ok"}))
    } else {
        json_response(StatusCode::NOT_FOUND, serde_json::json!({"error":"unknown cid"}))
    }
}

async fn api_backend_add(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<BackendReq>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    backend_add_impl(&state, &req)
}

async fn api_backend_remove(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<BackendReq>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    backend_remove_impl(&state, &req)
}

async fn api_kill_get(headers: HeaderMap, State(state): State<AppState>, Query(q): Query<KillReq>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    kill_connection_impl(&state, &q.cid)
}

async fn api_kill_post(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<KillReq>) -> Response {
    if !authorize_legacy_http(&state, &headers) { return legacy_unauthorized(); }
    kill_connection_impl(&state, &req.cid)
}

async fn api_v1_meta(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": state.api.refreshed_instance(),
    }))
}

async fn api_v1_poll(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let collecting = state.runtime.try_begin_forced_refresh(30_000);
    if collecting {
        let st = state.clone();
        tokio::spawn(async move {
            stats::refresh_backends_for_ui(st.clone(), stats::health_timeout(&st)).await;
        });
    }
    let st = build_state(&state);
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "poll": {
            "collecting": collecting,
            "state": if collecting { "collecting" } else { "ready" },
            "too_frequent": !collecting,
            "suggested_interval_ms": 1000,
            "min_interval_ms": 750,
            "backend_refresh_interval_ms": 30000,
            "server_ts_ms": stats::now_ms()
        },
        "state": {
            "schema_version": st.schema_version,
            "api_name": st.api_name,
            "api_version": st.api_version,
            "instance": st.instance,
            "ts": st.ts,
            "stats": st.stats,
            "active_connections": st.active_connections,
            "ports": st.ports,
            "connections": st.conns,
            "backends": st.backends,
            "limits": { "download_mbit": st.download_limit_mbit },
            "runtime": {
                "max_conns": state.args.max_conns,
                "backend_mode": format!("{:?}", state.args.backend_mode).to_ascii_lowercase(),
                "priority_speed_aware": state.args.priority_speed_aware,
                "buffer_size": state.args.buffer_size,
                "idle_timeout": state.args.idle_timeout,
                "connect_timeout": state.args.connect_timeout
            }
        }
    }))
}

async fn api_v1_state(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let st = build_state(&state);
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": st.instance,
        "ts": st.ts,
        "stats": st.stats,
        "active_connections": st.active_connections,
        "ports": st.ports,
        "connections": st.conns,
        "backends": st.backends,
        "limits": { "download_mbit": st.download_limit_mbit },
        "runtime": {
            "max_conns": state.args.max_conns,
            "backend_mode": format!("{:?}", state.args.backend_mode).to_ascii_lowercase(),
            "priority_speed_aware": state.args.priority_speed_aware,
            "buffer_size": state.args.buffer_size,
            "idle_timeout": state.args.idle_timeout,
            "connect_timeout": state.args.connect_timeout,
        }
    }))
}

async fn api_v1_connections(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let st = build_state(&state);
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": st.instance,
        "connections": st.conns,
        "active_connections": st.active_connections,
    }))
}

async fn api_v1_backends(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let backends = state.backends.lock().snapshot();
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": state.api.refreshed_instance(),
        "backends": backends,
    }))
}

async fn api_v1_limits(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let bps = state.runtime.download_limit_bps.load(std::sync::atomic::Ordering::Relaxed);
    let download_mbit = if bps > 0 { (bps as f64) * 8.0 / (1024.0 * 1024.0) } else { 0.0 };
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": state.api.refreshed_instance(),
        "limits": { "download_mbit": download_mbit },
    }))
}

async fn api_v1_limits_put(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<DownloadLimitReq>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let mbit = set_download_limit(&state, &req);
    json_response(StatusCode::OK, serde_json::json!({"schema_version":1,"api_name":"t2s","api_version":1,"ok":true,"limits":{"download_mbit":mbit}}))
}

async fn api_v1_download_limit(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<DownloadLimitReq>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    let mbit = set_download_limit(&state, &req);
    json_response(StatusCode::OK, serde_json::json!({"schema_version":1,"api_name":"t2s","api_version":1,"ok":true,"limits":{"download_mbit":mbit}}))
}

async fn api_v1_kill_connection(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<KillReq>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    kill_connection_impl(&state, &req.cid)
}

async fn api_v1_backend_add(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<BackendReq>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    backend_add_impl(&state, &req)
}

async fn api_v1_backend_remove(headers: HeaderMap, State(state): State<AppState>, Json(req): Json<BackendReq>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    backend_remove_impl(&state, &req)
}

async fn api_v1_backends_recheck(headers: HeaderMap, State(state): State<AppState>) -> Response {
    if !state.api.is_authorized(&headers) { return empty_404(); }
    stats::refresh_backends_for_ui(state.clone(), stats::health_timeout(&state)).await;
    json_response(StatusCode::OK, serde_json::json!({
        "schema_version": 1,
        "api_name": "t2s",
        "api_version": 1,
        "ok": true,
        "instance": state.api.refreshed_instance(),
        "backends": state.backends.lock().snapshot(),
    }))
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

async fn ws_upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    Query(q): Query<LegacyAuthQuery>,
) -> Response {
    if !authorize_legacy_query(&state, &q) {
        return legacy_unauthorized();
    }
    ws.on_upgrade(move |socket| ws_handle(socket, state)).into_response()
}

async fn ws_handle(mut socket: WebSocket, state: AppState) {
    // Track UI clients for "sleep"/idle optimizations.
    struct UiGuard {
        rt: std::sync::Arc<stats::RuntimeConfig>,
    }
    impl Drop for UiGuard {
        fn drop(&mut self) {
            self.rt.ui_clients.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
            self.rt.ui_wake_throttled(250);
        }
    }
    state.runtime.ui_clients.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    state.runtime.ui_wake_throttled(250);
    if state.runtime.try_begin_forced_refresh(30_000) {
        let st = state.clone();
        tokio::spawn(async move {
            stats::refresh_backends_for_ui(st.clone(), stats::health_timeout(&st)).await;
        });
    }
    let _guard = UiGuard { rt: state.runtime.clone() };

    // Event-driven updates with slow polling for backend changes and a rare keepalive.
    let mut poll_tick = tokio::time::interval(Duration::from_secs(30));
    let mut keepalive_tick = tokio::time::interval(Duration::from_secs(60));
    let mut events_rx = state.events.subscribe();
    let initial = build_state(&state);
    let initial_fp = state_fingerprint(&initial);
    if let Ok(txt) = serde_json::to_string(&initial) {
        if socket.send(Message::Text(txt)).await.is_err() { return; }
    }
    let mut last_sent = Instant::now();
    let mut last_fp: Option<u64> = Some(initial_fp);

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
                if socket.send(Message::Ping(Vec::new().into())).await.is_err() { break; }
            }
            evt = events_rx.recv() => {
                match evt {
                    Ok(_evt) => {
                        if last_sent.elapsed() < Duration::from_millis(750) { continue; }
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
    let total_active = state.conns.len();
    let (int_active, ext_active) = state.conns.ingress_counts();
    let conns = state.conns.list();
    let backends = state.backends.lock().snapshot();

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
    let backend_labels: HashMap<String, String> = backends
        .iter()
        .enumerate()
        .map(|(idx, b)| (b.addr.clone(), format!("#{}", idx + 1)))
        .collect();
    let conn_views: Vec<ConnView> = conns
        .into_iter()
        .map(|c| {
            let domain = c.domain.clone().filter(|d| !d.is_empty()).unwrap_or_else(|| "Domain not resolved".to_string());
            let dst_ip = c.dst_ip.clone().filter(|s| !s.is_empty()).unwrap_or_else(|| "—".to_string());

            let mode = if c.backend.is_some() { "socks5" } else { "transparent" };

            let server = c.backend
                .as_ref()
                .and_then(|be| backend_labels.get(be).cloned())
                .unwrap_or_else(|| if c.backend.is_some() { "#?".to_string() } else { "—".to_string() });

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
    let bps = state.runtime.download_limit_bps.load(std::sync::atomic::Ordering::Relaxed);
    let download_limit_mbit = if bps > 0 { (bps as f64) * 8.0 / (1024.0 * 1024.0) } else { 0.0 };

    ApiState {
        schema_version: 1,
        api_name: "t2s".to_string(),
        api_version: 1,
        instance: state.api.refreshed_instance(),
        ts: stats::now_ts(),
        stats,
        active_connections: total_active,
        ports,
        conns: conn_views,
        backends,
        download_limit_mbit,
    }
}
