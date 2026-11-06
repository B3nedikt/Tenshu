import XCTest

/// Duplicates (photos-ios.md §8.18) on the `-photosFixture` library, which holds three
/// sets of "identical" photographs: #100–#102 (three copies), #200–#201, and #302–#303 — where #303, a
/// fourth photograph, REFUSES the trash and the permanent delete, so the refusal is shown by the same
/// fixture as the successes.
final class DuplicatesUITests: XCTestCase {

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

    private func openDuplicates() {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15), "the Library tab")
        app.tabBars.buttons["Collections"].tap()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 15))
        let tile = app.staticTexts["Duplicates"]
        XCTAssertTrue(tile.waitForExistence(timeout: 5), "the Duplicates tile")
        tile.tap()
        XCTAssertTrue(app.navigationBars["Duplicates"].waitForExistence(timeout: 10), "the Duplicates screen")
        XCTAssertTrue(summary.waitForExistence(timeout: 15), "the sets, once the library has been compared")
    }

    private var summary: XCUIElement { app.staticTexts["duplicatesSummary"] }

    /// A copy's tile, found by its library number.
    private func tile(_ index: Int) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier CONTAINS %@", "IMG_\(10_000 + index)")).firstMatch
    }

    private func text(containing fragment: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
    }

    private func waitGone(_ element: XCUIElement, timeout: TimeInterval = 10) {
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        waitForExpectations(timeout: timeout)
    }

    /// Turns "Delete permanently" on. ⚠ On iOS 17+ a SwiftUI `Toggle` is a switch INSIDE a switch, and a
    /// tap on the outer element lands on its label — so the inner one is tapped when there is one.
    private func turnOnDeletePermanently() {
        let toggle = app.switches["deletePermanently"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), "the switch")
        XCTAssertEqual(toggle.value as? String, "0", "⚠ off by default: destroying is something the user turns on")
        let inner = toggle.switches.firstMatch
        if inner.exists { inner.tap() } else { toggle.tap() }
        expectation(for: NSPredicate(format: "value == '1'"), evaluatedWith: toggle)
        waitForExpectations(timeout: 5)
    }

    func testTheTileOpensTheScreen_andItListsTheSets() {
        openDuplicates()
        attach("duplicates")
        XCTAssertTrue(summary.label.hasPrefix("3 sets of identical photos"), summary.label)
        XCTAssertTrue(text(containing: "3 identical copies — tap the one to keep").exists, "the three-copy set")
        XCTAssertTrue(tile(100).exists && tile(101).exists && tile(102).exists)
        XCTAssertTrue(tile(200).exists && tile(201).exists)
        XCTAssertTrue(text(containing: "Extra copies go to the trash, where you can still get them back.").exists,
                      "the safe mode is the default, and says what it costs")
    }

    func testKeepingOneMovesTheOthersToTheTrash_andTheListFollows() {
        openDuplicates()
        tile(200).tap()
        // ⚠ The confirmation names the SURVIVOR and says what happens to the rest — and that it is the trash.
        XCTAssertTrue(app.staticTexts["Move the other copies to the trash?"].waitForExistence(timeout: 5))
        XCTAssertTrue(text(containing: "Keeping IMG_10200.jpg.").exists)
        XCTAssertTrue(text(containing: "1 other copy will move to the trash. You can restore it from there.").exists)
        attach("confirm-trash")
        app.buttons["Move to Trash"].tap()
        waitGone(tile(200))
        XCTAssertFalse(tile(201).exists, "the set is resolved: one copy left is not a duplicate")
        XCTAssertTrue(summary.label.hasPrefix("2 sets of identical photos"), summary.label)
        XCTAssertTrue(tile(100).exists, "the other sets stay")
    }

    func testBackingOutOfTheConfirmationRemovesNothing() {
        openDuplicates()
        tile(200).tap()
        XCTAssertTrue(app.buttons["Move to Trash"].waitForExistence(timeout: 5))
        app.buttons["Cancel"].tap()
        XCTAssertTrue(tile(200).waitForExistence(timeout: 5) && tile(201).exists, "a tap alone resolves nothing")
        XCTAssertTrue(summary.label.hasPrefix("3 sets of identical photos"), summary.label)
    }

    func testARefusalIsSaid_andTheSetStays() {
        openDuplicates()
        for _ in 0..<4 where !tile(302).isHittable { app.swipeUp() }
        tile(302).tap()                                      // keeping #302 means trashing #303, which refuses
        XCTAssertTrue(app.buttons["Move to Trash"].waitForExistence(timeout: 5))
        app.buttons["Move to Trash"].tap()
        XCTAssertTrue(app.alerts["Couldn't Move Every Copy to the Trash"].waitForExistence(timeout: 10), "the shared refusal")
        attach("trash-refused")
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(tile(302).exists && tile(303).exists, "both copies are still listed")
        // ⚠⚠ A NO is not "still there" — the real `trash` can refuse having MOVED the
        // file. The refused copy is locked, so it is never offered as the one to keep; and with it
        // locked this set has nothing left to choose, which the row says instead of "tap".
        XCTAssertTrue(text(containing: "2 identical copies — a copy here couldn't be removed cleanly").waitForExistence(timeout: 5),
                      "the set says why it offers nothing")
        tile(303).tap()
        XCTAssertFalse(app.buttons["Move to Trash"].waitForExistence(timeout: 1), "the refused copy is not offered as the survivor")
        tile(302).tap()
        XCTAssertFalse(app.buttons["Move to Trash"].waitForExistence(timeout: 1), "nor is there anything left to remove")
    }

    func testDeletingForGoodAsksFirst_andNamesWhatIsLost() {
        openDuplicates()
        turnOnDeletePermanently()
        XCTAssertTrue(text(containing: "Extra copies are removed from all your devices right away.").exists,
                      "the switch says what it costs")
        tile(100).tap()
        XCTAssertTrue(app.staticTexts["Delete the other copies for good?"].waitForExistence(timeout: 5),
                      "worded as a delete, not a move to the trash")
        XCTAssertTrue(text(containing: "2 other copies will be removed from all your devices. This can't be undone.").exists,
                      "the confirmation says what is lost")
        XCTAssertFalse(app.buttons["Move to Trash"].exists)
        attach("confirm-delete")
        app.buttons["Delete for Good"].tap()
        waitGone(tile(100))
        XCTAssertFalse(tile(101).exists || tile(102).exists)
        XCTAssertTrue(summary.label.hasPrefix("2 sets of identical photos"), summary.label)
    }

    func testThereIsNoLeavingAndNothingIsOfferedWhileAWriteRuns() {
        // ⚠⚠ §8.17 notes 6 and 14, for this screen: a pop and a reopen during a write builds a second
        // view model whose own guard lets a second keep-only run beside the first; and a tile that
        // still answered would open a confirmation the view model then drops.
        // `-photosSlowEdits`: writes land 6 s late.
        app.terminate()
        app.launchArguments = ["-photosFixture", "-photosSlowEdits"]
        app.launch()
        openDuplicates()
        let back = app.navigationBars["Duplicates"].buttons["Collections"]
        XCTAssertTrue(back.exists, "precondition: Back is there before the write")
        tile(200).tap()
        XCTAssertTrue(app.buttons["Move to Trash"].waitForExistence(timeout: 5))
        app.buttons["Move to Trash"].tap()
        XCTAssertFalse(back.waitForExistence(timeout: 1), "no Back while the keep-only runs")
        XCTAssertFalse(app.switches["deletePermanently"].isEnabled, "nor the switch")

        // ⚠ These tiles have no context menu — a tap and a long-press must both open nothing.
        tile(100).tap()
        XCTAssertFalse(app.buttons["Move to Trash"].waitForExistence(timeout: 1), "no confirmation while it runs")
        tile(100).press(forDuration: 1.0)
        XCTAssertFalse(app.buttons["Move to Trash"].exists || app.buttons["Delete for Good"].exists, "and no menu")
        attach("writing-nothing-offered")

        waitGone(tile(200), timeout: 20)
        XCTAssertTrue(back.waitForExistence(timeout: 10), "and Back again once it has landed")
        tile(100).tap()
        XCTAssertTrue(app.buttons["Move to Trash"].waitForExistence(timeout: 5), "and the tiles answer again")
    }
}
