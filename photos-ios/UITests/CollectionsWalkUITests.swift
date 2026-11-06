import XCTest

/// A return to the Collections tab walks the library only when something it draws may have changed
/// (photos-ios.md §8.22.3). A walk is ~2 s of store reads on a phone, and every return used to make one.
///
/// ⚠⚠ Every assertion is a COUNT — `library-walks`, the fixture's own tally of `allPhotosRead`
/// calls — never a time: nothing here moves with the load on the machine.
/// ⚠ With `-photosArrivals`: the readout's `arrive-one` / `arrive-burst` buttons are a sync, as the
/// app sees one — photographs landing at the head of the library.
final class CollectionsWalkUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture", "-photosArrivals", "-photosReadsReadout"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 20), "the Library tab")
    }

    private var walks: XCUIElement { app.descendants(matching: .any)["library-walks"] }

    /// Waits for the tally to read `count`, then long enough to see that it STAYS there.
    private func assertWalks(_ count: Int, _ why: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(walks.waitForExistence(timeout: 10), "the readout", file: file, line: line)
        let reached = expectation(for: NSPredicate(format: "value == %@", "\(count)"), evaluatedWith: walks)
        if XCTWaiter().wait(for: [reached], timeout: 15) != .completed {
            XCTFail("\(why): \(walks.value as? String ?? "?") walks, expected \(count)", file: file, line: line)
            return
        }
        // Longer than the change debounce's ceiling and the readout's poll together.
        Thread.sleep(forTimeInterval: 4.5)
        XCTAssertEqual(walks.value as? String, "\(count)", "\(why) — and no more after it", file: file, line: line)
    }

    private func show(_ tab: String) { app.tabBars.buttons[tab].tap() }

    func testReturnsWalkNothing_anArrivalWalksOnce_andABurstWalksOnce() {
        show("Collections")
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 20), "Collections")
        assertWalks(1, "the tab's first walk")

        for _ in 0..<4 {
            show("Library")
            show("Collections")
        }
        assertWalks(1, "four returns with nothing changed")

        // One photograph arrives while the Library is up: nothing is walked until the tab is back.
        show("Library")
        app.buttons["arrive-one"].tap()
        // ⚠ The arrival itself, seen: the first to arrive is IMG_9999, and the Library follows the top.
        XCTAssertTrue(app.descendants(matching: .any)["IMG_9999.jpg"].waitForExistence(timeout: 15), "the photograph arrived")
        assertWalks(1, "an arrival nobody is looking at Collections for")
        show("Collections")
        assertWalks(2, "the return after one arrival")
        show("Library")
        show("Collections")
        assertWalks(2, "and clean again")

        // Twelve arrive, 80 ms apart, with the tab on screen: one walk for all of them.
        app.buttons["arrive-burst"].tap()
        assertWalks(3, "a burst of twelve arrivals while the tab is shown")
    }

    /// An album made on the tab is one walk, though the edit is told twice — by the view model and by
    /// the store's own signal.
    func testAnEditMadeOnTheTabIsOneWalk() {
        show("Collections")
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 20), "Collections")
        assertWalks(1, "the tab's first walk")

        app.staticTexts["New album"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("Trip")
        app.alerts.buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Trip"].waitForExistence(timeout: 10), "the new album is in the row")
        assertWalks(2, "one album made")
    }
}
