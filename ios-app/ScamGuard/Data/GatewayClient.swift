import Foundation

/// An HTTP answer: the status code and, for 2xx, the decoded body (nil if it couldn't be read).
nonisolated struct HTTPResult<T: Sendable>: Sendable {
    let status: Int
    let body: T?
    var isSuccessful: Bool { (200..<300).contains(status) }
}

nonisolated enum GatewayError: Error, Equatable {
    case invalidURL
}

/// The URLSession every request goes through. Injected so tests can watch (or forbid) network traffic.
nonisolated enum Network {
    /// Client timeouts as on Android: connect 2 s, whole call 4 s. URLSession has no separate connect timeout, so
    /// both the idle and the whole-request limits are 4 s.
    static func gatewayConfiguration() -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 4
        c.timeoutIntervalForResource = 4
        c.waitsForConnectivity = false
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return c
    }
}

/// Port of android-app data/GatewayApi.kt + GatewayClient: the gateway's REST API.
nonisolated struct GatewayClient: Sendable {
    let baseURL: URL
    let session: URLSession
    /// The Developer view's log (it keeps a masked copy only while the view is unlocked).
    let log: DevLog

    /// "192.168.1.23:8080" -> "http://192.168.1.23:8080/" (scheme and trailing / added).
    static func normalize(_ url: String) -> String {
        var u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if !u.hasPrefix("http://") && !u.hasPrefix("https://") { u = "http://" + u }
        if !u.hasSuffix("/") { u += "/" }
        return u
    }

    /// Throws GatewayError.invalidURL if the address is not usable.
    init(baseUrl: String, session: URLSession, log: DevLog = .shared) throws {
        let s = Self.normalize(baseUrl)
        guard let url = URL(string: s), let host = url.host, !host.isEmpty, url.scheme == "http" || url.scheme == "https"
        else { throw GatewayError.invalidURL }
        baseURL = url
        self.session = session
        self.log = log
    }

    private static let encoder = JSONEncoder()

    private func url(_ path: String) -> URL { URL(string: path, relativeTo: baseURL)!.absoluteURL }

    func post<B: Encodable, R: Decodable & Sendable>(_ path: String, _ body: B, as: R.Type) async throws -> HTTPResult<R> {
        var req = URLRequest(url: url(path))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try Self.encoder.encode(body)
        return try await send(req, R.self)
    }

    func get<R: Decodable & Sendable>(_ path: String, as: R.Type) async throws -> HTTPResult<R> {
        try await send(URLRequest(url: url(path)), R.self)
    }

    private func send<R: Decodable & Sendable>(_ req: URLRequest, _: R.Type) async throws -> HTTPResult<R> {
        guard log.recording else { return try await exchange(req, R.self) }
        // Developer view only: a masked copy in memory (DevLog.add masks the bodies). The request isn't changed.
        let sent = req.httpBody.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let start = DispatchTime.now().uptimeNanoseconds
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let path = req.url?.path ?? ""
        do {
            let (data, resp) = try await session.data(for: req)
            let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
            log.add(timeMs: now, method: req.httpMethod ?? "GET", path: path, status: status,
                    ms: Int((DispatchTime.now().uptimeNanoseconds - start) / 1_000_000), request: sent,
                    response: String(data: data.prefix(64 * 1024), encoding: .utf8) ?? "")
            let body = (200..<300).contains(status) ? try? JSONDecoder().decode(R.self, from: data) : nil
            return HTTPResult(status: status, body: body)
        } catch {
            log.add(timeMs: now, method: req.httpMethod ?? "GET", path: path, status: 0,
                    ms: Int((DispatchTime.now().uptimeNanoseconds - start) / 1_000_000), request: sent,
                    response: CallViewModel.describe(error))
            throw error
        }
    }

    private func exchange<R: Decodable & Sendable>(_ req: URLRequest, _: R.Type) async throws -> HTTPResult<R> {
        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let body = (200..<300).contains(status) ? try? JSONDecoder().decode(R.self, from: data) : nil
        return HTTPResult(status: status, body: body)
    }

    func utterance(_ body: UtteranceRequest) async throws -> HTTPResult<UtteranceResponse> {
        try await post("v1/utterance", body, as: UtteranceResponse.self)
    }

    func health() async throws -> HTTPResult<HealthResponse> { try await get("healthz", as: HealthResponse.self) }

    /// Workstream B: the call is over -> final verdict; the server clears the call's memory.
    func endCall(_ body: EndCallRequest) async throws -> HTTPResult<EndCallResponse> {
        try await post("v1/call/end", body, as: EndCallResponse.self)
    }

    /// Workstream H.3: a consented report of a finished call.
    func report(_ body: ReportRequest) async throws -> HTTPResult<ReportResponse> {
        try await post("v1/report", body, as: ReportResponse.self)
    }

    /// G.3 (Hybrid): BERT's opinion on the caller's last 5 lines, masked on the phone. Stateless.
    func verify(_ body: VerifyRequest) async throws -> HTTPResult<VerifyResponse> {
        try await post("v1/verify", body, as: VerifyResponse.self)
    }
}
