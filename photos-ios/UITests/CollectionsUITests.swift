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

    // MARK: People (`ios-face-grouping.md` F7)

    /// Relaunch over `-photosPeople <state>`: a phone that groups faces (both build flags on).
    private func launchPeople(_ state: String) {
        app.terminate()
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture", "-photosPeople", state]
        app.launch()
        openCollections()
    }

    private func personCards() -> XCUIElementQuery {
        app.descendants(matching: .any).matching(identifier: "personCard")
    }

    /// ⚠⚠ The shipped build (no `-photosPeople`): no face backend, so People says nothing at all —
    /// `groupingRunsHere` false at runtime, as the literal was.
    func testWithNoFaceBackendPeopleSaysNothing() {
        openCollections()
        XCTAssertFalse(app.staticTexts["People"].exists, "no People section where faces cannot be grouped")
        XCTAssertFalse(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'To see people here'")).firstMatch.exists)
    }

    /// ⚠⚠ The shipped build WITH names synced from another device (`-photosPeople syncedOnly`: no face
    /// backend). People is exactly what it was before F7: the round, centred card, and a person's page is
    /// the plain grid — no naming that could never save, no "With…", the name as the title.
    func testWithNoFaceBackendASyncedNameIsThePreF7CardAndPage() {
        launchPeople("syncedOnly")
        let mia = personCards()["Mia"]
        XCTAssertTrue(mia.waitForExistence(timeout: 15), "a synced name still shows")
        XCTAssertEqual(mia.value as? String, "circle", "the pre-F7 card, not F7's")
        XCTAssertFalse(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'To see people here'")).firstMatch.exists)
        mia.tap()
        XCTAssertTrue(app.navigationBars["Mia"].waitForExistence(timeout: 10), "titled as before, no header")
        XCTAssertFalse(app.descendants(matching: .any)["photoCover"].exists, "no person header")
        XCTAssertFalse(app.navigationBars.buttons["Add another person"].exists, "no naming that cannot save")
        XCTAssertFalse(app.navigationBars.buttons["Add a name"].exists)
        XCTAssertFalse(app.buttons["With…"].exists, "no With… chip")
    }

    /// ⚠⚠ ED-3: with no face to cut, Leo's header draws his first photograph — which is EDITED, so
    /// it must be the rendition, never the original (a crop or Redact can hide what the original shows).
    func testAPersonHeaderWithNoFaceDrawsAnEditedPhotographsRendition() {
        launchPeople("faces")
        let leo = personCards()["Leo"]
        XCTAssertTrue(leo.waitForExistence(timeout: 15))
        leo.tap()
        let cover = app.descendants(matching: .any)["photoCover"]
        XCTAssertTrue(cover.waitForExistence(timeout: 10), "the header's whole-photograph cover")
        XCTAssertEqual(cover.value as? String, "fixture-60-edit.jpg", "the rendition")
        XCTAssertNotEqual(cover.value as? String, "IMG_10060.jpg", "never the original")
    }

    /// Each refusal's line, naming PHOTOS' Settings → Models (never Control's) — and, from the very
    /// first launch, the two names synced from another device, from their labels alone, with no face.
    func testEachEmptyStateSaysWhyAndWhereToGo() {
        let lines = [
            "noModel": "To see people here, download the face model in Photos, under Settings → Models.",
            "noConsent": "To see people here, turn on \"Group photos by people\" for this device in Photos, under Settings → Models.",
            "notIndexed": "People appear after Photos looks through your photos on this device. Tap Index now, under Settings → Models.",
        ]
        for (state, line) in lines {
            launchPeople(state)
            XCTAssertTrue(app.staticTexts[line].waitForExistence(timeout: 15), "\(state): \(line)")
            attach("people-\(state)")
            // ⚠ A refusal is said even with names: Mia and Leo synced, no face of either here.
            let mia = personCards()["Mia"]
            XCTAssertTrue(mia.exists, "\(state): a synced name shows from its labels alone")
            XCTAssertEqual(mia.value as? String, "photo", "\(state): no face to crop before this phone groups any")
        }
        // Grouping ran and found nobody: ⚠ the synced names ARE people, so no "No people found yet."
        // (`peopleStatusLine` goes quiet with people) — and still no face to cut for either.
        launchPeople("none")
        XCTAssertTrue(personCards()["Mia"].waitForExistence(timeout: 15))
        XCTAssertEqual(personCards()["Mia"].value as? String, "photo")
        XCTAssertFalse(app.staticTexts["No people found yet."].exists, "the synced names are people")
        XCTAssertFalse(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'To see people here'")).firstMatch.exists)
    }

    /// The unnamed clusters are cards, untitled; tapping one opens the person page, where naming it
    /// titles the page — and the card — with the typed name.
    func testUnnamedClustersAreCards_andNamingOneTitlesIt() {
        launchPeople("faces")
        let unnamed = personCards().matching(NSPredicate(format: "label == 'Unnamed person'"))
        XCTAssertTrue(unnamed.firstMatch.waitForExistence(timeout: 15), "the unnamed clusters")
        XCTAssertEqual(unnamed.count, 2, "g1 and g2 — g3 is entirely Mia's, so it is her")
        XCTAssertEqual(unnamed.firstMatch.value as? String, "face", "an unnamed cluster's cover is its face")
        attach("people-faces")
        unnamed.firstMatch.tap()

        // The header: the face, "Add a name" in the name slot and in the toolbar, the count.
        XCTAssertTrue(app.descendants(matching: .any)["faceCrop"].waitForExistence(timeout: 10), "the header's face")
        let add = app.navigationBars.buttons["Add a name"]
        XCTAssertTrue(add.exists, "naming is offered on an unnamed person")
        add.tap()
        XCTAssertTrue(app.alerts["Who is this?"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.staticTexts["This name is saved with these photos and appears on your other devices."].exists)
        XCTAssertFalse(app.alerts.buttons["Save"].isEnabled, "a blank name is not offered")
        app.alerts.textFields.firstMatch.typeText("Ada")
        app.alerts.buttons["Save"].tap()

        XCTAssertTrue(app.staticTexts["Ada"].waitForExistence(timeout: 10), "the page is titled")
        // ⚠⚠ Naming is additive: the action now adds ANOTHER person, never "Rename".
        XCTAssertTrue(app.navigationBars.buttons["Add another person"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars.buttons["Rename"].exists)
        attach("person-named")
        app.navigationBars.buttons.firstMatch.tap() // Back
        let ada = personCards()["Ada"]
        XCTAssertTrue(ada.waitForExistence(timeout: 10), "the card is titled")
        XCTAssertEqual(ada.value as? String, "face")
        XCTAssertEqual(personCards().matching(NSPredicate(format: "label == 'Unnamed person'")).count, 1)
    }

    /// ⚠⚠ A name typed on Android: once this phone has faces, a cluster entirely covered by Mia's
    /// labels becomes hers and lends her its face — cut to the face, not the photograph's centre.
    /// Leo, whom no cluster here covers, keeps his whole photograph.
    func testANameSyncedFromAndroidShowsItsFaceOnceThisPhoneHasFaces() {
        launchPeople("faces")
        let mia = personCards()["Mia"]
        XCTAssertTrue(mia.waitForExistence(timeout: 15))
        XCTAssertEqual(mia.value as? String, "face", "Mia's cover is a face now")
        XCTAssertEqual(personCards()["Leo"].value as? String, "photo", "no cluster here covers Leo")
        mia.tap()

        let face = app.descendants(matching: .any)["faceCrop"]
        XCTAssertTrue(face.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Mia"].exists, "the header names her")
        XCTAssertTrue(app.staticTexts["4 photos"].exists, "exactly her labelled photographs")
        // `left,top,side of WxH`: the cut is the face's square, upper right — not the centred square.
        XCTAssertTrue(waitForCut(face), "the cut is drawn")
        let numbers = (face.value as? String ?? "").split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        XCTAssertEqual(numbers.count, 5, "\(face.value ?? "")")
        let (left, top, side, width, height) = (numbers[0], numbers[1], numbers[2], numbers[3], numbers[4])
        XCTAssertNotEqual(left, (width - side) / 2, "cut from the face, not the photograph's centre")
        XCTAssertGreaterThan(left, width / 2 - side / 2, "the face is to the right of centre")
        XCTAssertLessThan(top, (height - side) / 2, "and above it")
        XCTAssertLessThan(side, min(width, height), "a face, not the whole frame")
        attach("person-synced")

        // F6b: "With…" narrows to photographs of both — #50 is Mia's and g1's.
        app.buttons["With…"].tap()
        XCTAssertTrue(app.navigationBars["Photos with…"].waitForExistence(timeout: 5))
        app.buttons["Leo"].tap()
        XCTAssertTrue(app.staticTexts["No photos of both yet"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Mia & Leo"].exists)
        XCTAssertFalse(app.navigationBars.buttons["Add another person"].exists, "no naming while narrowed")
        app.buttons["Remove Leo"].tap()
        XCTAssertTrue(app.staticTexts["4 photos"].waitForExistence(timeout: 5), "the whole grid again")
    }

    /// IN-6 F6c name suggestions: an unsure group is ASKED, and Yes names it into Mia; a sure one is
    /// FOLDED into Leo on screen, its extra photographs said to be unconfirmed, and "Not Leo" unfolds it.
    func testAnUnsureGroupIsAskedAndASureOneIsFoldedUntilNo() {
        launchPeople("suggest")
        let leo = personCards()["Leo"]
        XCTAssertTrue(leo.waitForExistence(timeout: 15))
        // Two unnamed cards — gA and gB; gLeo is folded into Leo.
        let unnamed = personCards().matching(NSPredicate(format: "label == 'Unnamed person'"))
        XCTAssertEqual(unnamed.count, 2)
        leo.tap()
        let unconfirmed = app.staticTexts["2 photos matched by face on this device — not yet named Leo."]
        XCTAssertTrue(unconfirmed.waitForExistence(timeout: 10), "the fold says what it is")
        XCTAssertTrue(app.staticTexts["4 photos"].exists, "Leo's two labelled and the two folded")
        attach("person-folded")
        app.buttons["Not Leo"].tap()
        XCTAssertTrue(app.staticTexts["2 photos"].waitForExistence(timeout: 10), "unfolded: his labelled photographs only")
        XCTAssertFalse(unconfirmed.exists)
        app.navigationBars.buttons.firstMatch.tap() // Back
        XCTAssertTrue(personCards().matching(NSPredicate(format: "label == 'Unnamed person'")).element(boundBy: 2)
            .waitForExistence(timeout: 10), "gLeo is its own unnamed card again")

        // gA, the largest unnamed group, is asked about Mia.
        personCards().matching(NSPredicate(format: "label == 'Unnamed person'")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Is this Mia?"].waitForExistence(timeout: 10))
        attach("person-asked")
        app.buttons["suggestion.yes"].tap()
        XCTAssertTrue(app.staticTexts["Mia"].waitForExistence(timeout: 10), "Yes names it: the page is Mia's")
        XCTAssertTrue(app.staticTexts["5 photos"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["Is this Mia?"].exists)
    }

    /// Waits for the face cover's cut to be drawn (the preview arrives asynchronously).
    private func waitForCut(_ face: XCUIElement) -> Bool {
        let drawn = NSPredicate(format: "value MATCHES '[0-9]+,[0-9]+,[0-9]+ of [0-9]+x[0-9]+'")
        return XCTWaiter().wait(for: [expectation(for: drawn, evaluatedWith: face)], timeout: 10) == .completed
    }
}
