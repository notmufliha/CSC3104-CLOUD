import CommonCrypto
import Foundation
import Security

/// The optional 4-digit PIN for Advanced settings and "For family". Only a salted PBKDF2-HMAC-SHA256 hash is kept
/// (PinStore, in the Keychain), never the PIN. The same hash as Android logic/Pin.kt, so both apps test the same
/// vectors.
nonisolated enum Pin {
    static let length = 4
    static let iterations: UInt32 = 20_000

    static func isValid(_ pin: String) -> Bool {
        pin.count == length && pin.unicodeScalars.allSatisfy { $0.value >= 48 && $0.value <= 57 }
    }

    static func newSalt() -> Data {
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        return Data(b)
    }

    static func hash(_ pin: String, salt: Data) -> Data {
        var out = [UInt8](repeating: 0, count: 32)
        let password = Array(pin.utf8).map { CChar(bitPattern: $0) }
        salt.withUnsafeBytes { s in
            _ = CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password, password.count,
                                     s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), iterations, &out, out.count)
        }
        return Data(out)
    }

    /// Constant-time compare of the typed PIN's hash with the saved one.
    static func matches(_ pin: String, salt: Data, saved: Data) -> Bool {
        guard isValid(pin) else { return false }
        let h = hash(pin, salt: salt)
        guard h.count == saved.count else { return false }
        return zip(h, saved).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    static func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    static func unhex(_ s: String) -> Data {
        var d = Data()
        var i = s.startIndex
        while i < s.endIndex, let j = s.index(i, offsetBy: 2, limitedBy: s.endIndex) {
            if let b = UInt8(s[i..<j], radix: 16) { d.append(b) }
            i = j
        }
        return d
    }
}

/// The PIN's salt + hash in the Keychain (this device only, after first unlock). "Forgot PIN" deletes just this
/// item; the call history is untouched. service is changeable for tests.
nonisolated final class PinStore: @unchecked Sendable {
    private let service: String
    private let account = "pin"

    init(service: String = "com.rhaylene.ScamGuard.pin") { self.service = service }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private func read() -> (salt: Data, hash: Data)? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data, d.count == 48 else { return nil }
        return (d.prefix(16), d.suffix(32))
    }

    var isSet: Bool { read() != nil }

    @discardableResult
    func set(_ pin: String) -> Bool {
        guard Pin.isValid(pin) else { return false }
        let salt = Pin.newSalt()
        let blob = salt + Pin.hash(pin, salt: salt)
        clear()
        var q = query
        q[kSecValueData as String] = blob
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func verify(_ pin: String) -> Bool {
        guard let saved = read() else { return false }
        return Pin.matches(pin, salt: saved.salt, saved: saved.hash)
    }

    func clear() { SecItemDelete(query as CFDictionary) }
}
