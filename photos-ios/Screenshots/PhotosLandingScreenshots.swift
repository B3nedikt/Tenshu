import XCTest

/// The landing page's Photos captures (`docs/how-to/store-screenshots.md` §9): the iOS twin of the
/// Android walk's `photos_landing`. Run only through `scripts/store-screenshots.sh --set landing`,
/// after `DriveLandingScreenshots` filled the realm on the same simulator — Photos reads every image
/// in the realm, so Drive's uploads are its library and it seeds nothing of its own.
///
/// ⚠⚠ The shipping app: the `Debug` configuration (no `DEV_HARNESS`, so no `-photosFixture` mock),
/// the route-1 runtime over the realm Control created, every screen reached by taps, no launch
/// arguments at all. The album is made the way a user makes one: Collections → New album, then a
/// selection in the library → Add to Album. ⚠ The names are `store/photos/screens.json` →
/// `landing.ios.captured`.
final class PhotosLandingScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// The Autumn walks folder's twelve photos — the newest in the realm — become the album, as on
    /// Android (`LANDING_ALBUM`), and the newest of them is the one the viewer shows.
    private let album = "Autumn walks"
    private let albumPhotos = [
        "IMG_7424.jpg", "IMG_7417.jpg", "IMG_7410.jpg", "IMG_7431.jpg", "IMG_7445.jpg", "IMG_7438.jpg",
        "IMG_7452.jpg", "IMG_7466.jpg", "IMG_7459.jpg", "IMG_7473.jpg", "IMG_7487.jpg", "IMG_7480.jpg",
    ]
    private let viewerPhoto = "IMG_7424.jpg"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testLandingScreenshots() throws {
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        XCTAssertFalse(devLab.isEmpty, "the landing set needs the dev lab's TENSHU_* origins")
        app.launchEnvironment = devLab
        app.launchArguments = []
        app.launch()

        // 01 — the library, the default mosaic.
        XCTAssertTrue(app.waitForShell(timeout: 60), "Photos never connected (is the realm provisioned?)")
        XCTAssertTrue(tile(viewerPhoto).waitForExistence(timeout: 120), "the library never showed Drive's photos")
        settle(seconds: 12) // thumbnails arrive per tile
        landingShot("01-library")

        // The album: Collections → New album, filled from a selection in the library.
        walk.createAlbum(album)
        walk.addToAlbum(albumPhotos, album: album)

        // 02 — the album.
        walk.openTab(1)
        let card = app.staticTexts[l: album].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        settle(seconds: 2)
        card.tap()
        XCTAssertTrue(app.navigationBars[l: album].waitForExistence(timeout: 10), "the album grid")
        XCTAssertTrue(tile(albumPhotos[0]).waitForExistence(timeout: 30), "the album is empty")
        settle(seconds: 10)
        landingShot("02-album")
        app.navigationBars.buttons.firstMatch.tap() // Back

        // 03 — one photo full screen, with the viewer's own chrome.
        walk.openTab(0)
        walk.scrollLibraryToTop(until: viewerPhoto)
        tile(viewerPhoto).tap()
        XCTAssertTrue(app.buttons[l: "More"].firstMatch.waitForExistence(timeout: 15), "the viewer")
        settle(seconds: 6)
        landingShot("03-viewer")
    }

    // MARK: - Helpers

    private var walk: PhotosWalk { PhotosWalk(app: app) }
    private func tile(_ name: String) -> XCUIElement { walk.tile(name) }
    private func settle(seconds: TimeInterval = 2) { walk.settle(seconds: seconds) }

    private func landingShot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
