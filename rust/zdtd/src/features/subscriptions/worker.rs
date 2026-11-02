use anyhow::{bail, Context, Result};
use serde_yaml::{Mapping as YamlMapping, Value as YamlValue};
use std::{collections::{BTreeSet}, fs, path::{Path}, sync::{atomic::{Ordering}}, thread, time::{Duration}};

use super::model::*;
use super::storage::*;
use super::crud::*;
use super::fetch::*;
use super::provider::*;

pub fn enqueue_refresh(id: &str) -> Result<bool> {
    ensure_id(id)?;
    let item = get(id)?;
    if !item.enabled { bail!("subscription is disabled"); }
    {
        let mut set = refreshing_set().lock().map_err(|_| anyhow::anyhow!("subscription refresh lock poisoned"))?;
        if !set.insert(id.to_string()) { return Ok(false); }
    }
    let id_owned = id.to_string();
    let started_modified_at = item.modified_at;
    thread::spawn(move || {
        if let Err(e) = refresh(&id_owned) {
            log::warn!("subscription refresh failed id={}: {e:#}", id_owned);
        }
        if let Ok(mut set) = refreshing_set().lock() { set.remove(&id_owned); }

        // If the subscription was edited/re-enabled while this request was in flight,
        // the enqueue attempt made by update() was intentionally deduplicated. Queue
        // one fresh request now, after the old worker has released the per-ID slot.
        let needs_follow_up = get(&id_owned)
            .map(|current| current.enabled && current.modified_at != started_modified_at)
            .unwrap_or(false);
        if needs_follow_up {
            let _ = enqueue_refresh(&id_owned);
        }
    });
    Ok(true)
}

pub fn enqueue_refresh_all() -> Result<usize> {
    let store = read_store()?;
    let mut queued = 0usize;
    for item in store.subscriptions.values().filter(|s| s.enabled) {
        if enqueue_refresh(&item.id).unwrap_or(false) { queued += 1; }
    }
    Ok(queued)
}

pub fn refresh_due_all() {
    let Ok(store) = read_store() else { return; };
    let now = now_unix();
    for item in store.subscriptions.values().filter(|s| s.enabled) {
        let status = read_status(&item.id);
        let due = !provider_path(&item.id).is_file() || status.next_update_at == 0 || status.next_update_at <= now;
        if due {
            let _ = enqueue_refresh(&item.id);
        }
    }
}

pub fn start_background_worker() {
    if WORKER_STARTED.swap(true, Ordering::AcqRel) { return; }
    thread::spawn(|| loop {
        refresh_due_all();
        thread::sleep(Duration::from_secs(60));
    });
}

pub fn enabled_selected(id: &str) -> bool {
    read_store().ok().and_then(|s| s.subscriptions.get(id).cloned()).map(|s| s.enabled).unwrap_or(false)
}

pub(super) fn cleanup_profile_subscription_dir(dir: &Path, keep: &BTreeSet<String>) {
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.starts_with("sub_") && name.ends_with(".yaml") && !keep.contains(&name) {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
}

pub fn apply_selected_to_runtime_yaml(profile: &str, raw_yaml: &str, selected_ids: &[String]) -> Result<String> {
    crate::programs::mihomo::ensure_valid_profile_name(profile)?;
    let local_dir = profile_provider_dir(profile);
    fs::create_dir_all(&local_dir)?;
    if selected_ids.is_empty() {
        cleanup_profile_subscription_dir(&local_dir, &BTreeSet::new());
        return Ok(raw_yaml.to_string());
    }
    let store = read_store().unwrap_or_default();
    let mut selected = Vec::<(&Subscription, String)>::new();
    let mut seen = BTreeSet::<String>::new();
    for id in selected_ids {
        if !seen.insert(id.clone()) { continue; }
        let Some(item) = store.subscriptions.get(id) else { continue; };
        if !item.enabled { continue; }
        let path = provider_path(id);
        if !path.is_file() {
            // Keep the runtime configuration stable even if the first download is
            // still in progress (or temporarily failed). Mihomo can load an empty
            // file provider now and will see the atomically replaced file later.
            write_text_atomic(&path, "proxies: []\n")
                .with_context(|| format!("create placeholder provider for subscription {id}"))?;
            chmod_600_best_effort(&path);
        }
        sync_provider_to_profile(profile, id)
            .with_context(|| format!("sync subscription {} into Mihomo profile {} HomeDir", id, profile))?;
        selected.push((item, format!("zdt_{}", id)));
    }
    let keep: BTreeSet<String> = selected.iter().map(|(item, _)| format!("{}.yaml", item.id)).collect();
    cleanup_profile_subscription_dir(&local_dir, &keep);
    if selected.is_empty() { return Ok(raw_yaml.to_string()); }

    let mut root: YamlValue = serde_yaml::from_str(raw_yaml).context("parse mihomo runtime YAML for subscriptions")?;
    let root_map = root.as_mapping_mut().ok_or_else(|| anyhow::anyhow!("mihomo config root must be a YAML mapping"))?;

    let providers_key = YamlValue::String("proxy-providers".to_string());
    if !root_map.contains_key(&providers_key) {
        root_map.insert(providers_key.clone(), YamlValue::Mapping(YamlMapping::new()));
    }
    let providers = root_map.get_mut(&providers_key).and_then(YamlValue::as_mapping_mut)
        .ok_or_else(|| anyhow::anyhow!("proxy-providers must be a YAML mapping"))?;

    let mut provider_names = Vec::<String>::new();
    for (item, base_provider_name) in &selected {
        let mut provider_name = base_provider_name.clone();
        let mut suffix = 1u32;
        while providers.contains_key(&YamlValue::String(provider_name.clone())) {
            provider_name = format!("{base_provider_name}_{suffix}");
            suffix += 1;
        }
        provider_names.push(provider_name.clone());
        let mut m = YamlMapping::new();
        m.insert(YamlValue::String("type".to_string()), YamlValue::String("file".to_string()));
        m.insert(YamlValue::String("path".to_string()), YamlValue::String(format!("./subscriptions/{}.yaml", item.id)));
        m.insert(YamlValue::String("interval".to_string()), serde_yaml::to_value(PROVIDER_RELOAD_INTERVAL_SECS)?);
        providers.insert(YamlValue::String(provider_name), YamlValue::Mapping(m));
    }

    let groups_key = YamlValue::String("proxy-groups".to_string());
    if !root_map.contains_key(&groups_key) {
        root_map.insert(groups_key.clone(), YamlValue::Sequence(Vec::new()));
    }
    let groups = root_map.get_mut(&groups_key).and_then(YamlValue::as_sequence_mut)
        .ok_or_else(|| anyhow::anyhow!("proxy-groups must be a YAML sequence"))?;

    let mut found_proxy = false;
    for group in groups.iter_mut() {
        let Some(map) = group.as_mapping_mut() else { continue; };
        let name_key = YamlValue::String("name".to_string());
        let name = map.get(&name_key).and_then(YamlValue::as_str).unwrap_or("");
        if name != "Proxy" { continue; }
        found_proxy = true;
        let use_key = YamlValue::String("use".to_string());
        if !map.contains_key(&use_key) { map.insert(use_key.clone(), YamlValue::Sequence(Vec::new())); }
        let use_seq = map.get_mut(&use_key).and_then(YamlValue::as_sequence_mut)
            .ok_or_else(|| anyhow::anyhow!("Proxy group use must be a YAML sequence"))?;
        let existing: BTreeSet<String> = use_seq.iter().filter_map(YamlValue::as_str).map(str::to_string).collect();
        for name in &provider_names {
            if !existing.contains(name) { use_seq.push(YamlValue::String(name.clone())); }
        }
        break;
    }
    if !found_proxy {
        let mut group = YamlMapping::new();
        group.insert(YamlValue::String("name".to_string()), YamlValue::String("Proxy".to_string()));
        group.insert(YamlValue::String("type".to_string()), YamlValue::String("select".to_string()));
        // A provider-only select group is valid and avoids inventing a proxy name
        // (such as DIRECT-OUT) that a custom user config may not define.
        group.insert(YamlValue::String("use".to_string()), YamlValue::Sequence(provider_names.iter().cloned().map(YamlValue::String).collect()));
        groups.push(YamlValue::Mapping(group));
    }

    let out = serde_yaml::to_string(&root).context("serialize Mihomo runtime YAML with subscriptions")?;
    Ok(out)
}
