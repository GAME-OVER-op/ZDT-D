use log::{warn};

use crate::{common::process::shell::{self, Capture}};

use std::sync::atomic::{AtomicBool, Ordering};

use super::config::*;

pub(super) static DNSCRYPT_STOP_REQUESTED: AtomicBool = AtomicBool::new(false);

pub fn request_stop() {
    DNSCRYPT_STOP_REQUESTED.store(true, Ordering::SeqCst);
}

pub fn reset_stop() {
    DNSCRYPT_STOP_REQUESTED.store(false, Ordering::SeqCst);
}

pub(super) fn stop_requested() -> bool {
    DNSCRYPT_STOP_REQUESTED.load(Ordering::SeqCst)
}

pub(super) fn set_ipv6_disabled_resetprops() {
    let pairs = [
        ("net.ipv6.conf.all.accept_redirects", "0"),
        ("net.ipv6.conf.all.disable_ipv6", "1"),
        ("net.ipv6.conf.default.accept_redirects", "0"),
        ("net.ipv6.conf.default.disable_ipv6", "1"),
    ];
    for (k, v) in pairs {
        match shell::run("resetprop", &[k, v], Capture::Stdout) {
            Ok((0, _)) => {}
            Ok((code, out)) => warn!("dnscrypt: resetprop set {}={} failed with code {}: {}", k, v, code, out.trim()),
            Err(e) => warn!("dnscrypt: resetprop set {}={} failed: {e:#}", k, v),
        }
    }
}

pub fn clear_ipv6_resetprops() {
    for k in IPV6_RESETPROP_KEYS {
        match shell::run("resetprop", &[k], Capture::Stdout) {
            Ok((0, _)) => {}
            Ok((code, out)) => warn!("dnscrypt: resetprop clear {} failed with code {}: {}", k, code, out.trim()),
            Err(e) => warn!("dnscrypt: resetprop clear {} failed: {e:#}", k),
        }
    }
}

