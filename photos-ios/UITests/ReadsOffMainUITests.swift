import XCTest

/// No screen of the app makes a repository call on the main thread (photos-ios.md §8.22.2).
///
/// On a phone the repository is the in-process route-1 runtime: a call is a store query on the
/// thread that makes it, and a walk of the library is ~2 s. Made from a view model — whose scope is
/// the main thread on Kotlin/Native — or from a Swift `Task` on the main actor, that is 2 s of a
/// frozen app: every return to Collections did it, and so did the scrubber's year index, a search, an
/// album, the trash, duplicates and the viewer's About sheet.
///
/// ⚠⚠ The assertion is a COUNT — the fixture notes every call that arrives on the main thread — not a
/// time: it does not move with the load on the machine, and the fixture answers from memory, so a
/// time could not see the defect at all. ⚠ `-photosHeldReads` is here so the same run FEELS it: each
/// call burns 200 ms where it runs, and with one on the main thread the taps below visibly stall.
///
/// ⚠ Each test names the calls its screen must have made off the main thread only by walking it:
/// a screen that was never opened proves nothing, so each asserts on what it reached first.
final class ReadsOffMainUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture", "-photosArrivals", "-photosHeldReads", "200", "-photosReadsReadout"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 20), "the Library tab")
    }

    /// The calls made on the main thread so far — after the screens have had time to finish theirs.
    private func assertNothingReadOnMain(_ screen: String, file: StaticString = #filePath, line: UInt = #line) {
        let readout = app.descendants(matching: .any)["reads-on-main"]
        XCTAssertTrue(readout.waitForExistence(timeout: 10), "the readout", file: file, line: line)
        // Longer than the readout's poll and than any held call still running.
        Thread.sleep(forTimeInterval: 2.5)
        let value = readout.value as? String ?? "unreadable"
        print("[reads-on-main] \(screen): \(value)")
        XCTAssertEqual(value, "none", "\(screen): repository calls made on the main thread", file: file, line: line)
    }

    private func tile(_ index: Int) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier CONTAINS %@", "IMG_\(10_000 + index)")).firstMatch
    }

    private func back() { app.navigationBars.buttons.firstMatch.tap() }

    private func openCollections() {
        app.tabBars.buttons["Collections"].tap()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 20), "Collections")
    }

    /// The feed pages as it scrolls, the scrubber's year index is read once, and the viewer reads its
    /// photograph's flag, its places and — with About open — its details, settings and albums.
    func testTheLibraryItsPagesAndTheViewer() {
        XCTAssertTrue(tile(3).waitForExistence(timeout: 20), "the grid")
        // Three screens down: pages past the first.
        for _ in 0..<3 { app.swipeUp() }
        for _ in 0..<3 { app.swipeDown() }
        XCTAssertTrue(tile(3).waitForExistence(timeout: 10))
        tile(3).tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 15), "the viewer")
        let star = app.buttons["Add to Favourites"]
        XCTAssertTrue(star.waitForExistence(timeout: 10), "the star, once the flag is read")
        star.tap()
        XCTAssertTrue(app.buttons["Remove from Favourites"].waitForExistence(timeout: 10), "the write landed")
        app.buttons["More"].tap()
        app.buttons["About"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["aboutSheet"].waitForExistence(timeout: 10), "About")
        assertNothingReadOnMain("library, pages, viewer, About")
    }

    /// Collections, an album, Favourites, the Trash and a restore, Duplicates.
    func testCollectionsAndEveryScreenBehindIt() {
        openCollections()
        app.staticTexts["Family"].tap()
        XCTAssertTrue(tile(0).waitForExistence(timeout: 15), "the album's photographs")
        back()
        XCTAssertTrue(app.staticTexts["Albums"].waitForExistence(timeout: 10))
        app.staticTexts["Favourites"].tap()
        XCTAssertTrue(app.staticTexts["No favourites yet.\nTap the heart on a photo to keep it here."].waitForExistence(timeout: 15))
        back()

        let trash = app.staticTexts["Trash"]
        for _ in 0..<6 where !trash.isHittable { app.swipeUp() }
        trash.tap()
        XCTAssertTrue(app.navigationBars["Trash"].waitForExistence(timeout: 10), "the Trash")
        XCTAssertTrue(tile(1490).waitForExistence(timeout: 15), "the seeded trash")
        tile(1490).tap() // restores
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: tile(1490))
        waitForExpectations(timeout: 15)
        back()

        let duplicates = app.staticTexts["Duplicates"]
        // The tiles are at the head of the tab, and the Trash was at its foot.
        for _ in 0..<6 where !duplicates.isHittable { app.swipeDown() }
        duplicates.tap()
        XCTAssertTrue(app.navigationBars["Duplicates"].waitForExistence(timeout: 10), "Duplicates")
        XCTAssertTrue(app.staticTexts["duplicatesSummary"].waitForExistence(timeout: 20), "the sets, once the walk answered")
        back()
        assertNothingReadOnMain("collections, album, favourites, trash, duplicates")
    }

    /// A search — two walks per query, by name and by tag — typed a letter at a time.
    func testASearch() {
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the search field")
        field.tap()
        field.typeText("IMG_10042")
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@", "IMG_10042.jpg")).firstMatch
            .waitForExistence(timeout: 20), "#42 found by name")
        assertNothingReadOnMain("search")
    }

    /// The selection's bulk actions and the album sheet — the one read Swift asks for itself.
    func testASelectionAndItsAlbumSheet() {
        XCTAssertTrue(tile(2).waitForExistence(timeout: 20), "the grid")
        tile(2).press(forDuration: 1.0)
        let add = app.buttons["Add to Album"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "the selection's bar")
        add.tap()
        XCTAssertTrue(app.staticTexts["Family"].waitForExistence(timeout: 15), "the albums, once read")
        app.staticTexts["Family"].tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 15), "the action's report")
        assertNothingReadOnMain("selection, album sheet, add to album")
    }
}
