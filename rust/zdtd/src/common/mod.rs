//! Shared building blocks used across ZDT-D program backends.
//!
//! Keep this module as a thin namespace layer. Domain-owned program, VPN and
//! T2S logic lives under `programs/` and `network/`.

pub mod apps;
pub mod components;
pub mod config;
pub mod fs;
pub mod network;
pub mod process;
pub mod profile;
