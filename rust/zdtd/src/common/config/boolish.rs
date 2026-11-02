use serde::Deserialize;

/// Backward-compatible boolean deserializer used by legacy active/enabled JSON
/// files. Accepts bool, numeric 0/1 and string true/false/0/1, while still
/// rejecting every other value.
pub fn deserialize_boolish<'de, D>(deserializer: D) -> std::result::Result<bool, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{Error as _, Unexpected};
    let value = serde_json::Value::deserialize(deserializer)?;
    match value {
        serde_json::Value::Bool(b) => Ok(b),
        serde_json::Value::Number(n) => match n.as_i64() {
            Some(0) => Ok(false),
            Some(1) => Ok(true),
            _ => Err(D::Error::invalid_value(Unexpected::Other("number"), &"bool or 0/1")),
        },
        serde_json::Value::String(s) => match s.trim().to_ascii_lowercase().as_str() {
            "true" | "1" => Ok(true),
            "false" | "0" => Ok(false),
            _ => Err(D::Error::invalid_value(Unexpected::Str(&s), &"bool or 0/1")),
        },
        _ => Err(D::Error::invalid_value(Unexpected::Other("non-bool"), &"bool or 0/1")),
    }
}
