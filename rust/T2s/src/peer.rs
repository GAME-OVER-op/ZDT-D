//! t2s-to-t2s coordination: peer discovery and shared backend health.
//!
//! Every t2s instance already publishes fresh metadata to
//! `<api-dir>/t2s/instances/<instance_id>.json` (refreshed every 10s) and
//! serves an authenticated `/api/v1/backends` snapshot. This module turns that
//! passive discovery into active coordination for instances that forward to
//! the same backend set (different ZDT-D profiles pointed at one proxy):
//!
//! * instances sharing at least one backend address form a coordination group;
//! * the group leader is chosen deterministically as the lowest
//!   `instance_id`, so every member computes the same leader without a
//!   separate election protocol;
//! * the leader keeps running its normal health loop; followers suspend their
//!   own backend probing and instead import the leader's backend snapshot
//!   (fresher-than-local wins, so a follower's own suspect recheck result is
//!   never clobbered);
//! * followers that observe failures (relay suspects, mass-failure network
//!   signatures) delegate the recheck to the leader via
//!   `/api/v1/backends/recheck` instead of probing the shared proxy again;
//! * if the leader disappears (stale metadata or failed HTTP), followers fall
//!   back to their own probing until the picture changes — coordination is
//!   strictly an optimization and can never manufacture an outage.
//!
//! Dial serialization to a fragile shared backend is handled separately by
//! `coord.rs` and works independently of this module.

use crate::stats::BackendStatus;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use serde::Deserialize;
use std::{
    collections::HashMap,
    fs,
    net::SocketAddr,
    path::PathBuf,
    time::Duration,
};

const METADATA_FRESH_SECS: u64 = 30;
const MAX_PEERS_PER_SCAN: usize = 8;
const HTTP_TIMEOUT: Duration = Duration::from_millis(1500);
const SCAN_INTERVAL_ACTIVE: Duration = Duration::from_secs(10);
const SCAN_INTERVAL_QUIET: Duration = Duration::from_secs(30);

#[derive(Clone, Deserialize)]
struct PeerInstanceMeta {
    instance_id: String,
    pid: u32,
    updated_at: u64,
    web_addr: String,
    web_port: u16,
}

#[derive(Clone)]
struct PeerEntry {
    instance_id: String,
    web: SocketAddr,
    states: Vec<BackendStatus>,
}

#[derive(Default)]
struct PeerTable {
    /// All live peer instances known from the last successful scan.
    peers: HashMap<String, PeerEntry>,
    /// Some only while another instance is the health leader of our backend
    /// group (we are a follower and must not probe backends ourselves).
    following: Option<String>,
    /// Web endpoint of the leader while following one.
    leader_web: Option<SocketAddr>,
    /// Shared API token used for peer HTTP requests.
    token: Option<String>,
}

static PEER_TABLE: Lazy<Mutex<PeerTable>> = Lazy::new(Mutex::default);

/// True while another t2s instance is the authoritative health source for our
/// backend set. The health loop uses this to suspend its own probing; local
/// suspect rechecks triggered by runtime failures delegate to the leader
/// instead of double-probing the shared backends.
pub fn following_leader() -> bool {
    PEER_TABLE.lock().following.is_some()
}

pub fn spawn_peer_loop(state: crate::AppState) {
    if state.args.no_peer_coordination {
        return;
    }
    if state.api.token.is_none() {
        tracing::warn!("t2s peer coordination disabled: no shared API token, cannot query peers");
        return;
    }
    PEER_TABLE.lock().token = state.api.token.clone();
    tokio::spawn(async move {
        peer_loop(state).await;
    });
}

async fn peer_loop(state: crate::AppState) {
    let mut quiet_scans: u32 = 0;

    loop {
        let interval = if quiet_scans >= 3 {
            SCAN_INTERVAL_QUIET
        } else {
            SCAN_INTERVAL_ACTIVE
        };
        tokio::time::sleep(interval).await;

        sync_once(&state).await;

        quiet_scans = if PEER_TABLE.lock().peers.is_empty() {
            quiet_scans.saturating_add(1)
        } else {
            0
        };
    }
}

/// One discovery/import pass: scan instance metadata, query live peers, elect
/// the deterministic leader for our backend group and, when we are a follower,
/// import the leader's backend snapshot. Also callable on demand from the
/// recovery path so a follower reacts to failures without probing shared
/// backends itself.
pub async fn sync_once(state: &crate::AppState) {
    let instances_dir = PathBuf::from(state.args.api_dir.trim())
        .join("t2s")
        .join("instances");
    let self_id = state.api.instance.instance_id.clone();
    let now_ts = crate::stats::now_ts();
    let mut peers: HashMap<String, PeerEntry> = HashMap::new();
    // Snapshot the token once: the table lock must never be held across the
    // per-peer HTTP awaits below.
    let token = PEER_TABLE.lock().token.clone();

    for meta in scan_instance_files(&instances_dir, now_ts).await {
        let Some(web) = parse_web_addr(&meta.web_addr, meta.web_port).await else {
            continue;
        };
        let Some(value) =
            http_request_json(web, "GET", "/api/v1/backends", token.as_deref(), HTTP_TIMEOUT)
                .await
        else {
            continue;
        };
        let Some(states) = value
            .get("backends")
            .and_then(|v| serde_json::from_value::<Vec<BackendStatus>>(v.clone()).ok())
            .filter(|states| !states.is_empty())
        else {
            continue;
        };
        peers.insert(
            meta.instance_id.clone(),
            PeerEntry {
                instance_id: meta.instance_id,
                web,
                states,
            },
        );
    }

    // Peers that stopped answering were simply not re-added; the table is
    // rebuilt from scratch every scan.

    let our_backends: Vec<String> = state
        .backends
        .lock()
        .snapshot()
        .into_iter()
        .map(|b| b.addr)
        .collect();

    let mut following: Option<String> = None;
    let mut leader_web: Option<SocketAddr> = None;
    if !our_backends.is_empty() {
        // Group = live peers forwarding to at least one of our backends.
        let group: Vec<&PeerEntry> = peers
            .values()
            .filter(|peer| {
                peer.states
                    .iter()
                    .any(|state| our_backends.contains(&state.addr))
            })
            .collect();

        let leader_id = group
            .iter()
            .map(|peer| peer.instance_id.as_str())
            .chain(std::iter::once(self_id.as_str()))
            .min()
            .unwrap_or(self_id.as_str())
            .to_string();

        if leader_id != self_id {
            if let Some(leader) = group.iter().find(|peer| peer.instance_id == leader_id) {
                let changed = state
                    .backends
                    .lock()
                    .import_peer_states(leader.states.clone());
                if changed {
                    state.runtime.backend_wake_throttled(750);
                }
                leader_web = Some(leader.web);
                following = Some(leader_id);
            }
        }
    }

    let mut table = PEER_TABLE.lock();
    table.peers = peers;
    table.following = following;
    table.leader_web = leader_web;
}

/// Ask the current health leader to run a full backend recheck. Used by
/// followers that observed failures (relay suspects, network-change
/// signatures) so the shared proxy is probed exactly once — by the leader.
pub async fn request_leader_recheck() {
    let (leader_web, token) = {
        let table = PEER_TABLE.lock();
        (table.leader_web, table.token.clone())
    };
    let Some(web) = leader_web else { return };
    let _ = http_request_json(
        web,
        "POST",
        "/api/v1/backends/recheck",
        token.as_deref(),
        Duration::from_millis(2500),
    )
    .await;
}

async fn scan_instance_files(instances_dir: &PathBuf, now_ts: u64) -> Vec<PeerInstanceMeta> {
    let mut metas = Vec::new();
    let entries = match fs::read_dir(instances_dir) {
        Ok(entries) => entries,
        Err(_) => return metas,
    };
    for entry in entries.flatten() {
        if metas.len() >= MAX_PEERS_PER_SCAN {
            break;
        }
        let Ok(bytes) = fs::read(entry.path()) else {
            continue;
        };
        let Ok(meta) = serde_json::from_slice::<PeerInstanceMeta>(&bytes) else {
            continue;
        };
        if meta.pid == std::process::id() {
            continue;
        }
        if now_ts.saturating_sub(meta.updated_at) > METADATA_FRESH_SECS {
            continue;
        }
        metas.push(meta);
    }
    metas
}

async fn parse_web_addr(host: &str, port: u16) -> Option<SocketAddr> {
    if let Ok(addr) = format!("{}:{}", host.trim(), port).parse::<SocketAddr>() {
        return Some(addr);
    }
    crate::net_utils::resolve_first(host, port).await.ok()
}

/// Minimal HTTP/1.1 request for loopback JSON APIs; avoids pulling in an HTTP
/// client dependency for a fixed set of trusted peer endpoints. `method` is
/// "GET" or "POST" (POST sends an empty body, which the recheck endpoint
/// accepts).
async fn http_request_json(
    addr: SocketAddr,
    method: &str,
    path: &str,
    token: Option<&str>,
    timeout: Duration,
) -> Option<serde_json::Value> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut request = format!("{} {} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n", method, path, addr);
    if let Some(token) = token {
        request.push_str(&format!("x-api-key: {}\r\n", token));
    }
    request.push_str("Content-Length: 0\r\n\r\n");

    let buf = tokio::time::timeout(timeout, async move {
        let mut stream = tokio::net::TcpStream::connect(addr).await.ok()?;
        stream.write_all(request.as_bytes()).await.ok()?;
        let mut buf = Vec::with_capacity(8 * 1024);
        stream.read_to_end(&mut buf).await.ok()?;
        Some(buf)
    })
    .await
    .ok()??;

    let text = String::from_utf8_lossy(&buf);
    let mut parts = text.split("\r\n\r\n");
    let headers = parts.next()?;
    let body = parts.next()?;
    let status_ok = headers.lines().next()?.contains(" 200 ");
    if !status_ok {
        return None;
    }
    serde_json::from_str(body.trim()).ok()
}
