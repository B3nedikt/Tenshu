import XCTest

/// The Control store screenshots (`docs/how-to/store-screenshots.md`). Run only through
/// `scripts/store-screenshots.sh`, which erases the simulator first, sets the status bar, and runs
/// the two classes below with Drive's capture in between:
///
///   ControlOnboardingScreenshots   — a fresh device: Welcome → set up → the recovery phrase → sign-in
///   (Drive's screenshots, which seed the realm this genesis created)
///   ControlMainScreenshots         — the same realm afterwards: Home, devices, Settings, Storage,
///                                    Add a device
///
/// ⚠⚠ **What makes these screens genuine.** No launch argument at all: the scheme builds the
/// shipping screens (`Debug`), the realm is created by tapping through the real onboarding over
/// the real in-process runtime, and every later screen is reached by taps. The DEBUG deep links
/// (`-settingsHome`, `-shares`, `-notificationCenter`, …) render over FAKES and must never be used
/// here — a store listing has to show what the release app renders.
///
/// **The dev-lab realm.** Unless the script runs `--offline`, it hands the runner the dev lab's
/// origins as `TENSHU_*` variables (forwarded to the app by [devLabEnvironment] — the Debug-only
/// seams, which a Release binary ignores) plus a throwaway account address and the file its OTP
/// lands in. The onboarding then signs that address up through the real "Turn on sync" screen, so
/// the realm ends on a Free plan, funded and syncing, and Drive's banner is gone.
///
/// Attachment names are the output file names (`NN-<screen>`); the script copies them verbatim.
/// ⚠ Every name is listed in `store/control/screens.json` — the support pages and the script check
/// against it. A new capture takes the next free number, so existing ones never shift.
class ControlScreenshotCase: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    /// Answers a system permission alert (notifications) if one is up, so it never lands in a capture.
    func dismissSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow", "OK"] {
            let button = springboard.alerts.buttons[l: label].firstMatch
            if button.exists {
                button.tap()
                return
            }
        }
    }

    func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
        dismissSystemAlerts()
    }

    func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// The `TENSHU_*` origins the script passed (`TEST_RUNNER_TENSHU_*` arrives here without the
    /// prefix), for the app's launch environment. Empty on an `--offline` run.
    static var devLabEnvironment: [String: String] {
        ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
    }

    /// Launches Control with the dev-lab origins, if any.
    func launch() {
        app.launchEnvironment = Self.devLabEnvironment
        app.launch()
    }

    /// `index` is the tab's position in `ControlIosApp`'s `AppDestination` list (0 Home, 1 Data,
    /// 2 Apps, 3 Settings). Positional, in every placement — iPhone tab bar, iPad top pill, sidebar —
    /// through the shared `shellTab` (`ios-shell/UITestSupport/ShellTabs.swift`, which says why).
    func tab(_ index: Int) {
        XCTAssertTrue(app.waitForShell(timeout: 10), "the shell's tabs never appeared")
        app.shellTab(index).tap()
    }
}

final class ControlOnboardingScreenshots: ControlScreenshotCase {

    func testOnboarding() throws {
        launch()

        let startFresh = app.buttons[l: "Start fresh on this device"]
        XCTAssertTrue(
            startFresh.waitForExistence(timeout: 60),
            "Welcome never appeared — is the simulator fresh? (the script erases it)"
        )
        settle()
        shot("01-welcome")

        startFresh.tap()
        let proceed = app.buttons[l: "Continue"].firstMatch
        XCTAssertTrue(proceed.waitForExistence(timeout: 10))
        settle(seconds: 1)
        shot("02-protected")

        proceed.tap()
        let saved = app.buttons[l: "I've saved it"].firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 120), "genesis did not finish")
        settle()
        shot("03-recovery-phrase")
        try savePhraseIfAsked()

        // "Turn on sync" → the sign-in screen, before anything is typed (the address the dev-lab
        // run then enters is a throwaway one and stays out of every capture).
        app.buttons[l: "Turn on sync"].firstMatch.tap()
        let field = app.textFields[l: "Email address"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the sign-in screen did not open")
        settle(seconds: 1)
        shot("08-sign-in")

        if Self.devLabEnvironment.isEmpty {
            // `--offline`: no account — back to "You're set up", as a user who declines does.
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(saved.waitForExistence(timeout: 10))
        } else {
            try signUp(field: field)
        }

        saved.tap()
        XCTAssertTrue(app.waitForShell(timeout: 30), "the main screen never appeared after onboarding")
        settle()
    }

    /// The sign-in screen (already open) → the email-OTP screen → back on "You're set up", with the
    /// realm bound to a fresh Free account. Not captured past `08-sign-in`: those screens carry the
    /// throwaway address.
    private func signUp(field: XCUIElement) throws {
        let env = ProcessInfo.processInfo.environment
        let email = try XCTUnwrap(env["SHOTS_ACCOUNT_EMAIL"], "SHOTS_ACCOUNT_EMAIL is unset on a dev-lab run")
        let otpFile = try XCTUnwrap(env["SHOTS_OTP_FILE"], "SHOTS_OTP_FILE is unset")

        field.tap()
        field.typeText(email)
        app.buttons[l: "Email me a code"].firstMatch.tap()

        let codeField = app.textFields[l: "6-digit code"].firstMatch
        XCTAssertTrue(
            codeField.waitForExistence(timeout: 60),
            "commerce never asked for a code — is the dev lab up? (\(errorText()))"
        )
        // The script polls the dev lab's mailbox for this address and writes the code here.
        var code = ""
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            code = ((try? String(contentsOfFile: otpFile, encoding: .utf8)) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if code.count == 6 { break }
            RunLoop.current.run(until: Date().addingTimeInterval(1))
        }
        XCTAssertEqual(code.count, 6, "no OTP arrived for the sign-up within 3 minutes")
        codeField.tap()
        codeField.typeText(code)
        app.buttons[l: "Verify"].firstMatch.tap()

        // A verified sign-up pops back to "You're set up".
        XCTAssertTrue(
            app.buttons[l: "I've saved it"].firstMatch.waitForExistence(timeout: 60),
            "verification did not return to the onboarding (\(errorText()))"
        )
        settle()
    }

    /// When the script passes `SHOTS_PHRASE_FILE` (a Fit run that also covers the iPad), writes the
    /// recovery phrase shown here to it, so the iPad can restore this same realm
    /// (`ControlRecoverScreenshots`). The phrase is one text element; BIP-39 words are English
    /// in every listing language.
    private func savePhraseIfAsked() throws {
        guard let path = ProcessInfo.processInfo.environment["SHOTS_PHRASE_FILE"] else { return }
        let phrase = app.staticTexts.allElementsBoundByIndex.map(\.label).first { label in
            let words = label.split(separator: " ")
            return words.count == 24 && words.allSatisfy { $0.allSatisfy { $0.isLowercase } }
        }
        let words = try XCTUnwrap(phrase, "no 24-word phrase on the recovery-phrase screen")
        try words.write(toFile: path, atomically: true, encoding: .utf8)
    }

    /// Every text on screen — the sign-in screen's error line among them — for a failure message.
    private func errorText() -> String {
        app.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ")
    }
}

/// A fresh device that joins an existing realm by its recovery phrase, as a user restoring onto a
/// new iPad does: Welcome → "Recover from a recovery phrase" → the 24 words, typed → Recover →
/// Continue. Captures nothing. The script runs it on the 13" iPad for Fit (store-screenshots.md
/// §3e), whose walk there shows the data the iPhone's walk put into this realm, synced — because
/// Apple Health cannot be seeded on the iPad simulator.
final class ControlRecoverScreenshots: ControlScreenshotCase {

    func testRecover() throws {
        let path = try XCTUnwrap(
            ProcessInfo.processInfo.environment["SHOTS_PHRASE_FILE"],
            "SHOTS_PHRASE_FILE is unset — run this through scripts/store-screenshots.sh"
        )
        let words = try String(contentsOfFile: path, encoding: .utf8)
            .split(whereSeparator: \.isWhitespace).map(String.init)
        XCTAssertEqual(words.count, 24, "the phrase file does not hold 24 words")

        launch()
        let recover = app.buttons[l: "Recover from a recovery phrase"].firstMatch
        XCTAssertTrue(
            recover.waitForExistence(timeout: 60),
            "Welcome never appeared — is the simulator fresh? (the script erases it)"
        )
        recover.tap()

        let fields = app.textFields
        XCTAssertTrue(fields.firstMatch.waitForExistence(timeout: 10), "the recovery screen did not open")
        XCTAssertEqual(fields.count, 24, "expected the 24 word fields")
        for (index, word) in words.enumerated() {
            let field = fields.element(boundBy: index)
            field.tap()
            field.typeText(word)
        }
        app.buttons[l: "Recover"].firstMatch.tap()

        // The claim goes to the relay; "Welcome back" + Continue once this device is in the realm.
        let proceed = app.buttons[l: "Continue"].firstMatch
        XCTAssertTrue(
            proceed.waitForExistence(timeout: 180),
            "recovery did not finish: \(app.staticTexts.allElementsBoundByIndex.map(\.label))"
        )
        settle()
        proceed.tap()
        XCTAssertTrue(app.waitForShell(timeout: 60), "the main screen never appeared after recovery")
        settle(seconds: 5)
    }
}

final class ControlMainScreenshots: ControlScreenshotCase {

    func testMainScreens() {
        launch()
        // "Require unlock" is on by default, so a relaunch meets the app lock. The script enrolls
        // Face ID on the simulator and answers each prompt with a match while this test runs — the
        // same unlock a user does, so what follows is the unlocked app, not a bypass.
        let unlock = app.buttons[l: "Unlock"].firstMatch
        let unlocked = NSPredicate(format: "exists == false")
        expectation(for: unlocked, evaluatedWith: unlock)
        waitForExpectations(timeout: 60)
        settle(seconds: 3)

        // Home — the realm's recent events (Drive's uploads, after its capture ran).
        tab(0)
        settle(seconds: 3)
        shot("04-home")

        // Settings → this realm's devices.
        tab(3)
        let devices = app.buttons[l: "This realm's devices"].firstMatch
        XCTAssertTrue(devices.waitForExistence(timeout: 10))
        settle()
        shot("06-settings")

        // Settings → Storage: the plan and the relay-reported usage. Only a funded realm has
        // anything to show here, so an `--offline` run leaves it out.
        if !Self.devLabEnvironment.isEmpty {
            // The row's label carries the plan as its value ("Storage, Free").
            app.buttons.matching(ShotStrings.labelBegins("Storage")).firstMatch.tap()
            XCTAssertTrue(app.navigationBars[l: "Storage"].waitForExistence(timeout: 10))
            settle(seconds: 4)
            shot("07-storage")
            app.navigationBars.buttons.element(boundBy: 0).tap()
            XCTAssertTrue(devices.waitForExistence(timeout: 10))

            // Settings → Add a device, on "Show a code": the QR a new device scans. The code is a
            // real host pairing session on the dev relay, so an `--offline` run leaves it out too.
            // ⚠ Not the "Scan a code" tab: the simulator has no camera, so it shows a fallback a
            // phone never does.
            app.buttons[l: "Add a device"].firstMatch.tap()
            let show = app.buttons[l: "Show a code"].firstMatch
            XCTAssertTrue(show.waitForExistence(timeout: 10), "the Add a device sheet did not open")
            show.tap()
            XCTAssertTrue(
                app.staticTexts[l: "Manual Code:"].firstMatch.waitForExistence(timeout: 60),
                "no pairing code: \(app.staticTexts.allElementsBoundByIndex.map(\.label))"
            )
            settle(seconds: 3)
            shot("09-add-device")
            // Stop ends the host session; Cancel alone would leave the monitor polling for a joiner.
            app.buttons[l: "Stop"].firstMatch.tap()
            app.buttons[l: "Cancel"].firstMatch.tap()
            XCTAssertTrue(devices.waitForExistence(timeout: 10))
        }

        devices.tap()
        settle(seconds: 3)
        shot("05-devices")
        // ⚠ No capture of the Apps tab: on iOS it lists nothing yet ("No apps installed."),
        // even with Drive installed beside it.
    }
}
