use anyhow::{bail, Context, Result};

use super::{cidr_network_mask, ipv4_to_u32, u32_to_ipv4};

pub fn is_ipv4(s: &str) -> bool {
    let parts = s.split('.').collect::<Vec<_>>();
    parts.len() == 4 && parts.iter().all(|part| !part.is_empty() && part.parse::<u8>().is_ok())
}

pub fn is_ipv4_cidr(s: &str) -> bool {
    let Some((ip, prefix)) = s.split_once('/') else { return false; };
    let Ok(prefix) = prefix.parse::<u8>() else { return false; };
    is_ipv4(ip) && prefix <= 32
}

pub fn normalize_cidr_network(cidr: &str) -> Result<String> {
    let (ip, prefix_s) = cidr
        .split_once('/')
        .ok_or_else(|| anyhow::anyhow!("bad cidr {cidr}"))?;
    let prefix = prefix_s
        .parse::<u8>()
        .with_context(|| format!("bad cidr prefix {cidr}"))?;
    if prefix > 32 {
        bail!("bad cidr prefix {cidr}");
    }
    let addr = ipv4_to_u32(ip).ok_or_else(|| anyhow::anyhow!("bad cidr ip {cidr}"))?;
    let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
    Ok(format!("{}/{}", u32_to_ipv4(addr & mask), prefix))
}

pub fn cidrs_overlap(a: &str, b: &str) -> Result<bool> {
    let (an, am) = cidr_network_mask(a)?;
    let (bn, bm) = cidr_network_mask(b)?;
    let a_end = an | !am;
    let b_end = bn | !bm;
    Ok(an <= b_end && bn <= a_end)
}


/// Generates the IPv4 /30 tuple used by VPN backends that allocate one
/// four-address subnet per stable profile index. The caller owns the pool
/// base/range policy; this helper only centralizes the arithmetic.
pub fn generated_ipv4_30(network_base: u32, index: u32, owner: &str) -> Result<(String, String, String)> {
    let offset = index
        .checked_mul(4)
        .ok_or_else(|| anyhow::anyhow!("{owner} cidr overflow"))?;
    let network = network_base
        .checked_add(offset)
        .ok_or_else(|| anyhow::anyhow!("{owner} cidr overflow"))?;
    let address = network
        .checked_add(1)
        .ok_or_else(|| anyhow::anyhow!("{owner} cidr overflow"))?;
    let gateway = network
        .checked_add(2)
        .ok_or_else(|| anyhow::anyhow!("{owner} cidr overflow"))?;
    Ok((
        format!("{}/30", u32_to_ipv4(address)),
        format!("{}/30", u32_to_ipv4(network)),
        u32_to_ipv4(gateway),
    ))
}
