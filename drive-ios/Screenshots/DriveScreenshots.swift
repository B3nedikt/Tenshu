import XCTest

/// The Drive store screenshots (`docs/how-to/store-screenshots.md`). Run only through
/// `scripts/store-screenshots.sh`, which owns the simulator, the status bar, Control's genesis on
/// the same simulator, the fixture tree and the export of the attachments below.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`, no
/// `DEV_HARNESS`), so the root is `RootView` and the route-1 runtime is the one a user's Drive runs
/// over the realm Control created. The only dev hook is `-seedFixture`, a separate launch that
/// writes the fixture through the same upload / create-folder ops the "+" menu calls, and renders
/// nothing that is captured. Every screenshot is taken in a launch with no arguments at all: the
/// empty Drive before the seed, and everything else after it. Everything on screen is reached by taps a user can make.
///
/// Unless the script runs `--offline`, both launches also carry the dev lab's `TENSHU_*` origins
/// (the Debug-only seams a Release binary ignores), and the realm Control created is funded: the
/// seed's uploads go to the dev relay for real, and the listing is captured only once the
/// "Not syncing" banner has gone.
///
/// Attachment names are the output file names (`NN-<screen>`); the script copies them verbatim.
/// ⚠ Every name is listed in `store/drive/screens.json` — the support pages and the script check
/// against it. A new capture takes the next free number, so existing ones never shift.
final class DriveScreenshots: XCTestCase {

    private var app: XCUIApplication!

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

        // The dev lab's origins (`TEST_RUNNER_TENSHU_*` arrives here without the prefix); empty on
        // an `--offline` run, which leaves the app on its production defaults with no account.
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        app.launchEnvironment = devLab

        // 0. The empty Drive a user meets right after setting up Control: the shipping launch, no
        // arguments, before anything is seeded.
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(
            app.staticTexts[l: "No files yet"].firstMatch.waitForExistence(timeout: 60),
            "the empty listing never appeared — is the realm fresh? (the script erases the simulator)"
        )
        if !devLab.isEmpty { waitForSyncing() }
        settle()
        shot("07-empty")
        app.terminate()

        // 1. Seed. Data only; nothing on this screen is captured.
        seed(fixture)

        // 2. The shipping launch: no arguments.
        app.launchArguments = []
        app.launch()

        let holiday = app.staticTexts[l: "Holiday 2026"].firstMatch
        XCTAssertTrue(holiday.waitForExistence(timeout: 60), "the seeded listing never appeared")
        if !devLab.isEmpty { waitForSyncing() }
        setViewMode("List")
        settle()
        shot("01-files")

        // The photo folder, as icons — thumbnails minted by the upload itself.
        holiday.tap()
        XCTAssertTrue(app.staticTexts[l: "IMG_4102.jpg"].firstMatch.waitForExistence(timeout: 20))
        setViewMode("Icons")
        settle(seconds: 4)
        shot("02-photos")

        // A photo opened — the preview screen.
        app.staticTexts[l: "IMG_4115.jpg"].firstMatch.tap()
        settle(seconds: 4)
        shot("03-preview")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        // The per-file actions: Share Link, Rename, Move, Delete.
        let actions = app.buttons[any: ShotStrings.format("Actions for %@", "IMG_4130.jpg")].firstMatch
        XCTAssertTrue(actions.waitForExistence(timeout: 10))
        actions.tap()
        let shareLink = app.buttons[l: "Share Link"].firstMatch
        XCTAssertTrue(shareLink.waitForExistence(timeout: 5))
        settle(seconds: 1)
        shot("04-file-actions")

        // The Share Link sheet, after "Generate Link" — only a funded, syncing realm can generate,
        // so an `--offline` run walks the empty form and captures nothing.
        app.buttons[l: "Share Link"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars[l: "Share Link"].waitForExistence(timeout: 10))
        if !devLab.isEmpty {
            app.buttons[l: "Generate Link"].firstMatch.tap()
            XCTAssertTrue(
                app.buttons[l: "Copy Link"].firstMatch.waitForExistence(timeout: 60),
                "no link was generated: \(app.staticTexts.allElementsBoundByIndex.map(\.label))"
            )
            settle(seconds: 1)
            shot("06-share-link")
        }
        app.buttons[l: "Done"].firstMatch.tap()

        // Leave the photo folder in list mode for the next run, then the "+" menu at the root.
        setViewMode("List")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(holiday.waitForExistence(timeout: 10))
        app.buttons[l: "add-file"].tap()
        XCTAssertTrue(app.buttons[l: "Choose Photo"].waitForExistence(timeout: 5))
        settle(seconds: 1)
        shot("05-add")
    }

    /// Mia's walk (`store-screenshots.md` §3g): the same fixture, seeded the same way, and nothing of
    /// Drive's captured — Mia reads the files. Ends once the seed's uploads are up, so Mia opens on a
    /// settled realm.
    func testSeedRealm() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh"
        )
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        app.launchEnvironment = devLab

        // The shipping launch first, so the runtime has met the realm and its entitlement.
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(
            app.staticTexts[l: "No files yet"].firstMatch.waitForExistence(timeout: 60),
            "the empty listing never appeared — is the realm fresh? (the script erases the simulator)"
        )
        if !devLab.isEmpty { waitForSyncing() }
        app.terminate()

        seed(fixture)

        app.launchArguments = []
        app.launch()
        XCTAssertTrue(
            app.staticTexts[l: "Holiday 2026"].firstMatch.waitForExistence(timeout: 60),
            "the seeded listing never appeared"
        )
        if !devLab.isEmpty { waitForSyncing() }
        waitForSyncIdle()
        app.terminate()
    }

    // MARK: - Helpers

    /// The `-seedFixture` launch: data only, nothing on its screen is captured.
    private func seed(_ fixture: String) {
        app.launchArguments = ["-seedFixture", fixture]
        app.launch()
        let result = app.staticTexts[l: "seed-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 10), "the seed view did not appear")
        let settled = NSPredicate(format: "label BEGINSWITH 'seed: ' ")
        expectation(for: settled, evaluatedWith: result)
        waitForExpectations(timeout: 180)
        XCTAssertTrue(
            result.label.contains("failed=0") || result.label.contains("files=0 skipped="),
            "seeding failed: \(result.label)"
        )
        app.terminate()
    }

    /// The funded realm's entitlement reaches this runtime over the shared replica; until it does,
    /// the "Not syncing" banner says what is true of an unfunded device. Dev-lab runs only.
    private func waitForSyncing() {
        let banner = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Not syncing'")).firstMatch
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: banner)
        waitForExpectations(timeout: 120)
    }

    /// Picks Icons / List in the toolbar's view-options menu (persisted per device by the app).
    private func setViewMode(_ mode: String) {
        let options = app.buttons[l: "view-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 10), "view-options missing")
        options.tap()
        let item = app.buttons[l: mode].firstMatch
        if item.waitForExistence(timeout: 5) {
            item.tap()
        } else {
            // Already-selected inline picker rows can surface as cells on some OS versions.
            app.cells.containing(ShotStrings.labelIs(mode)).firstMatch.tap()
        }
    }

    /// Lets thumbnails decode and transitions finish before a capture.
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

    private func shot(_ name: String) {
        waitForSyncIdle()
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
