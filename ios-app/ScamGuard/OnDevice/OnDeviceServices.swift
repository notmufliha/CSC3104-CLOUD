import Foundation

/// The on-device models, loaded once off the main thread and kept until `reload` (Android's OnDeviceScorer.get /
/// MobileBert.get singletons). A downloaded federated model (ModelUpdater) wins over the bundled one; if it can't
/// be read, the bundled one is used.
actor OnDeviceServices {
    nonisolated let updater: ModelUpdater
    private let bundle: Bundle
    private var cachedScorer: OnDeviceScorer?
    private var scorerFailed = false
    private var cachedBert: MobileBertStatus?

    init(bundle: Bundle = .main, updater: ModelUpdater = ModelUpdater()) {
        self.bundle = bundle
        self.updater = updater
    }

    func scorer() -> OnDeviceScorer? {
        if let cachedScorer { return cachedScorer }
        do {
            let s = try OnDeviceScorer.load { file in
                if let u = updater.downloadedFile(file), let data = try? Data(contentsOf: u),
                   (try? LinearTextModel.load(data)) != nil {
                    return data
                }
                guard let u = bundle.url(forResource: (file as NSString).deletingPathExtension, withExtension: "json")
                else { throw CocoaError(.fileNoSuchFile) }
                return try Data(contentsOf: u)
            }
            cachedScorer = s
            return s
        } catch {
            scorerFailed = true
            return nil
        }
    }

    /// A fresh call (its own rules and window) on the shared models; nil if they can't be loaded.
    func call() -> OnDeviceCall? { scorer().map { OnDeviceCall($0) } }

    /// G.10: forget the loaded models, so the next call picks up the ones just downloaded.
    func reload() { cachedScorer = nil }

    // ------------------------------------------------------------ G.4 MobileBERT

    func mobileBert() -> MobileBertStatus {
        if let cachedBert { return cachedBert }
        let s = MobileBertModel.load(bundle: bundle)
        cachedBert = s
        return s
    }

    func mobileBertModel() -> MobileBertModel? {
        if case .installed(let m) = mobileBert() { return m }
        return nil
    }

    /// "MobileBERT installed (version)" or why not, for the Settings screen.
    func mobileBertStatusLine() -> String {
        switch mobileBert() {
        case .installed(let m): return "MobileBERT installed (\(m.config.version)): second opinion on this phone"
        case .notInstalled(let reason): return reason + ": level 3 uses the small models only"
        }
    }
}
