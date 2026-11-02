/// Splits comma- or ASCII-whitespace-separated values, trimming and dropping
/// empty items. This exact parser is shared by several DNS setting models.
pub fn split_comma_whitespace(value: &str) -> Vec<String> {
    value
        .split(|c: char| c == ',' || c.is_ascii_whitespace())
        .map(str::trim)
        .filter(|item| !item.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}
