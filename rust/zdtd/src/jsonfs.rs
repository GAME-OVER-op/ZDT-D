//! Shared JSON file helpers.
//!
//! `read_json` / `write_json_pretty` were duplicated almost verbatim in ~19 files under
//! programs/ (plus api.rs and ports.rs). The copies are consolidated here to remove
//! copy-paste drift, following the same approach already used by programs/common.rs.
//!
//! Behavior is unchanged: the two reader variants keep the exact error-context strings
//! their original per-module copies produced, so log output and API error text stay
//! byte-identical. Modules with genuinely different contracts (api.rs / tgwsproxy.rs use
//! their own read_text(), ports.rs returns Value with its own messages, hysteria2.rs adds
//! no parse context, proxyinfo.rs requires T: Default) intentionally keep their own copy.

use anyhow::{Context, Result};
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::fs;
use std::path::Path;

/// Reads and parses a JSON file. Error context: "read <path>" / "parse json <path>".
pub fn read_json<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse json {}", path.display()))?;
    Ok(v)
}

/// Same as [`read_json`], but with the shorter "parse <path>" context used by the VPN and
/// proxy program modules. Kept separate on purpose: merging it into `read_json` would
/// change existing log and API error strings.
pub fn read_json_short_ctx<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let s = fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s).with_context(|| format!("parse {}", path.display()))?;
    Ok(v)
}

/// Writes pretty-printed JSON through a sibling temp file + rename.
pub fn write_json_pretty_tmp_rename<T: Serialize>(path: &Path, v: &T) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let txt = serde_json::to_string_pretty(v)?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, txt)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Interprets a JSON value as an "enabled" flag.
///
/// A real boolean wins; otherwise any non-zero integer counts as enabled, because older
/// setting files stored 0/1 instead of false/true. Strings are deliberately NOT accepted:
/// the runtime never treated "1"/"true" as enabled, and accepting them here would silently
/// change which profiles start. api.rs::deserialize_boolish is the stricter serde-level
/// counterpart and stays separate: it rejects unexpected values with an error instead of
/// falling back to "disabled".
///
/// Pass the looked-up field directly, e.g. `json_enabled(state.get("enabled"))`; a missing
/// field means disabled.
pub fn json_enabled(value: Option<&serde_json::Value>) -> bool {
    json_enabled_opt(value).unwrap_or(false)
}

/// Same interpretation as [`json_enabled`], but keeps "the field was not usable" distinct from
/// "the field said false".
///
/// Needed where the caller must react to a missing value instead of defaulting to disabled:
/// falling back to the value already stored on disk, or rejecting a request that requires the
/// field. Returns `None` for a missing field and for types that were never accepted (strings,
/// arrays, objects, null).
pub fn json_enabled_opt(value: Option<&serde_json::Value>) -> Option<bool> {
    let v = value?;
    if let Some(b) = v.as_bool() {
        return Some(b);
    }
    v.as_i64().map(|n| n != 0)
}
