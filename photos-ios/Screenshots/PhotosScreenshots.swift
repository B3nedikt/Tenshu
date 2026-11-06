import XCTest

/// Photos' App Store and support-page captures (`docs/how-to/store-screenshots.md` §3d). Run only
/// through `scripts/store-screenshots.sh photos`, after `DriveLandingScreenshots/testSeedLandingRealm`
/// filled the realm on the same simulator with the landing set's stock photos — Photos reads every
/// image in the realm, so Drive's uploads are its library and it seeds nothing of its own.
///
/// ⚠⚠ The shipping app: the `Debug` configuration (no `DEV_HARNESS`, so no `-photosFixture` mock),
/// the route-1 runtime over the realm Control created, every screen reached by taps, no launch
/// arguments at all. Albums are made as a user makes them (Collections → New album, then a library
/// selection → Add to Album); the favourite is the viewer's heart. ⚠ The viewer's About sheet is
/// not captured: at its medium detent half of it is empty glass over the photo. ⚠ The names are
/// `store/photos/screens.json` → `captured`.
final class PhotosScreenshots: XCTestCase {

    private var app: XCUIApplication!
    private var walk: PhotosWalk { PhotosWalk(app: app) }

    /// Two trip albums — each a folder of the set — and *Outdoors*, all three folders' 31 photos,
    /// every list in library order (newest first). ⚠ Outdoors is the album `03-album` opens: it has
    /// to fill the iPad's six-column grid (12 photos are two rows on a white page).
    /// ⚠ Made in THIS order because Collections lists the newest album first: *Outdoors*, made last,
    /// leads the row next to *New album*, where the phone's narrow row still shows it.
    private static let coast = [
        "IMG_3829.jpg", "IMG_3822.jpg", "IMG_3815.jpg", "IMG_3836.jpg", "IMG_3850.jpg", "IMG_3843.jpg",
        "IMG_3857.jpg", "IMG_3864.jpg",
    ]
    private static let dolomites = [
        "IMG_5116.jpg", "IMG_5109.jpg", "IMG_5102.jpg", "IMG_5123.jpg", "IMG_5137.jpg", "IMG_5130.jpg",
        "IMG_5144.jpg", "IMG_5158.jpg", "IMG_5151.jpg", "IMG_5165.jpg", "IMG_5172.jpg",
    ]
    private static let autumn = [
        "IMG_7424.jpg", "IMG_7417.jpg", "IMG_7410.jpg", "IMG_7431.jpg", "IMG_7445.jpg", "IMG_7438.jpg",
        "IMG_7452.jpg", "IMG_7466.jpg", "IMG_7459.jpg", "IMG_7473.jpg", "IMG_7487.jpg", "IMG_7480.jpg",
    ]
    private let albums: [(name: String, photos: [String])] = [
        ("Coast trip", coast),
        ("Dolomites", dolomites),
        ("Outdoors", autumn + dolomites + coast),
    ]
    /// The album `03-album` shows.
    private var shownAlbum: (name: String, photos: [String]) { albums[2] }
    /// The newest photo: the library opens on it, and the viewer shows it.
    private let viewerPhoto = "IMG_7424.jpg"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testStoreScreenshots() throws {
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        XCTAssertFalse(devLab.isEmpty, "Photos' set needs the dev lab's TENSHU_* origins (a funded realm)")
        app.launchEnvironment = devLab
        app.launchArguments = []
        app.launch()

        // 01 — the library, the default mosaic.
        XCTAssertTrue(app.waitForShell(timeout: 60),
                      "Photos never connected (is the realm provisioned?)\n\(app.debugDescription)")
        XCTAssertTrue(app.shellTab(1).exists, "no Collections tab where expected\n\(app.debugDescription)")
        XCTAssertTrue(walk.tile(viewerPhoto).waitForExistence(timeout: 120), "the library never showed Drive's photos")
        walk.settle(seconds: 12) // thumbnails arrive per tile
        shot("01-library")

        // The albums, made and filled as a user does.
        for album in albums {
            walk.createAlbum(album.name)
            // From the top each time: the last selection left the library scrolled past newer photos.
            walk.openTab(0)
            walk.scrollLibraryToTop(until: viewerPhoto)
            walk.addToAlbum(album.photos, album: album.name)
        }

        // 04 — one photo full screen, made a favourite with the viewer's heart.
        walk.openTab(0)
        walk.scrollLibraryToTop(until: viewerPhoto)
        walk.tile(viewerPhoto).tap()
        XCTAssertTrue(app.buttons[l: "More"].firstMatch.waitForExistence(timeout: 15), "the viewer")
        let heart = app.buttons[l: "Add to Favourites"].firstMatch
        XCTAssertTrue(heart.waitForExistence(timeout: 15), "the viewer's heart")
        waitUntilEnabled(heart)
        heart.tap()
        XCTAssertTrue(app.buttons[l: "Remove from Favourites"].firstMatch.waitForExistence(timeout: 15),
                      "the favourite did not take")
        walk.settle(seconds: 6)
        shot("04-viewer")

        // Back to the library.
        app.navigationBars.buttons.firstMatch.tap()

        // 02 — Collections: the tiles, the three albums with their covers, the years.
        walk.openTab(1)
        for album in albums {
            XCTAssertTrue(app.staticTexts[l: album.name].firstMatch.waitForExistence(timeout: 30), "\(album.name)'s card")
        }
        walk.settle(seconds: 8) // the covers
        shot("02-collections")

        // 03 — an album.
        let first = shownAlbum
        app.staticTexts[l: first.name].firstMatch.tap()
        XCTAssertTrue(app.navigationBars[l: first.name].waitForExistence(timeout: 10), "the album grid")
        XCTAssertTrue(walk.tile(first.photos[0]).waitForExistence(timeout: 30), "the album is empty")
        walk.settle(seconds: 10)
        shot("03-album")
        app.navigationBars.buttons.firstMatch.tap() // Back

        // 05 — the library grouped by month, through its grouping button (Mosaic → Day → Month).
        walk.openTab(0)
        walk.scrollLibraryToTop(until: viewerPhoto)
        let grouping = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Grouped by'")).firstMatch
        XCTAssertTrue(grouping.waitForExistence(timeout: 10), "the grouping button")
        var taps = 0
        while !grouping.label.hasPrefix("Grouped by month") && taps < 4 {
            grouping.tap()
            walk.settle(seconds: 1)
            taps += 1
        }
        XCTAssertTrue(grouping.label.hasPrefix("Grouped by month"), "never reached the month grouping: \(grouping.label)")
        walk.settle(seconds: 10)
        shot("05-months")
    }

    // MARK: - Helpers

    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval = 15) {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isEnabled == true"), object: element)
        XCTAssertEqual(XCTWaiter().wait(for: [enabled], timeout: timeout), .completed, "\(element) never enabled")
    }

    /// Captures once the sync-activity capsule has stayed gone (`PhotosWalk.waitForSyncIdle`).
    private func shot(_ name: String) {
        walk.waitForSyncIdle()
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
