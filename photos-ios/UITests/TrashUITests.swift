import XCTest

/// The Trash (photos-ios.md §8.17) on the `-photosFixture` library, whose trash holds
/// eight photographs, 1490 to 1497 — and every fourth photograph (1491, 1495) REFUSES restore and
/// delete, so the refusals are shown by the same fixture as the successes.
final class TrashUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"]
        app.launch()
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }

    private func openTrash() {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15), "the Library tab")
        app.tabBars.buttons["Collections"].tap()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 15))
        let row = app.staticTexts["Trash"]
        // Utilities sits at the foot of the tab.
        for _ in 0..<6 where !row.isHittable { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 5), "Utilities › Trash")
        row.tap()
        XCTAssertTrue(app.navigationBars["Trash"].waitForExistence(timeout: 10), "the Trash")
    }

    /// A trashed photograph's tile, found by its library number.
    private func tile(_ index: Int) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier CONTAINS %@", "IMG_\(10_000 + index)")).firstMatch
    }

    private func waitGone(_ element: XCUIElement) {
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        waitForExpectations(timeout: 10)
    }

    func testATapRestores_andARefusalIsSaid() {
        openTrash()
        XCTAssertTrue(tile(1490).waitForExistence(timeout: 10), "the seeded trash")
        attach("trash")
        tile(1490).tap()
        waitGone(tile(1490))

        tile(1491).tap()                                     // refuses
        XCTAssertTrue(app.alerts["Couldn't Restore the Photo"].waitForExistence(timeout: 10), "the shared refusal")
        attach("restore-refused")
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(tile(1491).exists, "still in the trash")
    }

    func testDeletingForGoodAsksFirst_andNamesWhatIsLost() {
        openTrash()
        XCTAssertTrue(tile(1492).waitForExistence(timeout: 10))
        tile(1492).press(forDuration: 1.2)
        let delete = app.buttons["Delete for Good"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5))
        delete.tap()
        XCTAssertTrue(app.staticTexts["This can't be undone. The photo is removed from all your devices."].waitForExistence(timeout: 5),
                      "the confirmation says what is lost")
        attach("confirm-delete")
        app.buttons["Delete for good"].tap()
        waitGone(tile(1492))
    }

    func testThereIsNoLeavingWhileAWriteRuns() {
        // ⚠⚠ Pins that a pop and a reopen during a write cannot build a second view model whose own
        // single-flight guard lets a second write race the first. `-photosSlowEdits`: writes land 3
        // s late.
        app.terminate()
        app.launchArguments = ["-photosFixture", "-photosSlowEdits"]
        app.launch()
        openTrash()
        XCTAssertTrue(tile(1490).waitForExistence(timeout: 10))
        let back = app.navigationBars["Trash"].buttons["Collections"]
        XCTAssertTrue(back.exists, "precondition: Back is there before the write")
        tile(1490).tap()
        XCTAssertFalse(back.waitForExistence(timeout: 1), "no Back while the restore runs")
        attach("writing-no-back")
        waitGone(tile(1490))
        XCTAssertTrue(back.waitForExistence(timeout: 10), "and Back again once it has landed")
    }

    func testThereIsNoMenuWhileAWriteRuns() {
        // ⚠ `.disabled` stops the tap but not the long-press — the menu's own buttons would offer a
        // Restore and a delete confirmation that the view model then drops in silence.
        app.terminate()
        app.launchArguments = ["-photosFixture", "-photosSlowEdits"]
        app.launch()
        openTrash()
        XCTAssertTrue(tile(1492).waitForExistence(timeout: 10))
        tile(1490).tap()                                    // a restore, 3 s
        tile(1492).press(forDuration: 1.0)
        XCTAssertFalse(app.buttons["Delete for Good"].waitForExistence(timeout: 1), "no menu while the restore runs")
        attach("writing-no-menu")
        waitGone(tile(1490))
        tile(1492).press(forDuration: 1.0)
        XCTAssertTrue(app.buttons["Delete for Good"].waitForExistence(timeout: 5), "and the menu again once it has landed")
    }

    func testAnEmptyThatLeavesSomeBehindSaysHowMany() {
        openTrash()
        XCTAssertTrue(tile(1490).waitForExistence(timeout: 10))
        app.navigationBars["Trash"].buttons["Empty"].tap()
        XCTAssertTrue(app.staticTexts["8 photos will be deleted for good. This can't be undone — they're removed from all your devices."]
            .waitForExistence(timeout: 5))
        app.buttons["Delete them"].tap()
        XCTAssertTrue(app.alerts["The Trash Wasn't Emptied"].waitForExistence(timeout: 15), "two refused")
        attach("empty-partial")
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(tile(1491).exists && tile(1495).exists, "the refused ones stay")
        XCTAssertFalse(tile(1490).exists, "the rest went")
    }
}
