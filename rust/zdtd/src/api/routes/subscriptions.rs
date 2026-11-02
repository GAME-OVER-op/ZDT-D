use anyhow::Result;
use serde_json::json;
use std::net::TcpStream;

use super::super::http::{write_empty_404, write_err, write_json, write_ok};
use super::super::EnabledReq;

/// Handles the global subscription library API.
///
/// These routes deliberately live outside `/api/programs/*`: subscriptions are
/// shared by Mihomo, sing-box, Hysteria2 and WireProxy.
pub(in crate::api) fn handle_subscriptions_subroutes(stream: TcpStream, method: &str, path: &str, body: &[u8]) -> Result<()> {
    let seg: Vec<&str> = path.trim_start_matches('/').split('/').collect();

    match (method, seg.as_slice()) {
        ("GET", ["api", "subscriptions"]) => {
            match crate::features::subscriptions::list_view() {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "subscriptions"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::SubscriptionInput = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let item = crate::features::subscriptions::create(req)?;
                Ok(json!({"ok": true, "subscription": item}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "subscriptions", "refresh-all"]) => {
            match crate::features::subscriptions::enqueue_refresh_all() {
                Ok(queued) => write_json(stream, 200, json!({"ok": true, "queued": queued})),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "subscriptions", id]) => {
            match crate::features::subscriptions::full_view(id) {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "subscriptions", id]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::SubscriptionInput = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let item = crate::features::subscriptions::update(id, req)?;
                Ok(json!({"ok": true, "subscription": item}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("DELETE", ["api", "subscriptions", id]) => {
            match crate::features::subscriptions::delete(id) {
                Ok(()) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "subscriptions", id, "refresh"]) => {
            match crate::features::subscriptions::enqueue_refresh(id) {
                Ok(queued) => write_json(stream, 200, json!({"ok": true, "queued": queued})),
                Err(e) => write_err(stream, e),
            }
        }
        ("PUT", ["api", "subscriptions", id, "enabled"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: EnabledReq = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let item = crate::features::subscriptions::set_enabled(id, req.enabled)?;
                Ok(json!({"ok": true, "subscription": item}))
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "subscriptions", id, "nodes"]) => {
            match crate::features::subscriptions::nodes_view(id) {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "subscriptions", id, "nodes", "import-bulk"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::BulkImportNodesRequest = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let result = crate::features::subscriptions::import_nodes_bulk(id, req)?;
                Ok(json!({"ok": true, "result": result}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "subscriptions", id, "nodes", node_id, "import"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::ImportNodeRequest = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                let link = crate::features::subscriptions::import_node(id, node_id, req)?;
                Ok(json!({"ok": true, "link": link}))
            })();
            match res {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("GET", ["api", "subscriptions", id, "nodes", node_id, "export"]) => {
            match crate::features::subscriptions::export_node(id, node_id) {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("POST", ["api", "subscription-import", "preview"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::ManualImportPreviewRequest = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::features::subscriptions::preview_manual_import(req)
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("POST", ["api", "subscription-import"]) => {
            let res = (|| -> Result<serde_json::Value> {
                let req: crate::features::subscriptions::ManualImportRequest = serde_json::from_slice(body)
                    .map_err(|e| anyhow::anyhow!("bad JSON body: {e}"))?;
                crate::features::subscriptions::import_manual(req)
            })();
            match res { Ok(v) => write_json(stream, 200, v), Err(e) => write_err(stream, e) }
        }
        ("GET", ["api", "subscription-links"]) => {
            match crate::features::subscriptions::links_view(None, None) {
                Ok(v) => write_json(stream, 200, v),
                Err(e) => write_err(stream, e),
            }
        }
        ("DELETE", ["api", "subscription-links", link_id]) => {
            match crate::features::subscriptions::detach_link(link_id) {
                Ok(()) => write_ok(stream),
                Err(e) => write_err(stream, e),
            }
        }
        _ => write_empty_404(stream),
    }
}

