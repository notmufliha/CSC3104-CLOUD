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
    /// Red warning sound, haptics and speech; the yellow tap. Silent in tests.
    let alerts: WarningAlerts

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
    /// Debug builds only: a demo connection state stays (no real check replaces it).
    private var connectionFixed = false
    /// "Read warnings aloud" and "Show 'Be careful' warnings".
    private(set) var readAloud: Bool
    private(set) var showBeCareful: Bool
    /// The trusted family contact, nil = none set.
    private(set) var family: Family?

    // ------------------------------------------------------------ Developer view (hidden; Logic/Developer.swift)
    // Only the "unlocked" flag is saved. The overlay switch, the playback speed and the log live in memory.
    let devLog: DevLog
    private let dev: DevSession
    private(set) var devUnlocked = false
    /// "Show live scores during calls".
    private(set) var devOverlay = false
    /// "Call playback speed": scripted and pasted calls.
    private(set) var playbackSpeed: PlaybackSpeed = .x1
    /// The newest caller line's numbers (the overlay).
    private(set) var devLatest: DevLine?
    /// Bumped when the log changes, so the Developer view redraws.
    private(set) var devTick = 0
    /// When each line of this call appeared (ms since it started).
    private var lineShownAt: [Int: Int] = [:]
    /// "Learn to spot scams": the bundled cards and quiz lines (Resources/Learn/learn.json, shared with Android).
    let learn = Learn.bundled()
    /// The practice quiz's best score, nil = never played.
    private(set) var quizBest: Int?
    func recordQuiz(_ score: Int) {
        prefs.quizBest = Quiz.best(prefs.quizBest, score)
        quizBest = prefs.quizBest
    }

    // ------------------------------------------------------------ weekly summary (Logic/WeeklySummary.swift)
    let notifier: WeeklyNotifier
    private(set) var weeklyReminder = false

    /// The note for the family: the last 7 days of this phone's history, counts and plain reasons only.
    func weeklySummary() -> String {
        WeeklySummary.text(store.calls().map { WeeklySummary.Call(endedAt: $0.endedAt, verdict: $0.finalVerdict, reasonCode: $0.reasonCode) },
                           nowMs: now())
    }

    /// The "Weekly reminder" switch. Notification permission is asked only here, when turning it on. Returns false
    /// when notifications were refused (the switch stays off).
    @discardableResult
    func setWeeklyReminder(_ on: Bool) async -> Bool {
        var action = WeeklyReminder.onToggle(on: on, permitted: await notifier.permitted())
        var stayOn = on
        if action == .askPermission {
            (stayOn, action) = WeeklyReminder.afterPermission(granted: await notifier.requestPermission())
        }
        switch action {
        case .schedule: notifier.scheduleWeekly()
        case .cancel: notifier.cancelWeekly()
        case .nothing, .askPermission: break
        }
        prefs.weeklyReminder = action == .schedule ? true : (action == .cancel ? false : stayOn)
        weeklyReminder = prefs.weeklyReminder
        return !(on && !weeklyReminder)
    }

    /// Developer view: MobileBERT's status, version and size.
    private(set) var mobileBertDev = "checking…"

    struct Family: Equatable { let name: String; let phone: String }
    /// App style: Simple mode (Simple Home, For family, short Settings) or Standard (the full app).
    private(set) var simpleMode = false
    /// Home's one-time tip for users set up before the "Who will use this phone?" question.
    private(set) var appStyleTip = false
    /// The optional PIN (only its salted hash is kept, in the Keychain).
    let pins: PinStore
    private(set) var pinSet = false

    // ------------------------------------------------------------ first-launch setup + guided tour

    /// What the app shows: the setup, or the app itself.
    enum Gate: Equatable { case setup, app }
    private(set) var gate: Gate = .app
    /// The setup screen showing, and the choices made so far.
    private(set) var setupStep: Onboarding.Step = .who
    private(set) var setupChoices: Onboarding.Choices
    /// true = opened from Settings ("Run setup again"): leaving it goes back to the app without saving.
    private(set) var setupIsReplay = false
    /// The tour step showing (0-6), nil = no tour.
    private(set) var tourStep: Int?
    private let startUp: Bool

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
    static var nothingShared: String { L.s(.nothing_shared) }
    /** The last report went through (the summary then turns its buttons off). */
    private(set) var reportSent = false
    static let cloudChecked = "checked with cloud"
    static let cloudUnavailable = "cloud check unavailable, checked on this phone"
    static let mobileBertChecked = "checked with MobileBERT on this phone"
    static let mobileBertFailed = "MobileBERT failed, small models only"
    private static let knownVerdicts: Set<String> = ["SAFE", "UNSURE", "SCAM"]
    static let underTests = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    private static let warningVerdicts: Set<String> = ["SCAM", "UNSURE"]

    init(prefs: AppPrefs = AppPrefs(),
         store: CallStore,
         scripts: [CallScript] = ScriptRepository.loadAll(),
         session: URLSession = URLSession(configuration: Network.gatewayConfiguration()),
         delayScale: Double = 1,
         haptic: @escaping @MainActor () -> Void = {},
         onDevice: OnDeviceServices = OnDeviceServices(),
         alerts: WarningAlerts? = nil,
         pins: PinStore = PinStore(),
         startUp: Bool = true,
         devLog: DevLog = .shared,
         notifier: WeeklyNotifier = LiveWeeklyNotifier()) {
        self.notifier = notifier
        self.prefs = prefs
        self.devLog = devLog
        dev = DevSession(store: prefs, log: devLog)
        devUnlocked = prefs.developerUnlocked
        quizBest = prefs.quizBest
        weeklyReminder = prefs.weeklyReminder
        self.store = store
        self.scripts = scripts
        self.session = session
        self.delayScale = delayScale
        self.haptic = haptic
        self.onDevice = onDevice
        // Sound and speech only in the real app, never while unit tests run inside it.
        self.alerts = alerts ?? (startUp && !Self.underTests ? AlertPlayer() : SilentAlerts())
        readAloud = prefs.readAloud
        textSize = prefs.textSize
        showBeCareful = prefs.showBeCareful
        family = Self.savedFamily(prefs)
        let simple = AppStyle.simpleAtLaunch(prefs.simpleMode)
        simpleMode = simple
        appStyleTip = AppStyle.showTip(simple: simple, tipShown: prefs.appStyleTipShown)
        self.pins = pins
        pinSet = pins.isSet
        gatewayUrl = prefs.gatewayUrl
        privacyLevel = prefs.privacyLevel
        saveSentence = prefs.saveRiskiestSentence
        helpImprove = prefs.helpImproveDetection
        modelStatus = onDevice.updater.describe()
        self.startUp = startUp
        setupChoices = Onboarding.defaults()
        if startUp { startUpTasks() }
        // First launch shows the setup; an install from before the setup (settings or history saved) never does.
        let completed = prefs.onboardingCompleted
        let hasSettings = prefs.hasAnySetting()
        let hasHistory = completed == nil && !hasSettings && !store.calls().isEmpty
        switch Onboarding.atLaunch(completed: completed, hasSettings: hasSettings, hasHistory: hasHistory) {
        case .showSetup: setupTextSize(); gate = .setup
        case .existingUser: Onboarding.markCompleted(prefs); openApp()
        case .done: openApp()
        }
    }

    /// App start: preload the models if needed and read MobileBERT's status (both stay on the phone). The network
    /// check for newer models waits until the app opens (openApp), so the setup sends nothing.
    private func startUpTasks() {
        // Re-arm a weekly reminder that is on. Never asks for anything.
        if prefs.weeklyReminder {
            let n = notifier
            Task { if WeeklyReminder.atLaunch(savedOn: true, permitted: await n.permitted()) == .schedule { n.scheduleWeekly() } }
        }
        if privacyLevel.scoresOnPhone { preloadModels() }
        Task {
            mobileBertStatus = await onDevice.mobileBertStatusLine()
            mobileBertDev = await onDevice.mobileBertDevLine()
        }
    }

    private func gateway() -> GatewayClient? { try? GatewayClient(baseUrl: prefs.gatewayUrl, session: session, log: devLog) }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func scaled(_ ms: Int) -> Int { Int(Double(ms) * delayScale) }

    // ------------------------------------------------------------ call lifecycle

    /// A random script of this kind, avoiding the one picked last time when there is another to choose. The
    /// learning-loop test calls only while the Developer view is unlocked.
    func randomScript(_ kind: String) -> CallScript? {
        let pool = scripts.filter { $0.kind == kind && (devUnlocked || !DevScripts.isTestCall($0.fileName)) }
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
        alerts.stop()
        reportSent = false
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
        lineShownAt = [:]
        let level = prefs.privacyLevel
        devLog.startCall(callId, privacy: level.rawValue)
        devLatest = nil
        state.phase = .inCall
        state.privacyLevel = level
        state.speedTag = playbackSpeed.tag
        if level.scoresOnPhone {
            playOnDevice(script, level)
            return
        }

        let api = gateway()
        if api == nil { state.gatewayError = "Bad server address. Check Settings." }
        let id = callId
        let speed = playbackSpeed
        playTask = Task { [weak self] in
            guard let self else { return }
            // Playback.play scales each line's wait (Developer view's playback speed; 1× normally) and always ends.
            await Playback.play(script.lines, speed: speed, defaultDelayMs: Self.defaultDelayMs,
                                sleep: { await Async.sleep(ms: self.scaled($0)) },
                                onLine: { i, line in
                let seq = i + 1                         // caller and receiver share one count, as the gateway expects
                let text = line.text ?? ""
                let waiting = line.isCaller && api != nil
                self.lineShownAt[seq] = Int(self.now() - self.callStartedAt)
                self.state.lines.append(ChatLine(seq: seq, isCaller: line.isCaller, text: text, pending: waiting,
                                                 failed: line.isCaller && api == nil))
                if waiting, let api {
                    self.sendTasks.append(Task { [weak self] in await self?.send(api, id, seq, text, script.lang) })
                }
            }) {
                await Async.waitAll(self.sendTasks, within: 5)   // let the last answers arrive
                guard !Task.isCancelled else { return }
                self.sendTasks.forEach { $0.cancel() }      // nothing may change the call after this point
                self.sendTasks = []
                guard await Async.sleep(ms: self.scaled(Playback.gap(1_500, speed))) else { return }   // a moment to read the final state
                self.endCall(Self.endedScript)
            }
        }
    }

    /// User pressed the red button (or "Hang up now" on the red alert).
    func hangUp() {
        alerts.stop()
        state.warningOpen = false
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
            self.devLog.endCall(id, endedBy: endedBy, end: resp.map {
                DevEnd(finalVerdict: $0.finalVerdict, callScore: $0.callScore, review: $0.review, topWindow: $0.topWindowSeqs ?? [])
            })
            self.devTick += 1
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
        let started = now()
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
        if body.ignored != true { devRecord(id, seq, body, place: "cloud", latencyMs: Int(now() - started)) }
    }

    /// One answered caller line into the Developer view's memory (numbers only; nothing while it is locked).
    func devRecord(_ id: String, _ seq: Int, _ body: UtteranceResponse, place: String, latencyMs: Int) {
        guard devLog.recording, id == callId else { return }
        devLog.addLine(id, DevLine(
            seq: seq, tMs: lineShownAt[seq] ?? 0, urgency: body.score("urgency"), money: body.score("money"),
            impersonation: body.score("impersonation"), combined: body.combinedScore, bertScore: body.bertScore,
            bertStatus: body.bertStatus, rule: body.rule, liveAlert: body.liveAlert, verdict: body.verdict,
            place: place, latencyMs: latencyMs))
        if let v = body.modelVersions { devLog.setModelVersions(v) }
        devLatest = devLog.latestLine(id)
        devTick += 1
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
        // First time the call turns SCAM -> the full-screen red warning, sound, haptics and the spoken warning.
        if state.verdict == "SCAM" && !state.scamAlert {
            alertAt = now()
            state.scamAlert = true
            state.warningOpen = true
            haptic()
            alerts.red(prefs.readAloud ? speech(state.warningKind) : nil)
        } else if state.verdict == "UNSURE" && !state.scamAlert && !state.carefulAlerted {
            // Yellow: one short tap, no sound, no pop-up; nothing at all when "Be careful" warnings are off (the
            // call is still saved as "Be careful").
            state.carefulAlerted = true
            if prefs.showBeCareful { alerts.yellow() }
        }
    }

    /// The spoken warning (English).
    func speech(_ kind: Warnings.Kind) -> WarningSpeech {
        WarningSpeech(text: Warnings.spoken(kind))
    }

    // ------------------------------------------------------------ warning actions + settings

    /// "Close this warning": back to the call (or its summary, if it has ended). The call goes on.
    func closeWarning() {
        alerts.stop()
        state.warningOpen = false
    }

    /// "Call my family" / "Call 1799": hang up the scam call first (if it is still on); the screen then opens the
    /// phone app with the number filled in. The user taps Call.
    func leaveCallForHelp() {
        if state.phase == .inCall { hangUp() } else { closeWarning() }
    }

    /// The message for "Tell [name]" (Messages opens with it; the user taps Send).
    func familyMessage() -> String { Warnings.familyMessage() }

    /// "Text size": AppPrefs.textLarge (default) or textXLarge.
    private(set) var textSize: String = AppPrefs.textNormal
    /// Settings → Text size: the user's own choice, kept when the app style changes.
    func setTextSize(_ size: String) { prefs.textSize = size; prefs.textSizeChosen = true; textSize = prefs.textSize }
    /// The setup shows a text size before anything is saved (a replay that is left restores the saved one).
    func previewTextSize(_ size: String) { textSize = size }

    // ------------------------------------------------------------ Simple mode, PIN

    /// Settings → App style. Keeps the history, contact and PIN; text size / read-aloud follow unless the user set them.
    func setAppStyle(simple: Bool) {
        AppStyle.switchTo(simple: simple, prefs)
        simpleMode = simple
        textSize = prefs.textSize
        readAloud = prefs.readAloud
        appStyleTip = false
    }

    /// Leaving Simple mode asks for the PIN first when one is set.
    func appStyleNeedsPin(toSimple: Bool) -> Bool { AppStyle.needsPin(fromSimple: simpleMode, toSimple: toSimple, pinSet: pinSet) }

    func dismissAppStyleTip() {
        prefs.appStyleTipShown = true
        appStyleTip = false
    }

    func verifyPin(_ pin: String) -> Bool { pins.verify(pin) }
    func setPin(_ pin: String) { pins.set(pin); pinSet = pins.isSet }
    /// "Forgot PIN" / "Turn off the PIN": removes only the PIN; the call history is not touched.
    func clearPin() { pins.clear(); pinSet = pins.isSet }

    func setReadAloud(_ on: Bool) { prefs.readAloud = on; prefs.readAloudChosen = true; readAloud = on }
    func setShowBeCareful(_ on: Bool) { prefs.showBeCareful = on; showBeCareful = on }

    func setFamily(name: String, phone: String) {
        prefs.familyName = name
        prefs.familyPhone = phone
        family = Self.savedFamily(prefs)
    }

    private static func savedFamily(_ p: AppPrefs) -> Family? {
        let phone = p.familyPhone
        guard !phone.isEmpty else { return nil }
        return Family(name: p.familyName.isEmpty ? phone : p.familyName, phone: phone)
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
            guard let me = self else { return }
            let speed = me.playbackSpeed
            await Playback.play(script.lines, speed: speed, defaultDelayMs: Self.defaultDelayMs,
                                sleep: { await Async.sleep(ms: me.scaled($0)) },
                                onLine: { i, line in
                let seq = i + 1
                let text = line.text ?? ""
                me.lineShownAt[seq] = Int(me.now() - me.callStartedAt)
                me.state.lines.append(ChatLine(seq: seq, isCaller: line.isCaller, text: text, pending: line.isCaller))
                if !line.isCaller { return }
                let before = previous
                let t = Task { [weak self] in
                    await before?.value                 // one line at a time, in order (the rules need that)
                    let started = Int64(Date().timeIntervalSince1970 * 1000)
                    func took() -> Int { Int(Int64(Date().timeIntervalSince1970 * 1000) - started) }
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
                        self?.devRecord(id, seq, a.response, place: a.second == nil ? "phone"
                                        : a.second?.bertScore != nil ? "phone + cloud BERT" : "phone (cloud BERT unavailable)", latencyMs: took())
                    } else if let mobileBert {
                        // G.4: same rule as Hybrid, but MobileBERT reads the lines on the phone (not masked: they
                        // never leave it).
                        let a = await local.answerWithSecondOpinion(callId: id, seq: seq, text: text, prepare: { $0 }) { lines in
                            await mobileBert.score(lines)
                        }
                        guard !Task.isCancelled else { return }
                        self?.applyAnswer(id, seq, a.response)
                        if let c = a.second { self?.markCloud(seq, c.bertScore != nil ? Self.mobileBertChecked : Self.mobileBertFailed) }
                        self?.devRecord(id, seq, a.response, place: a.second == nil ? "phone"
                                        : a.second?.bertScore != nil ? "phone + MobileBERT" : "phone (MobileBERT failed)", latencyMs: took())
                    } else {
                        let r = await local.answer(callId: id, seq: seq, text: text)
                        guard !Task.isCancelled else { return }
                        self?.applyAnswer(id, seq, r)
                        self?.devRecord(id, seq, r, place: "phone", latencyMs: took())
                    }
                }
                previous = t
                me.sendTasks.append(t)
            }) {
                await Async.waitAll(me.sendTasks, within: 5)
                guard !Task.isCancelled else { return }
                me.sendTasks.forEach { $0.cancel() }
                me.sendTasks = []
                guard await Async.sleep(ms: me.scaled(Playback.gap(1_500, speed))) else { return }
                me.endCall(Self.endedScript)
            }
        }
    }

    /// G.3: POST /v1/verify with the masked lines; BERT's score, or nil if the cloud didn't answer within 1.5 s
    /// (or isn't reachable / set up), in which case the phone keeps its own answer.
    nonisolated static func verifyInCloud(_ api: GatewayClient?, _ maskedLines: [String]) async -> Float? {
        guard let api, !maskedLines.isEmpty else { return nil }
        let t = Task<Float?, Never> {
            let reply = try? await api.verify(VerifyRequest(lines: maskedLines))
            if let v = reply?.body?.modelVersion { api.log.setModelVersions(["bert": v]) }
            guard let r = reply, r.isSuccessful,
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
        if lines.isEmpty { return L.s(.report_nothing) }
        let text = lines.map(\.text).joined(separator: " ")
        let chinese = Py.countUTF16(text) { (0x4E00...0x9FFF).contains($0) }
        let language = s.script?.lang ?? (chinese * 10 > text.utf16.count * 3 ? "zh" : "en")   // gateway's 30% rule
        guard let api = gateway() else { return L.s(.report_failed) }
        do {
            let resp = try await api.report(ReportRequest(
                callId: callId.isEmpty ? UUID().uuidString.lowercased() : callId, kind: kind,
                appVerdict: s.verdict ?? "", language: language, consent: true, lines: lines))
            if resp.isSuccessful {
                reportSent = true
                return L.s(.report_thanks, "\(resp.body?.linesStored ?? lines.count)")
            }
            if resp.status == 503 { return L.s(.report_not_set_up) }
            return L.s(.report_failed)
        } catch {
            return L.s(.report_failed)
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
        devLog.setHealth(DevHealth(ok: msg.hasPrefix("Connected"), message: "\(msg) (\(GatewayClient.normalize(url)))", timeMs: now()))
        devTick += 1
        return msg
    }

    /// Quiet GET /healthz on the saved address, for the Settings list and "Try again" on the plain status. Not at
    /// On-device: nothing may touch the network there, and the status says "not needed".
    func refreshConnection() async {
        if connectionFixed || prefs.privacyLevel == .onDevice { return }
        _ = await testConnection(prefs.gatewayUrl)
    }

    /// Developer view (and debug launch options): about 60 made-up calls.
    func loadSampleHistory() {
        #if !DEBUG
        guard devUnlocked else { return }
        #endif
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let todayStart = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
        store.insertSample(SampleHistory.generate(nowMs: now, todayStartMs: todayStart))
        historyVersion += 1
    }

    /// Developer view: remove the made-up calls again (real calls stay).
    func deleteSampleHistory() {
        guard devUnlocked else { return }
        store.deleteSample()
        historyVersion += 1
    }

    private func checkConnection(_ url: String) async -> String {
        guard let api = try? GatewayClient(baseUrl: url, session: session, log: devLog) else { return "Not a valid address" }
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
        // The Developer view's memory goes too (it holds numbers from the last call).
        dev.historyDeleted()
        devLatest = nil
        devTick += 1
    }

    // ------------------------------------------------------------ Developer view actions

    /// Help & about: one tap on the version number. Returns what to tell the user.
    func developerTap(_ count: Int) -> DevUnlock.Tap {
        let t = dev.tap(count)
        syncDev()
        return t
    }

    /// The switch in Developer view; off hides it again and forgets everything it held.
    func setDeveloperUnlocked(_ on: Bool) { dev.setUnlocked(on); syncDev() }
    func setDevOverlay(_ on: Bool) { dev.setOverlay(on); syncDev() }
    func setPlaybackSpeed(_ s: PlaybackSpeed) { dev.setSpeed(s); syncDev() }
    func refreshDevView() { devTick += 1 }

    private func syncDev() {
        devUnlocked = dev.unlocked
        devOverlay = dev.overlay
        playbackSpeed = dev.speed
        if !devUnlocked { devLatest = nil }
        devTick += 1
    }

    /// Developer view → 1. Connection.
    func devConnectionRows() -> [(String, String)] {
        let info = Bundle.main.infoDictionary
        let h = devLog.health()
        return [
            ("gateway URL", prefs.gatewayUrl),
            ("last /healthz", h.map { "\($0.ok ? "ok" : "failed") at \(DevFormat.time($0.timeMs)): \($0.message)" } ?? "not checked since unlocking"),
            ("privacy level", prefs.privacyLevel.rawValue),
            ("app version", "\(info?["CFBundleShortVersionString"] as? String ?? "?") (build \(info?["CFBundleVersion"] as? String ?? "?"))"),
            ("platform", "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion), \(UIDevice.current.model)"),
            ("local network permission", "iOS doesn't report it; a refused permission shows as a failed /healthz (Not connected to Internet)"),
        ]
    }

    /// Developer view → 2. Models.
    func devModelRows() -> [(String, String)] {
        let bundled = Bundle.main.url(forResource: "manifest", withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        let installed = try? Data(contentsOf: onDevice.updater.dir.appendingPathComponent("installed.json"))
        let downloaded = DevModels.downloaded(installed)
        let server = devLog.modelVersions().sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ")
        return DevModels.bundled(bundled) + (downloaded.isEmpty ? [("downloaded", "none")] : downloaded) + [
            ("MobileBERT", mobileBertDev),
            ("server model_versions", server.isEmpty ? "none yet (from the last reply)" : server),
        ]
    }

    /// Developer view → Tools → "Copy diagnostics": sections 1-3, no caller text.
    func devDiagnostics() -> String {
        Diagnostics.text(connection: devConnectionRows(), models: devModelRows(), call: devLog.lastCall())
    }

    /// Developer view → "Reset app to first launch": deletes the call history (all tables), every setting (the
    /// Developer view's flag too), the PIN, the downloaded models and the in-memory logs, then starts at "Who will use
    /// this phone?". Bundled models and scripts stay.
    func resetApp() {
        guard devUnlocked else { return }
        alerts.stop()
        cancelPlayback()
        callId = ""
        state = CallUiState()
        store.deleteHistory()
        prefs.clearAll()
        pins.clear()
        try? FileManager.default.removeItem(at: onDevice.updater.dir)
        dev.wasReset()
        syncDev()
        let services = onDevice
        Task { await services.reload() }
        connection = nil
        connectionFixed = false
        privacyLevel = prefs.privacyLevel
        gatewayUrl = prefs.gatewayUrl
        saveSentence = prefs.saveRiskiestSentence
        helpImprove = prefs.helpImproveDetection
        readAloud = prefs.readAloud
        showBeCareful = prefs.showBeCareful
        textSize = prefs.textSize
        simpleMode = AppStyle.simpleAtLaunch(prefs.simpleMode)
        appStyleTip = false
        family = Self.savedFamily(prefs)
        quizBest = prefs.quizBest
        notifier.cancelWeekly()
        weeklyReminder = prefs.weeklyReminder
        pinSet = pins.isSet
        modelStatus = onDevice.updater.describe()
        modelCheck = ""
        historyVersion += 1
        setupChoices = Onboarding.defaults()
        setupStep = .who
        setupIsReplay = false
        setupTextSize()
        tourStep = nil
        gate = .setup
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

    // ------------------------------------------------------------ setup + tour actions (same as Android)

    /// The app opens: now (not during the setup) it may use the network. G.10: quietly fetch newer federated models,
    /// except at level 3, which makes no network traffic at all.
    private func openApp() {
        gate = .app
        if startUp && prefs.privacyLevel != .onDevice { Task { _ = await checkModelUpdates() } }
    }

    func setSetupStep(_ step: Onboarding.Step) { setupStep = step }

    func updateSetup(_ change: (inout Onboarding.Choices) -> Void) { change(&setupChoices) }

    /// Skip, "Show me around" or "Skip tour": saves the choices (Skip = what is picked so far, defaults for the rest).
    func finishSetup(startTour: Bool) {
        let c = setupChoices
        let urlBefore = prefs.gatewayUrl
        Onboarding.apply(c, prefs)
        privacyLevel = prefs.privacyLevel
        gatewayUrl = prefs.gatewayUrl
        if prefs.gatewayUrl != urlBefore { connection = nil }
        saveSentence = c.saveSentence
        helpImprove = c.helpImprove
        textSize = prefs.textSize
        readAloud = prefs.readAloud
        simpleMode = prefs.simpleMode == true
        appStyleTip = false
        family = Self.savedFamily(prefs)
        if Pin.isValid(c.pin) { setPin(c.pin) }
        if !c.saveSentence { store.forgetSentences() }
        if !c.helpImprove { store.forgetLabelSentences() }
        historyVersion += 1
        if c.privacy.scoresOnPhone { preloadModels() }
        let wasReplay = setupIsReplay
        setupIsReplay = false
        if wasReplay { gate = .app } else { openApp() }
        if startTour { self.startTour() } else { tourStep = nil }
    }

    /// Until "Who will use this phone?" is answered the setup is in large text (an older person may be reading it);
    /// the answer then shows that style's size. A size the user picked themselves stays.
    func setupTextSize() {
        if !prefs.textSizeChosen { textSize = AppStyle.textSize(simple: true) }
    }

    /// Leaving "Run setup again" from its first screen: nothing is saved.
    func leaveSetup() {
        setupIsReplay = false
        textSize = prefs.textSize
        gate = .app
    }

    /// Help & about: "Run setup again", starting from the current settings.
    func runSetupAgain() {
        setupChoices = Onboarding.fromSaved(prefs)
        setupStep = .who
        setupTextSize()
        setupIsReplay = true
        tourStep = nil
        gate = .setup
    }

    /// Developer view → "Reset setup only" (and a debug launch option): show the setup as on a first launch. The
    /// history and settings stay until the setup saves its choices.
    func resetOnboarding() {
        #if !DEBUG
        guard devUnlocked else { return }
        #endif
        Onboarding.reset(prefs)
        setupChoices = Onboarding.defaults()
        setupStep = .who
        setupTextSize()
        setupIsReplay = false
        tourStep = nil
        gate = .setup
    }

    #if DEBUG
    /// Debug builds only (-demoTip YES): show the one-time App style tip again, for screenshots.
    func demoAppStyleTip() {
        prefs.appStyleTipShown = false
        appStyleTip = AppStyle.showTip(simple: simpleMode, tipShown: false)
    }

    /// Debug builds only (-demoSetup): open the setup at one screen, for screenshots.
    func demoSetup(_ step: Onboarding.Step, privacy: PrivacyLevel?, path: String? = nil) {
        setupChoices = Onboarding.fromSaved(prefs)          // as "Run setup again" (Android does the same)
        setupTextSize()
        setupIsReplay = prefs.onboardingCompleted == true
        if let privacy { setupChoices.privacy = privacy }
        let paths: [String: Onboarding.Path] = ["standard": .standard, "simple": .simple, "custom": .custom, "helper": .helper]
        if let p = path.flatMap({ paths[$0] }) {
            setupChoices = Onboarding.choose(setupChoices, p)
            previewTextSize(setupChoices.textSize)
        }
        setupStep = step
        gate = .setup
    }
    #endif

    /// The tour shown: Simple mode's 3 steps or the full app's 7, fixed when it starts.
    private(set) var tourSimple = false

    func startTour(at: Int = 0) {
        tourSimple = simpleMode
        tourStep = min(max(at, 0), Tour.steps(simple: tourSimple).count - 1)
    }

    func tourNext() {
        guard let i = tourStep else { return }
        tourStep = Tour.isLast(i, simple: tourSimple) ? nil : i + 1
    }

    func tourBack() {
        guard let i = tourStep else { return }
        tourStep = i == 0 ? nil : i - 1
    }

    func endTour() { tourStep = nil }

    /// The pretend call the tour's in-call steps show. Nothing is sent.
    #if DEBUG
    /// Debug builds only (screenshots): a pretend call showing the red warning (red = true) or the yellow banner.
    /// Nothing is sent and nothing is saved (no call id).
    func demoWarning(red: Bool) {
        cancelPlayback()
        callId = ""
        var s = Self.tourDemoCall(redAlert: red)
        s.privacyLevel = prefs.privacyLevel
        if !red {
            s.verdict = "UNSURE"
            s.combinedScore = 0.52
            s.callScores = ["urgency": 0.2, "money": 0.62, "impersonation": 0.1]
            s.lines = [ChatLine(seq: 1, isCaller: false, text: L.s(.demo_line_hello)),
                       ChatLine(seq: 2, isCaller: true, text: L.s(.demo_line_parcel), flags: ["money"])]
        }
        state = s
    }

    /// Debug builds only (screenshots, accessibility audits): a finished pretend call on its summary screen, Scam
    /// (red) or "Be careful". Nothing is sent and nothing is saved (no call id).
    func demoSummary(red: Bool) {
        cancelPlayback()
        callId = ""
        var s = Self.tourDemoCall(redAlert: false)
        s.phase = .ended
        s.privacyLevel = prefs.privacyLevel
        if red {
            s.scamAlert = true
            s.finalReview = FinalReview(finalVerdict: "SCAM", reason: "", reviewed: true, topWindowSeqs: [2, 3], callScore: 0.97)
        } else {
            s.verdict = "UNSURE"
            s.combinedScore = 0.52
            s.callScores = ["urgency": 0.2, "money": 0.62, "impersonation": 0.1]
            s.lines = [ChatLine(seq: 1, isCaller: false, text: L.s(.demo_line_hello)),
                       ChatLine(seq: 2, isCaller: true, text: L.s(.demo_line_parcel), flags: ["money"])]
            s.finalReview = FinalReview(finalVerdict: "UNSURE", reason: "", reviewed: true, topWindowSeqs: [2], callScore: 0.55)
        }
        state = s
    }

    /// Debug builds only (screenshots): the server check's result without a server.
    func demoConnection(_ ok: Bool?) { connection = ok; connectionFixed = true }
    #endif

    static func tourDemoCall(redAlert: Bool) -> CallUiState {
        var s = CallUiState()
        s.phase = .inCall
        s.verdict = "SCAM"
        s.combinedScore = 0.79
        s.callScores = ["urgency": 0.47, "money": 1, "impersonation": 0.9]
        s.lines = [
            ChatLine(seq: 1, isCaller: false, text: L.s(.demo_line_hello)),
            ChatLine(seq: 2, isCaller: true, text: L.s(.demo_line_officer), flags: ["impersonation"]),
            ChatLine(seq: 3, isCaller: true, text: L.s(.demo_line_otp), flags: ["money"]),
        ]
        s.scamAlert = redAlert
        s.warningOpen = redAlert
        return s
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
