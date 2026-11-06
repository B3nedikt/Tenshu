import XCTest

/// The landing page's Drive captures (`docs/how-to/store-screenshots.md` §9): the iOS twin of the
/// Android walk's `drive_landing`, over the stock-photo recipe `store/fixtures/landing/`. Run only
/// through `scripts/store-screenshots.sh --set landing`, which builds that tree, runs Control's
/// genesis on the same simulator first and exports these attachments under the device
/// `iphone69landing`, so a landing capture never replaces a support-page one. ⚠ Two tests, so the
/// script names the method (`…/testLandingScreenshots`); Photos' support set runs only
/// `testSeedLandingRealm`.
///
/// The same rules as `DriveScreenshots`: the shipping `Debug` app, the one `-seedFixture` launch that
/// writes data only and is never captured, and every capture in a launch with no arguments, reached
/// by taps. ⚠ The names are `store/drive/screens.json` → `landing.ios.captured`; `landingShot(…)` is
/// spelled apart from `shot(…)` so the support-page list never picks them up.
final class DriveLandingScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// The landing set's photo folder and the photo its preview opens — the Android walk's
    /// `LANDING_FOLDER` / `LANDING_PHOTO`, so both platforms show the same content.
    private let folder = "Dolomites 2026"
    private let photo = "IMG_5102.jpg"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testLandingScreenshots() throws {
        try seedLandingRealm()
        let dolomites = app.staticTexts[l: folder].firstMatch
        setViewMode("List")
        settle(seconds: 3)
        landingShot("01-files")

        // The photo folder as icons — real thumbnails, minted by the upload itself.
        dolomites.tap()
        XCTAssertTrue(app.staticTexts[l: photo].firstMatch.waitForExistence(timeout: 20))
        setViewMode("Icons")
        settle(seconds: 8)
        landingShot("02-photos")

        // One photo previewed.
        app.staticTexts[l: photo].firstMatch.tap()
        settle(seconds: 6)
        landingShot("03-preview")
    }

    /// Photos' support set (`store-screenshots.md` §3d): a realm filled the same way — from the
    /// `photos` set, the same photos six to a day — and nothing of Drive's captured. Photos reads every
    /// image in the realm, so this is its library.
    /// Ends once the seed's bytes are up (the capsule gone), so Photos opens on a settled realm.
    func testSeedLandingRealm() throws {
        try seedLandingRealm()
        waitForSyncIdle()
        app.terminate()
    }

    /// The shipping launch, the `-seedFixture` launch, then the shipping launch again, left on the
    /// seeded listing with the realm syncing.
    private func seedLandingRealm() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh --set landing"
        )
        // The landing set always runs on the dev lab: a funded realm takes the uploads.
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        XCTAssertFalse(devLab.isEmpty, "the landing set needs the dev lab's TENSHU_* origins")
        app.launchEnvironment = devLab

        // The shipping launch first, so the runtime has met the realm and its entitlement.
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(
            app.staticTexts[l: "No files yet"].firstMatch.waitForExistence(timeout: 60),
            "the empty listing never appeared — is the realm fresh? (the script erases the simulator)"
        )
        waitForSyncing()
        app.terminate()

        // Seed. Data only; nothing on this screen is captured. ⚠ The seed has stalled mid-tree on a
        // loaded machine (2026-10-17/18: once after 52 of 55 files, once after 25, never resuming), so
        // a launch gets ten minutes and a stalled one is relaunched: the seed skips every path already
        // in the realm, so a relaunch picks up where the last stopped.
        var summary = ""
        for attempt in 1...4 {
            app.launchArguments = ["-seedFixture", fixture]
            app.launch()
            let result = app.staticTexts[l: "seed-result"]
            XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
            let done = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label BEGINSWITH 'seed: ' "), object: result)
            if XCTWaiter().wait(for: [done], timeout: 600) == .completed {
                summary = result.label
                app.terminate()
                break
            }
            print("seed attempt \(attempt) stalled — relaunching")
            app.terminate()
        }
        XCTAssertTrue(summary.contains("failed=0"), "seeding failed or never finished: \(summary)")

        // The shipping launch: no arguments.
        app.launchArguments = []
        app.launch()
        let dolomites = app.staticTexts[l: folder].firstMatch
        XCTAssertTrue(dolomites.waitForExistence(timeout: 60), "the seeded listing never appeared")
        waitForSyncing()
    }

    // MARK: - Helpers (DriveScreenshots' own)

    private func waitForSyncing() {
        let banner = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Not syncing'")).firstMatch
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: banner)
        // ⚠ Longer than DriveScreenshots' 120 s: a dark run on 2026-10-18 timed out there once.
        waitForExpectations(timeout: 300)
    }

    private func setViewMode(_ mode: String) {
        let options = app.buttons[l: "view-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 10), "view-options missing")
        options.tap()
        let item = app.buttons[l: mode].firstMatch
        if item.waitForExistence(timeout: 5) {
            item.tap()
        } else {
            app.cells.containing(ShotStrings.labelIs(mode)).firstMatch.tap()
        }
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// ⚠ Waits out the sync-activity capsule (`ios-storage`'s `SyncActivityCapsule`, identifier
    /// `sync-activity-capsule`: "Uploading 3 of 12", "Uploading…", "Syncing…") before a capture. The
    /// seed's bytes are still going up when the listing appears, and a dark landing run on 2026-10-23
    /// captured the capsule on every Drive screen. It shows 700 ms after activity starts
    /// (`SyncActivityTiming`), so one absent check proves nothing: it must stay gone for `quiet`
    /// seconds. A capsule still up at `timeout` fails the walk rather than capture it.
    private func waitForSyncIdle(timeout: TimeInterval = 300, quiet: TimeInterval = 3) {
        let capsule = app.descendants(matching: .any)[l: "sync-activity-capsule"]
        let deadline = Date().addingTimeInterval(timeout)
        var goneSince: Date?
        while Date() < deadline {
            if capsule.exists {
                goneSince = nil
            } else if let since = goneSince {
                if Date().timeIntervalSince(since) >= quiet { return }
            } else {
                goneSince = Date()
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTFail("the sync-activity capsule was still up after \(Int(timeout)) s (\(capsule.exists ? capsule.label : "gone at the last check")) — not capturing mid-upload")
    }

    private func landingShot(_ name: String) {
        waitForSyncIdle()
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
