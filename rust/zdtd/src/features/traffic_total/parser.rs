use super::model::ParsedSaveRule;

pub(super) fn parse_iptables_save(family: &str, table: &str, out: &str) -> Vec<ParsedSaveRule> {
    let mut rules = Vec::new();
    for line in out.lines().map(str::trim) {
        if !line.starts_with('[') {
            continue;
        }
        let Some(end) = line.find(']') else { continue; };
        let Some((packets_s, bytes_s)) = line[1..end].split_once(':') else { continue; };
        let Ok(packets) = packets_s.parse::<u64>() else { continue; };
        let Ok(bytes) = bytes_s.parse::<u64>() else { continue; };
        let rest = line[end + 1..].trim();
        let Some(rest) = rest.strip_prefix("-A ") else { continue; };
        let mut parts = rest.splitn(2, char::is_whitespace);
        let Some(chain) = parts.next() else { continue; };
        let raw_rule = parts.next().unwrap_or("").trim().to_string();
        rules.push(ParsedSaveRule {
            family: family.to_string(),
            table: table.to_string(),
            chain: chain.to_string(),
            packets,
            bytes,
            raw_rule,
        });
    }
    rules
}

pub(super) fn is_relevant_rule(rule: &ParsedSaveRule) -> bool {
    is_relevant_chain(&rule.chain) || rule.raw_rule.split_whitespace().any(is_relevant_target)
}

pub(super) fn is_relevant_chain(chain: &str) -> bool {
    chain == "MANGLE_APP"
        || chain == "NAT_DPI"
        || chain == "NAT_DPI_LOCAL"
        || chain.starts_with("ZDT")
}

pub(super) fn is_relevant_target(tok: &str) -> bool {
    tok == "MANGLE_APP" || tok == "NAT_DPI" || tok == "NAT_DPI_LOCAL" || tok.starts_with("ZDT")
}
pub(super) fn value_after<'a>(tokens: &[&'a str], key: &str) -> Option<&'a str> {
    tokens.windows(2).find_map(|w| if w[0] == key { Some(w[1]) } else { None })
}

pub(super) fn parse_uid_owner(s: &str) -> Option<u32> {
    // Most ZDT-D rules use a single UID. If a future rule uses a range, expose
    // the first UID as a best-effort key while keeping raw_rule for exact data.
    let first = s.split('-').next().unwrap_or(s).trim();
    first.parse::<u32>().ok().filter(|v| *v > 0)
}

pub(super) fn parse_dest_ports(tokens: &[&str]) -> Vec<String> {
    let mut out = Vec::new();
    for key in ["--dports", "--dport", "--sports", "--sport"] {
        if let Some(v) = value_after(tokens, key) {
            for part in v.split(',') {
                let p = part.trim();
                if !p.is_empty() && !out.iter().any(|x| x == p) {
                    out.push(p.to_string());
                }
            }
        }
    }
    out
}

pub(super) fn parse_redirect_port(tokens: &[&str]) -> Option<u16> {
    for key in ["--to-destination", "--to-ports", "--to", "--on-port"] {
        if let Some(v) = value_after(tokens, key) {
            if let Some(port) = v.rsplit(':').next().and_then(|p| p.parse::<u16>().ok()) {
                return Some(port);
            }
            if let Ok(port) = v.parse::<u16>() {
                return Some(port);
            }
        }
    }
    None
}

pub(super) fn classify_semantic<'a>(chain: &str, target: &'a str, dest_ports: &[String], redirect_port: Option<u16>, tokens: &[&str]) -> &'a str {
    match target {
        "NFQUEUE" => "nfqueue",
        "DNAT" | "REDIRECT" => {
            let dns_ports = dest_ports.iter().any(|p| p == "53" || p.contains("53")) || redirect_port == Some(53) || redirect_port == Some(853) || redirect_port == Some(5353);
            if chain == "NAT_DPI" && dns_ports { "dns_redirect" } else { "nat_redirect" }
        }
        "TPROXY" => "tproxy",
        "MARK" => "mark",
        "DROP" => "drop",
        "REJECT" => "reject",
        "MASQUERADE" => "masquerade",
        "ACCEPT" => "accept",
        "RETURN" => {
            if tokens.iter().any(|t| *t == "-o" || *t == "-d") { "guard_return" } else { "chain_return" }
        }
        _ if is_relevant_target(target) => "chain_jump",
        _ => "unknown",
    }
}

pub(super) fn is_action_semantic(s: &str) -> bool {
    matches!(s, "nfqueue" | "nat_redirect" | "dns_redirect" | "tproxy" | "mark" | "drop" | "reject" | "masquerade" | "accept")
}

pub(super) fn classify_chain(chain: &str) -> &'static str {
    if chain.starts_with("ZDTN_") { "scoped_nat" }
    else if chain.starts_with("ZDTPP_") { "scoped_tproxy_pre" }
    else if chain.starts_with("ZDTP_") { "scoped_tproxy_out" }
    else if chain.starts_with("ZDTM_") { "scoped_mangle" }
    else if chain == "NAT_DPI" || chain == "NAT_DPI_LOCAL" { "base_nat" }
    else if chain == "ZDT_TPROXY_OUT" || chain == "ZDT_TPROXY_PRE" { "base_tproxy" }
    else if chain == "MANGLE_APP" { "base_mangle" }
    else if chain.starts_with("ZDT_VPN_TETHER") { "vpn_tether" }
    else if chain == "ZDT_BLOCKEDQUIC" { "blocked_quic" }
    else if chain == "ZDT_PROXYINFO" { "proxyinfo" }
    else { "zdt" }
}
