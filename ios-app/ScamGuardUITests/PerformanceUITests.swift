import XCTest

/// Start-up time and call-screen smoothness, measured on the Simulator. Numbers only (no pass/fail baseline is set):
/// they are read from the result bundle and reported.
final class PerformanceUITests: XCTestCase {
    private let base = ["-onboarding_completed", "YES", "-onboarding_version", "2", "-app_style_tip_shown", "YES", "-simple_mode", "NO",
                        "-demoPin", "off"]

    /// App start to the first frame of Home (cold launch, 5 runs).
    func testLaunchTime() {
        let app = XCUIApplication()
        app.launchArguments = base
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: options) { app.launch() }
    }

    /// Hitches while a call plays and the transcript scrolls (a scam script at 4× so lines keep coming).
    func testCallScreenHitches() throws {
        guard #available(iOS 26.0, *) else { throw XCTSkip("XCTHitchMetric needs iOS 26") }
        let app = XCUIApplication()
        app.launchArguments = base + ["-demoDev", "YES", "-demoSpeed", "4x", "-demoScript", "scam_en_10_gov_official.json"]
        app.launch()
        sleep(3)
        let options = XCTMeasureOptions()
        options.iterationCount = 3
        measure(metrics: [XCTHitchMetric(application: app)], options: options) {
            app.swipeUp()
            app.swipeDown()
        }
    }
}
