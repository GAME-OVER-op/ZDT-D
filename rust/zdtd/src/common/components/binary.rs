use std::path::{Path, PathBuf};

use anyhow::{bail, Result};

const MODULE_BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

/// Resolve one executable from the standard ZDT-D module bin directory.
///
/// This intentionally preserves the historical error text used by the
/// program backends that previously carried identical local `find_bin`
/// helpers.
pub fn find_module_binary(name: &str) -> Result<PathBuf> {
    let path = Path::new(MODULE_BIN_DIR).join(name);
    if path.is_file() {
        Ok(path)
    } else {
        bail!("binary not found: {}", path.display())
    }
}
