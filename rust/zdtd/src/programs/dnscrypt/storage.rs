use anyhow::{Result};
use serde::Deserialize;
use std::{path::Path};




pub(super) fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    crate::common::fs::json::read_json(path)
}
