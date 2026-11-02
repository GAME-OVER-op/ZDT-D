use std::collections::HashMap;

pub(in crate::api) fn is_authorized(headers: &HashMap<String, String>, token: &str) -> bool {
    if let Some(v) = headers.get("x-api-key") {
        return v.trim() == token;
    }
    if let Some(v) = headers.get("authorization") {
        let v = v.trim();
        if let Some(rest) = v.strip_prefix("Bearer ") {
            return rest.trim() == token;
        }
    }
    false
}

