import XCTest

/// An album's edits (photos-ios.md §8.16) on the `-photosFixture` library, whose two
/// albums differ on purpose: "Family" is real in the mock — six photographs, #0 to #5 — and every edit
/// works on it; "Holiday" is a fixed row the mock does not know, so it REFUSES every edit.
final class AlbumEditsUITests: XCTestCase {

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

    private func open(_ album: String) {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15), "the Library tab")
        app.tabBars.buttons["Collections"].tap()
        let card = app.staticTexts[album]
        XCTAssertTrue(card.waitForExistence(timeout: 15), "the \(album) card")
        card.tap()
        XCTAssertTrue(app.navigationBars[album].waitForExistence(timeout: 10), "the \(album) grid")
    }

    private func menu(_ item: String) {
        app.buttons["Album options"].tap()
        let button = app.buttons[item]
        XCTAssertTrue(button.waitForExistence(timeout: 5), item)
        button.tap()
    }

    private func rename(to name: String) {
        menu("Rename Album")
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 12) + name)
        app.alerts.buttons["Save"].tap()
    }

    func testARenameRetitlesTheAlbum_andItsCard() {
        open("Family")
        rename(to: "Kin")
        XCTAssertTrue(app.navigationBars["Kin"].waitForExistence(timeout: 10), "the grid's title")
        attach("renamed")
        app.navigationBars.buttons.firstMatch.tap() // Back
        XCTAssertTrue(app.staticTexts["Kin"].waitForExistence(timeout: 10), "the card on Collections")
        XCTAssertFalse(app.staticTexts["Family"].exists)
    }

    func testADeleteLeavesTheAlbum_andItIsGone() {
        open("Family")
        menu("Delete Album")
        XCTAssertTrue(app.staticTexts["The photos in it stay in your library."].waitForExistence(timeout: 5),
                      "the confirmation says what is kept")
        attach("confirm-delete")
        // ⚠ The confirmation's own button — the menu's has gone with the menu.
        app.buttons["Delete Album"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 10), "back on Collections")
        XCTAssertTrue(app.staticTexts["Holiday"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Family"].exists, "the album is gone")
    }

    func testRemovingAPhotographTakesItOffTheGrid() {
        open("Family")
        let tile = app.buttons["IMG_10000.jpg"]
        XCTAssertTrue(tile.waitForExistence(timeout: 10), "the album's newest photograph")
        tile.press(forDuration: 1.2)
        let remove = app.buttons["Remove from Album"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        attach("remove-menu")
        remove.tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.buttons["IMG_10000.jpg"])
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.buttons["IMG_10001.jpg"].exists, "only that photograph left")
    }

    func testThereIsNoLeavingWhileAnEditRuns() {
        // ⚠⚠ Leaving during an edit and reopening the album must not build a new view model whose
        // lock is free — a delete there could race a rename (an upsert) still able to land.
        // `-photosSlowEdits`: album edits land 6 s late.
        app.terminate()
        app.launchArguments = ["-photosFixture", "-photosSlowEdits"]
        app.launch()
        open("Family")
        let back = app.navigationBars["Family"].buttons["Collections"]
        XCTAssertTrue(back.exists, "precondition: Back is there before the edit")
        rename(to: "Kin")
        XCTAssertFalse(app.navigationBars.buttons["Collections"].waitForExistence(timeout: 1), "no Back while the rename runs")
        attach("editing-no-back")
        XCTAssertTrue(app.navigationBars["Kin"].waitForExistence(timeout: 15), "the rename lands")
        let backAgain = app.navigationBars["Kin"].buttons["Collections"]
        XCTAssertTrue(backAgain.waitForExistence(timeout: 5), "and Back is there again")
        backAgain.tap()
        XCTAssertTrue(app.staticTexts["Kin"].waitForExistence(timeout: 10), "the card follows")
    }

    func testARefusedRenameSaysSo_andTheNameStays() {
        open("Holiday")
        rename(to: "Summer")
        XCTAssertTrue(app.alerts["Couldn't Rename the Album"].waitForExistence(timeout: 10), "the shared refusal")
        attach("rename-refused")
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(app.navigationBars["Holiday"].exists, "the album keeps its name")
    }

    func testFavouritesOffersNoEdits() {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15))
        app.tabBars.buttons["Collections"].tap()
        let favourites = app.staticTexts["Favourites"]
        XCTAssertTrue(favourites.waitForExistence(timeout: 15))
        favourites.tap()
        XCTAssertTrue(app.navigationBars["Favourites"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["Album options"].exists, "no name or membership of its own to edit")
    }
}
