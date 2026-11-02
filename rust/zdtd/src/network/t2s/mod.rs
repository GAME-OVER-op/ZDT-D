pub const PROCESS_NAMES: &[&str] = &["t2s", "t2s.bin"];

mod profile;
mod routing;
mod spawn;
mod tun2socks;

pub use profile::*;
pub use routing::*;
pub use spawn::*;
pub use tun2socks::*;
