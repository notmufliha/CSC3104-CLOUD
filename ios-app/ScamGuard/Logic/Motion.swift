import Foundation

/// Motion and haptics, one set of rules for both apps (android-app logic/Motion.kt). With Reduce Motion on nothing
/// scales, slides or flashes: things fade or simply appear.
nonisolated enum Motion {
    /// Big buttons shrink a little while pressed.
    static func pressScale(reduce: Bool) -> Double { reduce ? 1 : 0.97 }

    /// The red warning comes in with a short scale + fade, or a plain fade with Reduce Motion.
    static func warningStartScale(reduce: Bool) -> Double { reduce ? 1 : 0.92 }
    static func warningEnterMs(reduce: Bool) -> Int { reduce ? 0 : 280 }

    /// The red warning's edge flash (the only flashing in the app) is off with Reduce Motion.
    static func warningFlash(reduce: Bool) -> Bool { !reduce }

    /// Risk bar colour / fill changes.
    static func riskBarMs(reduce: Bool) -> Int { reduce ? 0 : 450 }

    enum Haptic: Equatable { case light, warning, error }

    /// Light for taps, warning for "Be careful", error for Scam.
    static func hapticFor(_ level: Risk.Level) -> Haptic? {
        switch level {
        case .red: return .error
        case .yellow: return .warning
        default: return nil
        }
    }
}
