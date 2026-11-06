import XCTest

/// The Library's bar controls over photographs (photos-ios.md §8.21, rule 11): the density button
/// and the selection's Cancel are the system's items at rest and clear glass over a dim, with a white
/// label, while photographs lie under the bar.
///
/// Three claims: the state follows the SAME under-bar signal as the title; the control does not
/// move, and stays hittable, when it changes; and over the brightest backdrop there is — every tile
/// near-white, `-photosTileTone white` — its label still stands out from what is behind it.
///
/// ⚠⚠ The contrast claim is the one that fails without the fix: on iOS 26.x the system's own item is
/// a white glyph on glass the system has turned light (1.5:1 measured).
final class BarControlUITests: XCTestCase {

    private var app: XCUIApplication!

    /// ⚠ Before iOS 26 there is no glass and `LibraryChrome.overPhotos` is always false, so the
    /// `.over-photos` state these tests wait for never appears — skip rather than time out.
    override func setUpWithError() throws {
        guard #available(iOS 26.0, *) else {
            throw XCTSkip("the bar's over-photos state exists only on iOS 26+")
        }
    }

    private func launch(_ extra: [String] = []) {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"] + extra
        app.launch()
    }

    /// ⚠ The identifier carries the state — an identifier is never spoken, the label is unchanged.
    private func control(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", name)).firstMatch
    }

    private var density: XCUIElement { control("library-density") }
    private var cancel: XCUIElement { control("library-cancel-selection") }
    private var window: XCUIElement { app.windows.firstMatch }

    private func drag(by dy: CGFloat) {
        let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.4, dy: dy < 0 ? 0.7 : 0.35))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: dy)),
                    withVelocity: 300, thenHoldForDuration: 0.3)
    }

    private func waitFor(_ element: XCUIElement, identifier: String) {
        expectation(for: NSPredicate(format: "identifier == %@", identifier), evaluatedWith: element)
        waitForExpectations(timeout: 10)
    }

    /// The label's contrast against the control's own surface, from the pixels: the brightest 3 % of
    /// the control against its median, as WCAG relative luminance.
    private func labelContrast(of element: XCUIElement) -> Double {
        let shot = XCUIScreen.main.screenshot().image
        guard let image = shot.cgImage else { return 0 }
        let scale = CGFloat(image.width) / window.frame.width
        // The middle of the control: its glyph or text, and the surface around it.
        let rect = element.frame.insetBy(dx: 4, dy: 8)
        let crop = CGRect(x: rect.minX * scale, y: rect.minY * scale, width: rect.width * scale, height: rect.height * scale)
        guard let part = image.cropping(to: crop.integral) else { return 0 }
        let w = part.width, h = part.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        guard let context = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 0 }
        context.draw(part, in: CGRect(x: 0, y: 0, width: w, height: h))
        func linear(_ c: UInt8) -> Double {
            let v = Double(c) / 255
            return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
        }
        var luminance = [Double]()
        luminance.reserveCapacity(w * h)
        for i in stride(from: 0, to: pixels.count, by: 4) {
            luminance.append(0.2126 * linear(pixels[i]) + 0.7152 * linear(pixels[i + 1]) + 0.0722 * linear(pixels[i + 2]))
        }
        luminance.sort()
        let surface = luminance[luminance.count / 2]
        let top = luminance.suffix(max(luminance.count * 3 / 100, 8))
        let label = top.reduce(0, +) / Double(top.count)
        return (max(label, surface) + 0.05) / (min(label, surface) + 0.05)
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }

    func testTheDensityButtonFollowsTheBackdrop_andDoesNotMove() throws {
        launch(["-photosTileTone", "white"])
        XCTAssertTrue(density.waitForExistence(timeout: 30), "the density button")
        waitFor(density, identifier: "library-density")
        XCTAssertTrue(density.isHittable, "hittable at rest")
        let rest = density.frame
        attach("at rest")

        drag(by: -220)
        waitFor(density, identifier: "library-density.over-photos")
        XCTAssertTrue(density.isHittable, "hittable over photographs")
        let over = density.frame
        attach("over white photographs")
        // ⚠ The CENTRE: iOS 27 reports the system item 2.7 pt narrower than the one over photographs.
        XCTAssertEqual(over.midX, rest.midX, accuracy: 1, "the control does not move sideways")
        XCTAssertEqual(over.midY, rest.midY, accuracy: 1, "nor up or down")
        if #available(iOS 26.0, *) {
            XCTAssertGreaterThanOrEqual(labelContrast(of: density), 4.5, "a white glyph on a surface that stays dark")
        }

        // It still does its job from there — and off its glyph, 16 pt off centre: the hit target is
        // the 36 pt item, not the glyph. ⚠ Not 19 pt (the glass's edge): iOS 26.5 takes no tap in the
        // outer 4 pt of glass, 27.0 does (photos-ios.md §8.21 rule 11).
        let before = density.label
        density.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).withOffset(CGVector(dx: -16, dy: 0)).tap()
        expectation(for: NSPredicate(format: "label != %@", before), evaluatedWith: density)
        waitForExpectations(timeout: 10)

        // And back at the top it is the system's item again, where it was.
        for _ in 0..<4 where density.identifier != "library-density" { drag(by: 320) }
        waitFor(density, identifier: "library-density")
        XCTAssertEqual(density.frame.midX, rest.midX, accuracy: 1)
        XCTAssertEqual(density.frame.midY, rest.midY, accuracy: 1)
    }

    /// Settings' gear beside the density button (a build with Settings — here the `-photosModels`
    /// fixture): two trailing items, so the solo metrics above are not enough. Over photographs each
    /// draws its own glass; neither may move, the two may not overlap, and the gear's glyph must read.
    func testTheGearBesideTheDensityButton_neitherMovesNorOverlaps() throws {
        launch(["-photosTileTone", "white", "-photosModels", "loading"])
        let gear = app.buttons["library-settings"]
        XCTAssertTrue(density.waitForExistence(timeout: 30), "the density button")
        XCTAssertTrue(gear.waitForExistence(timeout: 10), "Settings' gear")
        waitFor(density, identifier: "library-density")
        let restDensity = density.frame, restGear = gear.frame
        attach("two items at rest")
        // ⚠ A GAP, not "no overlap": the frames are the 36 pt items, and each glass reaches 4 pt past its own.
        XCTAssertGreaterThanOrEqual(restDensity.minX - restGear.maxX, 8, "at rest: \(restGear) and \(restDensity) leave no room for two glasses")

        drag(by: -220)
        waitFor(density, identifier: "library-density.over-photos")
        attach("two items over white photographs")
        XCTAssertGreaterThanOrEqual(density.frame.minX - gear.frame.maxX, 8, "over photographs: \(gear.frame) and \(density.frame) leave no room for two glasses")
        XCTAssertEqual(gear.frame.midX, restGear.midX, accuracy: 1, "the gear does not move sideways")
        XCTAssertEqual(gear.frame.midY, restGear.midY, accuracy: 1, "nor up or down")
        XCTAssertEqual(density.frame.midX, restDensity.midX, accuracy: 1, "the density button does not move sideways")
        XCTAssertEqual(density.frame.midY, restDensity.midY, accuracy: 1, "nor up or down")
        XCTAssertTrue(gear.isHittable, "the gear is hittable over photographs")
        if #available(iOS 26.0, *) {
            XCTAssertGreaterThanOrEqual(labelContrast(of: gear), 4.5, "the gear's glyph on a surface that stays dark")
        }
        gear.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10), "the gear opens Settings")
    }

    func testCancelFollowsTheBackdropToo_andEndsTheSelection() throws {
        launch(["-photosTileTone", "white"])
        XCTAssertTrue(density.waitForExistence(timeout: 30), "the density button")

        // A long press on a tile starts the selection; Cancel takes the density button's place.
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6)).press(forDuration: 1.2)
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "Cancel")
        waitFor(cancel, identifier: "library-cancel-selection")
        XCTAssertTrue(cancel.isHittable, "hittable at rest")
        let rest = cancel.frame

        drag(by: -220)
        waitFor(cancel, identifier: "library-cancel-selection.over-photos")
        XCTAssertEqual(cancel.label, "Cancel")
        XCTAssertTrue(cancel.isHittable, "hittable over photographs")
        attach("selecting, over white photographs")
        XCTAssertEqual(cancel.frame.midX, rest.midX, accuracy: 1, "Cancel does not move sideways")
        XCTAssertEqual(cancel.frame.midY, rest.midY, accuracy: 1, "nor up or down")
        if #available(iOS 26.0, *) {
            XCTAssertGreaterThanOrEqual(labelContrast(of: cancel), 4.5, "white text on a surface that stays dark")
        }

        cancel.tap()
        XCTAssertTrue(density.waitForExistence(timeout: 10), "the density button is back")
        XCTAssertFalse(cancel.exists)
    }
}
