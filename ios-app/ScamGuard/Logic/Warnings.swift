import Foundation

/// What the red warning and the yellow "Be careful" banner say, in plain words: one sentence for what happened and
/// one for what to do. No scores, no percentages. Pure; the same choices and keys as Android logic/Warnings.kt.
nonisolated enum Warnings {
    enum Kind: String, CaseIterable, Sendable { case otp, money, official, rush, general, several, earlier }

    /// Every reason code a call can be saved with (Logic/Reasons.swift), for tests and the review checklist.
    static let reasonCodes = [Reasons.money, Reasons.impersonation, Reasons.urgency, Reasons.bert, Reasons.several,
                              Reasons.earlier, Reasons.none, Reasons.serverNotReached, Reasons.noLines]

    private static func saidOtp(_ flaggedTexts: [String]) -> Bool {
        let all = flaggedTexts.joined(separator: " ").lowercased()
        return all.contains("otp") || all.contains("one-time") || all.contains("验证码")
    }

    /// The warning's reason. Strongest first, as on the old banner: money (OTP when the caller said it), then a fake
    /// official, then rushing. With no signal lit the reason code decides.
    static func kind(_ lit: [String], _ flaggedTexts: [String], reasonCode: String? = nil) -> Kind {
        let otp = saidOtp(flaggedTexts)
        if lit.contains("money") { return otp ? .otp : .money }
        if lit.contains("impersonation") { return .official }
        if lit.contains("urgency") { return .rush }
        switch reasonCode {
        case Reasons.money: return otp ? .otp : .money
        case Reasons.impersonation: return .official
        case Reasons.urgency: return .rush
        case Reasons.several: return .several
        case Reasons.earlier: return .earlier
        default: return .general
        }
    }

    /// What happened (red warning).
    static func message(_ k: Kind) -> K {
        switch k {
        case .otp: return .warn_msg_otp
        case .money: return .warn_msg_money
        case .official: return .warn_msg_official
        case .rush: return .warn_msg_rush
        case .general: return .warn_msg_general
        case .several: return .warn_msg_several
        case .earlier: return .warn_msg_earlier
        }
    }

    /// What to do (red warning, and the spoken warning).
    static func doLine(_ k: Kind) -> K {
        switch k {
        case .otp: return .warn_do_otp
        case .money: return .warn_do_money
        default: return .warn_do_details
        }
    }

    /// The yellow "Be careful" banner's line.
    static func careful(_ k: Kind) -> K {
        switch k {
        case .otp, .money: return .careful_msg_money
        case .official: return .careful_msg_official
        case .rush: return .careful_msg_rush
        default: return .careful_msg_general
        }
    }

    /// Read aloud: "This may be a scam. Do not give your OTP. Hang up now."
    static func spoken(_ k: Kind) -> String {
        L.s(.warn_spoken, L.s(doLine(k)))
    }

    /// The message to family: opens Messages, the user taps Send.
    static func familyMessage() -> String { L.s(.sms_family) }
}

/// The red warning's alert sound: three short 880 Hz beeps, 16-bit mono PCM, made in code. The same samples as
/// Android logic/AlertTone.kt, so both apps sound the same.
nonisolated enum AlertTone {
    static let sampleRate = 22_050
    static let beeps = 3
    static let beepMs = 260
    static let gapMs = 140
    static let freq = 880.0
    static let amplitude = 0.7

    static var durationMs: Int { beeps * beepMs + (beeps - 1) * gapMs }

    static func pcm() -> [Int16] {
        let beep = sampleRate * beepMs / 1000
        let gap = sampleRate * gapMs / 1000
        let ramp = sampleRate / 100                 // 10 ms fade in / out: no clicks
        var out = [Int16](repeating: 0, count: beeps * beep + (beeps - 1) * gap)
        var at = 0
        for b in 0..<beeps {
            for i in 0..<beep {
                let env = min(1.0, Double(min(i, beep - 1 - i)) / Double(ramp))
                out[at + i] = Int16(sin(2 * Double.pi * freq * Double(i) / Double(sampleRate)) * amplitude * env * Double(Int16.max))
            }
            at += beep + (b < beeps - 1 ? gap : 0)
        }
        return out
    }

    /// The tone as a WAV file (for AVAudioPlayer).
    static func wav() -> Data {
        let samples = pcm()
        var d = Data()
        func u32(_ v: UInt32) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 4)) }
        func u16(_ v: UInt16) { var x = v.littleEndian; d.append(Data(bytes: &x, count: 2)) }
        let bytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + bytes); d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16); u16(1); u16(1); u32(UInt32(sampleRate)); u32(UInt32(sampleRate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(bytes)
        samples.withUnsafeBufferPointer { p in
            p.forEach { s in var x = s.littleEndian; d.append(Data(bytes: &x, count: 2)) }
        }
        return d
    }
}
