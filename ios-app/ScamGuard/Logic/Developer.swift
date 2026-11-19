import Foundation

// The hidden Developer view (demos and debugging). Pure logic, kept in step with android-app logic/Developer.kt; both
// apps test the same rules.
//
// Privacy: nothing here is written to disk or sent anywhere. The log and the last call live in memory only (cleared
// when the app closes, by "Delete my history" and by locking the view). Only the "unlocked" flag is saved. Caller
// text is always masked (Logic/Mask.swift) before it is kept. Nothing here changes detection, thresholds or what the
// server is sent.

/// Settings → Help & about → tap the version number 7 times.
nonisolated enum DevUnlock {
    static let taps = 7

    enum Outcome: Equatable, Sendable { case none, stepsLeft, unlocked, already }

    /// count = taps so far on this visit; left = taps still needed (for .stepsLeft).
    struct Tap: Equatable, Sendable {
        let count: Int
        let outcome: Outcome
        var left = 0
    }

    /// One tap. From the 4th tap on it says how many are left (as Android's own developer options do).
    static func tap(_ count: Int, unlocked: Bool) -> Tap {
        if unlocked { return Tap(count: 0, outcome: .already) }
        let n = count + 1
        if n >= taps { return Tap(count: 0, outcome: .unlocked) }
        if n >= taps - 3 { return Tap(count: n, outcome: .stepsLeft, left: taps - n) }
        return Tap(count: n, outcome: .none)
    }
}

/// Where the "unlocked" flag lives (AppPrefs on the phone, a dictionary in tests).
nonisolated protocol DevStore: AnyObject {
    var developerUnlocked: Bool { get set }
}

/// "Call playback speed" (Developer view → Tools). In memory only; back to 1× when the view is locked.
nonisolated enum PlaybackSpeed: String, CaseIterable, Identifiable, Sendable {
    case x1 = "1x", x2 = "2x", x4 = "4x", instant = "instant"

    var id: String { rawValue }
    var factor: Int {
        switch self {
        case .x1: return 1
        case .x2: return 2
        case .x4: return 4
        case .instant: return 0
        }
    }
    var label: String {
        switch self {
        case .x1: return "1×"
        case .x2: return "2×"
        case .x4: return "4×"
        case .instant: return "Instant"
        }
    }
    /// The small tag on the in-call screen; nil at 1×.
    var tag: String? {
        switch self {
        case .x1: return nil
        case .instant: return "Instant"
        default: return "\(label) speed"
        }
    }
}

/// The Developer view's state for one run of the app: the saved "unlocked" flag, and in memory the overlay switch and
/// the playback speed. Locking forgets the logs and puts both back. Delete my history forgets the logs too.
@MainActor
final class DevSession {
    private let store: DevStore
    let log: DevLog
    private(set) var overlay = false
    private(set) var speed: PlaybackSpeed = .x1
    var unlocked: Bool { store.developerUnlocked }

    init(store: DevStore, log: DevLog) {
        self.store = store
        self.log = log
        log.recording = store.developerUnlocked
    }

    /// One tap on the version number; the 7th unlocks.
    func tap(_ count: Int) -> DevUnlock.Tap {
        let t = DevUnlock.tap(count, unlocked: unlocked)
        if t.outcome == .unlocked { setUnlocked(true) }
        return t
    }

    func setUnlocked(_ on: Bool) {
        store.developerUnlocked = on
        log.recording = on
        if !on {
            log.clear()
            overlay = false
            speed = .x1
        }
    }

    func setOverlay(_ on: Bool) { if unlocked { overlay = on } }
    func setSpeed(_ s: PlaybackSpeed) { if unlocked { speed = s } }

    /// "Delete my history": the in-memory log goes too.
    func historyDeleted() { log.clear() }

    /// "Reset app to first launch" cleared the store: forget the rest as well.
    func wasReset() {
        log.clear()
        log.recording = false
        overlay = false
        speed = .x1
    }
}

nonisolated enum Playback {
    /// Instant still waits this long between lines, so the screen and the requests stay in order.
    static let instantGapMs = 150

    /// A script line's wait before it appears, at this speed.
    static func gap(_ delayMs: Int, _ speed: PlaybackSpeed) -> Int {
        speed == .instant ? instantGapMs : delayMs / speed.factor
    }

    /// Plays the lines in order: wait (scaled), show the line, then the end. Both call paths (cloud and on the phone)
    /// run through this, so every speed ends the same way (the end message is sent by `onEnd`). `sleep` returns false
    /// when the call was cancelled (then nothing more happens).
    @MainActor
    static func play(_ lines: [ScriptLine], speed: PlaybackSpeed, defaultDelayMs: Int,
                     sleep: (Int) async -> Bool,
                     onLine: (Int, ScriptLine) -> Void,
                     onEnd: () async -> Void) async {
        for (i, line) in lines.enumerated() {
            guard await sleep(gap(line.delayMs ?? defaultDelayMs, speed)) else { return }
            onLine(i, line)
        }
        await onEnd()
    }
}

/// The live score overlay: only while Developer view is unlocked, the toggle is on, the call is on and no red warning.
nonisolated enum DevOverlay {
    static func visible(enabled: Bool, unlocked: Bool, inCall: Bool, warningOpen: Bool) -> Bool {
        enabled && unlocked && inCall && !warningOpen
    }
}

/// The plain connection status normal users see (Settings, Simple Home): never a URL, IP or error code.
nonisolated enum ConnectionStatus: Equatable, Sendable {
    case connected, cantConnect, checking, notNeeded

    static func of(_ privacy: PrivacyLevel, _ connection: Bool?) -> ConnectionStatus {
        if privacy == .onDevice { return .notNeeded }            // checked on the phone, no server
        switch connection {
        case true?: return .connected
        case false?: return .cantConnect
        case nil: return .checking
        }
    }

    var key: K {
        switch self {
        case .connected: return .conn_ok
        case .cantConnect: return .conn_fail
        case .checking: return .status_checking
        case .notNeeded: return .conn_not_needed
        }
    }
}

/// Learning-loop test scripts (file number 90 and up, e.g. scam_en_90_…): shown only in Developer view.
nonisolated enum DevScripts {
    static func isTestCall(_ fileName: String) -> Bool {
        let parts = fileName.replacingOccurrences(of: ".json", with: "").split(separator: "_")
        return parts.count > 2 && (Int(parts[2]) ?? 0) >= 90
    }
}

/// Masks caller text inside a request / reply before the Developer view keeps it.
nonisolated enum DevMask {
    /// Keys whose string values are what a caller said (or a server echo of it).
    static let textKeys: Set<String> = ["text", "lines", "transcript", "caller_lines", "riskiest_sentence", "sentence"]

    /// JSON: masked and pretty-printed. Anything else: masked as plain text. Empty stays empty.
    static func body(_ raw: String) -> String {
        if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
              obj is [String: Any] || obj is [Any] else { return Mask.mask(raw) }
        let masked = walk(obj, mask: false)
        guard let out = try? JSONSerialization.data(withJSONObject: masked, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]),
              let s = String(data: out, encoding: .utf8) else { return Mask.mask(raw) }
        return s
    }

    private static func walk(_ v: Any, mask: Bool) -> Any {
        if let o = v as? [String: Any] {
            var r: [String: Any] = [:]
            for (k, x) in o { r[k] = walk(x, mask: mask || textKeys.contains(k)) }
            return r
        }
        if let a = v as? [Any] { return a.map { walk($0, mask: mask) } }
        if mask, let s = v as? String { return Mask.mask(s) }
        return v
    }
}

/// One caller line of the last call, for the Developer view's table and the overlay. Numbers only, no text.
nonisolated struct DevLine: Equatable, Sendable {
    let seq: Int
    /// When the line appeared, ms since the call started.
    let tMs: Int
    let urgency: Float?
    let money: Float?
    let impersonation: Float?
    let combined: Float?
    let bertScore: Float?
    let bertStatus: String?
    /// On the phone: the rule that decided (LocalRules); the cloud doesn't report one.
    let rule: String?
    /// The cloud's live_alert; on the phone the verdict is the live alert.
    let liveAlert: String?
    let verdict: String?
    /// "cloud", "phone", "phone + cloud BERT", "phone (cloud BERT unavailable)", "phone + MobileBERT"…
    let place: String
    let latencyMs: Int
}

/// The end-of-call reply (cloud only).
nonisolated struct DevEnd: Equatable, Sendable {
    let finalVerdict: String?
    let callScore: Float?
    let review: String?
    let topWindow: [Int]
}

nonisolated struct DevCall: Equatable, Sendable {
    let callId: String
    let privacy: String
    var lines: [DevLine] = []
    var endedBy: String?
    var end: DevEnd?
}

/// One request / reply, masked.
nonisolated struct DevEntry: Equatable, Sendable, Identifiable {
    let id = UUID()
    let timeMs: Int64
    let method: String
    let path: String
    let status: Int
    let ms: Int
    let request: String
    let response: String
}

nonisolated struct DevHealth: Equatable, Sendable {
    let ok: Bool
    let message: String
    let timeMs: Int64
}

/// The Developer view's memory: the last 20 requests / replies, the last call, the server's model versions from its
/// last reply and the last /healthz. In memory only. `recording` is on while the view is unlocked; nothing is kept
/// otherwise. Thread-safe: the gateway client records from any task.
nonisolated final class DevLog: @unchecked Sendable {
    static let capacity = 20
    /// The app's one log (GatewayClient writes to it); tests make their own.
    static let shared = DevLog()

    private let lock = NSLock()
    private var _recording = false
    private var entries: [DevEntry] = []
    private var call: DevCall?
    private var versions: [String: String] = [:]
    private var lastHealth: DevHealth?
    let capacity: Int

    init(capacity: Int = DevLog.capacity) { self.capacity = capacity }

    var recording: Bool {
        get { lock.withLock { _recording } }
        set { lock.withLock { _recording = newValue } }
    }

    /// Masks both bodies, keeps the newest `capacity`.
    func add(timeMs: Int64, method: String, path: String, status: Int, ms: Int, request: String, response: String) {
        guard recording else { return }
        let e = DevEntry(timeMs: timeMs, method: method, path: path, status: status, ms: ms,
                         request: DevMask.body(request), response: DevMask.body(response))
        lock.withLock {
            entries.insert(e, at: 0)
            if entries.count > capacity { entries.removeLast(entries.count - capacity) }
        }
    }

    /// Newest first.
    func all() -> [DevEntry] { lock.withLock { entries } }

    func startCall(_ callId: String, privacy: String) {
        lock.withLock { if _recording { call = DevCall(callId: callId, privacy: privacy) } }
    }

    func addLine(_ callId: String, _ line: DevLine) {
        lock.withLock {
            guard _recording, var c = call, c.callId == callId else { return }
            c.lines = (c.lines.filter { $0.seq != line.seq } + [line]).sorted { $0.seq < $1.seq }
            call = c
        }
    }

    func endCall(_ callId: String, endedBy: String, end: DevEnd?) {
        lock.withLock {
            guard _recording, var c = call, c.callId == callId else { return }
            c.endedBy = endedBy
            if let end { c.end = end }
            call = c
        }
    }

    func lastCall() -> DevCall? { lock.withLock { call } }

    /// The newest caller line of the last call (the overlay).
    func latestLine(_ callId: String) -> DevLine? {
        lock.withLock { call?.callId == callId ? call?.lines.max { $0.seq < $1.seq } : nil }
    }

    func setModelVersions(_ v: [String: String]) {
        lock.withLock { if _recording && !v.isEmpty { versions.merge(v) { $1 } } }
    }
    func modelVersions() -> [String: String] { lock.withLock { versions } }

    func setHealth(_ h: DevHealth) { lock.withLock { if _recording { lastHealth = h } } }
    func health() -> DevHealth? { lock.withLock { lastHealth } }

    func clear() {
        lock.withLock {
            entries = []
            call = nil
            versions = [:]
            lastHealth = nil
        }
    }
}

/// Number formats shared by the table, the overlay and "Copy diagnostics" (same on Android).
nonisolated enum DevFormat {
    static func score(_ v: Float?) -> String { v.map { String(format: "%.2f", $0) } ?? "-" }
    static func seconds(_ ms: Int) -> String { String(format: "%.1fs", Double(ms) / 1000) }
    static func time(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// One row, labelled: "#3  12.4s  U 0.12 M 0.98 I 0.40  C 0.79  BERT 0.91 ok  rule red_quorum  live SCAM  SCAM  cloud 182ms"
    static func line(_ l: DevLine) -> String {
        "#\(l.seq)  \(seconds(l.tMs))  U \(score(l.urgency)) M \(score(l.money)) I \(score(l.impersonation))  " +
            "C \(score(l.combined))  BERT \(score(l.bertScore)) \(l.bertStatus ?? "-")  rule \(l.rule ?? "-")  " +
            "live \(l.liveAlert ?? "-")  \(l.verdict ?? "-")  \(l.place) \(l.latencyMs)ms"
    }

    static let tableHeader = ["seq", "t", "U", "M", "I", "comb", "BERT", "status", "rule", "live", "verdict", "where", "ms"]

    static func tableRow(_ l: DevLine) -> [String] {
        ["\(l.seq)", seconds(l.tMs), score(l.urgency), score(l.money), score(l.impersonation), score(l.combined),
         score(l.bertScore), l.bertStatus ?? "-", l.rule ?? "-", l.liveAlert ?? "-", l.verdict ?? "-", l.place, "\(l.latencyMs)"]
    }

    /// The Developer view's table: a header and one row per caller line, columns lined up (monospace).
    static func table(_ lines: [DevLine]) -> String {
        let rows = [tableHeader] + lines.map(tableRow)
        let widths = tableHeader.indices.map { c in rows.map { $0[c].count }.max() ?? 0 }
        return rows.map { r in
            r.enumerated().map { c, v in v.padding(toLength: widths[c], withPad: " ", startingAt: 0) }
                .joined(separator: "  ").replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
        }.joined(separator: "\n")
    }

    static func end(_ e: DevEnd) -> String {
        let top = e.topWindow.map(String.init).joined(separator: ",")
        return "final \(e.finalVerdict ?? "-")  score \(score(e.callScore))  review \(e.review ?? "-")  top window \(top.isEmpty ? "-" : top)"
    }
}

/// "Copy diagnostics": sections 1-3 (connection, models, last call) as one text. It holds no caller text at all:
/// the last call is numbers, verdicts and rule names only.
nonisolated enum Diagnostics {
    static func text(connection: [(String, String)], models: [(String, String)], call: DevCall?) -> String {
        var out = ["ScamGuard diagnostics", "", "1. Connection"]
        out += connection.map { "\($0.0): \($0.1)" }
        out += ["", "2. Models"]
        out += models.map { "\($0.0): \($0.1)" }
        out += ["", "3. Last call"]
        if let call {
            out += ["call_id: \(call.callId)", "privacy: \(call.privacy)", "ended_by: \(call.endedBy ?? "-")"]
            out += call.lines.map(DevFormat.line)
            if let e = call.end { out.append("end: " + DevFormat.end(e)) }
        } else {
            out.append("none")
        }
        return out.joined(separator: "\n")
    }
}

/// The models section: what is bundled (manifest.json) and what was downloaded (installed.json).
nonisolated enum DevModels {
    static func bundled(_ manifest: Data?) -> [(String, String)] {
        guard let manifest, let o = try? JSONSerialization.jsonObject(with: manifest) as? [String: Any],
              let models = o["models"] as? [String: Any] else { return [] }
        return models.keys.sorted().map { name in
            let m = models[name] as? [String: Any] ?? [:]
            return ("bundled \(name)", "\(m["version"] as? String ?? "?")  sha256 \(m["sha256"] as? String ?? "?")")
        }
    }

    static func downloaded(_ installed: Data?) -> [(String, String)] {
        guard let installed, let o = try? JSONSerialization.jsonObject(with: installed) as? [String: Any] else { return [] }
        return o.keys.sorted().map { name in
            let m = o[name] as? [String: Any] ?? [:]
            return ("downloaded \(name)", "\(m["version"] as? String ?? "?")  sha256 \(m["sha256"] as? String ?? "?")")
        }
    }
}
