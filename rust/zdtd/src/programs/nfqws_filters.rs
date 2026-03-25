    use crate::iptables::port_filter::{self, ProtoPortFilter};

    /// Extract and merge `--filter-tcp=` and `--filter-udp=` values from raw config.txt.
    ///
    /// Supports multiple occurrences. Values are merged (dedupe + range union).
    ///
    /// Example:
    ///   --filter-udp=80,443,4000-5000
    ///   --filter-udp=443, 3000-6000
    ///   --filter-udp=3500-65000
    /// Result: 80,443,3000-65000
    pub fn extract_proto_port_filter(raw: &str) -> ProtoPortFilter {
        let mut tcp_specs: Vec<String> = Vec::new();
        let mut udp_specs: Vec<String> = Vec::new();

        // Respect line continuations: remove backslash-newline sequences.
        let mut s = raw.replace("\\
", "").replace("\\
", "");

        for line in s.lines() {
            // Strip comments
            let mut l = line;
            if let Some(pos) = l.find('#') {
                l = &l[..pos];
            }
            let l = l.trim();
            if l.is_empty() {
                continue;
            }

            collect_specs_from_line(l, "--filter-tcp=", &mut tcp_specs);
            collect_specs_from_line(l, "--filter-udp=", &mut udp_specs);
        }

        let tcp = port_filter::parse_and_merge_specs(&tcp_specs);
        let udp = port_filter::parse_and_merge_specs(&udp_specs);

        ProtoPortFilter { tcp, udp }
    }

    fn collect_specs_from_line(line: &str, key: &str, out: &mut Vec<String>) {
        let mut start = 0usize;
        while let Some(rel) = line[start..].find(key) {
            let pos = start + rel + key.len();
            let tail = &line[pos..];

            // Stop at next option marker " --" (whitespace + "--") if present.
            // This allows spaces inside "443, 3000-6000".
            let mut end = tail.len();
            for (i, ch) in tail.char_indices() {
                if ch.is_whitespace() {
                    // if after whitespace comes "--", that's next option
                    let rest = &tail[i..];
                    let rest_trim = rest.trim_start();
                    if rest_trim.starts_with("--") {
                        end = i;
                        break;
                    }
                }
            }

            let val = tail[..end].trim();
            if !val.is_empty() {
                out.push(val.to_string());
            }

            start = pos;
        }
    }
