import SwiftUI
import UIKit

/// Colours and words from android-app ui/InCallScreen.kt and ui/theme/Color.kt.
nonisolated enum Theme {
    /// Safe green, #2B7530: at least 4.5:1 on cards as text (accessibility audit; was #2E7D32, 4.4:1). Same on Android.
    static let safeGreen = Color(red: 0x2B / 255, green: 0x75 / 255, blue: 0x30 / 255)     // 0xFF2B7530
    static let warnYellow = Color(red: 0xF9 / 255, green: 0xA8 / 255, blue: 0x25 / 255)    // 0xFFF9A825
    static let scamRed = Color(red: 0xC6 / 255, green: 0x28 / 255, blue: 0x28 / 255)       // 0xFFC62828
    static let neutralGrey = Color(red: 0x9E / 255, green: 0x9E / 255, blue: 0x9E / 255)   // 0xFF9E9E9E

    /// Material's Purple40 (light) / Purple80 (dark), the Android app's primary colour.
    static let brand = Color(uiColor: UIColor { t in
        t.userInterfaceStyle == .dark
            ? UIColor(red: 0xD0 / 255, green: 0xBC / 255, blue: 0xFF / 255, alpha: 1)
            : UIColor(red: 0x66 / 255, green: 0x50 / 255, blue: 0xA4 / 255, alpha: 1)
    })

    /// Dynamic colour from light / dark hex values (0xRRGGBB).
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        func ui(_ v: UInt32) -> UIColor {
            UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                    blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        }
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? ui(dark) : ui(light) })
    }

    // The Android app's Material 3 roles (android-app ui/theme/Color.kt), so both apps look the same.
    /// Page background (M3 background).
    static let background = dynamic(0xFFFBFE, 0x141218)
    /// Card background (M3 surfaceContainer).
    static let card = dynamic(0xF3EDF7, 0x211F26)
    /// Material surfaceContainerHigh (Android's colorScheme.surfaceContainerHigh): cards that stand out from `card`.
    static let cardHigh = dynamic(0xECE6F0, 0x2B2930)
    /// Empty bar track (M3 surfaceVariant).
    static let track = dynamic(0xE7E0EC, 0x49454F)
    /// Secondary text (M3 onSurfaceVariant).
    static let muted = dynamic(0x49454F, 0xCAC4D0)
    /// Selected card / icon circle (M3 primaryContainer).
    static let brandContainer = dynamic(0xEADDFF, 0x4F378B)
    /// Text on brandContainer (M3 onPrimaryContainer).
    static let onBrandContainer = dynamic(0x21005D, 0xEADDFF)
    /// Borders (M3 outline / outlineVariant).
    static let outline = dynamic(0x79747E, 0x938F99)
    static let outlineVariant = dynamic(0xCAC4D0, 0x49454F)
    /// Red text that keeps its contrast in dark mode.
    static let warningInkAdaptive = dynamic(0xB71C1C, 0xFFB4AB)
    /// Text on a brand-coloured fill (M3 onPrimary): white in light mode, dark purple in dark mode.
    static let onBrand = dynamic(0xFFFFFF, 0x381E72)

    /// Privacy-level colours (purple family), the same on Android.
    static let privacyCloud = Color(red: 0x66 / 255, green: 0x50 / 255, blue: 0xA4 / 255)
    static let privacyHybrid = Color(red: 0x9A / 255, green: 0x82 / 255, blue: 0xDB / 255)
    static let privacyOnDevice = Color(red: 0xCD / 255, green: 0xB9 / 255, blue: 0xFF / 255)

    static func privacyColor(_ id: String) -> Color {
        switch id {
        case "hybrid": return privacyHybrid
        case "on_device": return privacyOnDevice
        default: return privacyCloud
        }
    }

    /// Signal line colours on the call detail chart (the same on Android).
    static let urgencyLine = Color(red: 0xEF / 255, green: 0x6C / 255, blue: 0x00 / 255)
    static let moneyLine = Color(red: 0x8E / 255, green: 0x24 / 255, blue: 0xAA / 255)
    static let impersonationLine = Color(red: 0x1E / 255, green: 0x88 / 255, blue: 0xE5 / 255)

    static func verdictWord(_ verdict: String) -> String { PlainWords.verdict(verdict) }

    static func levelColor(_ level: Risk.Level) -> Color {
        switch level {
        case .green: return safeGreen
        case .yellow: return warnYellow
        case .red: return scamRed
        case .error, .listening: return neutralGrey
        }
    }

    /// The level's word for the live call ("Safe so far", "Be careful", "Scam", "Could not check", "Listening…").
    static func levelLabel(_ level: Risk.Level) -> String {
        switch level {
        case .listening: return L.s(.risk_listening)
        case .green: return L.s(.risk_safe_so_far)
        case .yellow: return L.s(.risk_careful)
        case .red: return L.s(.risk_scam)
        case .error: return L.s(.risk_could_not_check)
        }
    }

    /// The level's word for a finished call ("Safe", "Be careful", "Scam", "Not checked").
    static func riskWord(_ level: Risk.Level) -> String {
        switch level {
        case .green: return L.s(.risk_safe)
        case .yellow: return L.s(.risk_careful)
        case .red: return L.s(.risk_scam)
        case .error: return L.s(.risk_not_checked)
        case .listening: return L.s(.risk_listening)
        }
    }

    /// Text on a white button / the warning: the risk red, darker, for contrast (7.4:1 on white).
    static let warningInk = Color(red: 0xB7 / 255, green: 0x1C / 255, blue: 0x1C / 255)
    /// Dark text on the yellow banner (11:1).
    static let onYellow = Color(red: 0x1C / 255, green: 0x1B / 255, blue: 0x1F / 255)
    /// Grey badge fill: dark enough for a white glyph (6.1:1).
    static let badgeGrey = Color(red: 0x61 / 255, green: 0x61 / 255, blue: 0x61 / 255)

    static func kindLabel(_ kind: String) -> String {
        switch kind {
        case "scam": return L.s(.pick_kind_scam)
        case "normal": return L.s(.pick_kind_normal)
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
        case "en": return L.s(.pick_english)
        case "zh": return L.s(.pick_chinese)
        default: return L.s(.pick_auto)
        }
    }
}
