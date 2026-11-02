use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Shared on-disk state used by profile-based backends whose `active.json`
/// format is exactly `{ "profiles": { "name": { "enabled": bool } } }`.
#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ProfileState {
    #[serde(default)]
    pub enabled: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct ActiveProfiles {
    #[serde(default)]
    pub profiles: BTreeMap<String, ProfileState>,
}
