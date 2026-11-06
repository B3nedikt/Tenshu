import XCTest

/// The viewer's gesture priority (D4, photos-ios.md §8.13), made with real gestures on the
/// `-photosFixture` library — what `simctl` cannot do.
///
/// ⚠ The subtitle is the handle on WHICH photograph is showing: #3 was taken at 22:00 and #4 at
/// 21:35 (`PhotosScreenshotFixture`, 25 minutes apart on 19 January).
final class ViewerGestureUITests: XCTestCase {

    private var app: XCUIApplication!

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"]
        app.launch()
    }

    private func openThree() -> XCUIElement {
        let tile = app.descendants(matching: .any)["IMG_10003.jpg"]
        XCTAssertTrue(tile.waitForExistence(timeout: 15), "the grid shows #3")
        tile.tap()
        let page = app.descendants(matching: .any)["viewerPhoto"]
        XCTAssertTrue(page.waitForExistence(timeout: 15), "the viewer opens on a loaded photograph")
        XCTAssertTrue(app.staticTexts["22:00"].waitForExistence(timeout: 5), "on #3")
        return page
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }

    /// Magnified, a sideways drag is the PAN's — the pager must not move. At fit-scale it pages.
    func testMagnifiedTheSwipePansInsteadOfPaging() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        page.swipeLeft()
        attach("magnified-after-swipe")
        XCTAssertTrue(app.staticTexts["22:00"].exists, "still #3 — the swipe panned the photograph")

        page.pinch(withScale: 0.2, velocity: -2)
        page.swipeLeft()
        XCTAssertTrue(app.staticTexts["21:35"].waitForExistence(timeout: 5), "at fit-scale it pages to #4")
    }

    /// A drag down short of the line springs back; past it, the viewer closes.
    func testADragDownPastTheLineCloses_andAShortOneDoesNot() {
        let page = openThree()
        let centre = page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4))

        centre.press(forDuration: 0.05, thenDragTo: page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
        XCTAssertTrue(page.waitForExistence(timeout: 2), "a short drag keeps the viewer")
        XCTAssertTrue(app.staticTexts["22:00"].exists)

        centre.press(forDuration: 0.05, thenDragTo: page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85)))
        attach("after-long-drag")
        XCTAssertTrue(app.descendants(matching: .any)["IMG_10003.jpg"].waitForExistence(timeout: 5),
                      "a drag past 18 % of the page closes the viewer — back on the grid")
    }

    /// Magnified, a drag DOWN is the pan's too — it must not close the viewer.
    func testMagnifiedADragDownPansInsteadOfClosing() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        let centre = page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))
        centre.press(forDuration: 0.05, thenDragTo: page.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9)))
        XCTAssertTrue(page.exists, "the viewer is still open")
        XCTAssertTrue(app.staticTexts["22:00"].exists, "on #3")
    }

    /// A sharper rung landing mid-zoom keeps the zoom: the fixture sends its second rung 6 s after
    /// the first, so a pinch made before it is still in force after it.
    func testTheZoomSurvivesTheSharperRungLanding() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        Thread.sleep(forTimeInterval: 8) // past the fixture's upgrade (6 s after the first rung)
        page.swipeLeft()
        attach("zoom-after-upgrade")
        XCTAssertTrue(app.staticTexts["22:00"].exists, "still #3 — the upgrade did not reset the zoom")
    }

    /// Zoomed, the photograph can be panned to its EDGE and no further — never into the letterbox a
    /// page-sized zoom view would carry. Every fixture picture is 4:3 landscape, so on a
    /// portrait phone it is letterboxed top and bottom: panned to the very top, the top of the screen
    /// must still be PHOTOGRAPH, not the black ground.
    func testZoomedThePhotographCannotBePannedIntoItsLetterbox() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        for _ in 0..<4 { page.swipeDown() } // pan to the top edge of the content
        Thread.sleep(forTimeInterval: 1)    // let the bounce settle
        let shot = app.screenshot()
        attach("zoomed-panned-to-top")
        let frame = page.frame
        let probe = CGPoint(x: frame.midX, y: frame.minY + frame.height * 0.1)
        XCTAssertFalse(Self.isNearBlack(shot.image, at: probe), "the photograph fills the top — not the letterbox")
    }

    /// Whether the screenshot's pixel at [point] (in points) is the viewer's black ground.
    private static func isNearBlack(_ image: UIImage, at point: CGPoint) -> Bool {
        guard let cg = image.cgImage else { return false }
        let x = Int(point.x * image.scale), y = Int(point.y * image.scale)
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        context.draw(cg, in: CGRect(x: -x, y: y - cg.height + 1, width: cg.width, height: cg.height))
        return Int(pixel[0]) + Int(pixel[1]) + Int(pixel[2]) < 60
    }

    /// A rotation WHILE magnified refits the photograph for the new page and keeps the zoom:
    /// its fitted base must be the NEW page's aspect-fit — for a 4:3 picture on a landscape
    /// phone, limited by height — not the portrait one it was fitted for before.
    func testARotationWhileMagnifiedRefitsThePhotograph() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 2)
        let window = app.windows.firstMatch.frame
        XCTAssertGreaterThan(window.width, window.height, "precondition: the app really is landscape")
        attach("rotated-magnified")

        let reading = (page.value as? String) ?? ""
        guard let zoom = Self.number(after: "zoom=", in: reading),
              let baseWidth = Self.number(after: "base=", in: reading) else {
            return XCTFail("no zoom reading: \(reading)")
        }
        XCTAssertGreaterThan(zoom, 1.5, "still magnified")
        let page4x3 = page.frame
        let expected = min(page4x3.width, page4x3.height * 4 / 3)
        XCTAssertEqual(baseWidth, expected, accuracy: 3, "fitted for the NEW page (\(reading))")
    }

    /// A rotation while magnified keeps the point the user was looking at: panned off
    /// centre, the photograph's point under the page's centre is the same after — or, where the new
    /// page cannot centre it without showing past the photograph's edge, as close as that edge allows.
    func testARotationWhileMagnifiedKeepsTheFocalPoint() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        page.swipeLeft() // off centre, toward the photograph's right
        Thread.sleep(forTimeInterval: 1)
        let before = (page.value as? String) ?? ""
        XCUIDevice.shared.orientation = .landscapeLeft
        Thread.sleep(forTimeInterval: 2)
        let after = (page.value as? String) ?? ""
        guard let bx = Self.number(after: "focus=", in: before),
              let by = Self.number(after: ",", in: String(before[before.range(of: "focus=")!.upperBound...])),
              let ax = Self.number(after: "focus=", in: after),
              let ay = Self.number(after: ",", in: String(after[after.range(of: "focus=")!.upperBound...])),
              let zoom = Self.number(after: "zoom=", in: after),
              let baseW = Self.number(after: "base=", in: after),
              let baseH = Self.number(after: "x", in: String(after[after.range(of: "base=")!.upperBound...])) else {
            return XCTFail("no reading: \(before) / \(after)")
        }
        XCTAssertGreaterThan(bx, 0.55, "precondition: panned off centre (\(before))")
        // The old focus, clamped to what the new page can centre: half the page, as a fraction of the
        // magnified photograph, in from each edge.
        let page2 = page.frame
        let halfX = Double(page2.width) / 2 / (baseW * zoom)
        let halfY = Double(page2.height) / 2 / (baseH * zoom)
        let expectedX = min(max(bx, halfX), 1 - halfX)
        let expectedY = min(max(by, halfY), 1 - halfY)
        XCTAssertEqual(ax, expectedX, accuracy: 0.02, "x kept, or at the edge (\(before) → \(after))")
        XCTAssertEqual(ay, expectedY, accuracy: 0.02, "y kept, or at the edge (\(before) → \(after))")
    }

    // MARK: About

    private func assertAboutOpen(_ why: String) {
        let sheet = app.descendants(matching: .any)["aboutSheet"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 5), why)
        XCTAssertTrue(app.staticTexts["IMG_10003.jpg"].exists, "it describes #3")
        XCTAssertTrue(app.staticTexts["Details"].exists)
        // The fixture holds no original, so the settings section is the one-line FACT, not rows and
        // not silence — and only an OPEN sheet reads it.
        XCTAssertTrue(
            app.staticTexts["Camera settings need the original, which isn't on this device."].waitForExistence(timeout: 5),
            "the settings section says why it has no rows"
        )
    }

    /// Up at fit-scale opens About — Compose's "upward scrolls into About" (§11).
    func testASwipeUpOpensAbout() {
        let page = openThree()
        page.swipeUp()
        attach("about-from-swipe")
        assertAboutOpen("a swipe up opens the About sheet")
    }

    /// The menu's first item opens it too.
    func testTheMenuOpensAbout() {
        _ = openThree()
        app.buttons["More"].tap()
        app.buttons["About"].tap()
        assertAboutOpen("About in the menu opens the sheet")
    }

    /// Magnified, an upward drag is the PAN's — it must not open About (the priority table).
    func testMagnifiedASwipeUpPansInsteadOfOpeningAbout() {
        let page = openThree()
        page.pinch(withScale: 3, velocity: 2)
        page.swipeUp()
        XCTAssertFalse(app.descendants(matching: .any)["aboutSheet"].waitForExistence(timeout: 2),
                       "no About sheet while magnified")
    }

    private static func number(after key: String, in text: String) -> Double? {
        guard let range = text.range(of: key) else { return nil }
        let rest = text[range.upperBound...].prefix { $0.isNumber || $0 == "." }
        return Double(rest)
    }
}

