import Foundation

/// Port of android-app data/AppPrefs.kt: settings in UserDefaults, same key names as Android's SharedPreferences.
nonisolated final class AppPrefs: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// The Simulator shares the Mac's network, so localhost is the Mac running docker compose.
    static let defaultURL = "http://localhost:8080/"

    static let keyURL = "gateway_url"
    static let keySaveSentence = "save_riskiest_sentence"
    static let keyPrivacyLevel = "privacy_level"
    static let keyHelpImprove = "help_improve_detection"
    static let keyFlPhoneId = "fl_phone_id"

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
}
