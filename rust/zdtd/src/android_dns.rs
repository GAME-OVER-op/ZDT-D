use crate::shell::{self, Capture};
use std::{collections::BTreeSet, net::Ipv4Addr, time::Duration};

const DNS_TIMEOUT: Duration = Duration::from_secs(4);

pub(crate) fn resolve_ipv4(host: &str) -> Option<String> {
    resolve_ipv4_all(host).into_iter().next()
}

pub(crate) fn resolve_ipv4_all(host: &str) -> Vec<String> {
    let host = host.trim().trim_end_matches('.');
    if host.is_empty() {
        return Vec::new();
    }
    if host.parse::<Ipv4Addr>().is_ok() {
        return vec![host.to_string()];
    }

    let attempts: [(&str, Vec<&str>); 4] = [
        ("toybox", vec!["nslookup", host, "1.1.1.1"]),
        ("nslookup", vec![host, "1.1.1.1"]),
        ("toybox", vec!["nslookup", host]),
        ("nslookup", vec![host]),
    ];

    for (cmd, args) in attempts {
        let Ok((code, out)) = shell::run_timeout(cmd, &args, Capture::Both, DNS_TIMEOUT) else {
            continue;
        };
        if code != 0 {
            continue;
        }
        let ips = resolved_ipv4s_from_text(&out);
        if !ips.is_empty() {
            return ips;
        }
    }

    if let Ok((code, out)) = shell::run_timeout(
        "ping",
        &["-c", "1", "-W", "2", host],
        Capture::Both,
        DNS_TIMEOUT,
    ) {
        if code == 0 {
            return ipv4s_from_text(&out);
        }
    }

    Vec::new()
}

fn ipv4s_from_text(text: &str) -> Vec<String> {
    collect_ipv4s(text, false)
}

fn resolved_ipv4s_from_text(text: &str) -> Vec<String> {
    collect_ipv4s(text, true)
}

fn collect_ipv4s(text: &str, filter_resolver_addresses: bool) -> Vec<String> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    for raw in text.split(|c: char| {
        c.is_ascii_whitespace() || matches!(c, ',' | ';' | '(' | ')' | '[' | ']' | '#')
    }) {
        let token = raw.trim();
        if token.parse::<Ipv4Addr>().is_err() {
            continue;
        }
        if filter_resolver_addresses
            && matches!(token, "0.0.0.0" | "1.1.1.1" | "8.8.8.8" | "127.0.0.1")
        {
            continue;
        }
        if seen.insert(token.to_string()) {
            out.push(token.to_string());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_and_deduplicates_ipv4s() {
        let out = ipv4s_from_text("PING example (203.0.113.8) 56 bytes 203.0.113.8");
        assert_eq!(out, vec!["203.0.113.8"]);
    }

    #[test]
    fn filters_explicit_resolver_from_nslookup_output() {
        let out = resolved_ipv4s_from_text(
            "Server: 1.1.1.1\nAddress 1.1.1.1#53\nName: example.com\nAddress: 203.0.113.10\nAddress: 203.0.113.11",
        );
        assert_eq!(out, vec!["203.0.113.10", "203.0.113.11"]);
    }
}
