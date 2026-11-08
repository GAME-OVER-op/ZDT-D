use std::{fs, path::Path};

// from programs/amneziawg.rs
pub fn is_valid_ifname(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 15
        && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-')
}

// from programs/dpitunnel.rs
pub fn default_iface() -> String {
    "auto".to_string()
}

/// Modem packet-data bearer prefixes, most specific first.
const MOBILE_IFACE_PREFIXES: &[&str] = &["rmnet_data", "ccmni", "pdp", "rmnet"];

/// First /sys/class/net entry matching one of `prefixes`, without shelling out.
pub fn detect_iface_by_prefix(prefixes: &[&str]) -> Option<String> {
    let mut names: Vec<String> = fs::read_dir("/sys/class/net")
        .ok()?
        .filter_map(|e| e.ok())
        .filter_map(|e| e.file_name().to_str().map(str::to_owned))
        .collect();
    names.sort();
    prefixes
        .iter()
        .find_map(|p| names.iter().find(|n| n.starts_with(p)).cloned())
}

/// Return the interface name accepted by the existing `ip link show` parser.
/// Examples: `rmnet_data0@rmnet_ipa0:` and `16:rmnet_data0`.
fn canonical_iface_token(name: &str) -> &str {
    let s = name.trim().trim_end_matches(':');
    let s = s.split_once('@').map_or(s, |(head, _)| head);
    if let Some(pos) = s.rfind(':') {
        if !s[..pos].is_empty() && s[..pos].bytes().all(|b| b.is_ascii_digit()) {
            return &s[pos + 1..];
        }
    }
    s
}

/// `rmnet_data2` -> `rmnet_data`, but only for a numbered bearer name.
fn mobile_iface_prefix(name: &str) -> Option<&'static str> {
    let name = canonical_iface_token(name);
    MOBILE_IFACE_PREFIXES.iter().copied().find(|p| {
        name.strip_prefix(p)
            .is_some_and(|rest| !rest.is_empty() && rest.bytes().all(|b| b.is_ascii_digit()))
    })
}

/// Mobile data moves between numbered bearers on every reconnect. Match the
/// whole family with an iptables `+` wildcard instead of pinning one bearer.
/// Explicit non-bearer interfaces and existing wildcards pass through.
pub fn normalize_mobile_iface(raw: &str) -> String {
    let s = raw.trim();
    if s.ends_with('+') {
        return s.to_owned();
    }
    if let Some(prefix) = mobile_iface_prefix(s) {
        return format!("{prefix}+");
    }
    if s.is_empty() || s.eq_ignore_ascii_case("auto") || s.eq_ignore_ascii_case("detect") {
        return detect_iface_by_prefix(MOBILE_IFACE_PREFIXES)
            .as_deref()
            .and_then(mobile_iface_prefix)
            .map(|prefix| format!("{prefix}+"))
            .unwrap_or_else(|| "rmnet_data+".to_owned());
    }
    s.to_owned()
}

/// `auto`/`detect`/empty for the Wi-Fi slot means the wireless interface.
pub fn normalize_wifi_iface(raw: &str) -> String {
    let s = raw.trim();
    if s.is_empty() || s.eq_ignore_ascii_case("auto") || s.eq_ignore_ascii_case("detect") {
        return detect_iface_by_prefix(&["wlan", "wifi"]).unwrap_or_else(|| "wlan0".to_owned());
    }
    s.to_owned()
}

/// Replay helper for stored interface lists: numbered mobile bearers become
/// family wildcards; `auto`/`detect` and explicit non-bearer names are kept.
pub fn normalize_bearer_tokens(raw: &str) -> String {
    raw.split(|c: char| c.is_whitespace() || c == ',')
        .filter(|tok| !tok.is_empty())
        .map(|tok| match mobile_iface_prefix(tok) {
            Some(prefix) => format!("{prefix}+"),
            None => tok.to_owned(),
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Normalize one per-app routing slot without changing the raw value used for
/// the runtime scope/snapshot. Empty values pass through to preserve their
/// legacy snapshot behavior. Mobile accepts lists; other slots pass through.
pub fn normalize_app_slot_iface(uid_file: &Path, raw: &str) -> String {
    if raw.trim().is_empty() {
        return raw.to_owned();
    }
    match uid_file.file_name().and_then(|name| name.to_str()) {
        Some("mobile_program") => {
            let s = raw.trim();
            if s.eq_ignore_ascii_case("auto") || s.eq_ignore_ascii_case("detect") {
                normalize_mobile_iface(raw)
            } else {
                normalize_bearer_tokens(raw)
            }
        }
        Some("wifi_program") => normalize_wifi_iface(raw),
        _ => raw.to_owned(),
    }
}

/// Reject interfaces that belong to Android/Linux networking rather than a ZDT-D TUN profile.
pub fn is_forbidden_tun_name(s: &str) -> bool {
    s == "lo"
        || s == "dummy0"
        || s.starts_with("wlan")
        || s.starts_with("rmnet")
        || s.starts_with("ccmni")
        || s.starts_with("eth")
        || s.starts_with("ap")
        || s.starts_with("rndis")
}

#[cfg(test)]
mod interface_tests {
    use super::*;

    #[test]
    fn numbered_mobile_bearer_becomes_family_wildcard() {
        assert_eq!(normalize_mobile_iface("rmnet_data0"), "rmnet_data+");
        assert_eq!(normalize_mobile_iface("rmnet_data2"), "rmnet_data+");
        assert_eq!(normalize_mobile_iface("ccmni1"), "ccmni+");
        assert_eq!(normalize_mobile_iface("pdp0"), "pdp+");
        assert_eq!(normalize_mobile_iface(" rmnet_data3 "), "rmnet_data+");
        assert_eq!(normalize_mobile_iface("rmnet_data0@rmnet_ipa0:"), "rmnet_data+");
    }

    #[test]
    fn manual_iface_overrides_pass_through() {
        assert_eq!(normalize_mobile_iface("usb0"), "usb0");
        assert_eq!(normalize_mobile_iface("wlan0"), "wlan0");
        assert_eq!(normalize_mobile_iface("rmnet_ipa0"), "rmnet_ipa0");
        assert_eq!(normalize_mobile_iface("rmnet_data+"), "rmnet_data+");
        assert_eq!(normalize_wifi_iface("wlan0"), "wlan0");
        assert_eq!(normalize_wifi_iface("wlan+"), "wlan+");
    }

    #[test]
    fn auto_mobile_always_resolves_to_a_wildcard() {
        assert!(normalize_mobile_iface("").ends_with('+'));
        assert!(normalize_mobile_iface("auto").ends_with('+'));
        assert!(normalize_mobile_iface("DETECT").ends_with('+'));
    }

    #[test]
    fn bearer_list_replay_only_rewrites_numbered_mobile_names() {
        assert_eq!(normalize_bearer_tokens("wlan0 rmnet_data0"), "wlan0 rmnet_data+");
        assert_eq!(normalize_bearer_tokens("wlan0,rmnet_data2"), "wlan0 rmnet_data+");
        assert_eq!(normalize_bearer_tokens("auto"), "auto");
        assert_eq!(normalize_bearer_tokens("usb0"), "usb0");
    }

    #[test]
    fn app_slot_normalization_preserves_non_mobile_slots() {
        let root = Path::new("/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out");
        assert!(normalize_app_slot_iface(&root.join("mobile_program"), "auto").ends_with('+'));
        let wifi = normalize_app_slot_iface(&root.join("wifi_program"), "auto");
        assert!(wifi.starts_with("wlan") || wifi.starts_with("wifi"));
        assert_eq!(normalize_app_slot_iface(&root.join("user_program"), "rmnet_data0"), "rmnet_data0");
        assert_eq!(normalize_app_slot_iface(&root.join("mobile_program"), "rmnet_data0@rmnet_ipa0:"), "rmnet_data+");
    }

    #[test]
    fn empty_app_slot_values_pass_through() {
        let root = Path::new("/data/adb/modules/ZDT-D/working_folder/operaproxy/app/out");
        for slot in ["mobile_program", "wifi_program"] {
            assert_eq!(normalize_app_slot_iface(&root.join(slot), ""), "");
            assert_eq!(normalize_app_slot_iface(&root.join(slot), "  "), "  ");
        }
    }
}
