import XCTest

/// The only automated check that can see N6's defect (`docs/proposals/ios-adaptive-navigation.md`).
///
/// ⚠⚠ **`ios-smoke.sh --build-only` cannot catch this.** A phone-shaped iPad is not a compile error
/// and not a crash; the compile gate only proves the iOS-18 APIs are correctly availability-gated.
/// What the shell actually renders has to be looked at, and this is the looking.
///
/// ⚠ **Run it on an iPad destination** — on iPhone every width is compact, the placement is always
/// `.tabBar`, and every assertion below passes without exercising anything:
/// ```
/// xcodebuild test -project drive-ios/DriveIos.xcodeproj -scheme DriveIos-UITests \
///   -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
///   -only-testing:DriveIosUITests/AdaptiveShellUITests
/// ```
///
/// ⭐ **The discriminator is the element TYPE, not a label.** Under tab-bar placement a destination
/// is a `Button`; under sidebar placement the same destination is a `Cell`. That is a real finding
/// (§2.1 F11) and it is also the only stable handle the shell exposes — the system's own sidebar
/// toggle has three different accessibility spellings across states and OS versions (§2.1 F4), so
/// nothing here touches it.
final class AdaptiveShellUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["DRIVE_DEV_PROVISION_CONTROL"] = "1"
        XCUIDevice.shared.orientation = .portrait
        app.launch()
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
    }

    /// iPad portrait (834 pt on an 11", 1024 pt on a 13") is **below** the shell's 1100 pt
    /// threshold, so it must render the tab bar — which is what Apple's own Files app does at both
    /// widths (§2.1 F12). Launching into a sidebar here would put a modal panel over the listing
    /// before the user has done anything.
    func test_portrait_rendersTheTabBar() throws {
        guard isPad else { throw XCTSkip("iPad-only: on iPhone every width is compact") }

        XCTAssertTrue(app.buttons["Files"].waitForExistence(timeout: 15), "no tab-bar destination")
        XCTAssertFalse(app.cells["Files"].exists, "portrait rendered a sidebar below the threshold")
    }

    /// Landscape on an 11" iPad is 1210 pt — above the threshold, and the width at which Files
    /// shows a persistent sidebar (§2.1 F13).
    ///
    /// ⚠⚠ This is the assertion N6-1 was gated on: it proves
    /// `defaultAdaptableTabBarPlacement` **re-evaluates when the measured width changes**, not just
    /// at first composition. If this ever starts failing, the fallback is a bare
    /// `.sidebarAdaptable` with no width logic — never a sidebar-always default.
    func test_rotatingToLandscape_promotesToTheSidebar() throws {
        guard isPad else { throw XCTSkip("iPad-only: on iPhone every width is compact") }

        XCTAssertTrue(app.buttons["Files"].waitForExistence(timeout: 15), "no tab-bar destination")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(
            app.cells["Files"].waitForExistence(timeout: 10),
            "landscape did not promote to the sidebar — placement is not re-evaluating on width change"
        )
    }

    /// And back down: rotating to portrait must demote to the tab bar rather than leaving a
    /// sidebar overlaying the listing.
    func test_rotatingBackToPortrait_demotesToTheTabBar() throws {
        guard isPad else { throw XCTSkip("iPad-only: on iPhone every width is compact") }

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.cells["Files"].waitForExistence(timeout: 15), "never reached the sidebar")

        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(app.buttons["Files"].waitForExistence(timeout: 10), "did not demote to the tab bar")
    }

    /// ⭐ The reason the shell is one adapting component rather than two swapped ones: a rotation
    /// must not cost the user their place. Push into a folder, rotate, and the pushed screen must
    /// still be there — which only holds because the `NavigationStack` is inside a container that
    /// changes *presentation* rather than being torn down and rebuilt.
    func test_navigationDepthSurvivesRotation() throws {
        guard isPad else { throw XCTSkip("iPad-only: on iPhone every width is compact") }

        let folder = app.buttons["Photos, 12 files"]
        XCTAssertTrue(folder.waitForExistence(timeout: 15), "no folder row to push")
        folder.tap()

        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5), "push did not happen")

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.cells["Files"].waitForExistence(timeout: 10), "never reached the sidebar")
        XCTAssertTrue(
            app.navigationBars.buttons.firstMatch.exists,
            "rotation unwound the navigation stack — the container was rebuilt, not re-presented"
        )
    }

    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }
}
