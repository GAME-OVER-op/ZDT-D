use anyhow::{Context, Result};
use serde::{Serialize};
use std::{collections::{BTreeSet}, fs, path::{Path, PathBuf}, sync::{Mutex}, time::{SystemTime, UNIX_EPOCH}};

use super::model::*;
use super::parse::*;

pub(super) fn refreshing_set() -> &'static Mutex<BTreeSet<String>> {
    REFRESHING.get_or_init(|| Mutex::new(BTreeSet::new()))
}

pub(super) fn store_lock() -> &'static Mutex<()> {
    STORE_LOCK.get_or_init(|| Mutex::new(()))
}

pub(super) fn links_lock() -> &'static Mutex<()> {
    LINKS_LOCK.get_or_init(|| Mutex::new(()))
}

pub fn is_refreshing(id: &str) -> bool {
    refreshing_set().lock().map(|set| set.contains(id)).unwrap_or(false)
}

pub(super) fn now_unix() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs()
}

pub fn root_path() -> PathBuf { PathBuf::from(ROOT) }
pub fn store_path() -> PathBuf { PathBuf::from(STORE) }
pub fn data_root() -> PathBuf { PathBuf::from(DATA_ROOT) }
pub fn provider_path(id: &str) -> PathBuf { data_root().join(id).join("provider.yaml") }
pub(super) fn status_path(id: &str) -> PathBuf { data_root().join(id).join("status.json") }
pub(super) fn nodes_path(id: &str) -> PathBuf { data_root().join(id).join("nodes.json") }
pub(super) fn links_path() -> PathBuf { PathBuf::from(LINKS) }

pub fn ensure_layout() -> Result<()> {
    fs::create_dir_all(data_root())?;
    if !store_path().exists() {
        write_json_atomic(&store_path(), &SubscriptionStore::default())?;
    }
    if !links_path().exists() {
        write_json_atomic(&links_path(), &SubscriptionLinks::default())?;
    }
    chmod_600_best_effort(&store_path());
    chmod_600_best_effort(&links_path());
    Ok(())
}

pub(super) fn read_store() -> Result<SubscriptionStore> {
    ensure_layout()?;
    let raw = fs::read_to_string(store_path()).context("read subscriptions store")?;
    serde_json::from_str(&raw).context("parse subscriptions store")
}

pub(super) fn read_links() -> Result<SubscriptionLinks> {
    ensure_layout()?;
    let raw = fs::read_to_string(links_path()).context("read subscription links")?;
    serde_json::from_str(&raw).context("parse subscription links")
}

pub(super) fn write_links(links: &SubscriptionLinks) -> Result<()> {
    write_json_atomic(&links_path(), links)?;
    chmod_600_best_effort(&links_path());
    Ok(())
}

pub(super) fn read_nodes(id: &str) -> Vec<SubscriptionNode> {
    fs::read_to_string(nodes_path(id))
        .ok()
        .and_then(|raw| serde_json::from_str::<Vec<SubscriptionNode>>(&raw).ok())
        .unwrap_or_default()
        .into_iter()
        .map(normalize_stored_node)
        .collect()
}

pub(super) fn write_nodes(id: &str, nodes: &[SubscriptionNode]) -> Result<()> {
    let path = nodes_path(id);
    write_json_atomic(&path, nodes)?;
    chmod_600_best_effort(&path);
    Ok(())
}

pub(super) fn preserve_existing_node_ids(subscription_id: &str, nodes: &mut [SubscriptionNode]) {
    let existing = read_nodes(subscription_id);
    let mut used = BTreeSet::<String>::new();
    for node in nodes {
        let Some(previous) = existing.iter().find(|previous| {
            previous.protocol.eq_ignore_ascii_case(&node.protocol)
                && previous.name.eq_ignore_ascii_case(&node.name)
                && !used.contains(&previous.id)
        }) else { continue; };
        node.id = previous.id.clone();
        used.insert(previous.id.clone());
    }
}

pub(super) fn write_store(store: &SubscriptionStore) -> Result<()> {
    ensure_layout()?;
    write_json_atomic(&store_path(), store)?;
    chmod_600_best_effort(&store_path());
    Ok(())
}

pub(super) fn read_status(id: &str) -> SubscriptionStatus {
    let p = status_path(id);
    fs::read_to_string(p)
        .ok()
        .and_then(|raw| serde_json::from_str(&raw).ok())
        .unwrap_or_default()
}

pub(super) fn write_status(id: &str, status: &SubscriptionStatus) -> Result<()> {
    let dir = data_root().join(id);
    fs::create_dir_all(&dir)?;
    let p = status_path(id);
    write_json_atomic(&p, status)?;
    chmod_600_best_effort(&p);
    Ok(())
}

pub(super) fn atomic_tmp_path(path: &Path) -> PathBuf {
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
    let suffix = format!("tmp.{}.{}", std::process::id(), nanos);
    let name = path.file_name().and_then(|v| v.to_str()).unwrap_or("file");
    path.with_file_name(format!("{name}.{suffix}"))
}

pub(super) fn write_json_atomic<T: Serialize + ?Sized>(path: &Path, value: &T) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let tmp = atomic_tmp_path(path);
    let data = serde_json::to_vec_pretty(value)?;
    fs::write(&tmp, data)?;
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    Ok(())
}

pub(super) fn write_text_atomic(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() { fs::create_dir_all(parent)?; }
    let tmp = atomic_tmp_path(path);
    fs::write(&tmp, text.as_bytes())?;
    if let Err(e) = fs::rename(&tmp, path) {
        let _ = fs::remove_file(&tmp);
        return Err(e.into());
    }
    Ok(())
}

pub(super) fn chmod_600_best_effort(path: &Path) {
    let _ = std::process::Command::new("chmod").arg("0600").arg(path).status();
}

