import Foundation
import Observation
import UIKit

/// Port of android-app ui/CallViewModel.kt: plays a script as a call, scores each caller line (in the cloud, or on
/// the phone at levels 2-3), ends the call, saves the dashboard row and the implicit labels.
@Observable
@MainActor
final class CallViewModel {
    // ------------------------------------------------------------ dependencies (injectable for tests)
    let prefs: AppPrefs
    let store: CallStore
    let scripts: [CallScript]
    /// Every request goes through this session (level 3 never builds a client from it).
    let session: URLSession
    /// 1 = the script's own timing; 0 in tests.
    let delayScale: Double
    /// iOS stand-in for Android's one 600 ms vibration on the first SCAM.
    let haptic: @MainActor () -> Void
    /// G.2: the on-device models (nil = could not be loaded).
    let onDevice: OnDeviceServices

    // ------------------------------------------------------------ observable state
    private(set) var state = CallUiState()
    private(set) var gatewayUrl: String
    private(set) var privacyLevel: PrivacyLevel
    private(set) var saveSentence: Bool
    private(set) var helpImprove: Bool
    /// Bumped whenever the database changes, so screens that read it refresh.
    private(set) var historyVersion = 0
    private(set) var modelStatus = ""
    private(set) var modelCheck = ""
    private(set) var mobileBertStatus = "Checking MobileBERT…"
    /// Settings: the server's state from the last check (true = connected, false = can't reach), nil = not checked yet.
    private(set) var connection: Bool?

    // ------------------------------------------------------------ per-call bookkeeping
    private var playTask: Task<Void, Never>?
    private var sendTasks: [Task<Void, Never>] = []
    private var callId = ""
    /// Highest seq whose answer (or error) has been applied. Older outcomes only update their own bubble.
    private var lastOutcomeSeq = 0
    /// This call's chart points (one per answered caller line) and when it started.
    private var points: [PointRow] = []
    private var callStartedAt: Int64 = 0
    /// When the red alert first showed in this call (ms), nil if it hasn't.
    private var alertAt: Int64?
    private var lastRandom: String?

    static let defaultDelayMs = 1_500
    /// B.5: how long the summary waits for the end-of-call answer before using the live verdict.
    static let finalReviewWait = 3.0
    static let endedScript = "script_finished"
    static let endedHungUp = "user_hung_up"
    static let endedDiscarded = "user_discarded"
    /// What the summary says instead of sharing a report at levels 2 and 3.
    static let nothingShared = "Privacy mode: calls aren't shared from this phone. Your answer is kept here only."
    static let cloudChecked = "checked with cloud"
    static let cloudUnavailable = "cloud check unavailable, checked on this phone"
    static let mobileBertChecked = "checked with MobileBERT on this phone"
    static let mobileBertFailed = "MobileBERT failed, small models only"
    private static let knownVerdicts: Set<String> = ["SAFE", "UNSURE", "SCAM"]
    private static let warningVerdicts: Set<String> = ["SCAM", "UNSURE"]

    init(prefs: AppPrefs = AppPrefs(),
         store: CallStore,
         scripts: [CallScript] = ScriptRepository.loadAll(),
         session: URLSession = URLSession(configuration: Network.gatewayConfiguration()),
         delayScale: Double = 1,
         haptic: @escaping @MainActor () -> Void = { UINotificationFeedbackGenerator().notificationOccurred(.error) },
         onDevice: OnDeviceServices = OnDeviceServices(),
         startUp: Bool = true) {
        self.prefs = prefs
        self.store = store
        self.scripts = scripts
        self.session = session
        self.delayScale = delayScale
        self.haptic = haptic
        self.onDevice = onDevice
        gatewayUrl = prefs.gatewayUrl
        privacyLevel = prefs.privacyLevel
        saveSentence = prefs.saveRiskiestSentence
        helpImprove = prefs.helpImproveDetection
        modelStatus = onDevice.updater.describe()
        if startUp { startUpTasks() }
    }

    /// App start: preload the models if needed, read MobileBERT's status, and (except at level 3, which must make
    /// no network traffic at all) quietly check for newer federated models.
    private func startUpTasks() {
        if privacyLevel.scoresOnPhone { preloadModels() }
        Task {
            mobileBertStatus = await onDevice.mobileBertStatusLine()
        }
        if privacyLevel != .onDevice {
            Task { _ = await checkModelUpdates() }
        }
    }

    private func gateway() -> GatewayClient? { try? GatewayClient(baseUrl: prefs.gatewayUrl, session: session) }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func scaled(_ ms: Int) -> Int { Int(Double(ms) * delayScale) }

    // ------------------------------------------------------------ call lifecycle

    /// A random script of this kind, avoiding the one picked last time when there is another to choose.
    func randomScript(_ kind: String) -> CallScript? {
        let pool = scripts.filter { $0.kind == kind }
        let pick = pool.filter { $0.fileName != lastRandom }.randomElement() ?? pool.randomElement()
        lastRandom = pick?.fileName
        return pick
    }

    /// Show the incoming-call screen for this script.
    func ring(_ script: CallScript) {
        cancelPlayback()
        state = CallUiState(script: script, phase: .ringing)
    }

    func decline() { reset() }

    func reset() {
        cancelPlayback()
        callId = ""               // a late end answer for the old call must not touch the next one
        state = CallUiState()
    }

    private func cancelPlayback() {
        playTask?.cancel()
        playTask = nil
        sendTasks.forEach { $0.cancel() }
        sendTasks = []
    }

    /// iOS has no Back button: the call screen's "Discard call". During a call: tell the server (ended_by
    /// "user_discarded", so it clears the call's memory), show no summary, save nothing.
    func discard() {
        let s = state
        if s.phase == .inCall {
            cancelPlayback()
            // Levels 2 and 3: the server keeps no memory of the call, so nothing is sent.
            if s.privacyLevel.cloudCall, let api = gateway() { _ = sendEnd(api, callId, Self.endedDiscarded, s) }
        }
        reset()
    }

    /// Paste-text mode: a one-line "call" that skips ringing.
    func startPasted(_ text: String) {
        let script = CallScript(fileName: "pasted", kind: "pasted", lang: nil, title: "Pasted text",
                                lines: [ScriptLine(speaker: "caller", text: text, delayMs: 0)])
        ring(script)
        answer()
    }

    /// Play the script line by line and score caller lines.
    func answer() {
        guard let script = state.script else { return }
        cancelPlayback()
        callId = UUID().uuidString.lowercased()
        lastOutcomeSeq = 0
        points = []
        alertAt = nil
        callStartedAt = now()
        let level = prefs.privacyLevel
        state.phase = .inCall
        state.privacyLevel = level
        if level.scoresOnPhone {
            playOnDevice(script, level)
            return
        }

        let api = gateway()
        if api == nil { state.gatewayError = "Bad server address. Check Settings." }
        let id = callId
        playTask = Task { [weak self] in
            for (i, line) in script.lines.enumerated() {
                guard await Async.sleep(ms: self?.scaled(line.delayMs ?? Self.defaultDelayMs) ?? 0), let self else { return }
                let seq = i + 1                         // caller and receiver share one count, as the gateway expects
                let text = line.text ?? ""
                let waiting = line.isCaller && api != nil
                self.state.lines.append(ChatLine(seq: seq, isCaller: line.isCaller, text: text, pending: waiting,
                                                 failed: line.isCaller && api == nil))
                if waiting, let api {
                    self.sendTasks.append(Task { [weak self] in await self?.send(api, id, seq, text, script.lang) })
                }
            }
            guard let tasks = self?.sendTasks else { return }
            await Async.waitAll(tasks, within: 5)       // let the last answers arrive
            guard !Task.isCancelled, let self else { return }
            self.sendTasks.forEach { $0.cancel() }      // nothing may change the call after this point
            self.sendTasks = []
            guard await Async.sleep(ms: self.scaled(1_500)) else { return }   // a moment to read the final state
            self.endCall(Self.endedScript)
        }
    }

    /// User pressed the red button (or "Hang up now" on the red alert).
    func hangUp() {
        cancelPlayback()                                // also cancels lines still waiting for an answer
        endCall(Self.endedHungUp)
    }

    /// B.5: the call is over. Shows "Final review…", sends POST /v1/call/end with the caller lines and waits up to
    /// 3 s for the final verdict, then shows the summary and saves the history row.
    private func endCall(_ endedBy: String) {
        guard state.phase == .inCall else { return }
        state.phase = .ended
        state.reviewing = true
        state.lines = state.lines.map { l in
            var l = l
            if l.pending { l.pending = false; l.failed = true }
            return l
        }
        let id = callId
        let s = state
        let pts = points
        let startedAt = callStartedAt
        let endedAt = now()
        let onPhone = !s.privacyLevel.cloudCall
        // Levels 2 and 3 send no end message (it would carry every caller line, unmasked).
        let answer: Task<EndCallResponse?, Never>? = onPhone ? nil : gateway().map { sendEnd($0, id, endedBy, s) }
        let firstAlert = alertAt
        Task { [weak self] in
            var resp: EndCallResponse?
            if let answer { resp = await Async.value(of: answer, within: Self.finalReviewWait) }
            guard let self else { return }
            let review = resp.map { FinalReview.from($0) } ?? (onPhone ? .onDeviceReview : .unavailable)
            if self.callId == id {
                self.state.reviewing = false
                self.state.finalReview = review
            }
            var done = s
            done.reviewing = false
            done.finalReview = review
            self.saveHistory(id, done, pts, startedAt, endedAt)
            // G.12: hung up soon after the red alert, or a very sure end-of-call review
            self.recordLabels(id, done, ImplicitLabels.atEnd(hungUp: endedBy == Self.endedHungUp, alertAt: firstAlert,
                                                             endedAt: endedAt, review: review))
        }
    }

    /// G.12: "This was fine" tapped on the summary of a call that ended with a warning -> a false-alarm label.
    func thisWasFine() {
        guard let label = ImplicitLabels.thisWasFine(state.summaryLevel) else { return }
        recordLabels(callId, state, [label])
    }

    /// G.12: labels go only to the phone's database, like the history row (never for pasted text).
    private func recordLabels(_ id: String, _ s: CallUiState, _ labels: [ImplicitLabels.Label]) {
        if s.script?.kind == "pasted" { return }
        store.recordLabels(callId: id, labels: labels, callerLines: s.lines.filter(\.isCaller).map { ($0.seq, $0.text) },
                           helpImprove: prefs.helpImproveDetection)
        historyVersion += 1
    }

    /// Sends the end message in its own task (it outlives the screen); the answer is nil if it failed.
    @discardableResult
    private func sendEnd(_ api: GatewayClient, _ id: String, _ endedBy: String, _ s: CallUiState) -> Task<EndCallResponse?, Never> {
        let callerLines = s.lines.filter { $0.isCaller && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { EndLine(seq: $0.seq, text: $0.text) }
        let request = EndCallRequest(callId: id, endedBy: endedBy, lastSeq: s.lines.map(\.seq).max() ?? 0,
                                     callerLines: callerLines, lang: s.script?.lang)
        return Task.detached {
            if id.isEmpty { return nil }
            guard let r = try? await api.endCall(request), r.isSuccessful else { return nil }
            return r.body
        }
    }

    /// One dashboard row + its chart points. endCall runs once per call, so this saves once.
    private func saveHistory(_ id: String, _ s: CallUiState, _ pts: [PointRow], _ startedAt: Int64, _ endedAt: Int64) {
        if s.script?.kind == "pasted" || id.isEmpty { return }
        let verdict = Risk.historyVerdict(s.summaryLevel)
        // Text is kept only if the user allowed it, and only for a call that ended with a warning.
        var sentence: String?
        if prefs.saveRiskiestSentence && Self.warningVerdicts.contains(verdict), let seq = Dashboard.riskiestSeq(pts) {
            sentence = s.lines.first { $0.seq == seq }?.text
        }
        let fired = Set(s.lit + s.lines.flatMap(\.flags))
        store.save(CallRow(
            id: id, startedAt: startedAt, endedAt: endedAt, finalVerdict: verdict,
            liveVerdict: Risk.historyVerdict(s.level), topReason: historyReason(s), callScore: s.combinedScore,
            signalsFired: Risk.signals.filter { fired.contains($0) }, review: s.finalReview?.reviewType ?? "unavailable",
            riskiestSentence: sentence, privacyLevel: s.privacyLevel.rawValue, reasonCode: s.reasonCode
        ), points: pts)
        historyVersion += 1
    }

    /// The reason text saved with the call (shown only if its code is unknown); the code is what the dashboard uses.
    private func historyReason(_ s: CallUiState) -> String {
        switch s.summaryLevel {
        case .red, .yellow:
            if let r = s.finalReview?.reason, !r.trimmingCharacters(in: .whitespaces).isEmpty { return r }
            return Reasons.text(s.reasonCode) ?? Risk.reason(s.lit, s.flaggedTexts)
        case .green: return "no warning signs"
        case .error: return "server not reached"
        case .listening: return "ended before any line was checked"
        }
    }

    // ------------------------------------------------------------ networking (level 1)

    private func send(_ api: GatewayClient, _ id: String, _ seq: Int, _ text: String, _ lang: String?) async {
        let resp: HTTPResult<UtteranceResponse>
        do {
            resp = try await api.utterance(UtteranceRequest(callId: id, seq: seq, speaker: "caller", text: text, lang: lang))
        } catch {
            if Task.isCancelled { return }
            markError(id, seq, "Could not reach the server")
            return
        }
        if Task.isCancelled { return }
        guard resp.isSuccessful else {
            markError(id, seq, "Server error \(resp.status)")   // 503 = no worker answered, 502 = aggregator failed
            return
        }
        guard let body = resp.body else {
            markError(id, seq, "Empty answer from server")
            return
        }
        applyAnswer(id, seq, body)
    }

    /// One caller line's answer -> bubble flags, chart point, bar, red alert. Used for the gateway's answer (cloud)
    /// and the phone's own (levels 2-3), so both drive the screen the same way.
    func applyAnswer(_ id: String, _ seq: Int, _ body: UtteranceResponse) {
        guard id == callId, state.phase == .inCall else { return }   // a late answer for an ended call
        if body.ignored == true {
            // B.6: the call had already ended on the server. No change: just stop the bubble's "checking…".
            updateLine(seq) { $0.pending = false }
            return
        }
        guard let verdict = body.verdict, Self.knownVerdicts.contains(verdict) else {
            markError(id, seq, "Server gave no verdict")   // not checked, never safe
            return
        }
        // A resent seq (duplicate: true) was not scored again; its answer still carries the call's state.
        let flags = Risk.signals.filter { (body.score($0) ?? 0) >= Risk.signalOn }
        if body.duplicate != true {
            points.append(PointRow(
                callId: callId, seq: seq, secondsIntoCall: Float(now() - callStartedAt) / 1000,
                combined: body.combinedScore, urgency: body.score("urgency"), money: body.score("money"),
                impersonation: body.score("impersonation")))
        }
        let isNewest = seq >= lastOutcomeSeq            // answers can arrive out of order; newest wins
        if isNewest { lastOutcomeSeq = seq }
        updateLine(seq) { $0.pending = false; $0.flags = flags }
        if isNewest {
            state.verdict = verdict
            state.combinedScore = body.combinedScore ?? state.combinedScore
            state.callScores = body.callScores ?? state.callScores
            state.serverAction = body.action ?? state.serverAction
            state.gatewayError = nil                    // a newer line got through: an earlier blip no longer counts
        }
        // First time the call turns SCAM -> one haptic + red pop-up.
        if state.verdict == "SCAM" && !state.scamAlert {
            alertAt = now()
            state.scamAlert = true
            haptic()
        }
    }

    func markError(_ id: String, _ seq: Int, _ msg: String) {
        guard id == callId, state.phase == .inCall else { return }
        let isNewest = seq >= lastOutcomeSeq            // an old line failing late does not override a newer answer
        if isNewest { lastOutcomeSeq = seq }
        updateLine(seq) { $0.pending = false; $0.failed = true }
        if isNewest { state.gatewayError = msg }
    }

    private func updateLine(_ seq: Int, _ f: (inout ChatLine) -> Void) {
        if let i = state.lines.firstIndex(where: { $0.seq == seq }) { f(&state.lines[i]) }
    }

    func markCloud(_ seq: Int, _ note: String) { updateLine(seq) { $0.cloudNote = note } }

    // ------------------------------------------------------------ levels 2 and 3 (on the phone)

    /// G.2 / G.3: the same playback, but every caller line is scored on the phone (OnDeviceCall). Level 3 makes no
    /// request at all. Level 2 asks the cloud's BERT only at unsure moments or on the spot check, with the caller's
    /// last 5 lines masked, and keeps its own answer if the cloud is slow or down. Lines are handled in order.
    private func playOnDevice(_ script: CallScript, _ level: PrivacyLevel) {
        let id = callId
        let hybrid = level == .hybrid
        let api: GatewayClient? = hybrid ? gateway() : nil       // level 3: never even built
        let services = onDevice
        playTask = Task { [weak self] in
            let loading = Task { await services.call() }
            let mobileBert: MobileBertModel? = hybrid ? nil : await services.mobileBertModel()
            var previous: Task<Void, Never>?
            for (i, line) in script.lines.enumerated() {
                guard await Async.sleep(ms: self?.scaled(line.delayMs ?? Self.defaultDelayMs) ?? 0), let self else { return }
                let seq = i + 1
                let text = line.text ?? ""
                self.state.lines.append(ChatLine(seq: seq, isCaller: line.isCaller, text: text, pending: line.isCaller))
                if !line.isCaller { continue }
                let before = previous
                let t = Task { [weak self] in
                    await before?.value                 // one line at a time, in order (the rules need that)
                    guard !Task.isCancelled, let local = await loading.value else {
                        if !Task.isCancelled { self?.markError(id, seq, "On-device models could not be loaded") }
                        return
                    }
                    if !OnDeviceCall.isEnglish(script.lang, text) {
                        self?.markError(id, seq, "On-device checks are English only")
                        return
                    }
                    if hybrid {
                        let a = await local.answerHybrid(callId: id, seq: seq, text: text) { lines in
                            await Self.verifyInCloud(api, lines)
                        }
                        guard !Task.isCancelled else { return }
                        self?.applyAnswer(id, seq, a.response)
                        if let c = a.second { self?.markCloud(seq, c.bertScore != nil ? Self.cloudChecked : Self.cloudUnavailable) }
                    } else if let mobileBert {
                        // G.4: same rule as Hybrid, but MobileBERT reads the lines on the phone (not masked: they
                        // never leave it).
                        let a = await local.answerWithSecondOpinion(callId: id, seq: seq, text: text, prepare: { $0 }) { lines in
                            await mobileBert.score(lines)
                        }
                        guard !Task.isCancelled else { return }
                        self?.applyAnswer(id, seq, a.response)
                        if let c = a.second { self?.markCloud(seq, c.bertScore != nil ? Self.mobileBertChecked : Self.mobileBertFailed) }
                    } else {
                        let r = await local.answer(callId: id, seq: seq, text: text)
                        guard !Task.isCancelled else { return }
                        self?.applyAnswer(id, seq, r)
                    }
                }
                previous = t
                self.sendTasks.append(t)
            }
            guard let tasks = self?.sendTasks else { return }
            await Async.waitAll(tasks, within: 5)
            guard !Task.isCancelled, let self else { return }
            self.sendTasks.forEach { $0.cancel() }
            self.sendTasks = []
            guard await Async.sleep(ms: self.scaled(1_500)) else { return }
            self.endCall(Self.endedScript)
        }
    }

    /// G.3: POST /v1/verify with the masked lines; BERT's score, or nil if the cloud didn't answer within 1.5 s
    /// (or isn't reachable / set up), in which case the phone keeps its own answer.
    nonisolated static func verifyInCloud(_ api: GatewayClient?, _ maskedLines: [String]) async -> Float? {
        guard let api, !maskedLines.isEmpty else { return nil }
        let t = Task<Float?, Never> {
            guard let r = try? await api.verify(VerifyRequest(lines: maskedLines)), r.isSuccessful,
                  let s = r.body?.score, (0...1).contains(s) else { return nil }
            return s
        }
        let v = await Async.value(of: t, within: OnDeviceCall.verifyTimeout)
        if v == nil { t.cancel() }
        return v
    }

    private func preloadModels() {
        let services = onDevice
        Task.detached { _ = await services.scorer() }
    }

    // ------------------------------------------------------------ H.3 report / "this was fine"

    /// Exactly what a report would send: the caller's lines, masked. Shown on the consent screen first.
    func reportPreview() -> [ReportLine] {
        state.lines.filter { $0.isCaller && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { ReportLine(seq: $0.seq, text: Mask.mask($0.text)) }
    }

    /// Sends the report after the user agreed. kind = "scam" or "fine". Returns a short message for the screen.
    func sendReport(_ kind: String) async -> String {
        let s = state
        if !s.privacyLevel.cloudCall { return Self.nothingShared }
        let lines = reportPreview()
        if lines.isEmpty { return "Nothing to report: the caller said nothing." }
        let text = lines.map(\.text).joined(separator: " ")
        let chinese = Py.countUTF16(text) { (0x4E00...0x9FFF).contains($0) }
        let language = s.script?.lang ?? (chinese * 10 > text.utf16.count * 3 ? "zh" : "en")   // gateway's 30% rule
        guard let api = gateway() else { return "Could not send the report: not a valid server address" }
        do {
            let resp = try await api.report(ReportRequest(
                callId: callId.isEmpty ? UUID().uuidString.lowercased() : callId, kind: kind,
                appVerdict: s.verdict ?? "", language: language, consent: true, lines: lines))
            if resp.isSuccessful {
                return "Thank you. Shared \(resp.body?.linesStored ?? lines.count) lines; " +
                    "lines not approved for training are deleted after 30 days."
            }
            if resp.status == 503 { return "Reporting is not set up on this server." }
            return "Could not send the report (server answered \(resp.status))."
        } catch {
            return "Could not send the report: \(Self.describe(error))"
        }
    }

    // ------------------------------------------------------------ settings + history

    func saveGatewayUrl(_ url: String) {
        if GatewayClient.normalize(url) != prefs.gatewayUrl { connection = nil }
        prefs.gatewayUrl = url
        gatewayUrl = prefs.gatewayUrl
    }

    /// GET /healthz on the given URL; returns a short message for the Settings screen.
    func testConnection(_ url: String) async -> String {
        let msg = await checkConnection(url)
        if GatewayClient.normalize(url) == prefs.gatewayUrl { connection = msg.hasPrefix("Connected") }
        return msg
    }

    /// Quiet GET /healthz on the saved address, for the Settings list.
    func refreshConnection() async { _ = await testConnection(prefs.gatewayUrl) }

    #if DEBUG
    /// Debug builds only (Help & about): sample history for demos and screenshots.
    func loadSampleHistory() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let todayStart = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
        store.insertSample(SampleHistory.generate(nowMs: now, todayStartMs: todayStart))
        historyVersion += 1
    }
    #endif

    private func checkConnection(_ url: String) async -> String {
        guard let api = try? GatewayClient(baseUrl: url, session: session) else { return "Not a valid address" }
        do {
            let resp = try await api.health()
            if !resp.isSuccessful {
                return "Server answered \(resp.status)" +
                    (resp.status == 404 ? ": check the address (Kubernetes HTTPS addresses end in /api/)" : "")
            }
            // Only the gateway's own JSON counts: a proxy in front of it can answer 200 with an empty body.
            if resp.body?.status == "ok" { return "Connected: ok" }
            return "Something answered, but not the ScamGuard gateway: check the address"
        } catch let e as URLError {
            switch e.code {
            case .serverCertificateUntrusted, .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
                 .serverCertificateHasBadDate, .clientCertificateRejected, .secureConnectionFailed:
                return "Certificate not trusted: install demo/tls/ca.crt as a profile on this iPhone (Settings → " +
                    "General → VPN & Device Management), then turn it on in General → About → Certificate Trust " +
                    "Settings. If it is installed, the certificate may not cover this address: re-run demo/expose."
            case .notConnectedToInternet:
                return "Could not connect: allow Local Network for ScamGuard (iOS Settings → Privacy & Security → " +
                    "Local Network), and check the Mac's address"
            case .timedOut:
                return "Could not connect: timed out (is the server running, and is this the Mac's address?)"
            case .cannotConnectToHost:
                return "Could not connect: nothing is listening at this address and port"
            default:
                return "Could not connect: \(Self.describe(e))"
            }
        } catch {
            return "Could not connect: \(Self.describe(error))"
        }
    }

    nonisolated static func describe(_ e: Error) -> String {
        if let u = e as? URLError { return "URLError \(u.code.rawValue) (\(u.localizedDescription))" }
        return String(describing: type(of: e))
    }

    /// "Delete my history": every saved call, its chart points, labels and stored sentences.
    func deleteHistory() {
        store.deleteHistory()
        historyVersion += 1
    }

    /// "Save the riskiest sentence". Turning it off also forgets the sentences already saved.
    func setSaveSentence(_ on: Bool) {
        prefs.saveRiskiestSentence = on
        saveSentence = on
        if !on { store.forgetSentences(); historyVersion += 1 }
    }

    /// Settings' privacy level. Applies from the next call.
    func setPrivacyLevel(_ level: PrivacyLevel) {
        guard level.available else { return }
        prefs.privacyLevel = level
        privacyLevel = prefs.privacyLevel
        if level.scoresOnPhone { preloadModels() }
    }

    /// "Help improve detection". Turning it off deletes the sentences already stored with labels.
    func setHelpImprove(_ on: Bool) {
        prefs.helpImproveDetection = on
        helpImprove = on
        if !on { store.forgetLabelSentences(); historyVersion += 1 }
    }

    /// G.10: ask the model-download service for newer versions; also the Settings button.
    func checkModelUpdates() async -> String {
        modelCheck = "Checking for model updates…"
        let msg = await onDevice.updater.update(gatewayUrl: prefs.gatewayUrl, session: session)
        modelCheck = msg
        modelStatus = onDevice.updater.describe()
        if msg.hasPrefix("Updated") { await onDevice.reload() }
        if prefs.privacyLevel.scoresOnPhone { preloadModels() }
        return msg
    }
}
