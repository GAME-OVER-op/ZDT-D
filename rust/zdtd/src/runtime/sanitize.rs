use std::{fs, path::{Path, PathBuf}};

const WORKING_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder";
const API_ROOT: &str = "/data/adb/modules/ZDT-D/api";

fn is_empty_nul_or_ws(bytes: &[u8]) -> bool {
    bytes.iter().all(|b| *b == 0 || b.is_ascii_whitespace())
}

fn read_file_bytes(path: &Path) -> Option<Vec<u8>> {
    match fs::read(path) {
        Ok(bytes) => Some(bytes),
        Err(e) => {
            if e.kind() != std::io::ErrorKind::NotFound {
                log::warn!("runtime_sanitize: failed to read {}: {e}", path.display());
            }
            None
        }
    }
}

fn remove_corrupt_file(path: &Path, reason: &str) -> bool {
    match fs::remove_file(path) {
        Ok(()) => {
            log::warn!("runtime_sanitize: removed corrupt runtime file {} ({reason})", path.display());
            true
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => {
            log::warn!("runtime_sanitize: failed to remove corrupt runtime file {} ({reason}): {e}", path.display());
            false
        }
    }
}

pub fn json_bytes_are_corrupt(bytes: &[u8]) -> bool {
    if is_empty_nul_or_ws(bytes) {
        return true;
    }
    serde_json::from_slice::<serde_json::Value>(bytes).is_err()
}

pub fn sanitize_json_runtime_file(path: &Path) -> bool {
    let Some(bytes) = read_file_bytes(path) else { return false; };
    if is_empty_nul_or_ws(&bytes) {
        return remove_corrupt_file(path, "empty/NUL-filled JSON");
    }
    if let Err(e) = serde_json::from_slice::<serde_json::Value>(&bytes) {
        return remove_corrupt_file(path, &format!("invalid JSON: {e}"));
    }
    false
}

pub fn sanitize_text_runtime_file(path: &Path) -> bool {
    let Some(bytes) = read_file_bytes(path) else { return false; };
    if !bytes.is_empty() && bytes.iter().all(|b| *b == 0) {
        return remove_corrupt_file(path, "NUL-filled text");
    }
    false
}

fn sanitize_json_dir(dir: &Path) -> usize {
    let Ok(entries) = fs::read_dir(dir) else { return 0; };
    let mut cleaned = 0usize;
    for ent in entries.flatten() {
        let path = ent.path();
        if !path.is_file() {
            continue;
        }
        if path.extension().and_then(|s| s.to_str()) != Some("json") {
            continue;
        }
        if sanitize_json_runtime_file(&path) {
            cleaned += 1;
        }
    }
    cleaned
}

fn known_working_json_runtime_files() -> Vec<PathBuf> {
    let root = Path::new(WORKING_ROOT);
    vec![
        root.join("vpn_tether/applied.json"),
        root.join("runtime_refresh/routing.json"),
        root.join("zdtd_runtime_state.json"),
        root.join("energy_saver/state.json"),
        root.join("nfqws_tester/session.json"),
    ]
}

fn known_working_text_runtime_files() -> Vec<PathBuf> {
    Vec::new()
}

/// Remove corrupted runtime/cache files that are safe for the daemon or child
/// services to recreate. This deliberately avoids user configuration files
/// such as profile setting.json/config.json and active.json. vpn_netd owns its
/// own applied snapshot cleanup, so this generic sanitizer does not touch it.
pub fn sanitize_runtime_files_best_effort() -> usize {
    let mut cleaned = 0usize;

    for path in known_working_json_runtime_files() {
        if sanitize_json_runtime_file(&path) {
            cleaned += 1;
        }
    }
    for path in known_working_text_runtime_files() {
        if sanitize_text_runtime_file(&path) {
            cleaned += 1;
        }
    }

    // t2s metadata lives under the shared API root, but it is runtime-only and
    // active t2s instances rewrite it periodically. Stale/corrupt files should
    // never surface as Construction Studio errors.
    let api_root = Path::new(API_ROOT);
    cleaned += sanitize_json_dir(&api_root.join("t2s/instances"));
    cleaned += sanitize_json_dir(&api_root.join("t2s/ports"));
    if sanitize_json_runtime_file(&api_root.join("t2s/info.json")) {
        cleaned += 1;
    }

    if cleaned > 0 {
        log::warn!("runtime_sanitize: cleaned {cleaned} corrupt runtime file(s)");
    }
    cleaned
}
