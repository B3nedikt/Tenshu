import XCTest

/// The Timeline store screenshots (`docs/how-to/store-screenshots.md` §3f). Run only through
/// `scripts/store-screenshots.sh timeline`, which owns the simulator, the status bar, Control's
/// genesis on the same simulator, the fixture (`store/fixtures/timeline/generate_timeline.py`) and
/// the export.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`, no
/// `DEV_HARNESS`), over the route-1 runtime and the realm Control created. The one dev hook is
/// `-seedFixture`, a separate launch that imports a Google Timeline export through the shared importer
/// (the call Android's and desktop's Takeout import makes) and renders nothing captured. Every capture
/// comes from the next launch, which has no arguments; the trip is made by tapping *Read as a trip* on
/// the suggestion, as a user does.
///
/// Timeline has no tab bar (one screen with a segmented Home / Places / Activities control), so no
/// `shellTab` lookup: the iPad shows the same stack, wider.
///
/// Attachment names are the output file names (`NN-<screen>`), listed in `store/timeline/screens.json`.
/// A new capture takes the next free number, so existing ones never shift.
final class TimelineScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// Today's walk across the river, Piazza Santo Spirito → Galleria dell'Accademia: its row starts
    /// with the time the manifest ends the stay before it.
    private let todaysWalk = "09:25"
    /// A stop of today's — its row is how the walk knows the feed has loaded.
    private let todaysStop = "Galleria dell'Accademia"
    /// The place `06-place` opens: three visits over the three days.
    private let place = "Piazza Santo Spirito"
    /// The trip's title — the label of the place most visited on it (`TripSuggester.dominantLabel`).
    private let trip = "Florence"

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

        // 1. Seed. Data only; nothing on this screen is captured.
        app.launchArguments = ["-seedFixture", fixture]
        app.launch()
        let result = app.staticTexts[l: "seed-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
        expectation(for: NSPredicate(format: "label BEGINSWITH 'seed: ' "), evaluatedWith: result)
        waitForExpectations(timeout: 300)
        XCTAssertTrue(result.label.hasPrefix("seed: OK"), "seeding failed: \(result.label)")
        app.terminate()

        // 2. The shipping launch: no arguments. It opens on the Home feed.
        // ⚠⚠ Relaunched — as a user would — while it opens under "Can't reach Tenshu Control". That
        // banner is a DEFECT, not the realm's state: now and then a read of an empty place-service
        // collection (merges, roles) fails with a message-less internal error over the in-process
        // runtime, `foldTracked` reads that as Control being gone, and the shell reads the flag only
        // when it mounts, so it stays up for the session (store-screenshots.md §3f). Never captured;
        // each relaunch is printed, so a run says how often it happened.
        app.launchArguments = []
        let stop = app.staticTexts[l: todaysStop].firstMatch
        let unreachable = app.buttons[l: "Set up Tenshu Control"]
        for attempt in 1...5 {
            app.launch()
            XCTAssertTrue(stop.waitForExistence(timeout: 120), "today's stops never reached the feed: \(labels())")
            settle(seconds: 5)
            if !unreachable.exists { break }
            print("TIMELINE-SHOTS launch \(attempt) opened under the Control-unreachable banner — relaunching")
            XCTAssertLessThan(attempt, 5, "every launch opened under the Control-unreachable banner")
            app.terminate()
        }

        // 01 — today, newest day first.
        settle(seconds: 3)
        shot("01-today")

        // 02 — the trip suggestion under today's chapter.
        let readAsTrip = app.buttons[l: "Read as a trip"].firstMatch
        XCTAssertTrue(readAsTrip.waitForExistence(timeout: 60), "no trip suggestion: \(labels())")
        reveal(readAsTrip, keepingAbove: 0.62)
        shot("02-suggestion")

        // 03 — the trip, made by accepting the suggestion, opened from "Bound chapters" at the feed's end.
        readAsTrip.tap()
        // The chapter's card is the one button whose label starts with the trip's title: a moment
        // row's starts with its time, and the place rows are on the Places tab.
        let cover = app.buttons.matching(ShotStrings.labelBegins(trip)).firstMatch
        XCTAssertTrue(cover.waitForExistence(timeout: 30), "the accepted trip never became a chapter: \(labels())")
        for _ in 0..<80 where !cover.isHittable { app.swipeUp() }
        XCTAssertTrue(cover.isHittable, "could not scroll to the trip's chapter")
        cover.tap()
        XCTAssertTrue(app.staticTexts[l: "places"].firstMatch.waitForExistence(timeout: 30), "the trip's detail")
        settle(seconds: 8) // the map draws its tiles
        shot("03-trip")
        goBack()

        // 04 — today's walk across the river, on the map.
        let walk = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", todaysWalk)).firstMatch
        XCTAssertTrue(walk.waitForExistence(timeout: 30), "today's walk row: \(labels())")
        for _ in 0..<80 where !walk.isHittable { app.swipeDown() }
        walk.tap()
        settle(seconds: 8)
        shot("04-walk")
        goBack()

        // 05 — every place, 06 — one of them.
        for _ in 0..<10 where !app.buttons[l: "Places"].firstMatch.isHittable { app.swipeDown() }
        app.buttons[l: "Places"].firstMatch.tap()
        let row = app.buttons.matching(ShotStrings.labelBegins(place)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the Places tab: \(labels())")
        settle(seconds: 3)
        shot("05-places")
        for _ in 0..<20 where !row.isHittable { app.swipeUp() }
        row.tap()
        XCTAssertTrue(app.staticTexts[l: "visits"].firstMatch.waitForExistence(timeout: 30), "the place's detail")
        settle(seconds: 8)
        shot("06-place")
    }

    /// Scrolls the feed in short drags until [element]'s middle sits between 30 % and `fraction` of
    /// the window's height — on screen, with the content above it in the picture too.
    private func reveal(_ element: XCUIElement, keepingAbove fraction: CGFloat) {
        let height = app.windows.firstMatch.frame.height
        let centre = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        for _ in 0..<60 {
            let mid = element.frame.midY
            if element.isHittable && mid > height * 0.3 && mid < height * fraction { break }
            let dy: CGFloat = mid >= height * fraction ? -240 : 240
            centre.press(forDuration: 0.05, thenDragTo: centre.withOffset(CGVector(dx: 0, dy: dy)))
        }
        settle(seconds: 2)
    }

    private func goBack() {
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 10), "no back button")
        back.tap()
        settle(seconds: 2)
    }

    private func labels() -> String {
        app.descendants(matching: .any).allElementsBoundByIndex.prefix(80).map(\.label).filter { !$0.isEmpty }
            .joined(separator: " | ")
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// ⚠ Waits for the sync-activity capsule to stay gone 3 s first, as every walk does (§3e): the
    /// seed's rows upload to the dev relay on the capture launch, and "Syncing…" must not be in a shot.
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
        XCTFail("the sync-activity capsule was still up after \(Int(timeout)) s (\(capsule.exists ? capsule.label : "gone at the last check")) — not capturing mid-sync")
    }

    private func shot(_ name: String) {
        waitForSyncIdle()
        let banner = app.buttons[l: "Set up Tenshu Control"]
        XCTAssertFalse(banner.exists, "\(name) would show the Control-unreachable banner")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
