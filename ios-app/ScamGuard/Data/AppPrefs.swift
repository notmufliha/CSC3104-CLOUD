import Foundation

/// Port of android-app data/AppPrefs.kt: settings in UserDefaults, same key names as Android's SharedPreferences.
nonisolated final class AppPrefs: OnboardingStore, @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// The Simulator shares the Mac's network, so localhost is the Mac running docker compose.
    static let defaultURL = "http://localhost:8080/"

    static let keyURL = "gateway_url"
    static let keySaveSentence = "save_riskiest_sentence"
    static let keyPrivacyLevel = "privacy_level"
    static let keyHelpImprove = "help_improve_detection"
    static let keyFlPhoneId = "fl_phone_id"
    static let keyOnboardingCompleted = "onboarding_completed"
    static let keyOnboardingVersion = "onboarding_version"
    static let keyTextSize = "text_size"
    static let keyReadAloud = "read_aloud"
    static let keyShowCareful = "show_be_careful"
    static let keySimple = "simple_mode"
    static let keySimpleOffered = "simple_mode_offered"
    static let keyFamilyName = "family_name"
    static let keyFamilyPhone = "family_phone"
    static let textLarge = "large"
    static let textXLarge = "xlarge"
    /// Keys an earlier version of the app could have saved: any of them means "not a new install".
    static let existingKeys = [keyURL, keySaveSentence, keyPrivacyLevel, keyHelpImprove, keyFlPhoneId]

    var gatewayUrl: String {
        get { defaults.string(forKey: Self.keyURL) ?? Self.defaultURL }
        set { defaults.set(GatewayClient.normalize(newValue), forKey: Self.keyURL) }
    }

    /// "Save the riskiest sentence" on the dashboard. Off by default: no transcript is kept.
    var saveRiskiestSentence: Bool {
        get { defaults.bool(forKey: Self.keySaveSentence) }
        set { defaults.set(newValue, forKey: Self.keySaveSentence) }
    }

    /// Where calls are scored. Cloud unless the user picked an available level.
    var privacyLevel: PrivacyLevel {
        get { PrivacyLevel.fromId(defaults.string(forKey: Self.keyPrivacyLevel)) }
        set { defaults.set(newValue.rawValue, forKey: Self.keyPrivacyLevel) }
    }

    /// "Help improve detection (federated learning)". Off by default: labels only, no sentences.
    var helpImproveDetection: Bool {
        get { defaults.bool(forKey: Self.keyHelpImprove) }
        set { defaults.set(newValue, forKey: Self.keyHelpImprove) }
    }

    /// A random id made once per install (kept for parity with Android; only the federated upload would use it).
    var flPhoneId: String {
        if let id = defaults.string(forKey: Self.keyFlPhoneId) { return id }
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: Self.keyFlPhoneId)
        return id
    }

    /// First-launch setup finished (or skipped). nil = never written (a new install, or one from before the setup).
    var onboardingCompleted: Bool? {
        get { defaults.object(forKey: Self.keyOnboardingCompleted) == nil ? nil : defaults.bool(forKey: Self.keyOnboardingCompleted) }
        set {
            if let newValue { defaults.set(newValue, forKey: Self.keyOnboardingCompleted) }
            else { defaults.removeObject(forKey: Self.keyOnboardingCompleted) }
        }
    }

    var onboardingVersion: Int {
        get { defaults.integer(forKey: Self.keyOnboardingVersion) }
        set { defaults.set(newValue, forKey: Self.keyOnboardingVersion) }
    }

    // ---- elderly-friendly settings (new keys only; the same names and defaults on Android)

    /// "large" (default) or "xlarge".
    var textSize: String {
        get { defaults.string(forKey: Self.keyTextSize) ?? Self.textLarge }
        set { defaults.set(newValue, forKey: Self.keyTextSize) }
    }

    /// "Read warnings aloud". On by default.
    var readAloud: Bool {
        get { defaults.object(forKey: Self.keyReadAloud) == nil ? true : defaults.bool(forKey: Self.keyReadAloud) }
        set { defaults.set(newValue, forKey: Self.keyReadAloud) }
    }

    /// "Show 'Be careful' warnings". On by default; off = only red warnings show (calls are still saved).
    var showBeCareful: Bool {
        get { defaults.object(forKey: Self.keyShowCareful) == nil ? true : defaults.bool(forKey: Self.keyShowCareful) }
        set { defaults.set(newValue, forKey: Self.keyShowCareful) }
    }

    /// Simple mode; nil = never written (an install from before Simple mode: it keeps the full app).
    var simpleMode: Bool? {
        get { defaults.object(forKey: Self.keySimple) == nil ? nil : defaults.bool(forKey: Self.keySimple) }
        set { if let newValue { defaults.set(newValue, forKey: Self.keySimple) } else { defaults.removeObject(forKey: Self.keySimple) } }
    }

    /// An existing user was offered Simple mode once (yes or no).
    var simpleModeOffered: Bool {
        get { defaults.bool(forKey: Self.keySimpleOffered) }
        set { defaults.set(newValue, forKey: Self.keySimpleOffered) }
    }

    /// The trusted family contact, on this phone only. Empty = none.
    var familyName: String {
        get { defaults.string(forKey: Self.keyFamilyName) ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.keyFamilyName) }
    }

    var familyPhone: String {
        get { defaults.string(forKey: Self.keyFamilyPhone) ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: Self.keyFamilyPhone) }
    }

    func hasAnySetting() -> Bool { Self.existingKeys.contains { defaults.object(forKey: $0) != nil } }
}
