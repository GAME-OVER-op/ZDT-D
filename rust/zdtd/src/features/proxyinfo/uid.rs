use anyhow::{Context, Result};
use std::{collections::{BTreeMap, BTreeSet}, fs};

use crate::{
    platform::android::pkg_uid::{self, Mode as UidMode, Sha256Tracker},
    settings,
};
use super::settings::ensure_layout;

// IMPORTANT: use only the shared working_folder/flag.sha256 file for sha tracking.
// Never introduce module-specific *.flag.sha256 files here.
const UID_TRACKER_FILE: &str = settings::SHARED_SHA_FLAG_FILE;
pub fn read_uid_program_text() -> Result<String> {
    ensure_layout()?;
    crate::common::apps::read_optional_uid_program_text(&settings::proxyinfo_uid_program_path())
}

pub fn write_uid_program_text(content: &str) -> Result<()> {
    ensure_layout()?;
    crate::common::fs::write_text_atomic_context(&settings::proxyinfo_uid_program_path(), content)
}

pub fn read_proxy_packages() -> Result<BTreeSet<String>> {
    let raw = read_uid_program_text()?;
    Ok(crate::common::apps::parse_package_set(&raw))
}



pub fn rebuild_out_program() -> Result<Vec<u32>> {
    ensure_layout()?;
    let tracker = Sha256Tracker::new(UID_TRACKER_FILE);
    let input = settings::proxyinfo_uid_program_path();
    let output = settings::proxyinfo_out_program_path();
    let _ = pkg_uid::unified_processing(UidMode::Default, &tracker, &output, &input)
        .with_context(|| "proxyInfo uid parsing")?;
    read_out_uids()
}

pub fn read_out_uids() -> Result<Vec<u32>> {
    ensure_layout()?;
    crate::common::apps::read_out_uids(&settings::proxyinfo_out_program_path())
}

pub fn read_out_uid_packages() -> Result<BTreeMap<u32, Vec<String>>> {
    ensure_layout()?;
    let raw = match fs::read_to_string(settings::proxyinfo_out_program_path()) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(e) => return Err(anyhow::anyhow!("read out_program failed: {e}")),
    };

    let mut out: BTreeMap<u32, Vec<String>> = BTreeMap::new();
    for line in raw.lines() {
        let s = line.trim();
        if s.is_empty() {
            continue;
        }
        let Some((pkg, rhs)) = s.rsplit_once('=') else {
            continue;
        };
        let pkg = pkg.trim();
        if pkg.is_empty() {
            continue;
        }
        if let Ok(uid) = rhs.trim().parse::<u32>() {
            if uid > 0 {
                out.entry(uid).or_default().push(pkg.to_string());
            }
        }
    }
    Ok(out)
}
