use anyhow::{Context, Result};
use serde::Deserialize;
use std::{fs, path::Path, sync::{Mutex, OnceLock}};

use super::model::ProxyRuleStrategy;

#[derive(Debug, Clone, Copy, Default)]
pub(super) struct CachedIpv4Strategies {
    pub(super) tcp: Option<ProxyRuleStrategy>,
    pub(super) udp: Option<ProxyRuleStrategy>,
}

pub(super) static IPV4_STRATEGY_CACHE: OnceLock<Mutex<CachedIpv4Strategies>> = OnceLock::new();

pub(super) fn ipv4_strategy_cache() -> &'static Mutex<CachedIpv4Strategies> {
    IPV4_STRATEGY_CACHE.get_or_init(|| Mutex::new(CachedIpv4Strategies::default()))
}

pub(super) fn load_cached_ipv4_strategies() -> CachedIpv4Strategies {
    *ipv4_strategy_cache().lock().unwrap_or_else(|e| e.into_inner())
}

pub(super) fn store_cached_ipv4_strategies(tcp: ProxyRuleStrategy, udp: ProxyRuleStrategy) {
    let mut guard = ipv4_strategy_cache().lock().unwrap_or_else(|e| e.into_inner());
    guard.tcp = Some(tcp);
    guard.udp = Some(udp);
}

pub(super) fn clear_cached_ipv4_strategies() {
    let mut guard = ipv4_strategy_cache().lock().unwrap_or_else(|e| e.into_inner());
    guard.tcp = None;
    guard.udp = None;
}

pub(super) fn read_json_file<T: for<'de> Deserialize<'de> + Default>(p: &Path) -> Result<T> {
    let raw = fs::read_to_string(p).with_context(|| format!("read {}", p.display()))?;
    let v: T = serde_json::from_str(&raw).with_context(|| format!("parse {}", p.display()))?;
    Ok(v)
}
