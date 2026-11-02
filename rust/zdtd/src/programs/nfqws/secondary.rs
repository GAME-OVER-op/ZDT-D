use anyhow::Result;

use super::engine::{self, NfqwsVariant};

const VARIANT: NfqwsVariant = NfqwsVariant::new(
    "/data/adb/modules/ZDT-D/working_folder/nfqws2",
    "/data/adb/modules/ZDT-D/bin/nfqws2",
    "nfqws2",
    "zapret2",
);

pub fn start_active_profiles() -> Result<()> {
    engine::start_active_profiles(&VARIANT)
}
