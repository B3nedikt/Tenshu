import XCTest

/// The only automated check that can see a cross-app open arrive
/// (`control-cross-app-open.md` §5i.7, §5i.8).
///
/// ⚠⚠⚠ **It exists because iOS asks the user first — when the opener is another app.**
/// `xcrun simctl openurl` on a custom scheme raises a SpringBoard alert — *Open in "Drive"?* /
/// Cancel / Open — and **waits**. Nothing is delivered until somebody taps Open, which is why two
/// sessions of headless attempts produced no signal at all: every URL sat behind an unconfirmed
/// modal. A dialog is not an event, so no amount of log-reading could see it.
///
/// ⭐ **Measured here: an app opening its OWN scheme is not prompted.** That is why the probe
/// self-opens — it exercises the identical receive path with no modal in the way. [confirmOpenAlert]
/// is kept for the externally-opened case and taps only if an alert actually appears; a test that
/// *required* one would now be asserting a fact about the opener rather than about the feature.
///
/// ⚠ `ios-smoke.sh --build-only` cannot catch this either — it proves the app compiles, and the
/// whole question is what happens at runtime when a URL is confirmed.
///
/// ```
/// xcodebuild test -project drive-ios/DriveIos.xcodeproj -scheme DriveIos-UITests \
///   -destination 'platform=iOS Simulator,name=iPhone 16' \
///   -only-testing:DriveIosUITests/OpenTargetUITests
/// ```
///
/// ⚠⚠ **A stale pending open survives across runs, and it fails this test with a confusing
/// message.** An unconfirmed `simctl openurl` stays queued in the simulator and is delivered on a
/// later launch — so the readout shows a target *nobody in this run asked for*, and the failure
/// reads like a wrong decode rather than leftover state. It survived an uninstall/reinstall and a
/// test re-run; `simctl shutdown` + `boot` cleared it. If this test fails naming a key you do not
/// recognise, that is what happened.
final class OpenTargetUITests: XCTestCase {

    /// ⚠ Must match `OpenTargetSelfFire` in the app target — change both or neither.
    private let expectedType = "tenshu/file"
    private let expectedKey = "/Photos/uitest.jpg"

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["DRIVE_DEV_PROVISION_CONTROL"] = "1"
    }

    /// ⭐ The whole round trip: the app builds a real URL with the **production encoder**, opens it
    /// at itself, iOS asks, the test confirms, and the app decodes it back to the row it named.
    ///
    /// ⚠⚠ Self-opening rather than driving Safari is deliberate — typing a base64url payload into
    /// an address bar is slow and brittle, and a browser adds a second confirmation surface with
    /// its own wording. The alert this taps is the same one either way.
    /// ⭐⭐ The whole round trip: the app builds a real URL with the **production encoder**, opens
    /// it at itself, and decodes it back to the row it named.
    func test_aCrossAppOpenReachesTheAppAndDecodes() throws {
        app.launchArguments.append("-fireOpenTargetSelfUrl")
        app.launch()

        confirmOpenAlertIfPresent()

        let readout = app.staticTexts[Self.readoutId]
        XCTAssertTrue(readout.waitForExistence(timeout: 30), "the harness readout never mounted")
        expectEventually(readout, toShow: "type=\(expectedType) key=\(expectedKey)")
    }

    /// ⚠⚠ **The sentinel, asserted on its own launch.** Without this, a readout that showed the
    /// expected value for some *other* reason would make the test above vacuous — and it cannot be
    /// checked in the same run, because the probe fires seconds after launch and races the query.
    /// Two launches, one fact each.
    func test_withNoOpenTheReadoutSaysNone() throws {
        app.launch()

        let readout = app.staticTexts[Self.readoutId]
        XCTAssertTrue(readout.waitForExistence(timeout: 30), "the harness readout never mounted")
        // ⚠ Held for a beat: "none" that turns into a value a second later would still be a bug.
        Thread.sleep(forTimeInterval: 6)
        XCTAssertEqual(readout.label, "none", "a target arrived with nothing having opened one")
    }

    /// Tap iOS's *Open in "…"?* confirmation if it appears.
    ///
    /// ⚠⚠ **Conditional on purpose, and the condition is a finding rather than a hedge.** An app
    /// opening its own scheme is not prompted; an *external* opener — `simctl openurl`, Safari — is,
    /// and then nothing is delivered until this tap happens. The probe takes the unprompted path, so
    /// this is normally a no-op; it stays because the moment anyone points an external opener at the
    /// app, its absence is the difference between a passing test and a hang.
    ///
    /// ⚠ Matches the button, never the alert title — the title carries the app's display name.
    private func confirmOpenAlertIfPresent() {
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 5) { open.tap() }
    }

    /// ⚠ Polls rather than asserting once: the decode happens after the app returns to the
    /// foreground, so a single read races the hand-back.
    private func expectEventually(
        _ element: XCUIElement,
        toShow expected: String,
        timeout: TimeInterval = 30
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label == expected { return }
            usleep(250_000)
        }
        XCTFail("readout showed '\(element.label)', expected '\(expected)'")
    }

    /// ⭐⭐ **The navigation, which is the half a user can see.** Decoding proves the carrier;
    /// this proves Drive actually shows the row it was handed. They are separate tests because
    /// they fail for different reasons — a broken encoder and a broken push are not the same bug,
    /// and one assertion covering both would name neither.
    ///
    /// ⚠ Asserts the navigation **title**, not the file's contents: the probe names a path that
    /// does not exist in the test realm, so the preview will not render bytes. Where the user
    /// landed is the fact under test; whether a fixture happens to be readable is not.
    func test_aCrossAppOpenNavigatesToTheFile() throws {
        app.launchArguments.append("-fireOpenTargetSelfUrl")
        app.launch()

        confirmOpenAlertIfPresent()

        // The last path component of `OpenTargetSelfFire.expectedKey`.
        let title = app.navigationBars["uitest.jpg"]
        XCTAssertTrue(
            title.waitForExistence(timeout: 40),
            "Drive decoded the target but never navigated to it — the file preview never opened"
        )
    }

    /// ⚠ Mirrors `OpenTargetHarness.accessibilityId`; the UI test target cannot import the app.
    private static let readoutId = "openTargetReceipt"
}
