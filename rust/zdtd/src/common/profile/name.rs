/// Returns true when `name` is a non-empty ASCII profile/server identifier
/// containing only letters, digits, `_` or `-` and not exceeding `max_len`
/// bytes. All accepted characters are ASCII, so the byte-length check matches
/// the previous backend-local `String::len()` checks exactly.
pub fn is_valid_ascii_profile_name(name: &str, max_len: usize) -> bool {
    !name.is_empty()
        && name.len() <= max_len
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}
