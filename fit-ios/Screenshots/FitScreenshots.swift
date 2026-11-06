import XCTest

/// The Fit store screenshots (`docs/how-to/store-screenshots.md`). Run only through
/// `scripts/store-screenshots.sh fit`, which owns the simulator, the status bar, Control's genesis
/// on the same simulator, the fixture (`store/fixtures/fit/generate_fit.py`) and the export.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`, no
/// `DEV_HARNESS`), over the route-1 runtime and the realm Control created. The one dev hook is
/// `-seedFixture`, a separate launch that puts the fixture where a user's data would be — the daily
/// figures into Apple Health, the workouts through the file import — and renders nothing captured.
/// Every capture comes from the next launch, which has no arguments: Apple Health comes in by taps
/// through Profile → "Import from Apple Health" (the seed's file import already counts as Fit's
/// first start), consent sheet included, exactly as on a phone.
///
/// Attachment names are the output file names (`NN-<screen>`), listed in `store/fit/screens.json`.
/// A new capture takes the next free number, so existing ones never shift.
final class FitScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// The newest workout in the fixture — today's run, the first row in History.
    private let workout = "Morning run along the Arno"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testStoreScreenshots() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh"
        )
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        app.launchEnvironment = devLab

        // 1. Seed. Data only; nothing on this screen is captured. Writing to Apple Health asks first.
        seed(fixture)

        // 2. The shipping launch: no arguments. Apple Health comes in by taps — through the first-run
        // onboarding if Fit shows it, else Profile → "Import from Apple Health", the explicit first
        // connection a user who started from a file import makes. ⚠ The seed's file import already
        // counts as a start, so today it is the second path.
        app.launchArguments = []
        app.launch()
        let appleHealth = app.buttons.containing(ShotStrings.labelIs("Apple Health")).firstMatch
        let deadline = Date().addingTimeInterval(90)
        while !app.isShellUp && !appleHealth.exists && Date() < deadline { settle(seconds: 1) }
        if appleHealth.exists && !app.isShellUp {
            appleHealth.tap()
            let importButton = app.buttons[l: "Import"].firstMatch
            XCTAssertTrue(importButton.waitForExistence(timeout: 20), "the category picker")
            importButton.tap()
            answerHealthSheet(timeout: 30)
            // "A bit about you" asks for what Apple Health cannot say (birth date, sex): skipped, as
            // a user may — Fit then uses its neutral estimate.
            for _ in 0..<2 where app.buttons[l: "Continue"].firstMatch.waitForExistence(timeout: 120) {
                app.buttons[l: "Continue"].firstMatch.tap()
            }
        } else {
            XCTAssertTrue(app.isShellUp, "Fit opened on neither onboarding nor Home")
            tab(4).tap()
            let importHealth = app.buttons[l: "Import from Apple Health"].firstMatch
            // ⚠ Below the fold: Profile is a List, which builds its rows only as they scroll in.
            XCTAssertTrue(app.staticTexts[l: "Data sources"].firstMatch.waitForExistence(timeout: 30), "Profile")
            for _ in 0..<5 where !(importHealth.exists && importHealth.isHittable) {
                app.swipeUp(velocity: .slow)
            }
            XCTAssertTrue(importHealth.exists, "Profile's Import from Apple Health")
            importHealth.tap()
            answerHealthSheet(timeout: 30)
            // ⚠ The Apple Health row appears as soon as the source registers, reading "Not imported
            // yet" — the import is done only once that status has gone.
            let row = app.staticTexts[l: "Apple Health"].firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 120), "the Apple Health source never registered")
            let pending = app.staticTexts[l: "Not imported yet"].firstMatch
            let until = Date().addingTimeInterval(180)
            while pending.exists && Date() < until { dismissHealthSyncNotice(); settle(seconds: 2) }
            XCTAssertFalse(pending.exists, "the Apple Health import never finished")
            settle(seconds: 5)
        }

        XCTAssertTrue(app.waitForShell(timeout: 60), "the app never opened after onboarding")
        captureScreens()
    }

    /// The 13" iPad's walk (store-screenshots.md §3e). Apple Health cannot be seeded on the iPad
    /// simulator, so this device joined the iPhone's realm by its recovery phrase
    /// (`ControlRecoverScreenshots`) and Fit shows what the iPhone's walk imported there, synced —
    /// the same data, arriving the way it does on a user's second device. No seed, no import: one
    /// launch with no arguments, then the same screens.
    func testStoreScreenshotsFromSyncedRealm() throws {
        app.launchEnvironment = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        app.launch()
        XCTAssertTrue(app.waitForShell(timeout: 90), "Fit never opened on its main screen")

        // Synced, not imported here: wait until History has the newest workout, then give the
        // daily figures (which sync beside it) time to land before Home is captured.
        tab(1).tap()
        let row = app.staticTexts[l: workout].firstMatch
        XCTAssertTrue(
            row.waitForExistence(timeout: 600),
            "the iPhone's workouts never synced to this device: \(app.staticTexts.allElementsBoundByIndex.map(\.label))"
        )
        settle(seconds: 30)
        captureScreens()
    }

    /// Mia's walk (`store-screenshots.md` §3g): the workouts, imported by the same seed launch, and
    /// nothing of Fit's captured — Mia reads them. iPhone only: the seed cannot run on the iPad (§3e).
    /// ⚠ The daily figures stay in Apple Health: they reach the realm only through the Profile import,
    /// which Mia's questions do not need.
    func testSeedRealm() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh"
        )
        app.launchEnvironment = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        seed(fixture)
    }

    /// The `-seedFixture` launch: the daily figures into Apple Health (asking first) and the
    /// workouts through the file import. Data only; nothing on its screen is captured.
    private func seed(_ fixture: String) {
        app.launchArguments = ["-seedFixture", fixture]
        app.launch()
        let result = app.staticTexts[l: "seed-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
        answerHealthSheet(timeout: 60)
        // ⚠ Polled rather than awaited: on the iPad, Apple Health follows the write consent with
        // "iCloud Health Data Sync is Off" (Open Settings / Not Now), and the seed waits behind it
        // for good (300 s, 2026-10-24); answered, the seed fails fast instead (§3e).
        let seeded = Date().addingTimeInterval(300)
        while !result.label.hasPrefix("seed: ") && Date() < seeded {
            dismissHealthSyncNotice()
            settle(seconds: 1)
        }
        XCTAssertTrue(result.label.hasPrefix("seed: OK"), "seeding failed: \(result.label)")
        app.terminate()
    }

    /// The listing's screens, from the shell, in order. The realm must already hold the fixture.
    private func captureScreens() {
        // 01 — Home.
        tab(0).tap()
        settle(seconds: 6)
        shot("01-home")

        // 02 — History, 03 — the newest workout.
        tab(1).tap()
        let row = app.staticTexts[l: workout].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 60), "the imported workouts never reached History")
        settle(seconds: 3)
        shot("02-history")
        row.tap()
        settle(seconds: 8) // the map draws its tiles
        shot("03-workout")

        // 05 — Browse, 06 — sleep.
        tab(3).tap()
        settle(seconds: 3)
        shot("05-browse")
        let sleep = app.buttons.containing(ShotStrings.labelIs("Sleep")).firstMatch
        if sleep.waitForExistence(timeout: 10) {
            sleep.tap()
            settle(seconds: 4)
            shot("06-sleep")
        }

        // 07 — Profile.
        tab(4).tap()
        settle(seconds: 3)
        shot("07-profile")
    }

    /// Answers the Apple Health consent sheet the way a user who wants Fit to work does: every
    /// type on, then Allow. ⚠ The sheet is the system's, drawn inside the app's own hierarchy, and
    /// its wording moves between releases: iOS ≤ 26 says "Turn On All", iOS 27 "Select All N Topics".
    /// A shell tab, by its position in the app's `AppDestination` list (`FitIosApp`: 0 Home, 1 History, 2 Record, 3 Browse, 4 Profile).
    ///
    /// By index, in every placement (iPhone tab bar, iPad top pill, sidebar): the shared `shellTab`
    /// (`ios-shell/UITestSupport/ShellTabs.swift` says why a title or `tabBars` lookup does not do).
    /// Resolved when called, so call it once the shell is up.
    private func tab(_ index: Int) -> XCUIElement {
        app.shellTab(index)
    }

    private func answerHealthSheet(timeout: TimeInterval) {
        let selectAll = app.descendants(matching: .any).matching(
            NSCompoundPredicate(orPredicateWithSubpredicates: [
                ShotStrings.labelIs("Turn On All"), ShotStrings.labelBegins("Select All"),
                ShotStrings.labelLike("Select All * Topics"),
            ])
        ).firstMatch
        guard selectAll.waitForExistence(timeout: timeout) else {
            let sheet = app.navigationBars[l: "Health Access"].exists
            XCTFail("no select-all control on the Apple Health sheet (sheet up: \(sheet)); labels: " +
                    app.descendants(matching: .any).allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
                        .joined(separator: " | "))
            return
        }
        selectAll.tap()
        // ⚠ iOS 27 closes the sheet with static text, not a button — "Allow" when asking to write,
        // "Continue" when asking to read — so controls are matched by label. A read request then has
        // a SECOND page, "How much data would you like to share?", whose Allow stays disabled until
        // a history choice is made: the test picks all recorded data, as a user bringing their
        // history in would. Pressed until neither page is up.
        let confirm = app.descendants(matching: .any).matching(
            ShotStrings.labelIs("Allow", "Continue")
        ).firstMatch
        let allHistory = app.descendants(matching: .any).matching(
            ShotStrings.labelBegins("All Recorded Data")
        ).firstMatch
        let firstPage = app.navigationBars[l: "Health Access"].firstMatch
        let historyPage = app.staticTexts.matching(ShotStrings.labelBegins("How much data")).firstMatch
        for _ in 0..<4 {
            if allHistory.exists {
                allHistory.tap()
                settle(seconds: 1)
            }
            guard confirm.waitForExistence(timeout: 10) else { break }
            for _ in 0..<6 where !confirm.isHittable { app.swipeUp() }
            confirm.tap()
            settle(seconds: 2)
            if !firstPage.exists && !historyPage.exists { break }
        }
        XCTAssertFalse(firstPage.exists || historyPage.exists, "the Apple Health sheet is still up")
        settle(seconds: 2)
        dismissHealthSyncNotice()
    }

    /// Answers Apple Health's "iCloud Health Data Sync is Off" notice with *Not Now*. Seen on the
    /// iPad simulator only, where ⚠⚠ it does not rescue the run: the request then ends with
    /// HealthKit's "Authorization is not determined", so the seed FAILS fast instead of hanging
    /// for 300 s — which is why the iPad runs `testStoreScreenshotsFromSyncedRealm` instead
    /// (store-screenshots.md §3e).
    /// ⚠ Like the consent sheet's controls on iOS 27, its buttons are static text: matched by label.
    private func dismissHealthSyncNotice() {
        guard app.staticTexts[l: "iCloud Health Data Sync is Off"].exists else { return }
        let notNow = app.descendants(matching: .any).matching(ShotStrings.labelIs("Not Now")).firstMatch
        if notNow.exists { notNow.tap() }
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
