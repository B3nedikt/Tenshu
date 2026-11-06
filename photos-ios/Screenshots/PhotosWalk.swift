import XCTest

/// The taps both Photos captures share (`PhotosLandingScreenshots`, §9, and `PhotosScreenshots`,
/// §3d of `docs/how-to/store-screenshots.md`): the shell's tabs, a tile by its file name, and an
/// album made and filled the way a user does it.
struct PhotosWalk {
    let app: XCUIApplication

    /// Opens the shell's destination at `index` (`PhotosIosApp`: 0 Library, 1 Collections, then
    /// Search) — by position, through the shared `shellTab` (`ios-shell/UITestSupport`), which finds
    /// the iPad's floating bar too. ⚠ Waits for the shell first: the viewer hides the bar, and it comes
    /// back only as *Back*'s pop ends (a tab asked for right after it found none, 2026-10-24).
    func openTab(_ index: Int) {
        XCTAssertTrue(app.waitForShell(timeout: 10), "the shell's tabs never came back")
        app.shellTab(index).tap()
    }

    /// A grid tile, by its file name (the tiles' accessibility identifier).
    func tile(_ name: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: name).firstMatch
    }

    func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    /// Scrolls the library down, slowly, until `name` can be tapped. The library is a lazy grid: a
    /// tile below the fold does not exist yet, so existence alone is not enough.
    ///
    /// ⚠⚠ Nor is `isHittable`: a tile whose centre sits under the floating tab bar or the selection's
    /// bottom toolbar reports hittable, and the tap lands on the bar — "11 Selected" of 12, twice in a
    /// row (2026-10-23). So the walk scrolls until the tile's centre is clear of the bottom
    /// `bottomClear` points — where it can: the library's last row never gets there, and is tapped
    /// where it is (`addToAlbum` checks every tap's count and taps once more if one was lost).
    @discardableResult
    func reveal(_ name: String, tries: Int = 12) -> XCUIElement {
        let t = tile(name)
        let limit = app.frame.maxY - bottomClear
        var n = 0
        while !(t.exists && t.isHittable) && n < tries {
            app.swipeUp(velocity: .slow)
            n += 1
        }
        // Clear of the bars, if the grid can still move: a swipe that leaves the tile where it was
        // means the end of the library.
        var lastY = t.frame.midY
        while t.exists && t.frame.midY >= limit && n < tries {
            app.swipeUp(velocity: .slow)
            n += 1
            if t.frame.midY == lastY { break }
            lastY = t.frame.midY
        }
        // ⚠ Let the scroll stop: a tap on a grid still decelerating only stops it.
        if n > 0 { settle(seconds: 1.5) }
        XCTAssertTrue(t.isHittable, "\(name) is not on screen (\(t.frame))")
        return t
    }

    /// The bottom band the bars may cover: the floating tab bar and, while selecting, the toolbar
    /// above it.
    private var bottomClear: CGFloat { 200 }

    /// Collections → New album → the name → Create, as a user makes one. Ends on Collections with the
    /// album's card showing.
    func createAlbum(_ name: String) {
        openTab(1)
        let newAlbum = app.buttons.containing(ShotStrings.labelIs("New album")).firstMatch
        XCTAssertTrue(newAlbum.waitForExistence(timeout: 30), "the New album card")
        settle(seconds: 2)
        newAlbum.tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(name)
        app.alerts.buttons[l: "Create"].tap()
        XCTAssertTrue(app.staticTexts[l: name].firstMatch.waitForExistence(timeout: 30), "the album card")
    }

    /// Fills `album` from a selection in the library: a long press selects the first photo, a tap adds
    /// each next one, then *Add to Album* → the album. `photos` in library order (newest first), so the
    /// walk only ever scrolls down.
    func addToAlbum(_ photos: [String], album: String) {
        openTab(0)
        for (i, name) in photos.enumerated() {
            let t = reveal(name)
            if i == 0 { t.press(forDuration: 1.0) } else { t.tap() }
            // ⚠ Each tap is checked by the count it must make: a tap can be lost (to a bar, or to a
            // grid that was still moving), and a selection one short only shows at the end. One
            // more tap, if it was; a tap that took and is repeated would deselect, so never blind.
            let counted = app.navigationBars[any: ShotStrings.format("%lld Selected", i + 1)]
            if !counted.waitForExistence(timeout: 3) {
                if i == 0 { t.press(forDuration: 1.0) } else { t.tap() }
            }
            XCTAssertTrue(counted.waitForExistence(timeout: 3),
                          "\(name) did not join the selection: \(app.navigationBars.firstMatch.identifier)")
        }
        XCTAssertTrue(app.navigationBars[any: ShotStrings.format("%lld Selected", photos.count)].waitForExistence(timeout: 5),
                      "the selection is not \(photos.count): \(app.navigationBars.firstMatch.identifier)")
        app.buttons[l: "Add to Album"].firstMatch.tap()
        let row = app.buttons.containing(.staticText, identifier: album).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 15), "the album in the picker")
        row.tap()
        let ok = app.alerts.buttons[l: "OK"].firstMatch
        if ok.waitForExistence(timeout: 20) { ok.tap() }
        let cancel = app.navigationBars.buttons[l: "Cancel"].firstMatch
        if cancel.exists { cancel.tap() }
    }

    /// Scrolls the library back to the top.
    func scrollLibraryToTop(until name: String) {
        var n = 0
        while !(tile(name).exists && tile(name).isHittable) && n < 12 {
            app.swipeDown(velocity: .fast)
            n += 1
        }
        if n > 0 { settle(seconds: 1.5) } // the scroll's deceleration, as in `reveal`
    }

    /// ⚠ Waits out the sync-activity capsule (`ios-storage`'s `SyncActivityCapsule`, identifier
    /// `sync-activity-capsule`: "Syncing…") before a capture — the Drive walks' rule (§9). It shows
    /// 700 ms after activity starts, so one absent check proves nothing: it must stay gone for `quiet`
    /// seconds. A capsule still up at `timeout` fails the walk rather than capture it.
    func waitForSyncIdle(timeout: TimeInterval = 300, quiet: TimeInterval = 3) {
        let capsule = app.descendants(matching: .any)[l: "sync-activity-capsule"]
        let deadline = Date().addingTimeInterval(timeout)
        var goneSince: Date?
        while Date() < deadline {
            if capsule.exists {
                goneSince = nil
            } else if let since = goneSince {
                if Date().timeIntervalSince(since) >= quiet { return }
            } else {
                goneSince = Date()
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTFail("the sync-activity capsule was still up after \(Int(timeout)) s — not capturing mid-sync")
    }
}
