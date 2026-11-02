use anyhow::{bail, Context, Result};

// from programs/amneziawg.rs
pub fn u32_to_ipv4(v: u32) -> String {
    format!("{}.{}.{}.{}", (v >> 24) & 0xff, (v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff)
}

// from programs/amneziawg.rs
pub fn ipv4_to_u32(s: &str) -> Option<u32> {
    let mut out = 0u32;
    let mut count = 0usize;
    for part in s.split('.') {
        let n = part.parse::<u8>().ok()? as u32;
        out = (out << 8) | n;
        count += 1;
    }
    if count == 4 { Some(out) } else { None }
}

// from programs/amneziawg.rs
pub fn first_host_for_cidr(cidr: &str) -> Option<String> {
    let (ip, prefix_s) = cidr.split_once('/')?;
    let prefix = prefix_s.parse::<u8>().ok()?;
    if prefix > 30 { return None; }
    let net = ipv4_to_u32(ip)?;
    Some(u32_to_ipv4(net.saturating_add(1)))
}

// from programs/amneziawg.rs
pub fn cidr_network_mask(cidr: &str) -> Result<(u32, u32)> {
    let (ip, prefix_s) = cidr.split_once('/').ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s.parse::<u8>().with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 { bail!("bad cidr prefix {cidr}"); }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok((addr & mask, mask))
}
