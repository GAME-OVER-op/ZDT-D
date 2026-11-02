use anyhow::Result;
use std::collections::{BTreeMap, BTreeSet};

use crate::{
    common::network::cidrs_overlap,
    programs::registry::ProgramId,
    programs::{
        amneziawg, dnscrypt, hysteria2, mieru, mihomo, myvpn, openvpn, singbox,
        singbox::lx as singbox_lx, tgwsproxy, tun2socks,
    },
    settings::ApiSettings,
};

/// Short-lived snapshot of configuration decisions for one cold/normal startup attempt.
///
/// This is deliberately not global state. Live process status, runtime refreshes and API
/// mutations continue to read current state directly; only values that should remain coherent
/// throughout one start are captured here.
#[derive(Debug, Clone)]
pub struct RuntimePlan {
    api_settings: ApiSettings,
    hotspot_vpn_selection: Option<(String, String)>,
    vpn_netd_expected: BTreeSet<ProgramId>,
    sanity_backend_expected: BTreeSet<ProgramId>,
    vpn_tun_claims: Vec<(String, String)>,
    vpn_cidr_claims: Vec<(String, String)>,
    dnscrypt_expected: bool,
    tgwsproxy_expected: bool,
}

impl RuntimePlan {
    pub fn build_with_settings(api_settings: ApiSettings) -> Self {
        let hotspot_vpn_selection = api_settings
            .hotspot_vpn_selection()
            .map(|(program, profile)| (program.to_string(), profile.to_string()));

        // Keep the exact pre-refactor semantics for each expectation. In particular,
        // "has enabled profiles" and "requires netd" are intentionally different tests.
        let openvpn_enabled = openvpn::has_enabled_profiles();
        let openvpn_netd = openvpn::has_profiles_requiring_netd();
        let amneziawg_enabled = amneziawg::has_enabled_profiles();
        let amneziawg_netd = amneziawg::has_profiles_requiring_netd();
        let tun2socks_enabled = tun2socks::has_enabled_profiles();
        let myvpn_enabled = myvpn::has_enabled_profiles();
        let mihomo_enabled = mihomo::has_enabled_profiles();
        let mihomo_netd = mihomo::has_profiles_requiring_netd();
        let mieru_enabled = mieru::has_enabled_profiles();
        let mieru_netd = mieru::has_profiles_requiring_netd();
        let singbox_vpn = singbox::has_enabled_vpn_profiles();
        let singbox_lx_vpn = singbox_lx::has_enabled_vpn_profiles();
        let hysteria2_vpn = hysteria2::has_enabled_vpn_profiles();

        let mut vpn_netd_expected = BTreeSet::new();
        for (id, expected) in [
            (ProgramId::OpenVpn, openvpn_netd),
            (ProgramId::AmneziaWg, amneziawg_netd),
            (ProgramId::Tun2Socks, tun2socks_enabled),
            (ProgramId::MyVpn, myvpn_enabled),
            (ProgramId::Mihomo, mihomo_netd),
            (ProgramId::Mieru, mieru_netd),
            (ProgramId::SingBox, singbox_vpn),
            (ProgramId::SingBoxLx, singbox_lx_vpn),
            (ProgramId::Hysteria2, hysteria2_vpn),
        ] {
            if expected {
                vpn_netd_expected.insert(id);
            }
        }

        let mut sanity_backend_expected = BTreeSet::new();
        for (id, expected) in [
            (ProgramId::OpenVpn, openvpn_enabled),
            (ProgramId::AmneziaWg, amneziawg_enabled),
            (ProgramId::Tun2Socks, tun2socks_enabled),
            (ProgramId::MyVpn, myvpn_enabled),
            (ProgramId::Mihomo, mihomo_enabled),
            (ProgramId::Mieru, mieru_enabled),
            (ProgramId::SingBox, singbox_vpn),
            (ProgramId::SingBoxLx, singbox_lx_vpn),
            (ProgramId::Hysteria2, hysteria2_vpn),
        ] {
            if expected {
                sanity_backend_expected.insert(id);
            }
        }

        // Collect cross-backend claims exactly once, preserving the historical source order.
        let mut vpn_tun_claims = Vec::new();
        vpn_tun_claims.extend(openvpn::enabled_tun_claims());
        vpn_tun_claims.extend(amneziawg::enabled_tun_claims());
        vpn_tun_claims.extend(tun2socks::enabled_tun_claims());
        vpn_tun_claims.extend(myvpn::enabled_tun_claims());
        vpn_tun_claims.extend(mihomo::enabled_tun_claims_with_settings(&api_settings));
        vpn_tun_claims.extend(mieru::enabled_tun_claims_with_settings(&api_settings));
        vpn_tun_claims.extend(singbox::enabled_tun_claims());
        vpn_tun_claims.extend(singbox_lx::enabled_tun_claims());
        vpn_tun_claims.extend(hysteria2::enabled_tun_claims());

        // OpenVPN CIDRs are still intentionally absent, matching the previous validator.
        let mut vpn_cidr_claims = Vec::new();
        vpn_cidr_claims.extend(amneziawg::enabled_cidr_claims());
        vpn_cidr_claims.extend(tun2socks::enabled_cidr_claims());
        vpn_cidr_claims.extend(myvpn::enabled_cidr_claims());
        vpn_cidr_claims.extend(mihomo::enabled_cidr_claims_with_settings(&api_settings));
        vpn_cidr_claims.extend(mieru::enabled_cidr_claims_with_settings(&api_settings));
        vpn_cidr_claims.extend(singbox::enabled_cidr_claims());
        vpn_cidr_claims.extend(singbox_lx::enabled_cidr_claims());
        vpn_cidr_claims.extend(hysteria2::enabled_cidr_claims());

        Self {
            api_settings,
            hotspot_vpn_selection,
            vpn_netd_expected,
            sanity_backend_expected,
            vpn_tun_claims,
            vpn_cidr_claims,
            dnscrypt_expected: dnscrypt::active_listen_port().ok().flatten().is_some(),
            tgwsproxy_expected: tgwsproxy::load_effective_enabled()
                .map(|v| v.enabled)
                .unwrap_or(false),
        }
    }

    pub fn api_settings(&self) -> &ApiSettings {
        &self.api_settings
    }

    pub fn hotspot_vpn_selection(&self) -> Option<(&str, &str)> {
        self.hotspot_vpn_selection
            .as_ref()
            .map(|(program, profile)| (program.as_str(), profile.as_str()))
    }

    pub fn hotspot_vpn_expected(&self) -> bool {
        self.hotspot_vpn_selection.is_some()
    }

    pub fn vpn_expected(&self) -> bool {
        !self.vpn_netd_expected.is_empty() || self.hotspot_vpn_expected()
    }

    pub fn sanity_backend_expected(&self, id: ProgramId) -> bool {
        self.sanity_backend_expected.contains(&id)
    }

    pub fn dnscrypt_expected(&self) -> bool {
        self.dnscrypt_expected
    }

    pub fn tgwsproxy_expected(&self) -> bool {
        self.tgwsproxy_expected
    }

    pub fn validate_vpn_claims_unique(&self) -> Result<()> {
        let mut seen = BTreeMap::<String, String>::new();
        for (label, tun) in &self.vpn_tun_claims {
            if let Some(other) = seen.insert(tun.clone(), label.clone()) {
                anyhow::bail!("VPN tun conflict: tun {tun} is used by {other} and {label}");
            }
        }

        for i in 0..self.vpn_cidr_claims.len() {
            for j in (i + 1)..self.vpn_cidr_claims.len() {
                let left = &self.vpn_cidr_claims[i];
                let right = &self.vpn_cidr_claims[j];
                if cidrs_overlap(&left.1, &right.1)? {
                    anyhow::bail!(
                        "VPN CIDR conflict: {} {} overlaps with {} {}",
                        left.0,
                        left.1,
                        right.0,
                        right.1
                    );
                }
            }
        }
        Ok(())
    }
}
