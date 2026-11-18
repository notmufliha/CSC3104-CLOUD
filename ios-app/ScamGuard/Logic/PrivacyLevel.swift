import Foundation

/// Port of android-app logic/PrivacyLevel.kt: where a call's sentences are scored. `id` is what is saved
/// (settings and each dashboard row), so never rename one.
nonisolated enum PrivacyLevel: String, CaseIterable, Identifiable, Sendable {
    case cloud = "cloud"
    case hybrid = "hybrid"
    case onDevice = "on_device"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .cloud: return "1. Cloud (default)"
        case .hybrid: return "2. Hybrid"
        case .onDevice: return "3. On-device"
        }
    }

    var explanation: String {
        switch self {
        case .cloud: return "Every caller sentence is checked by the ScamGuard server. Nothing is stored there."
        case .hybrid: return "Checked on this phone. Only at unsure moments, the caller's last 5 sentences go to the server, with numbers and names removed. English calls only."
        case .onDevice: return "Everything is checked on this phone, even in airplane mode. Nothing about the call leaves it. English calls only."
        }
    }

    /// Short label for the in-call badge and the dashboard.
    var badge: String {
        switch self {
        case .cloud: return "Cloud"
        case .hybrid: return "Hybrid"
        case .onDevice: return "On-device"
        }
    }

    /// Built in this version of the app. A level that isn't built can't be picked and is read back as Cloud.
    var available: Bool { true }

    /// Caller lines are scored by the on-device models (levels 2 and 3).
    var scoresOnPhone: Bool { self != .cloud }

    /// Level 1 only: the server scores every line, gets the end message, and the user can share a report.
    var cloudCall: Bool { self == .cloud }

    static let defaultLevel: PrivacyLevel = .cloud

    /// A saved id -> the level to use. Unknown, missing or not-yet-built levels fall back to Cloud.
    static func fromId(_ id: String?) -> PrivacyLevel {
        allCases.first { $0.rawValue == id && $0.available } ?? defaultLevel
    }

    /// A dashboard row's id -> its badge, for rows saved by any version.
    static func badgeFor(_ id: String) -> String {
        allCases.first { $0.rawValue == id }?.badge ?? id
    }
}
