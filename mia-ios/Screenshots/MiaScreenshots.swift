import XCTest

/// The Mia store screenshots (`docs/how-to/store-screenshots.md` §3g). Run only through
/// `scripts/store-screenshots.sh mia`, which owns the simulator, the status bar, Control's genesis on
/// the same simulator, and the realm's data — seeded beforehand by Drive's, Calendar's and (iPhone
/// only) Fit's own `-seedFixture` launches, so Mia has something true to answer from.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`) over the
/// route-1 runtime and the realm Control created. Mia itself has no hook at all: one launch with no
/// arguments, the one-time intro passed by taps, and every question typed into the field and sent.
/// The answers are the planner's and the realm's — the walk only checks they carry real rows
/// (a citation list, never "— nothing matched.") before it captures them.
///
/// ⚠ One launch for everything: Mia keeps its conversation in memory (MQ-D8), so History has
/// something to show only in the launch that asked.
///
/// ⚠ Settings is walked by no one and never captured: every row there is a "Coming soon" placeholder,
/// and a store listing must not advertise one.
///
/// Attachment names are the output file names (`NN-<screen>`), listed in `store/mia/screens.json`.
/// A new capture takes the next free number, so existing ones never shift.
final class MiaScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// What the walk asks, with the start of the caption the answer must carry. The questions are
    /// phrasings `QueryPlanner` routes to a read over the seeded fixtures (§3g says which arm each takes).
    private struct Question {
        let text: String
        let caption: String
        /// A row that must be among the answer's citations, when the fixture pins one.
        let row: String?
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testStoreScreenshots() throws {
        let env = ProcessInfo.processInfo.environment
        let devLab = env.filter { $0.key.hasPrefix("TENSHU_") }
        XCTAssertFalse(devLab.isEmpty, "Mia's set needs the dev lab's TENSHU_* origins (a funded realm)")
        app.launchEnvironment = devLab
        // The workouts exist only where Fit's seed could run — the iPhone (§3e); the script says so.
        let withWorkouts = env["MIA_WORKOUTS"] == "1"

        app.launchArguments = []
        app.launch()

        // Mia's one-time intro (`OnboardingView`), the first launch's: passed by taps, not captured.
        let intro = app.staticTexts[l: "Meet Mia"].firstMatch
        let deadline = Date().addingTimeInterval(60)
        while !intro.exists && !app.isShellUp && Date() < deadline { settle(seconds: 1) }
        if intro.exists {
            app.buttons[l: "Next"].firstMatch.tap()
            app.buttons[l: "Next"].firstMatch.tap()
            let start = app.buttons[l: "Get started"].firstMatch
            XCTAssertTrue(start.waitForExistence(timeout: 10), "the intro's last step")
            start.tap()
        }
        XCTAssertTrue(app.waitForShell(timeout: 60), "Mia never opened on Chat (not provisioned?)")
        waitForSyncing()

        // 01 — the empty chat.
        XCTAssertTrue(app.staticTexts[l: "Hi, I'm Mia"].firstMatch.waitForExistence(timeout: 20), "the greeting")
        settle()
        shot("01-welcome")

        // 02 — a file search by name: the Taxes folder and its return.
        ask(Question(text: "Show me my tax files",
                     caption: "Searched your files for names containing",
                     row: "Tax return 2025.pdf"))
        shot("02-files")

        // 03 — photos by time: the holiday's, all dated July.
        ask(Question(text: "Photos from July",
                     caption: "Searched your files for photos from",
                     row: "IMG_4102.jpg"))
        shot("03-photos")

        // 04 — the calendar: next week is always a full one in the fixture.
        ask(Question(text: "What's on my calendar next week?",
                     caption: "Looked at your calendar for",
                     row: nil))
        shot("04-calendar")

        // 05 — the workouts, where Fit's seed ran.
        if withWorkouts {
            ask(Question(text: "Show my workouts from last week",
                         caption: "Looked through your workouts from",
                         row: nil))
            shot("05-workouts")
        }

        // 06 — History: this session's conversation, titled by its first question.
        tab(1).tap()
        let row = app.staticTexts[l: "Show me my tax files"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 20), "History never listed the conversation")
        settle()
        shot("06-history")
    }

    // MARK: - Helpers

    /// Types [question] into the field, sends it with the keyboard's return (which also closes the
    /// keyboard, as for a user), and waits for an answer that cites rows.
    private func ask(_ question: Question) {
        let field = app.textFields[l: "Ask Mia…"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the question field")
        let before = answersSoFar(question.caption)
        field.tap()
        field.typeText(question.text)
        // ⚠ What reaches Mia is what the field holds: check it before sending, so a dropped key
        // fails here, naming itself, rather than as an answer about something else.
        // (The keyboard's smart punctuation may curl the apostrophe; the planner reads both.)
        XCTAssertEqual((field.value as? String)?.replacingOccurrences(of: "’", with: "'"), question.text,
                       "the field does not hold what was typed")
        field.typeText("\n")

        // The citation cell's caption is the explanation alone; an answer with no rows is the same
        // sentence with " — nothing matched." after it, which this refuses to capture.
        let caption = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@ AND NOT (label CONTAINS %@)",
                        question.caption, "nothing matched")
        ).element(boundBy: before)
        XCTAssertTrue(
            caption.waitForExistence(timeout: 60),
            "“\(question.text)” got no answer with rows: \(app.staticTexts.allElementsBoundByIndex.map(\.label).suffix(12))"
        )
        let thinking = app.staticTexts[l: "Mia is thinking…"].firstMatch
        XCTAssertTrue(thinking.waitForNonExistence(timeout: 30), "Mia is still thinking")
        if let row = question.row {
            XCTAssertTrue(
                app.staticTexts[l: row].firstMatch.waitForExistence(timeout: 10),
                "“\(question.text)” did not cite \(row): \(app.staticTexts.allElementsBoundByIndex.map(\.label).suffix(24))"
            )
        }
        if app.keyboards.firstMatch.exists {
            // ⚠ Still up (a hardware-keyboard setting can keep it): close it the way a user does.
            app.keyboards.buttons.matching(ShotStrings.labelIs("return", "Return", "send", "Send")).firstMatch.tap()
        }
        settle(seconds: 2) // the thread's scroll to the newest message
        revealQuestion(question.text)
    }

    /// A long answer (the week's calendar on the iPhone) scrolls its own question and caption off
    /// the top, and a capture of rows nobody asked for says nothing. So, as a user would, drag the
    /// thread back until the question sits just under the title; the answer then runs off the
    /// bottom instead. A slow drag with a hold, so the thread does not coast on past it.
    private func revealQuestion(_ text: String) {
        let bubble = app.staticTexts.matching(
            NSPredicate(format: "label == %@ OR label == %@", text, text.replacingOccurrences(of: "'", with: "’"))
        ).firstMatch
        let bar = app.navigationBars.firstMatch
        let top = (bar.exists ? bar.frame.maxY : 0) + 16
        let window = app.windows.firstMatch.frame
        for _ in 0..<4 {
            let y = bubble.exists ? bubble.frame.minY : -window.height / 2
            guard y < top else { return }
            // ⚠ At least 80 pt, with a margin: a drag of a few points is under the scroll view's
            // touch slop and moves nothing (a 10 pt one, four times over, 2026-10-30).
            let distance = min(max(top - y + 24, 80), window.height * 0.5)
            let start = app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
            let end = start.withOffset(CGVector(dx: 0, dy: distance))
            start.press(forDuration: 0.1, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.5)
            settle(seconds: 1)
        }
        XCTAssertTrue(bubble.exists && bubble.frame.minY >= top - 1, "could not bring “\(text)” back into view")
    }

    /// How many answers with this caption the thread already holds — the new one is the next.
    private func answersSoFar(_ caption: String) -> Int {
        app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@ AND NOT (label CONTAINS %@)", caption, "nothing matched")
        ).count
    }

    /// A shell tab, by its position in the app's `AppDestination` list (`MiaIosApp`: 0 Chat,
    /// 1 History, 2 Settings). By index, in every placement (iPhone tab bar, iPad top pill): the
    /// shared `shellTab` (`ios-shell/UITestSupport/ShellTabs.swift`). Resolved when called.
    private func tab(_ index: Int) -> XCUIElement {
        app.shellTab(index)
    }

    /// The funded realm's entitlement reaches this runtime over the shared replica; until it does,
    /// the storage banner says "Not syncing", which is what an unfunded device shows.
    private func waitForSyncing() {
        let banner = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Not syncing'")).firstMatch
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: banner)
        waitForExpectations(timeout: 180)
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// ⚠ Waits out the sync-activity capsule (`ios-storage`'s `SyncActivityCapsule`, identifier
    /// `sync-activity-capsule`) before a capture — the twin of Calendar's walk. Mia hides it on Chat
    /// and draws it over History. It shows 700 ms after activity starts, so it must stay gone for
    /// `quiet` seconds; still up at `timeout`, the walk fails rather than capture it.
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
