use std::collections::BTreeSet;
use std::sync::Mutex;

const MODULE_BIN_PREFIX: &str = "/data/adb/modules/ZDT-D/bin/";
static MISSING_COMPONENTS_REPORTED: Mutex<BTreeSet<String>> = Mutex::new(BTreeSet::new());

/// Extract missing ZDT-D executable names from an error chain.
///
/// Most program backends historically used plain anyhow strings such as
/// `file missing: /data/adb/modules/ZDT-D/bin/t2s`.  Keeping the classifier
/// here lets the runtime present a precise user-facing message without
/// changing every backend error type at once.
pub fn missing_binary_components(error: &anyhow::Error) -> Vec<String> {
    let rendered = format!("{error:#}");
    let rendered_lower = rendered.to_ascii_lowercase();
    let looks_missing = rendered_lower.contains("missing")
        || rendered_lower.contains("not found")
        || rendered_lower.contains("no such file or directory")
        || rendered_lower.contains("os error 2")
        || rendered_lower.contains("enoent");
    if !looks_missing {
        return Vec::new();
    }

    let mut out = BTreeSet::<String>::new();
    for cause in error.chain() {
        let text = cause.to_string();
        let mut rest = text.as_str();
        let mut saw_module_path = false;
        while let Some(idx) = rest.find(MODULE_BIN_PREFIX) {
            let tail = &rest[idx + MODULE_BIN_PREFIX.len()..];
            let raw_name: String = tail
                .chars()
                .take_while(|c| c.is_ascii_alphanumeric() || matches!(*c, '.' | '_' | '-'))
                .collect();
            let name = raw_name.trim_end_matches('.');
            if !name.is_empty() {
                out.insert(name.to_string());
                saw_module_path = true;
            }
            let consumed = raw_name.len().max(1).min(tail.len());
            rest = &tail[consumed..];
        }

        let lower = text.to_ascii_lowercase();
        if lower.contains("no byedpi binary candidates") || lower.contains("byedpi binary not found") {
            out.insert("byedpi".to_string());
        }

        if !saw_module_path {
            for marker in [" binary missing", " binary not found"] {
                if let Some(idx) = lower.find(marker) {
                    let prefix = text[..idx].trim_end();
                    if let Some(candidate) = prefix.split_whitespace().last() {
                        let candidate = candidate.trim_matches(|c: char| {
                            !c.is_ascii_alphanumeric() && c != '_' && c != '-'
                        });
                        if !candidate.is_empty() {
                            out.insert(candidate.to_ascii_lowercase());
                        }
                    }
                }
            }
        }
    }

    out.into_iter().collect()
}

/// Write the dedicated user-facing diagnostic for one missing executable.
pub fn user_warn_missing_component(component: &str) {
    let should_log = match MISSING_COMPONENTS_REPORTED.lock() {
        Ok(mut seen) => seen.insert(component.to_string()),
        Err(poisoned) => poisoned.into_inner().insert(component.to_string()),
    };
    if should_log {
        crate::logging::user_warn(&format!("Компонент {component} отсутствует"));
    }
}

pub fn reset_missing_component_reported() {
    match MISSING_COMPONENTS_REPORTED.lock() {
        Ok(mut seen) => seen.clear(),
        Err(poisoned) => poisoned.into_inner().clear(),
    }
}

pub fn missing_component_reported() -> bool {
    match MISSING_COMPONENTS_REPORTED.lock() {
        Ok(seen) => !seen.is_empty(),
        Err(poisoned) => !poisoned.into_inner().is_empty(),
    }
}

/// Report every missing executable encoded in an anyhow error chain.
/// Returns true when the error was classified as an installation-integrity
/// problem, allowing callers to suppress the generic `startup error` line.
pub fn report_missing_components(error: &anyhow::Error) -> bool {
    let components = missing_binary_components(error);
    for component in &components {
        user_warn_missing_component(component);
    }
    !components.is_empty()
}
