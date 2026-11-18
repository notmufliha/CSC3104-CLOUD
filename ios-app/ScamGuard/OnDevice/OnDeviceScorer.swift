import Foundation

/// Port of android-app ondevice/OnDeviceScorer.kt (G.2): the three English checks, run on the phone. Each signal is
/// scored the way its worker scores a caller line, so the numbers match the server's:
///   urgency        highest probability over the line's sentences and pairs of sentences (predict.score)
///   money          highest calibrated probability over the line's sentences (MoneyModel.score_chunk)
///   impersonation  calibrated probability of the whole line (predict.score)
nonisolated final class OnDeviceScorer: @unchecked Sendable {
    let urgencyModel: LinearTextModel
    let moneyModel: LinearTextModel
    let impersonationModel: LinearTextModel
    let impersonationText: ImpersonationText
    /// money_en v2c cleans with brand masking; a v1 model file has none (exactly v1's cleaning).
    let moneyBrands: MoneyBrands?

    static let files = ["urgency_en.json", "money_en.json", "impersonation_en.json"]

    init(urgency: LinearTextModel, money: LinearTextModel, impersonation: LinearTextModel) {
        urgencyModel = urgency
        moneyModel = money
        impersonationModel = impersonation
        impersonationText = ImpersonationText(impersonation.orgTypes)
        moneyBrands = money.maskBrands ? MoneyBrands(money.brandTypes) : nil
    }

    /// From any source of the three files (the app bundle, Application Support, test fixtures).
    static func load(_ open: (String) throws -> Data) throws -> OnDeviceScorer {
        let m = try files.map { try LinearTextModel.load(open($0)) }
        return OnDeviceScorer(urgency: m[0], money: m[1], impersonation: m[2])
    }

    /// urgency / money / impersonation -> 0-1, for one caller line.
    func score(_ line: String) -> [String: Float] {
        ["urgency": Float(urgency(line)), "money": Float(money(line)), "impersonation": Float(impersonation(line))]
    }

    func urgency(_ text: String) -> Double {
        UrgencyText.windows(text).map { urgencyModel.proba(UrgencyText.normalize($0)) }.max() ?? 0
    }

    func money(_ text: String) -> Double {
        let sentences = MoneyText.splitSentences(text)
        return (sentences.isEmpty ? [text] : sentences).map { moneyModel.proba(MoneyText.clean($0, moneyBrands)) }.max() ?? 0
    }

    func impersonation(_ text: String) -> Double { impersonationModel.proba(impersonationText.prep(text)) }

    var models: [LinearTextModel] { [urgencyModel, moneyModel, impersonationModel] }
}
