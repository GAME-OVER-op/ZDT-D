//! Experimental TPROXY routing backend for ZDT-D.
//!
//! # Status
//!
//! **NOT READY / NOT WORKING / DO NOT USE AS PRODUCTION ROUTING.**
//!
//! This file is intentionally kept as a full, connected module instead of a
//! deleted experiment. The goal is to preserve everything learned during the
//! first TPROXY implementation so the routing can be fixed later without
//! starting from zero.
//!
//! Current production routing must continue to use `iptables_port.rs` DNAT:
//!
//! ```text
//! Android app UID -> nat OUTPUT/NAT_DPI -> ZDTN_* -> DNAT 127.0.0.1:<t2s/redir port>
//! ```
//!
//! This module is connected to the `tproxy_enabled` setting only as an
//! experimental warning path. When `tproxy_enabled=true`, ZDT-D logs this
//! module's warning and then continues with the normal DNAT backend.
//!
//! # What was tested
//!
//! A previous experimental backend tried this path:
//!
//! ```text
//! mangle OUTPUT
//!   -> owner UID match
//!   -> MARK --set-xmark <scope_mark>/0xfff00000
//!   -> ip rule fwmark 0x50000000/0xf0000000 lookup 1057
//!   -> ip route table 1057: local default dev lo
//!   -> mangle PREROUTING -i lo
//!   -> TPROXY --on-port <port>
//! ```
//!
//! The packet counters showed that this path could be reached:
//!
//! - OUTPUT mark counters grew.
//! - PREROUTING counters grew.
//! - Scoped TPROXY counters grew.
//! - Route table 1057 resolved marks to `local dev lo`.
//!
//! But traffic still did not become usable in real programs. Because of that,
//! this backend is intentionally disabled until the design is corrected.
//!
//! # Known problems from testing
//!
//! 1. **DNAT/t2s ports are not TPROXY ports.**
//!    Old ports like `10000`, `12348`, `12350`, `12351` were ordinary local
//!    TCP listeners used by t2s/DNAT. They cannot be blindly reused as TPROXY
//!    targets. TPROXY needs a real transparent inbound, e.g. mihomo
//!    `tproxy-port` or sing-box `type = "tproxy"` inbound.
//!
//! 2. **A separate TPROXY port is required.**
//!    The implementation needs a dedicated setting such as:
//!
//!    ```json
//!    "tproxy_port": 17891
//!    ```
//!
//!    and the TPROXY target should use that port, not `dest_port` from the
//!    DNAT/t2s pipeline.
//!
//! 3. **`--on-ip` matters.**
//!    mihomo in tests listened on `127.0.0.1:<tproxy_port>`. Rules using
//!    `--on-ip 0.0.0.0` reached the TPROXY target counters, but traffic still
//!    failed. `--on-ip 127.0.0.1` also needs careful validation. The correct
//!    value may depend on how the transparent socket is bound.
//!
//! 4. **DIVERT can conflict with per-scope marks.**
//!    A DIVERT hook like:
//!
//!    ```text
//!    PREROUTING -p tcp -m socket -j ZDT_TPROXY_DIVERT
//!    ```
//!
//!    before scoped TPROXY rules can accept packets early and replace routing
//!    metadata with a generic mark. For a future per-scope design, either avoid
//!    DIVERT initially or place/design it so it cannot bypass scoped routing.
//!
//! 5. **Do not return early from PREROUTING for 127/8.**
//!    The local OUTPUT -> policy route -> lo -> PREROUTING path may present
//!    packets in a way where a rule like:
//!
//!    ```text
//!    ZDT_TPROXY_PRE -d 127.0.0.0/8 -j RETURN
//!    ```
//!
//!    skips packets that TPROXY still needs to see. This rule caused counters
//!    to grow heavily during tests and must not be placed before scoped TPROXY.
//!
//! 6. **Do not overwrite Android fwmark low bits.**
//!    Android uses low fwmark bits for netId/permission/VPN routing. Full
//!    `--set-mark` or `/0xffffffff` masks can break system routing. Use high
//!    bits only, for example:
//!
//!    ```text
//!    route mark: 0x50000000/0xf0000000
//!    scope mark: 0x5xx00000/0xfff00000
//!    MARK --set-xmark <scope>/0xfff00000
//!    ```
//!
//! 7. **UDP is not solved.**
//!    The old experiment was mostly TCP-oriented. UDP needs separate testing,
//!    socket support, and receiver support.
//!
//! 8. **Cleanup must be isolated from DNAT.**
//!    DNAT and TPROXY must never delete each other's chains during apply. TPROXY
//!    cleanup can remove only TPROXY-owned chains/rules and policy routes.
//!
//! 9. **Runtime refresh must not restore broken TPROXY automatically.**
//!    Until this module is fixed, UID refresh should restore DNAT only. A stale
//!    `Tproxy` snapshot should log a warning and fall back to DNAT behavior.
//!
//! 10. **Receiver must be verified independently.**
//!     Before enabling ZDT-D TPROXY, run a minimal manual rule against a known
//!     working mihomo/sing-box TPROXY inbound and confirm packets are accepted.
//!
//! # Future implementation sketch
//!
//! A future working implementation should probably do this:
//!
//! ```text
//! settings:
//!   tproxy_enabled: bool
//!   tproxy_port: u16
//!
//! receiver:
//!   mihomo: tproxy-port: <tproxy_port>
//!   or sing-box inbound: { type: "tproxy", listen: "::", listen_port: <tproxy_port> }
//!
//! mangle OUTPUT:
//!   skip lo and proxy process UID
//!   match selected app UIDs
//!   MARK --set-xmark <scope_mark>/0xfff00000
//!
//! policy routing:
//!   ip rule add pref 9999 fwmark 0x50000000/0xf0000000 lookup 1057
//!   ip route replace local 0.0.0.0/0 dev lo table 1057
//!
//! mangle PREROUTING:
//!   scoped rules before any generic return/divert
//!   -i lo -m mark --mark <scope_mark>/0xfff00000 -p tcp \
//!     -j TPROXY --on-ip <verified_ip> --on-port <tproxy_port> \
//!     --tproxy-mark <verified_mark>/<verified_mask>
//! ```
//!
//! Only after that design is proven should `iptables_port.rs` switch from
//! warning-only mode to a real TPROXY apply path.

use anyhow::Result;
use log::warn;
use std::{path::Path, time::Duration};

use crate::{shell::Capture, xtables_lock};

use super::iptables_port::{DpiTunnelOptions, ProtoChoice};

const IPT_CMD_TIMEOUT: Duration = Duration::from_secs(5);
const XT_WAIT_SECS: &str = "5";

const OUT_CHAIN: &str = "ZDT_TPROXY_OUT";
const PRE_CHAIN: &str = "ZDT_TPROXY_PRE";
const DIVERT_CHAIN: &str = "ZDT_TPROXY_DIVERT";
const ROUTE_TABLE: u32 = 1057;
const ROUTE_PREF: &str = "9999";
const ROUTE_MARK: &str = "0x50000000/0xf0000000";
const LEGACY_ROUTE_MARK: &str = "0x5d700000/0xffff0000";

fn ipt_run_timeout(args: &[&str], capture: Capture, timeout: Duration) -> Result<(i32, String)> {
    let mut a: Vec<&str> = Vec::with_capacity(args.len() + 2);
    a.push("-w");
    a.push(XT_WAIT_SECS);
    a.extend_from_slice(args);
    xtables_lock::run_timeout_retry("iptables", &a, capture, timeout)
}

/// Warning-only TPROXY apply hook.
///
/// This function is deliberately shaped like a real backend entry point so it
/// can be replaced later without changing call sites. For now it applies no
/// routing rules and returns `Ok(())`; the caller must continue with DNAT.
///
/// Parameters are accepted and logged to make it obvious which DNAT route would
/// have been considered for TPROXY.
pub fn apply(
    uid_file: &Path,
    dest_port: u16,
    proto_choice: ProtoChoice,
    ifaces_raw: Option<&str>,
    opt: &DpiTunnelOptions,
) -> Result<()> {
    warn!(
        "TPROXY: tproxy_enabled=true, but TPROXY backend is documented as not working; \
         skipping TPROXY and continuing with DNAT. uid_file={} dest_port={} proto={:?} ifaces={} port_preference={} dpi_ports='{}'",
        uid_file.display(),
        dest_port,
        proto_choice,
        ifaces_raw.unwrap_or(""),
        opt.port_preference,
        opt.dpi_ports,
    );
    Ok(())
}

/// Clean only old experimental TPROXY state.
///
/// This cleanup is safe to call during service stop because it targets only
/// `ZDT_TPROXY_*`, `ZDTP_*`, `ZDTPP_*`, route table 1057, and known TPROXY
/// fwmark rules. It must not touch DNAT/NAT_DPI/ZDTN_* state.
pub fn cleanup_all() -> Result<()> {
    let _xtables_guard = xtables_lock::lock();

    // Remove hooks. Ignore failures because hooks/chains may not exist.
    for _ in 0..32 {
        let mut removed = false;
        for rule in [
            vec!["-t", "mangle", "-D", "OUTPUT", "-j", OUT_CHAIN],
            vec!["-t", "mangle", "-D", "PREROUTING", "-p", "tcp", "-m", "socket", "-j", DIVERT_CHAIN],
            vec!["-t", "mangle", "-D", "PREROUTING", "-p", "tcp", "-m", "socket", "--transparent", "-j", DIVERT_CHAIN],
            vec!["-t", "mangle", "-D", "PREROUTING", "-j", PRE_CHAIN],
        ] {
            if let Ok((0, _)) = ipt_run_timeout(&rule, Capture::Stderr, IPT_CMD_TIMEOUT) {
                removed = true;
            }
        }
        if !removed { break; }
    }

    // Flush base chains first so scoped chains can be deleted.
    for chain in [OUT_CHAIN, PRE_CHAIN, DIVERT_CHAIN] {
        let _ = ipt_run_timeout(&["-t", "mangle", "-F", chain], Capture::Stderr, IPT_CMD_TIMEOUT);
    }

    // Delete scoped chains left by previous experiments.
    for chain in list_mangle_chains_with_prefix("ZDTP")? {
        let _ = ipt_run_timeout(&["-t", "mangle", "-F", &chain], Capture::Stderr, IPT_CMD_TIMEOUT);
        let _ = ipt_run_timeout(&["-t", "mangle", "-X", &chain], Capture::Stderr, IPT_CMD_TIMEOUT);
    }

    for chain in [OUT_CHAIN, PRE_CHAIN, DIVERT_CHAIN] {
        let _ = ipt_run_timeout(&["-t", "mangle", "-X", chain], Capture::Stderr, IPT_CMD_TIMEOUT);
    }

    cleanup_policy_rules_best_effort();
    let _ = crate::shell::run("ip", &["route", "flush", "table", &ROUTE_TABLE.to_string()], Capture::Stderr);
    Ok(())
}

fn list_mangle_chains_with_prefix(prefix: &str) -> Result<Vec<String>> {
    let (rc, out) = ipt_run_timeout(&["-t", "mangle", "-S"], Capture::Stdout, IPT_CMD_TIMEOUT)?;
    if rc != 0 {
        return Ok(Vec::new());
    }
    let mut chains = Vec::new();
    for line in out.lines() {
        if let Some(rest) = line.strip_prefix("-N ") {
            let name = rest.split_whitespace().next().unwrap_or("");
            if name.starts_with(prefix) {
                chains.push(name.to_string());
            }
        }
    }
    chains.sort();
    chains.dedup();
    Ok(chains)
}

fn cleanup_policy_rules_best_effort() {
    for mark in [ROUTE_MARK, LEGACY_ROUTE_MARK] {
        for _ in 0..32 {
            match crate::shell::run(
                "ip",
                &["rule", "del", "fwmark", mark, "lookup", &ROUTE_TABLE.to_string()],
                Capture::Stderr,
            ) {
                Ok((0, _)) => {}
                _ => break,
            }
        }
    }
    let _ = crate::shell::run("ip", &["rule", "del", "pref", ROUTE_PREF], Capture::Stderr);
}
