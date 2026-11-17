import SwiftUI
import UIKit

/// Colours and words from android-app ui/InCallScreen.kt and ui/theme/Color.kt.
nonisolated enum Theme {
    static let safeGreen = Color(red: 0x2E / 255, green: 0x7D / 255, blue: 0x32 / 255)     // 0xFF2E7D32
    static let warnYellow = Color(red: 0xF9 / 255, green: 0xA8 / 255, blue: 0x25 / 255)    // 0xFFF9A825
    static let scamRed = Color(red: 0xC6 / 255, green: 0x28 / 255, blue: 0x28 / 255)       // 0xFFC62828
    static let neutralGrey = Color(red: 0x9E / 255, green: 0x9E / 255, blue: 0x9E / 255)   // 0xFF9E9E9E

    /// Material's Purple40 (light) / Purple80 (dark), the Android app's primary colour.
    static let brand = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark
            ? UIColor(red: 0xD0 / 255, green: 0xBC / 255, blue: 0xFF / 255, alpha: 1)
            : UIColor(red: 0x66 / 255, green: 0x50 / 255, blue: 0xA4 / 255, alpha: 1)
    })

    static func levelColor(_ level: Risk.Level) -> Color {
        switch level {
        case .green: return safeGreen
        case .yellow: return warnYellow
        case .red: return scamRed
        case .error, .listening: return neutralGrey
        }
    }

    static func levelLabel(_ level: Risk.Level) -> String {
        switch level {
        case .listening: return "Listening…"
        case .green: return "Looks safe so far"
        case .yellow: return "Suspicious"
        case .red: return "Likely scam"
        case .error: return "Could not check"
        }
    }

    static func kindLabel(_ kind: String) -> String {
        switch kind {
        case "scam": return "SCAM CALL"
        case "normal": return "NORMAL CALL"
        default: return kind.uppercased()
        }
    }

    static func kindColor(_ kind: String) -> Color {
        switch kind {
        case "scam": return scamRed
        case "normal": return safeGreen
        default: return neutralGrey
        }
    }

    static func langLabel(_ lang: String?) -> String {
        switch lang {
        case "en": return "English"
        case "zh": return "Chinese"
        default: return "Auto-detect"
        }
    }
}
