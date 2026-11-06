import XCTest

/// The Calendar store screenshots (`docs/how-to/store-screenshots.md` §3b). Run only through
/// `scripts/store-screenshots.sh calendar`, which owns the simulator, the status bar, Control's
/// genesis on the same simulator, the fixture (`store/fixtures/calendar/generate_calendar.py`) and
/// the export.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`), over the
/// route-1 runtime and the realm Control created. The one dev hook is `-seedFixture`, a separate
/// launch that makes the calendars and imports their `.ics` files through the calls the Calendars
/// tab's sheets make, and renders nothing captured. Every capture comes from the next launch, which
/// has no arguments; the new event, the reminders permission and the calendar sheet are all taps.
///
/// Attachment names are the output file names (`NN-<screen>`), listed in `store/calendar/screens.json`.
/// A new capture takes the next free number, so existing ones never shift.
final class CalendarScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// A one-off on the next weekday, with a location, notes and a reminder — the detail screen.
    private let detailEvent = "Dentist — Dr. Okafor"

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
        seed(fixture)

        // 2. The shipping launch: no arguments. It opens on the month, today selected.
        app.launchArguments = []
        app.launch()
        XCTAssertTrue(app.waitForShell(timeout: 60), "Calendar never opened (not provisioned?)")

        // 01 — the month, with today's events beneath it.
        // ⚠⚠ Waits for a fixture event BY NAME. "The second cell" was the wait before, and it also
        // matched the loading `ProgressView` row, so a capture could land on the spinner.
        let todaysEvent = app.staticTexts[Self.eventOnToday()].firstMatch
        XCTAssertTrue(todaysEvent.waitForExistence(timeout: 60), "today's events never appeared under the month")
        settle(seconds: 4)
        shot("01-month")

        // 02 — the agenda, 03 — one event.
        tab(1).tap()
        let row = app.staticTexts[l: detailEvent].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the imported events never reached the agenda")
        settle(seconds: 3)
        shot("02-agenda")
        row.tap()
        XCTAssertTrue(app.navigationBars[l: "Event"].waitForExistence(timeout: 10), "the event's detail")
        settle(seconds: 2)
        shot("03-event")
        app.navigationBars[l: "Event"].buttons.firstMatch.tap() // back

        // 04 — a new event, typed in. Saved, so it is a real one afterwards.
        let add = app.buttons[l: "New event"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 10), "the agenda's + button")
        add.tap()
        XCTAssertTrue(app.navigationBars[l: "New event"].waitForExistence(timeout: 10), "the editor")
        type("Coffee with Elena", into: app.textFields[l: "Title"].firstMatch)
        type("Bean & Leaf, Market Square", into: app.textFields[l: "Location"].firstMatch)
        app.keyboards.buttons[l: "return"].firstMatch.tapIfPresent(orElse: app.keyboards.buttons[l: "Return"].firstMatch)
        settle(seconds: 2)
        shot("04-new-event")
        app.navigationBars[l: "New event"].buttons[l: "Save"].tap()
        XCTAssertTrue(app.navigationBars[l: "New event"].waitForNonExistence(timeout: 20), "the new event never saved")

        // 05 — the calendars, once reminders are on (the row asks iOS, a user taps Allow).
        tab(2).tap()
        XCTAssertTrue(app.staticTexts[l: "Travel"].firstMatch.waitForExistence(timeout: 20), "the calendars list")
        let reminders = app.buttons[l: "Turn on reminders"].firstMatch
        if reminders.waitForExistence(timeout: 5) {
            reminders.tap()
            let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
            let allow = springboard.buttons[l: "Allow"].firstMatch
            XCTAssertTrue(allow.waitForExistence(timeout: 15), "the notification permission alert")
            allow.tap()
            XCTAssertTrue(reminders.waitForNonExistence(timeout: 15), "the reminders row stayed after Allow")
        }
        settle(seconds: 2)
        shot("05-calendars")

        // 06 — a calendar's sheet: its name, the lock-screen switch and the colours.
        app.buttons[any: ShotStrings.format("Options for %@", "Work")].firstMatch.tap()
        let edit = app.buttons[l: "Edit"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 10), "the calendar row's menu")
        edit.tap()
        XCTAssertTrue(app.navigationBars[l: "Edit calendar"].waitForExistence(timeout: 10), "the calendar sheet")
        settle(seconds: 2)
        shot("06-edit-calendar")
        app.navigationBars[l: "Edit calendar"].buttons[l: "Cancel"].tap()
    }

    /// Mia's walk (`store-screenshots.md` §3g): the calendars and their events, seeded the same way,
    /// and nothing of Calendar's captured — Mia reads them. No new event, no reminders prompt.
    func testSeedRealm() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh"
        )
        app.launchEnvironment = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        seed(fixture)
    }

    /// The `-seedFixture` launch: data only, nothing on its screen is captured.
    private func seed(_ fixture: String) {
        app.launchArguments = ["-seedFixture", fixture]
        app.launch()
        let result = app.staticTexts[l: "seed-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
        expectation(for: NSPredicate(format: "label BEGINSWITH 'seed: ' "), evaluatedWith: result)
        waitForExpectations(timeout: 180)
        XCTAssertTrue(result.label.hasPrefix("seed: OK"), "seeding failed: \(result.label)")
        app.terminate()
    }

    /// A shell tab, by its position in the app's `AppDestination` list (`CalendarIosApp`: 0 Month, 1 Agenda, 2 Calendars).
    ///
    /// By index, in every placement (iPhone tab bar, iPad top pill, sidebar): the shared `shellTab`
    /// (`ios-shell/UITestSupport/ShellTabs.swift` says why a title or `tabBars` lookup does not do).
    /// Resolved when called, so call it once the shell is up.
    private func tab(_ index: Int) -> XCUIElement {
        app.shellTab(index)
    }

    private func type(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no field \(field)")
        field.tap()
        field.typeText(text)
    }

    /// An event the fixture always puts on today (`store/fixtures/calendar/manifest.json`), which
    /// the month opens on. A weekday has the `workday: 0` lunch; a weekend's `workday: 0` is the
    /// next Monday, so Saturday and Sunday each name their own weekly event.
    private static func eventOnToday() -> String {
        switch Calendar(identifier: .gregorian).component(.weekday, from: Date()) {
        case 1: return "Call Mum"   // Sunday
        case 7: return "Yoga"       // Saturday
        default: return "Lunch with Priya"
        }
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// ⚠ Waits out the sync-activity capsule (`ios-storage`'s `SyncActivityCapsule`, identifier
    /// `sync-activity-capsule`) before a capture — the twin of Drive's walk. The shipping launch
    /// comes straight after the seed, so the realm's first sync with the dev lab (the seeded
    /// calendars and events going up) is still running when the month appears, and the shell
    /// draws "Syncing…" over it: the iPhone `01-month` capture of 2026-10-23 had it. It shows 700 ms
    /// after activity starts (`SyncActivityTiming`), so one absent check proves nothing: it must
    /// stay gone for `quiet` seconds. Still up at `timeout`, the walk fails rather than capture it.
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
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}

private extension XCUIElement {
    /// The keyboard's return key is spelled `return` or `Return` depending on the keyboard.
    func tapIfPresent(orElse other: XCUIElement) {
        if exists { tap() } else if other.exists { other.tap() }
    }
}
