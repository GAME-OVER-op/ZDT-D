use anyhow::{Context, Result};
use log::{info, warn};
use serde::Deserialize;
use std::{
    fs,
    path::Path,
    process::{Command, Stdio},
    time::Duration,
};
use std::os::unix::process::CommandExt;

use crate::shell::{self, Capture};

const MODULE_DIR: &str = "/data/adb/modules/ZDT-D";
const WORKING_DIR: &str = "/data/adb/modules/ZDT-D/working_folder";
const DNSCRYPT_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt";
const BIN_DIR: &str = "/data/adb/modules/ZDT-D/bin";

const ACTIVE_JSON: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json";
const DNSCRYPT_TOML: &str =
    "/data/adb/modules/ZDT-D/working_folder/dnscrypt/setting/dnscrypt-proxy.toml";

#[derive(Debug, Deserialize)]
struct ActiveJson {
    enabled: bool,
}



pub fn active_listen_port() -> Result<Option<u16>> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(DNSCRYPT_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.enabled {
        return Ok(None);
    }

    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        return Ok(None);
    }

    let port = parse_listen_port(toml_path)?;
    if port == 0 {
        return Ok(None);
    }
    Ok(Some(port))
}

pub fn start_if_enabled() -> Result<()> {
    ensure_dir(MODULE_DIR)?;
    ensure_dir(WORKING_DIR)?;
    ensure_dir(DNSCRYPT_ROOT)?;

    let active_path = Path::new(ACTIVE_JSON);
    let active: ActiveJson = read_json(active_path)
        .with_context(|| format!("read {}", active_path.display()))?;

    if !active.enabled {
        info!("dnscrypt disabled (active.json enabled=false) -> skip");
        return Ok(());
    }

    crate::logging::user_info("DNSCrypt: запуск");

    let toml_path = Path::new(DNSCRYPT_TOML);
    if !toml_path.is_file() {
        warn!("dnscrypt enabled but config missing: {} -> skip", toml_path.display());
        return Ok(());
    }

    let listen_port = parse_listen_port(toml_path)?;
    if listen_port == 0 {
        warn!("dnscrypt enabled but failed to parse listen port -> skip");
        return Ok(());
    }

    // start before other programs
    spawn_dnscrypt(toml_path, listen_port)?;

        crate::logging::user_info("DNSCrypt: правила iptables");
    // Apply iptables rules (method from shell function, but without notifications)
    apply_dns_iptables(listen_port)?;

    info!("dnscrypt started and iptables rules applied (listen port={})", listen_port);
    Ok(())
}

fn spawn_dnscrypt(toml_path: &Path, listen_port: u16) -> Result<()> {
    let bin = Path::new(BIN_DIR).join("dnscrypt");
    if !bin.is_file() {
        warn!("dnscrypt enabled but binary not found: {} -> skip", bin.display());
        return Ok(());
    }

    // no logs: redirect to /dev/null
    let mut cmd = Command::new(&bin);
    cmd.arg("-config")
        .arg(toml_path)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    unsafe {
        cmd.pre_exec(|| {
            unsafe {
                let _ = libc::setsid();
            }
            Ok(())
        });
    }

    let child = cmd.spawn().with_context(|| format!("spawn {}", bin.display()))?;
    let pid = child.id();
    info!("spawned dnscrypt pid={} (listen port={})", pid, listen_port);

    std::thread::sleep(Duration::from_millis(120));
    Ok(())
}

fn parse_listen_port(toml_path: &Path) -> Result<u16> {
    // Supported formats:
    // listen_addresses = ['127.0.0.1:PORT']
    // listen_addresses = ['[::1]:PORT']
    // listen_addresses = ['127.0.0.1:PORT', '[::1]:PORT']
    let s = fs::read_to_string(toml_path)
        .with_context(|| format!("read {}", toml_path.display()))?;

    for line in s.lines() {
        let line = line.trim();
        if !line.starts_with("listen_addresses") {
            continue;
        }

        let mut ports: Vec<u16> = Vec::new();
        let patterns = ["127.0.0.1:", "[::1]:", "::1:"];

        for pat in patterns {
            let mut start = 0usize;
            while let Some(rel) = line[start..].find(pat) {
                let pos = start + rel + pat.len();
                let tail = &line[pos..];
                let digits: String = tail.chars().take_while(|c| c.is_ascii_digit()).collect();
                if !digits.is_empty() {
                    let port: u16 = digits
                        .parse()
                        .with_context(|| format!("parse dnscrypt listen port from '{}'", line))?;
                    ports.push(port);
                }
                start = pos;
            }
        }

        if ports.is_empty() {
            break;
        }

        let first = ports[0];
        if ports.iter().any(|&p| p != first) {
            warn!(
                "dnscrypt listen_addresses has mismatched ports {:?} in line '{}' -> using {}",
                ports, line, first
            );
        }
        return Ok(first);
    }

    Ok(0)
}

fn apply_dns_iptables(listen_port: u16) -> Result<()> {
    let dns_ports = [53u16, 853u16];
    let dports_multi = "53,853";
    let prots = ["udp", "tcp", "sctp"];

    let ipt = find_iptables();

    // Ensure MANGLE_APP exists and OUTPUT jumps to it
    ensure_chain(&ipt, "mangle", "MANGLE_APP")?;
    ensure_jump(&ipt, "mangle", "OUTPUT", "MANGLE_APP", false)?;

    // Ensure NAT_DPI exists and OUTPUT jumps to it (needed if no other programs created it)
    ensure_chain(&ipt, "nat", "NAT_DPI")?;
    ensure_jump(&ipt, "nat", "OUTPUT", "NAT_DPI", true)?;

    // 1) MANGLE exceptions: RETURN for DNS ports
    for proto in prots {
        let mp = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
        if !rule_exists(&ipt, "mangle", "MANGLE_APP", &mp)? {
            if add_rule(&ipt, "mangle", "MANGLE_APP", true, &mp).is_ok() {
                info!("dns: mangle added multiport RETURN ({}) for {}", dports_multi, proto);
            } else {
                for p in dns_ports {
                    let ps = p.to_string();
                    let r = ["-p", proto, "--dport", &ps, "-j", "RETURN"];
                    if !rule_exists(&ipt, "mangle", "MANGLE_APP", &r)? {
                        let _ = add_rule(&ipt, "mangle", "MANGLE_APP", true, &r)
                            .or_else(|_| add_rule(&ipt, "mangle", "MANGLE_APP", false, &r));
                    }
                }
                info!("dns: mangle multiport unavailable -> added per-port RETURN for {}", proto);
            }
        }
    }

    // 2) remove possible global DNAT in nat OUTPUT for our listen port
    for p in dns_ports {
        let ps = p.to_string();
        let to = format!("127.0.0.1:{}", listen_port);
        for proto in prots {
            let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
            if rule_exists(&ipt, "nat", "OUTPUT", &r)? {
                let _ = del_rule(&ipt, "nat", "OUTPUT", &r);
            }
        }
    }

    // Also remove legacy DNAT rules in PREROUTING (we no longer use PREROUTING in dnscrypt).
    for proto in prots {
        let to = format!("127.0.0.1:{}", listen_port);
        let mp = [
            "-p",
            proto,
            "-m",
            "multiport",
            "--dports",
            dports_multi,
            "-j",
            "DNAT",
            "--to-destination",
            &to,
        ];
        if rule_exists(&ipt, "nat", "PREROUTING", &mp)? {
            let _ = del_rule(&ipt, "nat", "PREROUTING", &mp);
        }
        for p in dns_ports {
            let ps = p.to_string();
            let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
            if rule_exists(&ipt, "nat", "PREROUTING", &r)? {
                let _ = del_rule(&ipt, "nat", "PREROUTING", &r);
            }
        }
    }

    // 3) add DNAT inside NAT_DPI only (no PREROUTING)
    for proto in prots {
        for chain in ["NAT_DPI"] {
            let to = format!("127.0.0.1:{}", listen_port);
            let mp = [
                "-p",
                proto,
                "-m",
                "multiport",
                "--dports",
                dports_multi,
                "-j",
                "DNAT",
                "--to-destination",
                &to,
            ];
            if !rule_exists(&ipt, "nat", chain, &mp)? {
                if add_rule(&ipt, "nat", chain, true, &mp).is_ok() {
                    info!("dns: nat added multiport DNAT ({}) {} -> {} in {}", dports_multi, proto, to, chain);
                } else {
                    for p in dns_ports {
                        let ps = p.to_string();
                        let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                        if !rule_exists(&ipt, "nat", chain, &r)? {
                            let _ = add_rule(&ipt, "nat", chain, true, &r)
                                .or_else(|_| add_rule(&ipt, "nat", chain, false, &r));
                        }
                    }
                    info!("dns: nat multiport unavailable -> added per-port DNAT for {} in {}", proto, chain);
                }
            }
        }
    }

    // Keep these at the very beginning of NAT_DPI / MANGLE_APP.
    ensure_loopback_returns(&ipt)?;

    // IPv6 (ip6tables) is best-effort: not all Android builds have ip6tables nat.
    if let Err(e) = apply_dns_ip6tables(listen_port) {
        warn!("dns: ip6tables rules skipped: {e:#}");
    }

    Ok(())
}

fn find_iptables() -> String {
    if let Ok((code, out)) = shell::run("sh", &["-c", "command -v iptables"], Capture::Stdout) {
        if code == 0 {
            let s = out.trim();
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    for p in [
        "/system/bin/iptables",
        "/system/xbin/iptables",
        "/sbin/iptables",
        "/bin/iptables",
        "/usr/sbin/iptables",
    ] {
        if Path::new(p).is_file() {
            return p.to_string();
        }
    }
    "iptables".to_string()
}

fn find_ip6tables() -> String {
    if let Ok((code, out)) = shell::run("sh", &["-c", "command -v ip6tables"], Capture::Stdout) {
        if code == 0 {
            let s = out.trim();
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    for p in [
        "/system/bin/ip6tables",
        "/system/xbin/ip6tables",
        "/sbin/ip6tables",
        "/bin/ip6tables",
        "/usr/sbin/ip6tables",
    ] {
        if Path::new(p).is_file() {
            return p.to_string();
        }
    }
    "ip6tables".to_string()
}

fn ip6_nat_supported(ip6t: &str) -> bool {
    match shell::run(ip6t, &["-t", "nat", "-nL"], Capture::None) {
        Ok((c, _)) => c == 0,
        Err(_) => false,
    }
}

fn apply_dns_ip6tables(listen_port: u16) -> Result<()> {
    let dns_ports = [53u16, 853u16];
    let dports_multi = "53,853";
    let prots = ["udp", "tcp", "sctp"];

    let ip6t = find_ip6tables();

    // Ensure MANGLE_APP exists and OUTPUT jumps to it
    ensure_chain(&ip6t, "mangle", "MANGLE_APP")?;
    ensure_jump(&ip6t, "mangle", "OUTPUT", "MANGLE_APP", false)?;

    let nat_ok = ip6_nat_supported(&ip6t);
    if nat_ok {
        // Ensure NAT_DPI exists and OUTPUT jumps to it
        ensure_chain(&ip6t, "nat", "NAT_DPI")?;
        ensure_jump(&ip6t, "nat", "OUTPUT", "NAT_DPI", true)?;
    }

    // Keep loopback / localhost traffic at the very beginning to avoid feedback loops.
    ensure_loopback_returns_v6(&ip6t, nat_ok)?;

    if nat_ok {
        // If we previously had a non-NAT build (blocked IPv6 DNS), remove those block rules now.
        for proto in prots {
            let mp_rej = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "REJECT"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_rej)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_rej);
            }
            let mp_drop = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "DROP"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_drop)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_drop);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r_rej = ["-p", proto, "--dport", &ps, "-j", "REJECT"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_rej)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r_rej);
                }
                let r_drop = ["-p", proto, "--dport", &ps, "-j", "DROP"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_drop)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r_drop);
                }
            }
        }

        // 1) MANGLE exceptions: RETURN for DNS ports
        for proto in prots {
            let mp = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
            if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp)? {
                if add_rule(&ip6t, "mangle", "MANGLE_APP", true, &mp).is_ok() {
                    info!("dns6: mangle added multiport RETURN ({}) for {}", dports_multi, proto);
                } else {
                    for p in dns_ports {
                        let ps = p.to_string();
                        let r = ["-p", proto, "--dport", &ps, "-j", "RETURN"];
                        if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r)? {
                            let _ = add_rule(&ip6t, "mangle", "MANGLE_APP", true, &r)
                                .or_else(|_| add_rule(&ip6t, "mangle", "MANGLE_APP", false, &r));
                        }
                    }
                    info!("dns6: mangle multiport unavailable -> added per-port RETURN for {}", proto);
                }
            }
        }

        let to = format!("[::1]:{}", listen_port);

        // 2) remove possible global DNAT in nat OUTPUT for our listen port
        for p in dns_ports {
            let ps = p.to_string();
            for proto in prots {
                let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                if rule_exists(&ip6t, "nat", "OUTPUT", &r)? {
                    let _ = del_rule(&ip6t, "nat", "OUTPUT", &r);
                }
            }
        }

        // Also remove legacy DNAT rules in PREROUTING (we no longer use PREROUTING in dnscrypt).
        for proto in prots {
            let mp = [
                "-p",
                proto,
                "-m",
                "multiport",
                "--dports",
                dports_multi,
                "-j",
                "DNAT",
                "--to-destination",
                &to,
            ];
            if rule_exists(&ip6t, "nat", "PREROUTING", &mp)? {
                let _ = del_rule(&ip6t, "nat", "PREROUTING", &mp);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                if rule_exists(&ip6t, "nat", "PREROUTING", &r)? {
                    let _ = del_rule(&ip6t, "nat", "PREROUTING", &r);
                }
            }
        }

        // 3) add DNAT inside NAT_DPI only (no PREROUTING)
        for proto in prots {
            let mp = [
                "-p",
                proto,
                "-m",
                "multiport",
                "--dports",
                dports_multi,
                "-j",
                "DNAT",
                "--to-destination",
                &to,
            ];
            if !rule_exists(&ip6t, "nat", "NAT_DPI", &mp)? {
                if add_rule(&ip6t, "nat", "NAT_DPI", true, &mp).is_ok() {
                    info!(
                        "dns6: nat added multiport DNAT ({}) {} -> {} in NAT_DPI",
                        dports_multi, proto, to
                    );
                } else {
                    for p in dns_ports {
                        let ps = p.to_string();
                        let r = ["-p", proto, "--dport", &ps, "-j", "DNAT", "--to-destination", &to];
                        if !rule_exists(&ip6t, "nat", "NAT_DPI", &r)? {
                            let _ = add_rule(&ip6t, "nat", "NAT_DPI", true, &r)
                                .or_else(|_| add_rule(&ip6t, "nat", "NAT_DPI", false, &r));
                        }
                    }
                    info!(
                        "dns6: nat multiport unavailable -> added per-port DNAT for {} in NAT_DPI",
                        proto
                    );
                }
            }
        }
    } else {
        warn!("dns: ip6tables nat table unsupported; disabling IPv6 DNS (53/853)");
        crate::logging::user_warn("DNSCrypt: IPv6 NAT не поддерживается — IPv6 DNS (53/853) отключён");

        // Remove RETURN exceptions for 53/853 (otherwise they would bypass the block).
        for proto in prots {
            let mp_ret = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "RETURN"];
            if rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_ret)? {
                let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &mp_ret);
            }
            for p in dns_ports {
                let ps = p.to_string();
                let r = ["-p", proto, "--dport", &ps, "-j", "RETURN"];
                if rule_exists(&ip6t, "mangle", "MANGLE_APP", &r)? {
                    let _ = del_rule(&ip6t, "mangle", "MANGLE_APP", &r);
                }
            }
        }

        // Add blocking rules at position 3 (after loopback RETURN rules).
        for proto in prots {
            let mp_rej = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "REJECT"];
            let mp_drop = ["-p", proto, "-m", "multiport", "--dports", dports_multi, "-j", "DROP"];

            if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_rej)? && !rule_exists(&ip6t, "mangle", "MANGLE_APP", &mp_drop)? {
                // try multiport first
                let res = add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &mp_rej)
                    .or_else(|_| add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &mp_drop));

                if res.is_ok() {
                    info!("dns6: blocked IPv6 DNS {} ports {}", proto, dports_multi);
                } else {
                    // fallback to per-port
                    for p in dns_ports {
                        let ps = p.to_string();
                        let r_rej = ["-p", proto, "--dport", &ps, "-j", "REJECT"];
                        let r_drop = ["-p", proto, "--dport", &ps, "-j", "DROP"];
                        if !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_rej)? && !rule_exists(&ip6t, "mangle", "MANGLE_APP", &r_drop)? {
                            let _ = add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_rej)
                                .or_else(|_| add_rule_pos(&ip6t, "mangle", "MANGLE_APP", 3, &r_drop));
                        }
                    }
                    info!("dns6: blocked IPv6 DNS {} ports (per-port)", proto);
                }
            }
        }
    }

    ensure_loopback_returns_v6(&ip6t, nat_ok)?;
    Ok(())
}

fn ensure_chain(iptables: &str, table: &str, chain: &str) -> Result<()> {
    let (code, _) = shell::run(iptables, &["-t", table, "-nL", chain], Capture::Stdout)
        .with_context(|| format!("iptables list chain {} {}", table, chain))?;
    if code != 0 {
        let (c2, _) = shell::run(iptables, &["-t", table, "-N", chain], Capture::Stdout)
            .with_context(|| format!("iptables create chain {} {}", table, chain))?;
        if c2 != 0 {
            anyhow::bail!("failed to create chain {} in table {}", chain, table);
        }
    }
    Ok(())
}

fn ensure_jump(iptables: &str, table: &str, from_chain: &str, to_chain: &str, insert_first: bool) -> Result<()> {
    let (code, _) = shell::run(iptables, &["-t", table, "-C", from_chain, "-j", to_chain], Capture::Stdout)
        .unwrap_or((1, String::new()));
    if code == 0 {
        return Ok(());
    }
    let args: Vec<&str> = if insert_first {
        vec!["-t", table, "-I", from_chain, "1", "-j", to_chain]
    } else {
        vec!["-t", table, "-A", from_chain, "-j", to_chain]
    };
    let (c2, _) = shell::run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add jump {}:{} -> {}", table, from_chain, to_chain))?;
    if c2 != 0 {
        anyhow::bail!("failed to add jump {}:{} -> {}", table, from_chain, to_chain);
    }
    Ok(())
}

fn rule_exists(iptables: &str, table: &str, chain: &str, rule: &[&str]) -> Result<bool> {
    let mut args = vec!["-t", table, "-C", chain];
    args.extend_from_slice(rule);
    let (code, _) = shell::run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables -C {} {}", table, chain))?;
    Ok(code == 0)
}

fn add_rule(iptables: &str, table: &str, chain: &str, insert_first: bool, rule: &[&str]) -> Result<()> {
    let mut args = vec!["-t", table, if insert_first { "-I" } else { "-A" }, chain];
    if insert_first {
        args.push("1");
    }
    args.extend_from_slice(rule);
    let (code, _) = shell::run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add rule {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables add rule failed {} {}", table, chain);
    }
    Ok(())
}

fn add_rule_pos(iptables: &str, table: &str, chain: &str, pos: usize, rule: &[&str]) -> Result<()> {
    let pos_s = pos.to_string();
    let mut args = vec!["-t", table, "-I", chain, &pos_s];
    args.extend_from_slice(rule);
    let (code, _) = shell::run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables add rule(pos) {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables add rule(pos) failed {} {}", table, chain);
    }
    Ok(())
}

fn del_rule(iptables: &str, table: &str, chain: &str, rule: &[&str]) -> Result<()> {
    let mut args = vec!["-t", table, "-D", chain];
    args.extend_from_slice(rule);
    let (code, _) = shell::run(iptables, &args, Capture::Stdout)
        .with_context(|| format!("iptables del rule {} {}", table, chain))?;
    if code != 0 {
        anyhow::bail!("iptables del rule failed {} {}", table, chain);
    }
    Ok(())
}

fn ensure_loopback_returns(iptables: &str) -> Result<()> {
    // Keep loopback / localhost traffic at the very beginning to avoid feedback loops.
    let mut del_all = |args: &[&str]| {
        loop {
            let (c, _) = shell::run(iptables, args, Capture::Stdout)
                .unwrap_or((1, String::new()));
            if c != 0 { break; }
        }
    };

    // nat: NAT_DPI
    del_all(&["-t", "nat", "-D", "NAT_DPI", "-d", "127.0.0.1", "-j", "RETURN"]); // legacy
    del_all(&["-t", "nat", "-D", "NAT_DPI", "-o", "lo", "-j", "RETURN"]);
    del_all(&["-t", "nat", "-D", "NAT_DPI", "-d", "127.0.0.0/8", "-j", "RETURN"]);
    let _ = shell::run(
        iptables,
        &["-t", "nat", "-I", "NAT_DPI", "1", "-o", "lo", "-j", "RETURN"],
        Capture::Stdout,
    );
    let _ = shell::run(
        iptables,
        &["-t", "nat", "-I", "NAT_DPI", "2", "-d", "127.0.0.0/8", "-j", "RETURN"],
        Capture::Stdout,
    );

    // mangle: MANGLE_APP
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-d", "127.0.0.1", "-j", "RETURN"]); // legacy
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-o", "lo", "-j", "RETURN"]);
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-d", "127.0.0.0/8", "-j", "RETURN"]);
    let _ = shell::run(
        iptables,
        &["-t", "mangle", "-I", "MANGLE_APP", "1", "-o", "lo", "-j", "RETURN"],
        Capture::Stdout,
    );
    let _ = shell::run(
        iptables,
        &["-t", "mangle", "-I", "MANGLE_APP", "2", "-d", "127.0.0.0/8", "-j", "RETURN"],
        Capture::Stdout,
    );

    Ok(())
}

fn ensure_loopback_returns_v6(ip6tables: &str, nat_ok: bool) -> Result<()> {
    // Keep loopback / localhost traffic at the very beginning to avoid feedback loops.
    let mut del_all = |args: &[&str]| {
        loop {
            let (c, _) = shell::run(ip6tables, args, Capture::Stdout).unwrap_or((1, String::new()));
            if c != 0 {
                break;
            }
        }
    };

    if nat_ok {
        // nat: NAT_DPI
        del_all(&["-t", "nat", "-D", "NAT_DPI", "-d", "::1", "-j", "RETURN"]); // legacy
        del_all(&["-t", "nat", "-D", "NAT_DPI", "-o", "lo", "-j", "RETURN"]);
        del_all(&["-t", "nat", "-D", "NAT_DPI", "-d", "::1/128", "-j", "RETURN"]);
        let _ = shell::run(
            ip6tables,
            &["-t", "nat", "-I", "NAT_DPI", "1", "-o", "lo", "-j", "RETURN"],
            Capture::Stdout,
        );
        let _ = shell::run(
            ip6tables,
            &["-t", "nat", "-I", "NAT_DPI", "2", "-d", "::1/128", "-j", "RETURN"],
            Capture::Stdout,
        );
    }

    // mangle: MANGLE_APP
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-d", "::1", "-j", "RETURN"]); // legacy
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-o", "lo", "-j", "RETURN"]);
    del_all(&["-t", "mangle", "-D", "MANGLE_APP", "-d", "::1/128", "-j", "RETURN"]);
    let _ = shell::run(
        ip6tables,
        &["-t", "mangle", "-I", "MANGLE_APP", "1", "-o", "lo", "-j", "RETURN"],
        Capture::Stdout,
    );
    let _ = shell::run(
        ip6tables,
        &["-t", "mangle", "-I", "MANGLE_APP", "2", "-d", "::1/128", "-j", "RETURN"],
        Capture::Stdout,
    );

    Ok(())
}


fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T> {
    let s = std::fs::read_to_string(path)
        .with_context(|| format!("read {}", path.display()))?;
    let v = serde_json::from_str::<T>(&s)
        .with_context(|| format!("parse json {}", path.display()))?;
    Ok(v)
}

fn ensure_dir(p: &str) -> Result<()> {
    let path = Path::new(p);
    if !path.is_dir() {
        anyhow::bail!("directory missing: {}", path.display());
    }
    Ok(())
}
