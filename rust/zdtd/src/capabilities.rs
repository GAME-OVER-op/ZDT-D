use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    os::unix::fs::PermissionsExt,
    path::Path,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use crate::shell::{self, Capture};

const SHORT_TIMEOUT: Duration = Duration::from_secs(2);
const PACKAGE_TIMEOUT: Duration = Duration::from_secs(5);
const NDC_TIMEOUT: Duration = Duration::from_secs(3);

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::from_secs(0))
        .as_secs()
}

fn run_ok(cmd: &str, args: &[&str], timeout: Duration) -> bool {
    shell::run_timeout(cmd, args, Capture::None, timeout)
        .map(|(code, _)| code == 0)
        .unwrap_or(false)
}

fn run_ok_output_nonempty(cmd: &str, args: &[&str], timeout: Duration) -> bool {
    shell::run_timeout(cmd, args, Capture::Stdout, timeout)
        .map(|(code, out)| code == 0 && !out.trim().is_empty())
        .unwrap_or(false)
}

fn help_check(cmd: &str, args: &[&str], needle: &str) -> bool {
    shell::run_timeout(cmd, args, Capture::Both, SHORT_TIMEOUT)
        .map(|(code, out)| {
            let lower = out.to_ascii_lowercase();
            code == 0
                && !lower.contains("couldn't load")
                && !lower.contains("can't initialize")
                && !lower.contains("no chain/target/match")
                && !lower.contains("unknown option")
                && !lower.contains("unknown arg")
                && (needle.is_empty() || lower.contains(&needle.to_ascii_lowercase()))
        })
        .unwrap_or(false)
}

fn get_text(cmd: &str, args: &[&str], timeout: Duration) -> Option<String> {
    shell::run_timeout(cmd, args, Capture::Stdout, timeout)
        .ok()
        .and_then(|(code, out)| if code == 0 { Some(out.trim().to_string()) } else { None })
        .filter(|s| !s.is_empty())
}

fn binary_available(path: &str) -> bool {
    let p = Path::new(path);
    let Ok(meta) = p.metadata() else {
        return false;
    };
    meta.is_file() && (meta.permissions().mode() & 0o111 != 0)
}

fn binary_map() -> BTreeMap<&'static str, bool> {
    let mut out = BTreeMap::new();
    out.insert("dnscrypt", binary_available("/data/adb/modules/ZDT-D/bin/dnscrypt"));
    out.insert("t2s", binary_available("/data/adb/modules/ZDT-D/bin/t2s"));
    out.insert("sing-box", binary_available("/data/adb/modules/ZDT-D/bin/sing-box"));
    out.insert("wireproxy", binary_available("/data/adb/modules/ZDT-D/bin/wireproxy"));
    out.insert("tun2socks", binary_available("/data/adb/modules/ZDT-D/bin/tun2socks"));
    out.insert("openvpn", binary_available("/data/adb/modules/ZDT-D/bin/openvpn"));
    out.insert("amneziawg-go", binary_available("/data/adb/modules/ZDT-D/bin/amneziawg-go"));
    out.insert("awg", binary_available("/data/adb/modules/ZDT-D/bin/awg"));
    out.insert("mihomo", binary_available("/data/adb/modules/ZDT-D/bin/mihomo"));
    out.insert("mieru", binary_available("/data/adb/modules/ZDT-D/bin/mieru"));
    out.insert("nfqws", binary_available("/data/adb/modules/ZDT-D/bin/nfqws"));
    out.insert("nfqws2", binary_available("/data/adb/modules/ZDT-D/bin/nfqws2"));
    out.insert("dpitunnel-cli", binary_available("/data/adb/modules/ZDT-D/bin/dpitunnel-cli"));
    out.insert("torproxy", binary_available("/data/adb/modules/ZDT-D/bin/torproxy"));
    out.insert("lyrebird", binary_available("/data/adb/modules/ZDT-D/bin/lyrebird"));
    out
}

/// Collect a read-only capability report.
///
/// This function intentionally does not create iptables chains, ip rules,
/// cache files, or any other persistent state. It only runs read-only/list/help
/// probes and returns the result for the current API request.
pub fn collect() -> Value {
    let mut warnings: Vec<String> = Vec::new();

    let iptables_available = run_ok("iptables", &["--version"], SHORT_TIMEOUT)
        || run_ok("iptables", &["-L", "-n"], SHORT_TIMEOUT);
    let iptables_nat_output = run_ok("iptables", &["-t", "nat", "-L", "OUTPUT", "-n"], SHORT_TIMEOUT);
    let iptables_mangle_output = run_ok("iptables", &["-t", "mangle", "-L", "OUTPUT", "-n"], SHORT_TIMEOUT);
    let iptables_save_nat = run_ok("iptables-save", &["-t", "nat"], SHORT_TIMEOUT);
    let iptables_save_mangle = run_ok("iptables-save", &["-t", "mangle"], SHORT_TIMEOUT);

    let ip6tables_available = run_ok("ip6tables", &["--version"], SHORT_TIMEOUT)
        || run_ok("ip6tables", &["-L", "-n"], SHORT_TIMEOUT);
    let ip6tables_mangle_output = run_ok("ip6tables", &["-t", "mangle", "-L", "OUTPUT", "-n"], SHORT_TIMEOUT);
    let ip6tables_save_mangle = run_ok("ip6tables-save", &["-t", "mangle"], SHORT_TIMEOUT);

    let owner_match = help_check("iptables", &["-m", "owner", "-h"], "owner");
    let multiport_v4 = help_check("iptables", &["-m", "multiport", "-h"], "multiport");
    let multiport_v6 = help_check("ip6tables", &["-m", "multiport", "-h"], "multiport");

    let dnat = help_check("iptables", &["-j", "DNAT", "-h"], "dnat");
    let redirect = help_check("iptables", &["-j", "REDIRECT", "-h"], "redirect");
    let nfqueue = help_check("iptables", &["-j", "NFQUEUE", "-h"], "nfqueue");
    let mark = help_check("iptables", &["-j", "MARK", "-h"], "mark");
    let tproxy = help_check("iptables", &["-j", "TPROXY", "-h"], "tproxy");
    let tproxy_disabled_flag = Path::new(crate::settings::SETTING_DIR).join("tproxy_no").is_file();
    let tproxy_disabled_reason = if tproxy_disabled_flag {
        std::fs::read_to_string(Path::new(crate::settings::SETTING_DIR).join("tproxy_no"))
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    } else {
        None
    };

    let ip_rule = run_ok("ip", &["rule", "show"], SHORT_TIMEOUT);
    let ip_route_table_all = run_ok("ip", &["route", "show", "table", "all"], SHORT_TIMEOUT);

    let package_uid_lookup = run_ok_output_nonempty("cmd", &["package", "list", "packages", "-U"], PACKAGE_TIMEOUT);
    let package_uid_lookup_shell = if package_uid_lookup {
        false
    } else {
        run_ok_output_nonempty("su", &["-lp", "2000", "-c", "cmd package list packages -U"], PACKAGE_TIMEOUT)
    };
    let ndc_network_list = run_ok("ndc", &["network", "list"], NDC_TIMEOUT);
    let selinux = get_text("getenforce", &[], SHORT_TIMEOUT)
        .map(|s| s.to_ascii_lowercase())
        .unwrap_or_else(|| "unknown".to_string());

    let binaries = binary_map();

    let nat_redirect_routing = iptables_available && iptables_nat_output && owner_match && dnat;
    let nfqueue_routing = iptables_available && iptables_mangle_output && owner_match && nfqueue;
    let vpn_netd_routing = ndc_network_list && ip_rule && (package_uid_lookup || package_uid_lookup_shell);
    let tproxy_experimental = !tproxy_disabled_flag && iptables_available && iptables_mangle_output && owner_match && mark && tproxy && ip_rule && ip_route_table_all;

    if !iptables_available {
        warnings.push("iptables is not available".to_string());
    }
    if iptables_available && !owner_match {
        warnings.push("iptables owner match was not found by readonly check".to_string());
    }
    if iptables_available && !dnat {
        warnings.push("DNAT target was not found by readonly check".to_string());
    }
    if iptables_available && !nfqueue {
        warnings.push("NFQUEUE target was not found by readonly check".to_string());
    }
    if iptables_available && !tproxy {
        warnings.push("TPROXY target was not found by readonly check".to_string());
    }
    if ip6tables_available && !multiport_v6 {
        warnings.push("ip6tables multiport was not found by readonly check".to_string());
    }
    if !ndc_network_list {
        warnings.push("ndc network list is not available".to_string());
    }
    if !(package_uid_lookup || package_uid_lookup_shell) {
        warnings.push("fast package UID lookup is not available".to_string());
    }

    json!({
        "ok": true,
        "schema_version": 1,
        "generated_at": now_unix(),
        "scan_mode": "readonly",
        "note": "Read-only capability report. No test chains, ip rules, cache files, or persistent state are created.",
        "iptables": {
            "available": iptables_available,
            "nat_output": iptables_nat_output,
            "mangle_output": iptables_mangle_output,
            "save_nat": iptables_save_nat,
            "save_mangle": iptables_save_mangle
        },
        "ip6tables": {
            "available": ip6tables_available,
            "mangle_output": ip6tables_mangle_output,
            "save_mangle": ip6tables_save_mangle
        },
        "matches": {
            "owner": owner_match,
            "multiport_v4": multiport_v4,
            "multiport_v6": multiport_v6
        },
        "targets": {
            "DNAT": dnat,
            "REDIRECT": redirect,
            "NFQUEUE": nfqueue,
            "MARK": mark,
            "TPROXY": tproxy
        },
        "tproxy": {
            "priority_backend": true,
            "fallback_backend": "DNAT",
            "disabled_by_flag": tproxy_disabled_flag,
            "disabled_reason": tproxy_disabled_reason,
            "mark_prefix": "0x5d700000/0xffff0000",
            "route_table": 1057
        },
        "routing": {
            "ip_rule": ip_rule,
            "ip_route_table_all": ip_route_table_all
        },
        "android": {
            "package_uid_lookup": package_uid_lookup,
            "package_uid_lookup_shell_fallback": package_uid_lookup_shell,
            "netd": ndc_network_list,
            "ndc_network_list": ndc_network_list,
            "selinux": selinux
        },
        "binaries": binaries,
        "features": {
            "nat_redirect_routing": nat_redirect_routing,
            "nfqueue_routing": nfqueue_routing,
            "vpn_netd_routing": vpn_netd_routing,
            "tproxy_experimental": tproxy_experimental
        },
        "warnings": warnings
    })
}
