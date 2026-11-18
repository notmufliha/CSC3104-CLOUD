import Foundation

// Port of android-app data/Models.kt. Every response field is optional, as on Android (Gson ignored Kotlin defaults).

/// One line of a call script (a .json file in Resources/Scripts).
nonisolated struct ScriptLine: Codable, Equatable, Sendable {
    var speaker: String?          // "caller" or "receiver"
    var text: String?
    var delayMs: Int?             // wait before this line appears

    enum CodingKeys: String, CodingKey {
        case speaker, text
        case delayMs = "delay_ms"
    }

    var isCaller: Bool {
        guard let s = speaker?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() else { return false }
        return ["caller", "speaker 1", "speaker1", "speaker_1"].contains(s)
    }
}

/// A whole script plus what we read from its file name, e.g. scam_en_11_bank_otp.json.
nonisolated struct CallScript: Equatable, Identifiable, Sendable {
    let fileName: String
    let kind: String        // "scam" | "normal" | "pasted"
    let lang: String?       // "en" | "zh" | nil (let the gateway detect)
    let title: String
    let lines: [ScriptLine]
    var id: String { fileName }
}

/// Body of POST /v1/utterance. A nil lang is left out (the gateway detects it).
nonisolated struct UtteranceRequest: Encodable, Sendable {
    let callId: String
    let seq: Int
    let speaker: String
    let text: String
    var lang: String?

    enum CodingKeys: String, CodingKey {
        case callId = "call_id", seq, speaker, text, lang
    }
}

nonisolated struct ServerReason: Codable, Equatable, Sendable {
    var signal: String?
    var score: Float?
    var text: String?
}

/// Answer from POST /v1/utterance.
nonisolated struct UtteranceResponse: Decodable, Equatable, Sendable {
    var callId: String?
    var seq: Int?
    var scored: Bool?
    var duplicate: Bool?
    var verdict: String?                    // SAFE | UNSURE | SCAM
    var liveAlert: String?
    var finalVerdict: String?
    var combinedScore: Float?
    var reasons: [ServerReason]?
    var action: String?
    var warmingUp: Bool?
    var quorumMet: Bool?
    var language: String?
    var languageSource: String?
    var scores: [String: Float?]?           // this line only
    var modelVersions: [String: String]?
    var missing: [String]?
    /// Best per signal over the call's recent caller lines: "recent_scores" (old name "call_scores").
    var callScores: [String: Float]?
    var fastScore: Float?
    var bertScore: Float?
    var bertStatus: String?
    var callerLines: Int?
    var latencyMs: Int?
    /// B.6: true = the call had already ended, the line was not scored. Treat as "no change".
    var ignored: Bool?
    /// Why it was ignored ("call_ended"); nil otherwise.
    var reason: String?
    var error: String?

    enum CodingKeys: String, CodingKey {
        case callId = "call_id", seq, scored, duplicate, verdict
        case liveAlert = "live_alert", finalVerdict = "final_verdict", combinedScore = "combined_score"
        case reasons, action
        case warmingUp = "warming_up", quorumMet = "quorum_met", language
        case languageSource = "language_source", scores
        case modelVersions = "model_versions", missing
        case recentScores = "recent_scores", callScoresOld = "call_scores"
        case fastScore = "fast_score", bertScore = "bert_score", bertStatus = "bert_status"
        case callerLines = "caller_lines", latencyMs = "latency_ms", ignored, reason, error
    }

    init(callId: String? = nil, seq: Int? = nil, scored: Bool? = nil, duplicate: Bool? = nil, verdict: String? = nil,
         combinedScore: Float? = nil, language: String? = nil, scores: [String: Float?]? = nil, missing: [String]? = nil,
         callScores: [String: Float]? = nil, callerLines: Int? = nil, latencyMs: Int? = nil, ignored: Bool? = nil,
         reason: String? = nil, reasons: [ServerReason]? = nil, action: String? = nil) {
        self.callId = callId; self.seq = seq; self.scored = scored; self.duplicate = duplicate; self.verdict = verdict
        self.combinedScore = combinedScore; self.language = language; self.scores = scores; self.missing = missing
        self.callScores = callScores; self.callerLines = callerLines; self.latencyMs = latencyMs
        self.ignored = ignored; self.reason = reason; self.reasons = reasons; self.action = action
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        callId = try? c.decodeIfPresent(String.self, forKey: .callId)
        seq = try? c.decodeIfPresent(Int.self, forKey: .seq)
        scored = try? c.decodeIfPresent(Bool.self, forKey: .scored)
        duplicate = try? c.decodeIfPresent(Bool.self, forKey: .duplicate)
        verdict = try? c.decodeIfPresent(String.self, forKey: .verdict)
        liveAlert = try? c.decodeIfPresent(String.self, forKey: .liveAlert)
        finalVerdict = try? c.decodeIfPresent(String.self, forKey: .finalVerdict)
        combinedScore = try? c.decodeIfPresent(Float.self, forKey: .combinedScore)
        reasons = try? c.decodeIfPresent([ServerReason].self, forKey: .reasons)
        action = try? c.decodeIfPresent(String.self, forKey: .action)
        warmingUp = try? c.decodeIfPresent(Bool.self, forKey: .warmingUp)
        quorumMet = try? c.decodeIfPresent(Bool.self, forKey: .quorumMet)
        language = try? c.decodeIfPresent(String.self, forKey: .language)
        languageSource = try? c.decodeIfPresent(String.self, forKey: .languageSource)
        scores = try? c.decodeIfPresent([String: Float?].self, forKey: .scores)
        modelVersions = try? c.decodeIfPresent([String: String].self, forKey: .modelVersions)
        missing = try? c.decodeIfPresent([String].self, forKey: .missing)
        callScores = (try? c.decodeIfPresent([String: Float].self, forKey: .recentScores))
            ?? (try? c.decodeIfPresent([String: Float].self, forKey: .callScoresOld))
        fastScore = try? c.decodeIfPresent(Float.self, forKey: .fastScore)
        bertScore = try? c.decodeIfPresent(Float.self, forKey: .bertScore)
        bertStatus = try? c.decodeIfPresent(String.self, forKey: .bertStatus)
        callerLines = try? c.decodeIfPresent(Int.self, forKey: .callerLines)
        latencyMs = try? c.decodeIfPresent(Int.self, forKey: .latencyMs)
        ignored = try? c.decodeIfPresent(Bool.self, forKey: .ignored)
        reason = try? c.decodeIfPresent(String.self, forKey: .reason)
        error = try? c.decodeIfPresent(String.self, forKey: .error)
    }

    /// One signal's score for this line (nil = that check didn't answer).
    func score(_ signal: String) -> Float? { scores?[signal] ?? nil }
}

/// One caller line in the end message, for the end-of-call review (not stored by the server).
nonisolated struct EndLine: Codable, Equatable, Sendable {
    let seq: Int
    let text: String
}

/// Body of POST /v1/call/end.
nonisolated struct EndCallRequest: Encodable, Sendable {
    let callId: String
    let endedBy: String         // script_finished | user_hung_up | user_discarded
    let lastSeq: Int
    let callerLines: [EndLine]
    var lang: String?           // nil = the gateway detects it from the lines (left out of the JSON)
    var event: String = "end"

    enum CodingKeys: String, CodingKey {
        case callId = "call_id", endedBy = "ended_by", lastSeq = "last_seq", callerLines = "caller_lines", lang, event
    }
}

/// Answer from POST /v1/call/end.
nonisolated struct EndCallResponse: Decodable, Equatable, Sendable {
    var callId: String?
    var finalVerdict: String?   // SAFE | UNSURE | SCAM | UNKNOWN
    var callScore: Float?
    var liveVerdict: String?
    var review: String?         // "bert" | "fallback"
    var topWindowSeqs: [Int]?
    var reason: String?
    var endedBy: String?
    var alreadyEnded: Bool?
    var latencyMs: Int?
    var error: String?

    enum CodingKeys: String, CodingKey {
        case callId = "call_id", finalVerdict = "final_verdict", callScore = "call_score", liveVerdict = "live_verdict"
        case review, topWindowSeqs = "top_window_seqs", reason, endedBy = "ended_by"
        case alreadyEnded = "already_ended", latencyMs = "latency_ms", error
    }

    init(callId: String? = nil, finalVerdict: String? = nil, callScore: Float? = nil, liveVerdict: String? = nil,
         review: String? = nil, topWindowSeqs: [Int]? = nil, reason: String? = nil, endedBy: String? = nil,
         alreadyEnded: Bool? = nil, latencyMs: Int? = nil, error: String? = nil) {
        self.callId = callId; self.finalVerdict = finalVerdict; self.callScore = callScore
        self.liveVerdict = liveVerdict; self.review = review; self.topWindowSeqs = topWindowSeqs
        self.reason = reason; self.endedBy = endedBy; self.alreadyEnded = alreadyEnded
        self.latencyMs = latencyMs; self.error = error
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        callId = try? c.decodeIfPresent(String.self, forKey: .callId)
        finalVerdict = try? c.decodeIfPresent(String.self, forKey: .finalVerdict)
        callScore = try? c.decodeIfPresent(Float.self, forKey: .callScore)
        liveVerdict = try? c.decodeIfPresent(String.self, forKey: .liveVerdict)
        review = try? c.decodeIfPresent(String.self, forKey: .review)
        topWindowSeqs = try? c.decodeIfPresent([Int].self, forKey: .topWindowSeqs)
        reason = try? c.decodeIfPresent(String.self, forKey: .reason)
        endedBy = try? c.decodeIfPresent(String.self, forKey: .endedBy)
        alreadyEnded = try? c.decodeIfPresent(Bool.self, forKey: .alreadyEnded)
        latencyMs = try? c.decodeIfPresent(Int.self, forKey: .latencyMs)
        error = try? c.decodeIfPresent(String.self, forKey: .error)
    }
}

/// One caller line in a report, already masked on the phone (Mask).
nonisolated struct ReportLine: Codable, Equatable, Sendable {
    let seq: Int
    let text: String
}

/// Body of POST /v1/report: sent only after the user agreed on the consent screen.
nonisolated struct ReportRequest: Encodable, Sendable {
    let callId: String
    let kind: String            // "scam" | "fine"
    let appVerdict: String
    let language: String        // "en" | "zh"
    let consent: Bool
    let lines: [ReportLine]
    /// Which consent wording the user saw. 2 = lines a reviewer approves may be kept after 30 days.
    var consentVersion: Int = 2

    enum CodingKeys: String, CodingKey {
        case callId = "call_id", kind, appVerdict = "app_verdict", language, consent, lines
        case consentVersion = "consent_version"
    }
}

/// Answer from POST /v1/report.
nonisolated struct ReportResponse: Decodable, Equatable, Sendable {
    var reportId: Int?
    var linesStored: Int?
    var expiresAt: Int?

    enum CodingKeys: String, CodingKey {
        case reportId = "report_id", linesStored = "lines_stored", expiresAt = "expires_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        reportId = try? c.decodeIfPresent(Int.self, forKey: .reportId)
        linesStored = try? c.decodeIfPresent(Int.self, forKey: .linesStored)
        expiresAt = try? c.decodeIfPresent(Int.self, forKey: .expiresAt)
    }
}

/// G.3: body of POST /v1/verify: 1-5 caller lines, oldest first, already masked.
nonisolated struct VerifyRequest: Encodable, Sendable {
    let lines: [String]
    var lang: String = "en"
}

/// Answer from POST /v1/verify.
nonisolated struct VerifyResponse: Decodable, Equatable, Sendable {
    var score: Float?
    var verdict: String?
    var modelVersion: String?
    var latencyMs: Int?

    enum CodingKeys: String, CodingKey {
        case score, verdict, modelVersion = "model_version", latencyMs = "latency_ms"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        score = try? c.decodeIfPresent(Float.self, forKey: .score)
        verdict = try? c.decodeIfPresent(String.self, forKey: .verdict)
        modelVersion = try? c.decodeIfPresent(String.self, forKey: .modelVersion)
        latencyMs = try? c.decodeIfPresent(Int.self, forKey: .latencyMs)
    }
}

/// GET /healthz.
nonisolated struct HealthResponse: Decodable, Sendable {
    var status: String?
    var callsInMemory: Int?

    enum CodingKeys: String, CodingKey { case status, callsInMemory = "calls_in_memory" }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        status = try? c.decodeIfPresent(String.self, forKey: .status)
        callsInMemory = try? c.decodeIfPresent(Int.self, forKey: .callsInMemory)
    }
}
