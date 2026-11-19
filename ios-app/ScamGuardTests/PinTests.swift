import XCTest
@testable import ScamGuard

/// Phase 4: the optional PIN (set / verify / reset). The same vectors as Android PinTest.
@MainActor
final class PinTests: XCTestCase {
    private let salt = Pin.unhex("000102030405060708090a0b0c0d0e0f")

    func testOnlyFourDigits() {
        XCTAssertTrue(Pin.isValid("0000"))
        XCTAssertTrue(Pin.isValid("2468"))
        for p in ["", "123", "12345", "12a4", "١٢٣٤", " 1234"] { XCTAssertFalse(Pin.isValid(p), p) }
    }

    /// The same hash on both platforms (PBKDF2-HMAC-SHA256, 20 000 rounds, 32 bytes).
    func testKnownHash() {
        XCTAssertEqual(Pin.hex(Pin.hash("2468", salt: salt)), "874306222d2ae5883abfc71a9ee1618098b7a33f9504cea3e2213739c89d5275")
        XCTAssertEqual(Pin.hash("0000", salt: salt).count, 32)
    }

    func testSetVerifyResetInTheKeychain() throws {
        let store = PinStore(service: "test.pin.\(UUID().uuidString)")
        defer { store.clear() }
        XCTAssertFalse(store.isSet)
        XCTAssertFalse(store.set("12"), "only 4 digits")
        XCTAssertTrue(store.set("2468"))
        XCTAssertTrue(store.isSet)
        XCTAssertTrue(store.verify("2468"))
        XCTAssertFalse(store.verify("2469"))
        XCTAssertTrue(store.set("1357"), "change")
        XCTAssertFalse(store.verify("2468"))
        XCTAssertTrue(store.verify("1357"))
        store.clear()                                     // "Forgot PIN"
        XCTAssertFalse(store.isSet)
        XCTAssertFalse(store.verify("1357"))
    }

    /// "Forgot PIN" removes only the PIN: the call history and settings stay.
    func testForgotPinKeepsTheHistory() throws {
        let suite = "pin-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let store = try CallStore(inMemory: true)
        let pins = PinStore(service: "test.\(suite)")
        defer { pins.clear() }
        let vm = CallViewModel(prefs: AppPrefs(defaults: d), store: store, scripts: [], pins: pins, startUp: false)
        vm.loadSampleHistory()
        let calls = store.calls().count
        XCTAssertGreaterThan(calls, 0)
        vm.setPin("2468")
        XCTAssertTrue(vm.pinSet)
        XCTAssertTrue(vm.verifyPin("2468"))
        vm.clearPin()
        XCTAssertFalse(vm.pinSet)
        XCTAssertEqual(store.calls().count, calls)
    }
}
