import XCTest

/// The sync capsule on the Library (`ios-sync-activity-indicator.md` §4, §6), on the `-photosFixture`
/// library with the capsule's input scripted by `-photosSyncCapsule` (`SyncCapsuleProbe`).
///
/// Two claims: the capsule is there exactly when the script says a pull is running and nothing
/// outranks it, and **the grid does not move** when it comes or goes — its frame in the window, its
/// `adjustedContentInset` and its content offset are the same with the capsule shown and hidden.
///
/// ⚠ The script's steps are read from its whole record once it is done, not caught one by one: an
/// accessibility query takes about a second, and a step that is current when it starts may not be
/// when it returns.
final class SyncCapsuleUITests: XCTestCase {

    private var app: XCUIApplication!

    private func launch(_ extra: [String] = []) {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture", "-photosSyncCapsule"] + extra
        app.launch()
    }

    private var capsule: XCUIElement { app.descendants(matching: .any)["sync-activity-capsule"] }
    private var stepProbe: XCUIElement { app.descendants(matching: .any)["sync-capsule-step"] }
    private var recordProbe: XCUIElement { app.descendants(matching: .any)["sync-capsule-record"] }

    private func waitForStep(_ prefix: String, timeout: TimeInterval) {
        XCTAssertTrue(stepProbe.waitForExistence(timeout: 30), "the probe")
        expectation(for: NSPredicate(format: "value BEGINSWITH %@", prefix), evaluatedWith: stepProbe)
        waitForExpectations(timeout: timeout)
    }

    private struct Sample {
        let step: String
        let visible: Bool
        let geometry: String
    }

    /// The finished script's samples, `name:visible:geometry` joined by `|`.
    private func samples() -> [Sample] {
        let record = recordProbe.value as? String ?? ""
        return record.split(separator: "|").compactMap { entry in
            let parts = entry.split(separator: ":", maxSplits: 2).map(String.init)
            guard parts.count == 3 else { return nil }
            return Sample(step: parts[0], visible: parts[1] == "true", geometry: parts[2])
        }
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    func testTheCapsuleComesAndGoes_andTheGridDoesNotMove() {
        launch()

        // Live, once: the element a user would meet, and what VoiceOver would read.
        waitForStep("shown", timeout: 40)
        XCTAssertTrue(capsule.waitForExistence(timeout: 2), "a pull that lasts shows the capsule")
        XCTAssertEqual(capsule.label, "Syncing with your other devices")
        attach("capsule-shown")

        waitForStep("done", timeout: 60)
        attach("capsule-gone")
        let all = samples()
        XCTAssertEqual(
            all.map { "\($0.step)=\($0.visible)" },
            [
                "hidden-before=false",
                "on-inside-show-delay=false",    // 400 ms of activity shows nothing
                "shown=true",
                "hidden-after-off=false",
                "shown-again=true",
                "hidden-by-host-problem=false",  // a problem bar shows alone
                "shown-after-problem=true",
                "hidden-flow-ended=false",       // a flow that ended leaves nothing on screen
            ]
        )
        let geometries = Set(all.map(\.geometry))
        XCTAssertFalse(geometries.contains("-"), "the grid reported its geometry")
        XCTAssertEqual(geometries.count, 1, "the grid moved: \(all.map { "\($0.step) \($0.geometry)" })")
        assertTheGridLiesUnderBothBars(geometries.first ?? "")
    }

    /// ⚠⚠ The system's bars are glass, and glass over nothing is a white slab (photos-ios.md §8.21):
    /// the grid fills the WINDOW, top to bottom, and its `adjustedContentInset` — not its frame — is
    /// what keeps the first and last rows clear of the bars.
    private func assertTheGridLiesUnderBothBars(_ geometry: String) {
        func numbers(_ key: String) -> [Double] {
            guard let field = geometry.split(separator: " ").first(where: { $0.hasPrefix(key + "=") }) else { return [] }
            return field.dropFirst(key.count + 1).split(separator: ",").compactMap { Double($0) }
        }
        let frame = numbers("frame"), inset = numbers("inset"), offset = numbers("offset")
        guard frame.count == 4, inset.count == 4, offset.count == 2 else { return XCTFail("unreadable: \(geometry)") }
        let window = app.windows.firstMatch.frame
        XCTAssertEqual(frame[1], 0, "the grid starts at the top of the window, under the navigation bar: \(geometry)")
        XCTAssertEqual(frame[3], Double(window.height), accuracy: 1, "and ends at the bottom, under the tab bar: \(geometry)")
        XCTAssertGreaterThan(inset[0], 0, "its content clears the navigation bar by an inset: \(geometry)")
        XCTAssertGreaterThan(inset[2], 0, "and the tab bar: \(geometry)")
        XCTAssertEqual(offset[1], -inset[0], accuracy: 1, "at rest the first row sits just below the bar: \(geometry)")
    }

    func testTheGridDoesNotMove_whileScrolledIntoTheLibrary() {
        // Off the top, where the grid keeps a photograph in place rather than following arrivals.
        launch(["-photosSyncCapsuleScroll", "40"])

        waitForStep("done", timeout: 90)
        let all = samples()
        XCTAssertEqual(all.filter(\.visible).map(\.step), ["shown", "shown-again", "shown-after-problem"])
        let geometries = Set(all.map(\.geometry))
        XCTAssertEqual(geometries.count, 1, "the grid moved: \(all.map { "\($0.step) \($0.geometry)" })")
        XCTAssertFalse(geometries.first?.hasSuffix("offset=0.0,0.0") ?? true, "the grid was scrolled: \(geometries)")
    }

    /// Nobody outlives the view: each live observation holds two collectors of the scripted input
    /// (the line and the label), and `SyncCapsuleProbe.runLifetime` counts them.
    func testCollectorsEndWithTheirMount_andARestartLeavesOne() {
        launch(["-photosSyncCapsuleLifetime"])

        waitForStep("lifetime-ready", timeout: 60)
        XCTAssertEqual(
            samples().map { "\($0.step)=\($0.geometry)" },
            [
                "mounted=2",
                "second-mount=4",
                "second-mount-removed=2",       // removed while the scene stayed active
                "restarted-twenty-times=4",     // twenty starts of one model leave ONE observation
                "last-restart-cancelled=2",
            ]
        )

        // Away and back through the real scene phase. ⚠ The simulator coalesces these: three
        // presses were one activation when this was written, so the RAPID restart is what the
        // script's twenty-restart step pins, and this is the mount's own path taken once.
        let live = app.descendants(matching: .any)["sync-capsule-live"]
        for _ in 0..<3 {
            XCUIDevice.shared.press(.home)
            app.activate()
        }
        // Long enough for a cancelled observation to unwind, and for a leaked one to still be there.
        Thread.sleep(forTimeInterval: 4)
        let value = live.value as? String ?? ""
        XCTAssertTrue(value.hasPrefix("collectors=2 "), "one observation after the returns: \(value)")
        XCTAssertFalse(value.hasSuffix("activations=0"), "the scene did leave and come back: \(value)")
        print("[capsule-lifetime] \(value)")
    }
}
