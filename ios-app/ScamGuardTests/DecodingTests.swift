import XCTest
@testable import ScamGuard

/// Codable: the real answers recorded from the running backend (ios-app/docs/IOS_PORT_NOTES.md §2). Every field is
/// optional, as on Android, so a missing, null or unexpected field never breaks a call.
final class DecodingTests: XCTestCase {
    private func decode<T: Decodable>(_ json: String, _ t: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    func testRealUtteranceAnswer() throws {
        let json = """
        {"call_id":"mc-ios-1","seq":1,"scored":true,"duplicate":false,"verdict":"UNSURE","live_alert":"UNSURE",
         "final_verdict":"","combined_score":0.8525,
         "reasons":[{"signal":"impersonation","score":0.9978,"text":"Claims to be police, a bank or the government"},
                    {"signal":"money","score":0.9945,"text":"Asked for your OTP or money"},
                    {"signal":"urgency","score":0.5653,"text":"Pressuring you to act now"}],
         "action":"Don't share any codes or transfer money. Hang up and check with the official number.",
         "warming_up":true,"quorum_met":true,"language":"en","language_source":"phone",
         "scores":{"impersonation":0.9978,"money":0.9945,"urgency":0.5653},
         "model_versions":{"impersonation":"impersonation_en_v5","money":"money_en_v2c","urgency":"v4"},
         "missing":[],"recent_scores":{"impersonation":0.9978,"money":0.9945,"urgency":0.5653},
         "fast_score":0.8525,"bert_score":null,"bert_status":"no_context","caller_lines":1,"latency_ms":84,"ignored":false}
        """
        let r = try decode(json, UtteranceResponse.self)
        XCTAssertEqual(r.callId, "mc-ios-1")
        XCTAssertEqual(r.seq, 1)
        XCTAssertEqual(r.verdict, "UNSURE")
        XCTAssertEqual(r.combinedScore!, 0.8525, accuracy: 1e-6)
        XCTAssertEqual(r.reasons?.count, 3)
        XCTAssertEqual(r.reasons?.first?.text, "Claims to be police, a bank or the government")
        XCTAssertEqual(r.action, LocalRules.actionUnsure)
        XCTAssertEqual(r.warmingUp, true)
        XCTAssertEqual(r.score("money")!, 0.9945, accuracy: 1e-6)
        XCTAssertEqual(r.callScores?["urgency"]!, 0.5653)
        XCTAssertEqual(r.modelVersions?["money"], "money_en_v2c")
        XCTAssertNil(r.bertScore)
        XCTAssertEqual(r.bertStatus, "no_context")
        XCTAssertEqual(r.callerLines, 1)
        XCTAssertEqual(r.latencyMs, 84)
        XCTAssertEqual(r.ignored, false)
        XCTAssertEqual(r.duplicate, false)
    }

    func testRecentScoresAndTheOldName() throws {
        let now = try decode(#"{"verdict":"UNSURE","recent_scores":{"money":0.9}}"#, UtteranceResponse.self)
        XCTAssertEqual(now.callScores?["money"], 0.9)
        let old = try decode(#"{"verdict":"UNSURE","call_scores":{"money":0.7}}"#, UtteranceResponse.self)
        XCTAssertEqual(old.callScores?["money"], 0.7)
    }

    func testIgnoredAfterTheEndAndDuplicate() throws {
        let late = try decode(#"{"verdict":"SCAM","scored":false,"ignored":true,"reason":"call_ended"}"#, UtteranceResponse.self)
        XCTAssertEqual(late.ignored, true)
        XCTAssertEqual(late.reason, "call_ended")
        let dup = try decode(#"{"call_id":"c","seq":3,"scored":false,"duplicate":true,"verdict":"SAFE"}"#, UtteranceResponse.self)
        XCTAssertEqual(dup.duplicate, true)
        XCTAssertEqual(dup.scored, false)
    }

    func testMissingNullAndOddFieldsNeverFail() throws {
        let empty = try decode("{}", UtteranceResponse.self)
        XCTAssertNil(empty.verdict)
        XCTAssertNil(empty.callScores)
        // a worker that didn't answer: null score, listed in missing
        let missing = try decode(#"{"verdict":"UNSURE","scores":{"money":null,"urgency":0.2},"missing":["money"]}"#,
                                 UtteranceResponse.self)
        XCTAssertNil(missing.score("money"))
        XCTAssertEqual(missing.score("urgency"), 0.2)
        XCTAssertEqual(missing.missing, ["money"])
        // a field with an unexpected type is dropped, not fatal
        let odd = try decode(#"{"verdict":"SAFE","seq":"three","combined_score":"high","new_field":[1,2]}"#, UtteranceResponse.self)
        XCTAssertEqual(odd.verdict, "SAFE")
        XCTAssertNil(odd.seq)
        XCTAssertNil(odd.combinedScore)
    }

    func testRealEndAnswer() throws {
        let r = try decode("""
        {"call_id":"c1","final_verdict":"SCAM","call_score":0.99,"live_verdict":"SCAM",
         "review":"bert","top_window_seqs":[5,7,9,11,13],"reason":"Asked for your OTP or money",
         "ended_by":"script_finished","already_ended":false,"latency_ms":849,"error":null}
        """, EndCallResponse.self)
        XCTAssertEqual(r.finalVerdict, "SCAM")
        XCTAssertEqual(r.callScore!, 0.99, accuracy: 1e-6)
        XCTAssertEqual(r.review, "bert")
        XCTAssertEqual(r.topWindowSeqs, [5, 7, 9, 11, 13])
        XCTAssertEqual(r.alreadyEnded, false)
        XCTAssertNil(r.error)
        let review = FinalReview.from(r)
        XCTAssertTrue(review.reviewed)
        XCTAssertEqual(review.topWindowSeqs, [5, 7, 9, 11, 13])
        XCTAssertEqual(review.reviewType, "bert")
    }

    func testUnknownCallAndAlreadyEnded() throws {
        let unknown = FinalReview.from(try decode(#"{"call_id":"x","final_verdict":"UNKNOWN","review":"fallback"}"#, EndCallResponse.self))
        XCTAssertNil(unknown.finalVerdict)
        XCTAssertFalse(unknown.reviewed)
        XCTAssertEqual(unknown.reviewType, "fallback")
        let late = try decode(#"{"final_verdict":"UNSURE","review":"fallback","already_ended":true}"#, EndCallResponse.self)
        XCTAssertEqual(late.alreadyEnded, true)
        XCTAssertFalse(FinalReview.from(late).reviewed)   // the 30 s silence end: only the live-verdict fallback
    }

    func testVerifyReportHealth() throws {
        let v = try decode(#"{"score":0.043,"verdict":"SAFE","model_version":"bert-v2-20261026","latency_ms":57}"#, VerifyResponse.self)
        XCTAssertEqual(v.score!, 0.043, accuracy: 1e-6)
        XCTAssertEqual(v.modelVersion, "bert-v2-20261026")
        let r = try decode(#"{"report_id":12,"lines_stored":6,"expires_at":1795000000}"#, ReportResponse.self)
        XCTAssertEqual(r.reportId, 12)
        XCTAssertEqual(r.linesStored, 6)
        XCTAssertEqual(r.expiresAt, 1_795_000_000)
        let h = try decode(#"{"status":"ok","calls_in_memory":0}"#, HealthResponse.self)
        XCTAssertEqual(h.status, "ok")
        XCTAssertEqual(h.callsInMemory, 0)
    }

    private func encode<T: Encodable>(_ v: T) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: JSONEncoder().encode(v)) as! [String: Any]
    }

    func testRequestsUseTheContractNames() throws {
        let u = try encode(UtteranceRequest(callId: "c1", seq: 3, speaker: "caller", text: "hi", lang: "en"))
        XCTAssertEqual(u["call_id"] as? String, "c1")
        XCTAssertEqual(u["seq"] as? Int, 3)
        XCTAssertEqual(u["lang"] as? String, "en")
        XCTAssertNil(try encode(UtteranceRequest(callId: "c", seq: 1, speaker: "caller", text: "x"))["lang"])   // detect

        let end = try encode(EndCallRequest(callId: "c1", endedBy: "script_finished", lastSeq: 16,
                                            callerLines: [EndLine(seq: 1, text: "hello"), EndLine(seq: 3, text: "OTP please")], lang: "en"))
        XCTAssertEqual(end["call_id"] as? String, "c1")
        XCTAssertEqual(end["event"] as? String, "end")
        XCTAssertEqual(end["ended_by"] as? String, "script_finished")
        XCTAssertEqual(end["last_seq"] as? Int, 16)
        XCTAssertEqual(((end["caller_lines"] as? [[String: Any]])?[1]["seq"]) as? Int, 3)
        XCTAssertNil(try encode(EndCallRequest(callId: "c", endedBy: "user_discarded", lastSeq: 0, callerLines: []))["lang"])

        let rep = try encode(ReportRequest(callId: "c", kind: "scam", appVerdict: "SCAM", language: "en", consent: true,
                                           lines: [ReportLine(seq: 1, text: "masked")]))
        XCTAssertEqual(rep["app_verdict"] as? String, "SCAM")
        XCTAssertEqual(rep["consent_version"] as? Int, 2)
        XCTAssertEqual(rep["consent"] as? Bool, true)
        let ver = try encode(VerifyRequest(lines: ["a", "b"]))
        XCTAssertEqual(ver["lang"] as? String, "en")
        XCTAssertEqual((ver["lines"] as? [String])?.count, 2)
    }

    func testScriptsAreAllBundledAndParsed() throws {
        let scripts = ScriptRepository.loadAll()
        XCTAssertEqual(scripts.count, 25)
        XCTAssertEqual(scripts.filter { $0.kind == "scam" }.count, 15)
        XCTAssertEqual(scripts.filter { $0.kind == "normal" }.count, 10)
        let otp = try XCTUnwrap(scripts.first { $0.fileName == "scam_en_11_bank_otp.json" })
        XCTAssertEqual(otp.lang, "en")
        XCTAssertEqual(otp.title, "Bank otp")
        XCTAssertTrue(otp.lines.first!.isCaller)
        XCTAssertEqual(otp.lines.first!.delayMs, 1500)
        XCTAssertEqual(scripts.first { $0.fileName == "normal_zh_14_family_call.json" }?.lang, "zh")
    }

    func testGatewayAddressNormalisation() throws {
        XCTAssertEqual(GatewayClient.normalize("192.168.1.23:8080"), "http://192.168.1.23:8080/")
        XCTAssertEqual(GatewayClient.normalize(" https://h:8443/api "), "https://h:8443/api/")
        XCTAssertThrowsError(try GatewayClient(baseUrl: "http://", session: .shared))
        let c = try GatewayClient(baseUrl: "http://localhost:8088/api/", session: .shared)
        XCTAssertEqual(c.baseURL.absoluteString, "http://localhost:8088/api/")
    }
}
