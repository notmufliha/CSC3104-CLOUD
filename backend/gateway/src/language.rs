/// Languages this deployment currently has workers for.
///
/// TODO: replace this allow-list with real language detection (e.g. whatever
/// fasttext/langid model the team picks) so `language` can be optional in
/// the request and inferred from `transcript` instead of trusted as-is.
const SUPPORTED_LANGUAGES: &[&str] = &["en", "zh"];

pub fn validate_language(language: &str) -> Result<&str, String> {
    let normalized = language.trim().to_lowercase();
    if SUPPORTED_LANGUAGES.contains(&normalized.as_str()) {
        // Leak-free: return the canonical static str, not the owned one.
        SUPPORTED_LANGUAGES
            .iter()
            .find(|&&l| l == normalized)
            .copied()
            .ok_or_else(|| format!("unsupported language: {language}"))
    } else {
        Err(format!(
            "unsupported language '{language}', expected one of {SUPPORTED_LANGUAGES:?}"
        ))
    }
}
