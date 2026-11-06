import XCTest

/// The Collections tab, on the `-photosFixture` library: two years of photographs
/// (2025, 2024), an empty fixed "Holiday" and a six-photograph "Family" (§8.16), no favourites.
final class CollectionsUITests: XCTestCase {

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

    private func openCollections() {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15), "the Library tab")
        app.tabBars.buttons["Collections"].tap()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 15), "the Albums row is always there")
    }

    func testTheTabShowsAlbumsAndYears() {
        openCollections()
        attach("collections")
        XCTAssertTrue(app.staticTexts["Holiday"].exists, "a fixture album")
        XCTAssertTrue(app.staticTexts["Years"].exists, "the Years row — the library spans 2025 and 2024")
        XCTAssertTrue(app.staticTexts["Favourites"].exists, "the Favourites tile")
        // ⚠ The tile has its screen (§8.18) — `DuplicatesUITests` opens it.
        XCTAssertTrue(app.staticTexts["Duplicates"].exists, "the Duplicates tile")
        XCTAssertTrue(app.staticTexts["Find copies"].exists, "a promise of work, not a count")
    }

    func testAYearOpensItsPhotographs_andAPhotographOpensTheViewerOverThem() {
        openCollections()
        app.staticTexts["2024"].firstMatch.tap()
        let tile = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'IMG_'")).firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 10), "the year's grid")
        tile.tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 10), "the viewer opens")
        attach("viewer-from-year")
        // ⚠ Full screen: no tab bar over the photograph.
        XCTAssertFalse(app.tabBars.buttons["Collections"].isHittable, "the viewer hides the tab bar")
        app.navigationBars.buttons.firstMatch.tap() // Back
        XCTAssertTrue(tile.waitForExistence(timeout: 10), "back on the year's grid")
    }

    func testAnEmptyAlbumAndFavouritesSaySo() {
        openCollections()
        app.staticTexts["Holiday"].tap()
        XCTAssertTrue(app.staticTexts["This album is empty.\nAdd photos to it from the feed."].waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        app.staticTexts["Favourites"].tap()
        XCTAssertTrue(app.staticTexts["No favourites yet.\nTap the heart on a photo to keep it here."].waitForExistence(timeout: 10))
    }

    func testANewAlbumAppearsInTheRow() {
        openCollections()
        app.staticTexts["New album"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText("Trip")
        app.alerts.buttons["Create"].tap()
        XCTAssertTrue(app.staticTexts["Trip"].waitForExistence(timeout: 10), "the new album is in the row")
        attach("new-album")
    }
}
