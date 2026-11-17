import Foundation
import XCTest
@testable import ScamGuard

/// Test fixtures (ScamGuardTests/Fixtures) are copied into the test bundle.
final class Fixtures {
    static let bundle = Bundle(for: Fixtures.self)

    static func data(_ name: String) throws -> Data {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        guard let url = bundle.url(forResource: base, withExtension: ext) else {
            throw NSError(domain: "Fixtures", code: 1, userInfo: [NSLocalizedDescriptionKey: "missing fixture \(name)"])
        }
        return try Data(contentsOf: url)
    }

    static func json(_ name: String) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: data(name)) as! [String: Any]
    }

    /// The app's own bundled resources (scripts, models).
    static func appResource(_ name: String) throws -> Data {
        let base = (name as NSString).deletingPathExtension
        guard let url = Bundle.main.url(forResource: base, withExtension: (name as NSString).pathExtension) else {
            throw NSError(domain: "Fixtures", code: 2, userInfo: [NSLocalizedDescriptionKey: "missing app resource \(name)"])
        }
        return try Data(contentsOf: url)
    }

    static func script(_ fileName: String) throws -> CallScript {
        try ScriptRepository.parse(name: fileName, data: appResource(fileName))
    }
}

/// A URLProtocol that answers every request from `handler` and records it. `handler == nil` fails the request.
final class StubProtocol: URLProtocol {
    struct Seen: Sendable {
        let url: URL
        let method: String
        let body: Data?
        var path: String { url.path }
        var json: [String: Any]? { body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } }
    }

    nonisolated(unsafe) static var handler: (@Sendable (URLRequest, Data?) -> (Int, Data, Double))?
    nonisolated(unsafe) private static var seenStore: [Seen] = []
    private static let lock = NSLock()

    static var seen: [Seen] { lock.lock(); defer { lock.unlock() }; return seenStore }

    static func reset() {
        lock.lock(); seenStore = []; lock.unlock()
        handler = nil
    }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [StubProtocol.self]
        c.timeoutIntervalForRequest = 4
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody
        if body == nil, let stream = request.httpBodyStream {
            stream.open()
            var d = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                d.append(buf, count: n)
            }
            stream.close()
            body = d
        }
        Self.lock.lock()
        Self.seenStore.append(Seen(url: request.url!, method: request.httpMethod ?? "GET", body: body))
        Self.lock.unlock()
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        let (status, data, delay) = handler(request, body)
        let respond = { [weak self] in
            guard let self else { return }
            let resp = HTTPURLResponse(url: self.request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
            self.client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: data)
            self.client?.urlProtocolDidFinishLoading(self)
        }
        if delay > 0 { DispatchQueue.global().asyncAfter(deadline: .now() + delay, execute: respond) } else { respond() }
    }

    override func stopLoading() {}
}

/// UserDefaults that start empty for each test.
func freshDefaults(_ name: String = UUID().uuidString) -> UserDefaults {
    let d = UserDefaults(suiteName: name)!
    d.removePersistentDomain(forName: name)
    return d
}
