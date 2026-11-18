import CryptoKit
import Foundation

/// Port of android-app data/ModelUpdater.kt (G.10): keeps the three on-device models up to date from the cloud's
/// model registry. For every model with a version this phone doesn't have yet it downloads the file, checks its
/// SHA-256 against the manifest, checks the app can load it, and only then installs it to
/// Application Support/models/<signal>_en.json. If anything fails the phone keeps the model it has; the models
/// bundled in the app are the fallback. Only model files come down; nothing about calls goes up.
nonisolated final class ModelUpdater: @unchecked Sendable {
    /// Registry name -> the file OnDeviceScorer reads.
    static let names: [(String, String)] = [
        ("urgency_en_phone", "urgency_en.json"),
        ("money_en_phone", "money_en.json"),
        ("impersonation_en_phone", "impersonation_en.json"),
    ]

    let dir: URL

    init(dir: URL = ModelUpdater.defaultDir) { self.dir = dir }

    static var defaultDir: URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("models", isDirectory: true)
    }

    private var installedFile: URL { dir.appendingPathComponent("installed.json") }

    /// The model service lives next to /api/ on the edge / Ingress (`:8080/models/manifest` is a 404), so:
    ///   https://1.2.3.4:8443/api/ -> https://1.2.3.4:8443/models/
    ///   http://localhost:8088/api/ -> http://localhost:8088/models/
    ///   http://localhost:8080/    -> http://localhost:8088/models/   (the gateway port -> the compose edge)
    static func modelsBase(_ gatewayUrl: String) -> String? {
        let u = GatewayClient.normalize(gatewayUrl)
        if u.hasSuffix("/api/") { return String(u.dropLast(4)) + "models/" }
        guard var c = URLComponents(string: u), c.host != nil else { return nil }
        if c.port == 8080 { c.port = 8088; c.path = "/" }
        guard let s = c.string else { return nil }
        return (s.hasSuffix("/") ? s : s + "/") + "models/"
    }

    struct Installed: Codable, Equatable, Sendable {
        let version: String
        let sha256: String
        let installedAt: Int64

        enum CodingKeys: String, CodingKey { case version, sha256, installedAt = "installed_at" }
    }

    func installed() -> [String: Installed] {
        guard let data = try? Data(contentsOf: installedFile),
              let m = try? JSONDecoder().decode([String: Installed].self, from: data) else { return [:] }
        return m
    }

    /// A downloaded model file for the scorer, if one is installed.
    func downloadedFile(_ file: String) -> URL? {
        let u = dir.appendingPathComponent(file)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    /// One line per model for the Settings screen.
    func describe() -> String {
        let inst = installed()
        let f = DateFormatter()
        f.dateFormat = "d MMM HH:mm"
        return Self.names.map { name, _ in
            let signal = String(name.split(separator: "_")[0])
            if let i = inst[name] {
                return "\(signal): federated \(i.version) (downloaded \(f.string(from: Date(timeIntervalSince1970: Double(i.installedAt) / 1000))))"
            }
            return "\(signal): built-in model (no download yet)"
        }.joined(separator: "\n")
    }

    static func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    private struct Manifest: Decodable {
        struct Entry: Decodable {
            let version: String?
            let sha256: String?
        }
        let models: [String: Entry]?
    }

    /// Check the manifest and install anything newer. Returns a short message for the Settings screen.
    @concurrent
    func update(gatewayUrl: String, session: URLSession) async -> String {
        guard let base = Self.modelsBase(gatewayUrl), let manifestURL = URL(string: base + "manifest") else {
            return "Bad server address"
        }
        let manifest: [String: Manifest.Entry]
        do {
            let data = try await get(manifestURL, session)
            manifest = try JSONDecoder().decode(Manifest.self, from: data).models ?? [:]
        } catch {
            return "No model service at \(manifestURL.absoluteString) (\(CallViewModel.describe(error))). " +
                "Is the edge (port 8088 on compose, 8443 on Kubernetes) up?"
        }
        var inst = installed()
        var changed: [String] = []
        var problems: [String] = []
        for (name, file) in Self.names {
            guard let entry = manifest[name], let version = entry.version, let sha = entry.sha256 else { continue }
            if inst[name]?.sha256 == sha { continue }
            let signal = String(name.split(separator: "_")[0])
            do {
                guard let url = URL(string: base + "\(name)/\(version)") else { throw URLError(.badURL) }
                let bytes = try await get(url, session)
                guard Self.sha256Hex(bytes) == sha else { throw UpdateError.checksum }
                _ = try LinearTextModel.load(bytes)              // refuse a file the app can't read
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try bytes.write(to: dir.appendingPathComponent(file), options: .atomic)
                inst[name] = Installed(version: version, sha256: sha, installedAt: Int64(Date().timeIntervalSince1970 * 1000))
                changed.append("\(signal) \(version)")
            } catch {
                problems.append("\(signal): \((error as? LocalizedError)?.errorDescription ?? CallViewModel.describe(error))")
            }
        }
        if !changed.isEmpty, let json = try? JSONEncoder().encode(inst) {
            try? json.write(to: installedFile, options: .atomic)
        }
        if !changed.isEmpty {
            return "Updated: \(changed.joined(separator: ", "))" +
                (problems.isEmpty ? "" : ". Kept the old model for \(problems.joined(separator: "; "))")
        }
        if !problems.isEmpty { return "Kept the current models: \(problems.joined(separator: "; "))" }
        if manifest.isEmpty { return "The registry has no phone models yet (run a federated round with --publish)" }
        return "Up to date"
    }

    enum UpdateError: Error, LocalizedError {
        case checksum
        case http(Int)
        var errorDescription: String? {
            switch self {
            case .checksum: return "checksum mismatch"
            case .http(let c): return "HTTP \(c)"
            }
        }
    }

    private func get(_ url: URL, _ session: URLSession) async throws -> Data {
        var req = URLRequest(url: url)
        req.timeoutInterval = 30
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw UpdateError.http(code) }
        return data
    }
}
