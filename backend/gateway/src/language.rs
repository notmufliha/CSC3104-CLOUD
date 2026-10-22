//! Which language's workers get an utterance.
//!
//! Rule: use the phone's `lang` field if it is present; otherwise, if more than 30% of the text's
//! letters/digits are Chinese characters, use the ZH workers, else EN.

/// Languages this deployment has workers for.
pub const SUPPORTED: [&str; 2] = ["en", "zh"];
/// Share of Chinese characters above which text counts as Chinese.
pub const CHINESE_SHARE: f64 = 0.30;

/// Where the decision came from (logged, and returned to the phone).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    Phone,
    Detected,
}

/// `lang` from the phone wins; an unsupported value is an error (the phone has a bug), not a silent guess.
pub fn choose(lang: Option<&str>, text: &str) -> Result<(&'static str, Source), String> {
    match lang.map(str::trim).filter(|l| !l.is_empty()) {
        Some(l) => {
            let l = l.to_lowercase();
            // "zh-CN", "zh_SG", "en-US" -> base language
            let base = l.split(['-', '_']).next().unwrap_or("");
            SUPPORTED
                .iter()
                .find(|s| **s == base)
                .map(|s| (*s, Source::Phone))
                .ok_or_else(|| format!("unsupported lang '{l}', expected one of {SUPPORTED:?}"))
        }
        None => Ok((if chinese_share(text) > CHINESE_SHARE { "zh" } else { "en" }, Source::Detected)),
    }
}

/// Chinese characters / all letters and digits (spaces and punctuation don't count either way).
pub fn chinese_share(text: &str) -> f64 {
    let (mut cjk, mut total) = (0usize, 0usize);
    for c in text.chars().filter(|c| c.is_alphanumeric()) {
        total += 1;
        if is_chinese(c) {
            cjk += 1;
        }
    }
    if total == 0 {
        0.0
    } else {
        cjk as f64 / total as f64
    }
}

fn is_chinese(c: char) -> bool {
    matches!(c as u32,
        0x4E00..=0x9FFF      // CJK Unified Ideographs
        | 0x3400..=0x4DBF    // Extension A
        | 0x20000..=0x2A6DF  // Extension B
        | 0xF900..=0xFAFF)   // Compatibility Ideographs
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn phone_lang_wins() {
        assert_eq!(choose(Some("zh-SG"), "hello there").unwrap(), ("zh", Source::Phone));
        assert_eq!(choose(Some("EN"), "我是警察").unwrap(), ("en", Source::Phone));
        assert!(choose(Some("fr"), "bonjour").is_err());
    }

    #[test]
    fn detects_by_chinese_share() {
        assert_eq!(choose(None, "我是警察，你必须马上把钱转到安全账户").unwrap().0, "zh");
        assert_eq!(choose(None, "Transfer the money now").unwrap().0, "en");
        // Singlish with a few characters: 2 of 28 letters are Chinese (7%, < 30%) -> EN
        assert_eq!(choose(None, "Eh you transfer the money now ok 快点").unwrap().0, "en");
        // mostly Chinese with an English word -> ZH
        assert_eq!(choose(None, "请把OTP告诉我，马上转账").unwrap().0, "zh");
        assert_eq!(choose(Some("  "), "").unwrap().0, "en");
    }
}
