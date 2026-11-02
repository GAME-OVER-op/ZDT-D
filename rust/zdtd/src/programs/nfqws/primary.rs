use anyhow::Result;

use super::engine::{self, NfqwsVariant};

const VARIANT: NfqwsVariant = NfqwsVariant::new(
    "/data/adb/modules/ZDT-D/working_folder/nfqws",
    "/data/adb/modules/ZDT-D/bin/nfqws",
    "nfqws",
    "zapret",
);

pub fn start_active_profiles() -> Result<()> {
    engine::start_active_profiles(&VARIANT)
}
