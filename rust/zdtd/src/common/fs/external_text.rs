use anyhow::{bail, Result};

const CP1251_HIGH: [u32; 128] = [
    0x0402, 0x0403, 0x201A, 0x0453, 0x201E, 0x2026, 0x2020, 0x2021,
    0x20AC, 0x2030, 0x0409, 0x2039, 0x040A, 0x040C, 0x040B, 0x040F,
    0x0452, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0xFFFD, 0x2122, 0x0459, 0x203A, 0x045A, 0x045C, 0x045B, 0x045F,
    0x00A0, 0x040E, 0x045E, 0x0408, 0x00A4, 0x0490, 0x00A6, 0x00A7,
    0x0401, 0x00A9, 0x0404, 0x00AB, 0x00AC, 0x00AD, 0x00AE, 0x0407,
    0x00B0, 0x00B1, 0x0406, 0x0456, 0x0491, 0x00B5, 0x00B6, 0x00B7,
    0x0451, 0x2116, 0x0454, 0x00BB, 0x0458, 0x0405, 0x0455, 0x0457,
    0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417,
    0x0418, 0x0419, 0x041A, 0x041B, 0x041C, 0x041D, 0x041E, 0x041F,
    0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0425, 0x0426, 0x0427,
    0x0428, 0x0429, 0x042A, 0x042B, 0x042C, 0x042D, 0x042E, 0x042F,
    0x0430, 0x0431, 0x0432, 0x0433, 0x0434, 0x0435, 0x0436, 0x0437,
    0x0438, 0x0439, 0x043A, 0x043B, 0x043C, 0x043D, 0x043E, 0x043F,
    0x0440, 0x0441, 0x0442, 0x0443, 0x0444, 0x0445, 0x0446, 0x0447,
    0x0448, 0x0449, 0x044A, 0x044B, 0x044C, 0x044D, 0x044E, 0x044F,
];

#[derive(Clone, Copy)]
enum Utf16Endian {
    Little,
    Big,
}

/// Decode text imported from Android's document picker into the one internal
/// representation used by ZDT-D: UTF-8, LF line endings and no leading BOM.
///
/// Supported external encodings:
///   * UTF-8 / UTF-8 BOM
///   * UTF-16 LE/BE, with BOM and a conservative no-BOM heuristic
///   * Windows-1251 fallback for legacy Cyrillic configuration files
pub fn decode_external_text(data: &[u8]) -> Result<String> {
    let decoded = if data.starts_with(&[0xEF, 0xBB, 0xBF]) {
        std::str::from_utf8(&data[3..])
            .map_err(|e| anyhow::anyhow!("invalid UTF-8 after BOM: {e}"))?
            .to_string()
    } else if data.starts_with(&[0xFF, 0xFE]) {
        decode_utf16(&data[2..], Utf16Endian::Little)?
    } else if data.starts_with(&[0xFE, 0xFF]) {
        decode_utf16(&data[2..], Utf16Endian::Big)?
    } else if let Ok(s) = std::str::from_utf8(data) {
        s.to_string()
    } else if let Some(endian) = detect_utf16_without_bom(data) {
        decode_utf16(data, endian)?
    } else {
        decode_windows_1251(data)?
    };

    normalize_text(&decoded)
}

/// Normalize a String that already arrived through JSON/UTF-8 transport.
pub fn normalize_text(input: &str) -> Result<String> {
    let mut s = input.trim_start_matches('\u{feff}').to_string();
    if s.contains('\0') {
        bail!("text contains NUL bytes");
    }

    // Normalize all common external line endings. Do CRLF first so it does not
    // become two line breaks when lone CR is handled below.
    if s.contains('\r') {
        s = s.replace("\r\n", "\n").replace('\r', "\n");
    }

    let controls = s
        .chars()
        .filter(|c| c.is_control() && !matches!(*c, '\n' | '\t'))
        .count();
    if controls > 0 {
        bail!("text contains unsupported control characters");
    }
    Ok(s)
}

fn decode_utf16(data: &[u8], endian: Utf16Endian) -> Result<String> {
    if data.len() % 2 != 0 {
        bail!("UTF-16 input has odd byte length");
    }
    let units = data.chunks_exact(2).map(|p| match endian {
        Utf16Endian::Little => u16::from_le_bytes([p[0], p[1]]),
        Utf16Endian::Big => u16::from_be_bytes([p[0], p[1]]),
    });
    let collected: Vec<u16> = units.collect();
    String::from_utf16(&collected).map_err(|e| anyhow::anyhow!("invalid UTF-16: {e}"))
}

fn detect_utf16_without_bom(data: &[u8]) -> Option<Utf16Endian> {
    if data.len() < 8 || data.len() % 2 != 0 {
        return None;
    }
    let pairs = data.len() / 2;
    let zero_even = data.iter().step_by(2).filter(|&&b| b == 0).count();
    let zero_odd = data.iter().skip(1).step_by(2).filter(|&&b| b == 0).count();

    // ASCII-heavy config files encoded as UTF-16 have a very strong zero-byte
    // signal on one side. Keep the threshold conservative to avoid treating
    // arbitrary binary files as text.
    if zero_odd * 3 >= pairs && zero_even * 10 < pairs {
        Some(Utf16Endian::Little)
    } else if zero_even * 3 >= pairs && zero_odd * 10 < pairs {
        Some(Utf16Endian::Big)
    } else {
        None
    }
}

fn decode_windows_1251(data: &[u8]) -> Result<String> {
    let mut out = String::with_capacity(data.len());
    for &b in data {
        if b < 0x80 {
            out.push(b as char);
            continue;
        }
        let cp = CP1251_HIGH[(b - 0x80) as usize];
        if cp == 0xFFFD {
            bail!("input is neither UTF-8/UTF-16 nor valid Windows-1251");
        }
        let ch = char::from_u32(cp)
            .ok_or_else(|| anyhow::anyhow!("invalid Windows-1251 code point"))?;
        out.push(ch);
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_utf8_bom_and_normalizes_newlines() {
        let text = decode_external_text(b"\xEF\xBB\xBF[Interface]\r\nKey = value\r\n").unwrap();
        assert_eq!(text, "[Interface]\nKey = value\n");
    }

    #[test]
    fn decodes_utf16le() {
        let mut bytes = vec![0xFF, 0xFE];
        for u in "remote example.com 443\r\n".encode_utf16() {
            bytes.extend_from_slice(&u.to_le_bytes());
        }
        assert_eq!(decode_external_text(&bytes).unwrap(), "remote example.com 443\n");
    }

    #[test]
    fn decodes_cp1251() {
        let bytes = [0x23, 0x20, 0xD2, 0xE5, 0xF1, 0xF2]; // # Тест
        assert_eq!(decode_external_text(&bytes).unwrap(), "# Тест");
    }
}
