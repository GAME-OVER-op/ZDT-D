// from programs/byedpi.rs
pub fn normalize_config_args(raw: &str) -> Vec<String> {
    // Convert multiline config text into argv without invoking a shell.
    //
    // Supported shell-like syntax is deliberately limited to tokenization:
    // - unquoted whitespace separates arguments;
    // - single and double quotes group whitespace and are removed from argv;
    // - adjacent quoted/unquoted fragments form one argument (for example
    //   `-H:"1.com 2.com"` -> `-H:1.com 2.com`);
    // - backslash can escape whitespace, quotes, or another backslash;
    // - backslash + LF/CRLF remains a line continuation, as before.
    //
    // No shell expansion or execution is performed: `$`, `*`, `;`, pipes,
    // command substitutions, redirects, etc. remain ordinary argument text.

    // Preserve the historical multiline behavior first: remove explicit line
    // continuations and turn other line breaks into spaces.
    let mut s = String::with_capacity(raw.len());
    let mut it = raw.chars().peekable();

    while let Some(c) = it.next() {
        if c == '\\' {
            match it.peek().copied() {
                Some('\n') => {
                    it.next();
                    continue;
                }
                Some('\r') => {
                    it.next();
                    if matches!(it.peek().copied(), Some('\n')) {
                        it.next();
                    }
                    continue;
                }
                _ => {}
            }
        }

        if c == '\n' || c == '\r' {
            s.push(' ');
        } else {
            s.push(c);
        }
    }

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum Quote {
        None,
        Single,
        Double,
    }

    let mut out = Vec::new();
    let mut token = String::new();
    let mut token_started = false;
    let mut quote = Quote::None;
    let mut chars = s.chars().peekable();

    while let Some(c) = chars.next() {
        match quote {
            Quote::None => match c {
                c if c.is_whitespace() => {
                    if token_started {
                        if token != "\\" {
                            out.push(std::mem::take(&mut token));
                        } else {
                            token.clear();
                        }
                        token_started = false;
                    }
                }
                '\'' => {
                    quote = Quote::Single;
                    token_started = true;
                }
                '"' => {
                    quote = Quote::Double;
                    token_started = true;
                }
                '\\' => {
                    token_started = true;
                    match chars.peek().copied() {
                        // Keep the historical behavior where a standalone `\`
                        // between arguments is ignored instead of becoming an
                        // argument containing one escaped space.
                        Some(next) if next.is_whitespace() && token.is_empty() => {
                            token.push('\\');
                        }
                        Some(next)
                            if next.is_whitespace()
                                || next == '\''
                                || next == '"'
                                || next == '\\' =>
                        {
                            if let Some(next) = chars.next() {
                                token.push(next);
                            }
                        }
                        Some(_) | None => token.push('\\'),
                    }
                }
                _ => {
                    token.push(c);
                    token_started = true;
                }
            },
            Quote::Single => {
                if c == '\'' {
                    quote = Quote::None;
                } else {
                    token.push(c);
                }
            }
            Quote::Double => match c {
                '"' => quote = Quote::None,
                '\\' => match chars.peek().copied() {
                    Some(next) if next == '"' || next == '\\' || next.is_whitespace() => {
                        if let Some(next) = chars.next() {
                            token.push(next);
                        }
                    }
                    Some(_) | None => token.push('\\'),
                },
                _ => token.push(c),
            },
        }
    }

    if token_started && token != "\\" {
        out.push(token);
    }

    out
}

// from programs/mieru.rs

#[cfg(test)]
mod config_arg_tests {
    use super::normalize_config_args;

    fn strings(items: &[&str]) -> Vec<String> {
        items.iter().map(|item| (*item).to_string()).collect()
    }

    #[test]
    fn quoted_byedpi_host_list_stays_one_argument() {
        assert_eq!(
            normalize_config_args(r#"-H:"1.com 2.com 3.com" -s1 -q1"#),
            strings(&["-H:1.com 2.com 3.com", "-s1", "-q1"]),
        );
        assert_eq!(
            normalize_config_args("-H \":1.com 2.com 3.com\""),
            strings(&["-H", ":1.com 2.com 3.com"]),
        );
    }

    #[test]
    fn repeated_byedpi_host_options_are_preserved() {
        assert_eq!(
            normalize_config_args("-H :1.com -H :2.com -H :3.com"),
            strings(&["-H", ":1.com", "-H", ":2.com", "-H", ":3.com"]),
        );
    }

    #[test]
    fn supports_single_quotes_and_escaped_whitespace_without_shell_expansion() {
        assert_eq!(
            normalize_config_args(r#"--name 'one two' --other=three\ four '$HOME;*|x'"#),
            strings(&["--name", "one two", "--other=three four", "$HOME;*|x"]),
        );
    }

    #[test]
    fn preserves_existing_line_continuation_and_standalone_backslash_behavior() {
        assert_eq!(
            normalize_config_args("--one=1 \\\n--two=2 \\ --three=3"),
            strings(&["--one=1", "--two=2", "--three=3"]),
        );
    }

    #[test]
    fn quoted_empty_value_is_kept_as_an_argument() {
        assert_eq!(
            normalize_config_args(r#"--one "" --two"#),
            strings(&["--one", "", "--two"]),
        );
    }
}
