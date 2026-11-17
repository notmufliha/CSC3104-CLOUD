import Foundation
import OnnxRuntimeBindings

/// Port of android-app ondevice/MobileBert.kt (G.4): MobileBERT on the phone, the level-3 second opinion (the
/// on-phone stand-in for the cloud's BERT), run with ONNX Runtime (Swift package microsoft/onnxruntime-swift-package-manager).
///
/// Files, bundled in the app (Resources/MobileBERT):
///   model.onnx         fine-tuned MobileBERT, ONNX (INT8), inputs input_ids / attention_mask / token_type_ids
///   vocab.txt          its WordPiece vocabulary
///   bert_config.json   version, max_len, temperature, scam_index, max_turns, lower_case, model_file
///
/// Scoring follows the server's BERT (backend/workers/bert/predict.py + bert_text.py): the last 5 caller lines are
/// normalised (NFKC, whitespace collapsed) and joined with " | ", tokenised with left truncation (the newest line is
/// always read), and score = softmax(logits / temperature)[scam_index].
nonisolated enum MobileBertStatus: Sendable {
    case installed(MobileBertModel)
    case notInstalled(String)
}

nonisolated final class MobileBertModel: @unchecked Sendable {
    struct Config: Decodable, Sendable {
        var version = "unknown"
        var maxLen = 128
        var temperature = 1.0
        var scamIndex = 1
        var maxTurns = 5
        var lowerCase = true
        var modelFile = "model.onnx"

        enum CodingKeys: String, CodingKey {
            case version, maxLen = "max_len", temperature, scamIndex = "scam_index", maxTurns = "max_turns"
            case lowerCase = "lower_case", modelFile = "model_file"
        }

        init() {}

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            version = (try? c.decodeIfPresent(String.self, forKey: .version)) ?? "unknown"
            maxLen = (try? c.decodeIfPresent(Int.self, forKey: .maxLen)) ?? 128
            temperature = (try? c.decodeIfPresent(Double.self, forKey: .temperature)) ?? 1.0
            scamIndex = (try? c.decodeIfPresent(Int.self, forKey: .scamIndex)) ?? 1
            maxTurns = (try? c.decodeIfPresent(Int.self, forKey: .maxTurns)) ?? 5
            lowerCase = (try? c.decodeIfPresent(Bool.self, forKey: .lowerCase)) ?? true
            modelFile = (try? c.decodeIfPresent(String.self, forKey: .modelFile)) ?? "model.onnx"
        }
    }

    let config: Config
    let modelBytes: Int
    private let env: ORTEnv
    private let session: ORTSession
    private let tokenizer: WordPieceTokenizer
    private let inputs: Set<String>
    private let lock = NSLock()

    private init(env: ORTEnv, session: ORTSession, tokenizer: WordPieceTokenizer, config: Config, modelBytes: Int) throws {
        self.env = env
        self.session = session
        self.tokenizer = tokenizer
        self.config = config
        self.modelBytes = modelBytes
        inputs = Set(try session.inputNames())
    }

    /// bert_text.make_window: NFKC, whitespace collapsed, empty lines dropped, the last `maxTurns`, " | ".
    static func window(_ lines: [String], maxTurns: Int = 5) -> String {
        lines.map { Py.strip(Py.sub(ws, Py.nfkc($0), " ")) }
            .filter { !$0.isEmpty }
            .suffix(maxTurns)
            .joined(separator: " | ")
    }
    private static let ws = Py.re(#"\s+"#)

    static func softmax(_ logits: [Float], temperature: Double) -> [Double] {
        let z = logits.map { Double($0) / temperature }
        let m = z.max() ?? 0
        let e = z.map { exp($0 - m) }
        let sum = e.reduce(0, +)
        return e.map { $0 / sum }
    }

    /// P(scam) for the caller's recent lines, oldest first; empty -> 0; nil if the model failed.
    @concurrent
    func score(_ lines: [String]) async -> Float? { try? scoreNow(lines) }

    func scoreNow(_ lines: [String]) throws -> Float {
        let text = Self.window(lines, maxTurns: config.maxTurns)
        if text.isEmpty { return 0 }
        let ids = tokenizer.encode(text, maxLen: config.maxLen)
        let shape: [NSNumber] = [1, NSNumber(value: ids.count)]
        func tensor(_ v: [Int64]) throws -> ORTValue {
            let data = v.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<Int64>.size) }
            return try ORTValue(tensorData: data, elementType: .int64, shape: shape)
        }
        var feed: [String: ORTValue] = [:]
        if inputs.contains("input_ids") { feed["input_ids"] = try tensor(ids) }
        if inputs.contains("attention_mask") { feed["attention_mask"] = try tensor([Int64](repeating: 1, count: ids.count)) }
        if inputs.contains("token_type_ids") { feed["token_type_ids"] = try tensor([Int64](repeating: 0, count: ids.count)) }
        lock.lock()
        defer { lock.unlock() }
        let outputName = try session.outputNames().first ?? "logits"
        let out = try session.run(withInputs: feed, outputNames: [outputName], runOptions: nil)
        guard let value = out[outputName] else { throw CocoaError(.coderValueNotFound) }
        let data = try value.tensorData() as Data
        let logits: [Float] = data.withUnsafeBytes { raw in
            (0..<(data.count / 4)).map { raw.loadUnaligned(fromByteOffset: $0 * 4, as: Float.self) }
        }
        let p = Self.softmax(Array(logits.prefix(2)), temperature: config.temperature)
        guard config.scamIndex < p.count else { throw CocoaError(.coderValueNotFound) }
        return Float(p[config.scamIndex])
    }

    /// Loads from any file source (`url(name)` = the file's URL, or nil when it isn't there).
    static func load(_ url: (String) -> URL?) -> MobileBertStatus {
        var cfg = Config()
        if let u = url("bert_config.json"), let data = try? Data(contentsOf: u),
           let c = try? JSONDecoder().decode(Config.self, from: data) {
            cfg = c
        }
        guard let modelURL = url(cfg.modelFile) else { return .notInstalled("MobileBERT not installed (no \(cfg.modelFile))") }
        guard let vocabURL = url("vocab.txt"), let vocabText = try? String(contentsOf: vocabURL, encoding: .utf8) else {
            return .notInstalled("MobileBERT not installed (no vocab.txt)")
        }
        do {
            let tokenizer = try WordPieceTokenizer.fromVocab(vocabText, lowerCase: cfg.lowerCase)
            let env = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            let session = try ORTSession(env: env, modelPath: modelURL.path, sessionOptions: options)
            let size = (try? FileManager.default.attributesOfItem(atPath: modelURL.path)[.size] as? Int) ?? 0
            return .installed(try MobileBertModel(env: env, session: session, tokenizer: tokenizer, config: cfg, modelBytes: size))
        } catch {
            return .notInstalled("MobileBERT could not be loaded: \(String(describing: error).prefix(160))")
        }
    }

    /// From the app bundle (Resources/MobileBERT, copied flat into the bundle).
    static func load(bundle: Bundle) -> MobileBertStatus {
        load { name in
            bundle.url(forResource: (name as NSString).deletingPathExtension, withExtension: (name as NSString).pathExtension)
        }
    }
}
