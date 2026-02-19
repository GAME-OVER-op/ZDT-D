use regex::Regex;
use serde::Deserialize;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    Socks,
    Direct,
    Drop,
    Reset,
    Wait,
}

#[derive(Clone, Debug)]
pub struct Rules {
    rules: Vec<Rule>,
}

#[derive(Clone, Debug, Deserialize)]
struct RawRules {
    rules: Vec<RawRule>,
}

#[derive(Clone, Debug, Deserialize)]
struct RawRule {
    when: Option<When>,
    action: String,
}

#[derive(Clone, Debug, Deserialize)]
struct When {
    proto: Option<String>,
    port: Option<u16>,
    port_range: Option<String>,
    host_regex: Option<String>,
    socks_available: Option<bool>,
    is_udp: Option<bool>,
}

#[derive(Clone, Debug)]
struct Rule {
    when: WhenNorm,
    action: Action,
}

#[derive(Clone, Debug, Default)]
struct WhenNorm {
    proto: Option<String>,
    port: Option<u16>,
    port_range: Option<(u16, u16)>,
    host_regex: Option<Regex>,
    socks_available: Option<bool>,
    is_udp: Option<bool>,
}

impl Rules {
    pub fn load_from_env() -> Self {
        let raw = std::env::var("TRAFFIC_RULES").unwrap_or_default();
        if raw.trim().is_empty() {
            return Self { rules: vec![] };
        }
        let parsed = serde_json::from_str::<serde_json::Value>(&raw).ok()
            .and_then(|v| {
                if v.is_object() && v.get("rules").is_some() { v.get("rules").cloned() } else { Some(v) }
            });

        let mut out = vec![];
        if let Some(v) = parsed {
            if let Ok(arr) = serde_json::from_value::<Vec<RawRule>>(v) {
                for rr in arr {
                    if let Some(r) = Self::compile(rr) {
                        out.push(r);
                    }
                }
            } else if let Ok(obj) = serde_json::from_str::<RawRules>(&raw) {
                for rr in obj.rules {
                    if let Some(r) = Self::compile(rr) {
                        out.push(r);
                    }
                }
            }
        }
        Self { rules: out }
    }

    fn compile(rr: RawRule) -> Option<Rule> {
        let action = match rr.action.trim().to_lowercase().as_str() {
            "socks" => Action::Socks,
            "direct" => Action::Direct,
            "drop" => Action::Drop,
            "reset" => Action::Reset,
            "wait" => Action::Wait,
            _ => return None,
        };
        let w = rr.when.unwrap_or(When{
            proto: None, port: None, port_range: None, host_regex: None, socks_available: None, is_udp: None,
        });

        let pr = w.port_range.as_ref().and_then(|s| {
            let parts: Vec<_> = s.split('-').collect();
            if parts.len() != 2 { return None; }
            let lo = parts[0].trim().parse::<u16>().ok()?;
            let hi = parts[1].trim().parse::<u16>().ok()?;
            Some((lo, hi))
        });

        let hr = w.host_regex.as_ref().and_then(|s| Regex::new(&format!("(?i){}", s)).ok());

        Some(Rule{
            when: WhenNorm{
                proto: w.proto.map(|s| s.to_lowercase()),
                port: w.port,
                port_range: pr,
                host_regex: hr,
                socks_available: w.socks_available,
                is_udp: w.is_udp,
            },
            action,
        })
    }

    pub fn decide(&self, proto: &str, host: &str, port: u16, socks_available: bool, is_udp: bool) -> Option<Action> {
        if self.rules.is_empty() {
            return None;
        }
        for r in &self.rules {
            if r.matches(proto, host, port, socks_available, is_udp) {
                return Some(r.action);
            }
        }
        None
    }
}

impl Rule {
    fn matches(&self, proto: &str, host: &str, port: u16, socks_available: bool, is_udp: bool) -> bool {
        if let Some(p) = &self.when.proto {
            if p != &proto.to_lowercase() {
                return false;
            }
        }
        if let Some(u) = self.when.is_udp {
            if u != is_udp { return false; }
        }
        if let Some(sa) = self.when.socks_available {
            if sa != socks_available { return false; }
        }
        if let Some(p) = self.when.port {
            if p != port { return false; }
        }
        if let Some((lo,hi)) = self.when.port_range {
            if !(lo <= port && port <= hi) { return false; }
        }
        if let Some(re) = &self.when.host_regex {
            if !re.is_match(host) { return false; }
        }
        true
    }
}

pub fn classify_protocol(port: u16) -> String {
    match port {
        80 | 8080 | 8000 => "http".to_string(),
        443 | 8443 => "https".to_string(),
        53 => "dns".to_string(),
        _ => "tcp".to_string(),
    }
}
