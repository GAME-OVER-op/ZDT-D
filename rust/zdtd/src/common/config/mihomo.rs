/// Parses Mihomo's top-level `external-controller:` scalar without bringing in
/// a YAML dependency. This is intentionally identical to the parser formerly
/// duplicated by ports.rs and proxyinfo.rs.
pub fn external_controller_port(raw: &str) -> Option<u16> {
    for line in raw.lines() {
        let trimmed = line.trim_start();
        let indent = line.len().saturating_sub(trimmed.len());
        if indent != 0 {
            continue;
        }
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('-') {
            continue;
        }
        let (key, value) = trimmed.split_once(':')?;
        if key.trim() != "external-controller" {
            continue;
        }
        return port_from_yaml_scalar(value);
    }
    None
}

fn port_from_yaml_scalar(value: &str) -> Option<u16> {
    let mut v = value.trim();
    if v.is_empty() {
        return None;
    }
    if let Some(idx) = v.find(" #") {
        v = &v[..idx];
    }
    v = v.trim().trim_matches('"').trim_matches('\'').trim();
    if v.is_empty() {
        return None;
    }
    if let Ok(port) = v.parse::<u16>() {
        return if port != 0 { Some(port) } else { None };
    }
    let idx = v.rfind(':')?;
    let port_s = v[idx + 1..]
        .trim()
        .trim_matches(']')
        .trim_matches('"')
        .trim_matches('\'');
    port_s.parse::<u16>().ok().filter(|p| *p != 0)
}
