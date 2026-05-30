/// Port range inclusive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PortRange {
    pub start: u16,
    pub end: u16,
}

impl PortRange {
    pub fn new(start: u16, end: u16) -> Self {
        Self { start, end }
    }
}

#[derive(Debug, Clone, Default)]
pub struct ProtoPortFilter {
    pub tcp: Vec<PortRange>,
    pub udp: Vec<PortRange>,
}

impl ProtoPortFilter {
    pub fn is_empty(&self) -> bool {
        self.tcp.is_empty() && self.udp.is_empty()
    }
}

/// Parse a single `80,443,4000-5000`-style spec into ranges.
///
/// Supports whitespace and both `-` and `:` as a range separator.
pub fn parse_ranges(spec: &str) -> Vec<PortRange> {
    let mut out = Vec::new();
    let cleaned = spec.replace(' ', "").replace('\t', "");
    for token in cleaned.split(',') {
        let t = token.trim();
        if t.is_empty() {
            continue;
        }
        // range: A-B or A:B
        if let Some((a, b)) = t.split_once('-').or_else(|| t.split_once(':')) {
            if let (Ok(sa), Ok(sb)) = (a.parse::<u16>(), b.parse::<u16>()) {
                if sa >= 1 && sb >= 1 && sa <= 65535 && sb <= 65535 {
                    let (s, e) = if sa <= sb { (sa, sb) } else { (sb, sa) };
                    out.push(PortRange::new(s, e));
                }
            }
            continue;
        }
        if let Ok(p) = t.parse::<u16>() {
            if p >= 1 && p <= 65535 {
                out.push(PortRange::new(p, p));
            }
        }
    }
    out
}

/// Merge and normalize ranges: sort and union overlaps/touches.
pub fn merge_ranges(mut ranges: Vec<PortRange>) -> Vec<PortRange> {
    if ranges.is_empty() {
        return ranges;
    }
    ranges.sort_by_key(|r| (r.start, r.end));
    let mut merged: Vec<PortRange> = Vec::new();
    let mut cur = ranges[0];
    for r in ranges.into_iter().skip(1) {
        if r.start <= cur.end.saturating_add(1) {
            cur.end = cur.end.max(r.end);
        } else {
            merged.push(cur);
            cur = r;
        }
    }
    merged.push(cur);
    merged
}

/// Parse and merge multiple specs into a single compact range list.
pub fn parse_and_merge_specs(specs: &[String]) -> Vec<PortRange> {
    let mut all = Vec::new();
    for s in specs {
        all.extend(parse_ranges(s));
    }
    merge_ranges(all)
}

/// Convert merged ranges into iptables multiport elements:
/// single -> "80", range -> "4000:5000".
pub fn to_multiport_elements(ranges: &[PortRange]) -> Vec<String> {
    let mut out = Vec::new();
    for r in ranges {
        if r.start == r.end {
            out.push(r.start.to_string());
        } else {
            out.push(format!("{}:{}", r.start, r.end));
        }
    }
    out
}

/// Return how many multiport slots an element consumes.
///
/// iptables multiport accepts up to 15 ports. A range like `1000:2000`
/// counts as two ports, not one textual element, so chunking must be based
/// on this weight instead of a simple element count.
pub fn multiport_element_weight(element: &str) -> usize {
    if element.contains(':') {
        2
    } else {
        1
    }
}

/// Split multiport elements into chunks that fit the iptables multiport limit.
///
/// `max_elems` is the maximum multiport slot count, normally 15. Single ports
/// consume one slot, while ranges (`start:end`) consume two slots.
pub fn chunk_multiport(elements: &[String], max_elems: usize) -> Vec<Vec<String>> {
    if elements.is_empty() {
        return Vec::new();
    }

    let max_slots = max_elems.max(1);
    let mut out: Vec<Vec<String>> = Vec::new();
    let mut current: Vec<String> = Vec::new();
    let mut current_weight = 0usize;

    for element in elements {
        let weight = multiport_element_weight(element).min(max_slots);

        if !current.is_empty() && current_weight + weight > max_slots {
            out.push(current);
            current = Vec::new();
            current_weight = 0;
        }

        current.push(element.clone());
        current_weight += weight;
    }

    if !current.is_empty() {
        out.push(current);
    }

    out
}

pub fn join_elems_csv(elems: &[String]) -> String {
    elems.join(",")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_multiport_counts_ranges_as_two_slots() {
        let elems = vec![
            "1".to_string(),
            "7".to_string(),
            "9".to_string(),
            "11".to_string(),
            "13".to_string(),
            "15".to_string(),
            "17".to_string(),
            "19:23".to_string(),
            "25".to_string(),
            "37".to_string(),
            "42:43".to_string(),
            "53".to_string(),
            "69".to_string(),
            "77".to_string(),
            "79".to_string(),
        ];

        let chunks = chunk_multiport(&elems, 15);
        assert_eq!(chunks.len(), 2);
        for chunk in chunks {
            let weight: usize = chunk.iter().map(|e| multiport_element_weight(e)).sum();
            assert!(weight <= 15);
        }
    }
}
