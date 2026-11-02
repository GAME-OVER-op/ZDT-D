use serde::{Deserialize, Serialize};
use crate::common::config::deserialize_boolish;
use std::collections::BTreeMap;


#[derive(Debug, Deserialize, Serialize, Default)]
pub(crate) struct ProfileState {
    // Legacy active.json files stored 0/1 here. Serialization intentionally
    // remains a plain bool so a rewritten file converges to true/false.
    #[serde(default, deserialize_with = "deserialize_boolish")]
    pub(crate) enabled: bool,
}

#[derive(Debug, Deserialize, Serialize, Default)]
pub(crate) struct ProfilesActive {
    pub(crate) profiles: BTreeMap<String, ProfileState>,
}

#[derive(Debug, Deserialize, Serialize, Default)]
pub(crate) struct EnabledActive {
    #[serde(default, deserialize_with = "deserialize_boolish")]
    pub(crate) enabled: bool,
}
