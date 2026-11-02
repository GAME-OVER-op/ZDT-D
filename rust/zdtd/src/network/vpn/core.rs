use anyhow::{bail, Result};
use std::collections::BTreeMap;

use crate::common::{network::stable_netid, profile::ActiveProfiles};
use crate::programs::registry::ProgramId;

#[derive(Debug, Clone, Copy)]
pub struct VpnProfileCore {
    owner_program: ProgramId,
    netid_base: u32,
    netid_max: u32,
}

impl VpnProfileCore {
    pub const fn new(owner_program: ProgramId, netid_range: (u32, u32)) -> Self {
        Self {
            owner_program,
            netid_base: netid_range.0,
            netid_max: netid_range.1,
        }
    }

    pub fn program_id(self) -> ProgramId {
        self.owner_program
    }

    pub fn owner_program(self) -> &'static str {
        self.owner_program.canonical()
    }

    pub fn enabled_profile_names(self, active: &ActiveProfiles) -> Vec<String> {
        active
            .profiles
            .iter()
            .filter(|(_, state)| state.enabled)
            .map(|(name, _)| name.clone())
            .collect()
    }

    pub fn stable_netid(self, active: &ActiveProfiles, profile: &str) -> Result<u32> {
        let all_profiles = active.profiles.keys().cloned().collect::<Vec<_>>();
        stable_netid(self.netid_base, self.netid_max, &all_profiles, profile)
    }

    pub fn claim(self, profile: &str, value: impl Into<String>) -> (String, String) {
        (format!("{}/{}", self.owner_program.canonical(), profile), value.into())
    }

    pub fn validate_unique_tuns<'a, I>(self, profiles: I) -> Result<()>
    where
        I: IntoIterator<Item = (&'a str, &'a str)>,
    {
        let mut seen = BTreeMap::<String, String>::new();
        for (name, tun) in profiles {
            if let Some(other) = seen.insert(tun.to_string(), name.to_string()) {
                bail!(
                    "{} tun conflict: tun {} is used by enabled profiles {} and {}",
                    self.owner_program.canonical(),
                    tun,
                    other,
                    name
                );
            }
        }
        Ok(())
    }

    pub fn generated_tun_addr_and_cidr(self, network_base: u32, netid: u32) -> Result<(String, String)> {
        if !(self.netid_base..=self.netid_max).contains(&netid) {
            bail!("{} netid out of generated range: {netid}", self.owner_program.canonical());
        }
        let (address, cidr, _) = crate::common::network::generated_ipv4_30(
            network_base,
            netid - self.netid_base,
            self.owner_program.canonical(),
        )?;
        Ok((address, cidr))
    }
}
