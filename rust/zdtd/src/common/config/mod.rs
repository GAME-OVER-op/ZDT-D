mod boolish;
mod list;
mod args;
mod validation;

pub use args::*;
pub use validation::*;

pub mod mihomo;

pub use boolish::deserialize_boolish;

pub use list::split_comma_whitespace;
