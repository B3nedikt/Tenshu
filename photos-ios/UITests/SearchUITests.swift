import XCTest

/// The Search tab (photos-ios.md §8.19) on the `-photosFixture` library. By NAME every
/// photograph is `IMG_1xxxx.jpg` ("IMG_10042" finds #42 alone, "1004" finds #40–#49); by TAG #10–#12
/// are "dog" (synced labels, AI-R); "giraffe" finds nothing; 1490–1497 are in the trash.
/// `-photosSearchFails` makes the first name search fail; `-photosSlowSearch` answers "1004" 6 s late.
final class SearchUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"]
        app.launch()
    }

    private func relaunch(_ extra: String) {
        app.terminate()
        app.launchArguments = ["-photosFixture", extra]
        app.launch()
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }

    private func tile(_ index: Int) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == %@", "IMG_\(10_000 + index).jpg")).firstMatch
    }

    private func text(containing fragment: String) -> XCUIElement {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", fragment)).firstMatch
    }

    private var field: XCUIElement { app.searchFields.firstMatch }

    /// The tab — on iOS 18 the search-role tab is in the tab bar; on 26 it is the detached button,
    /// which is still a tab-bar button to XCUITest.
    private func openSearch() {
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 15), "the Library tab")
        let search = app.tabBars.buttons["Search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5), "the Search tab")
        search.tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the search field")
    }

    private func search(_ query: String) {
        field.tap()
        field.typeText(query)
    }

    func testTheTabOpensSearch_andSaysWhatItCanDo() {
        openSearch()
        XCTAssertTrue(app.staticTexts["Search by file name."].waitForExistence(timeout: 5), "the shared idle line")
        attach("search-idle")
    }

    func testANameFindsAPhotograph_itOpens_andBackKeepsTheQuery() {
        openSearch()
        search("IMG_10042")
        XCTAssertTrue(tile(42).waitForExistence(timeout: 10), "#42 found by name")
        XCTAssertFalse(tile(41).exists, "and nothing else")
        // ⚠⚠ Search by description is OFF on iOS — said beside the result, never instead of it.
        XCTAssertTrue(text(containing: "Search by description isn't available on this iPhone yet.").exists)
        attach("search-name")
        tile(42).tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 10), "the viewer opens")
        app.navigationBars.buttons.firstMatch.tap() // Back
        XCTAssertTrue(tile(42).waitForExistence(timeout: 10), "back on the results")
        XCTAssertEqual(field.value as? String, "IMG_10042", "with the query intact")
    }

    func testATagFindsPhotographs_andSaysSo() {
        openSearch()
        search("dog")
        XCTAssertTrue(tile(10).waitForExistence(timeout: 10), "#10 found by its synced tag")
        XCTAssertTrue(tile(11).exists && tile(12).exists)
        XCTAssertTrue(app.staticTexts["tagged \"dog\""].exists, "the source badge")
        attach("search-tag")
    }

    func testNothingFoundSaysSo_andOffIsNotNothingFound() {
        openSearch()
        search("giraffe")
        XCTAssertTrue(app.staticTexts["No photos match \"giraffe\"."].waitForExistence(timeout: 10))
        XCTAssertTrue(text(containing: "Search by description isn't available on this iPhone yet.").exists,
                      "⚠⚠ off is said as off, beside the empty answer — no switch, no download")
        XCTAssertFalse(text(containing: "Settings").exists, "no route to a Models screen iOS does not have")
        // A trashed photograph is not found.
        field.buttons["Clear text"].tap()
        search("IMG_11490")
        XCTAssertTrue(app.staticTexts["No photos match \"IMG_11490\"."].waitForExistence(timeout: 10),
                      "photo 1490 is in the trash")
        attach("search-empty")
    }

    func testASearchThatCouldNotLookSaysSo_andTryAgainLooks() {
        relaunch("-photosSearchFails")
        openSearch()
        search("IMG_10042")
        let status = app.staticTexts["searchStatus"]
        XCTAssertTrue(status.waitForExistence(timeout: 10))
        XCTAssertTrue(status.label.hasPrefix("Couldn't search everything."), status.label)
        XCTAssertFalse(app.staticTexts["No photos match \"IMG_10042\"."].exists, "⚠⚠ never 'no results'")
        attach("search-could-not-look")
        app.buttons["Try Again"].tap()
        XCTAssertTrue(tile(42).waitForExistence(timeout: 10), "asked again, found")
    }

    func testANewerQuerysResultsAreNeverReplacedByAnOlderOnes() {
        // `-photosSlowSearch`: "1004" (#40–#49) answers 6 s late. "10042" typed after it is answered at
        // once — and must still be what is on screen when the older answer lands.
        relaunch("-photosSlowSearch")
        openSearch()
        search("1004")
        sleep(1)                                    // past the debounce: the slow search is running
        field.typeText("2")
        XCTAssertTrue(tile(42).waitForExistence(timeout: 5), "the newer question answered without waiting")
        sleep(8)                                    // the older answer has landed by now
        XCTAssertTrue(tile(42).exists)
        XCTAssertFalse(tile(41).exists, "the older answer (#40–#49) never replaced the newer one")
        attach("search-superseded")
    }

    func testAPhotographTrashedFromTheResultsLeavesThem() {
        openSearch()
        search("1004")
        XCTAssertTrue(tile(40).waitForExistence(timeout: 10), "precondition: #40 is a result")
        tile(40).tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 10))
        app.buttons["More"].tap()
        app.buttons["Move to Trash"].tap()
        // The viewer closes on a trash that happened, and the results re-read.
        XCTAssertTrue(tile(41).waitForExistence(timeout: 10), "back on the results")
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: tile(40))
        waitForExpectations(timeout: 10)
    }

    func testAPushedResultSurvivesARotation() {
        // ⚠ The shell's rule (N6): a destination's navigation path survives a rotation, on the `Tab`
        // builder the search destination uses as on `tabItem`.
        openSearch()
        search("IMG_10042")
        XCTAssertTrue(tile(42).waitForExistence(timeout: 10))
        tile(42).tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 10))
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].exists, "still in the viewer")
        XCUIDevice.shared.orientation = .portrait
        sleep(2)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(tile(42).waitForExistence(timeout: 10), "and Back still returns to the results")
        app.tabBars.buttons["Collections"].tap()
        app.tabBars.buttons["Search"].tap()
        XCTAssertEqual(field.value as? String, "IMG_10042", "a tab switch keeps the query")
    }
}
