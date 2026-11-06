import XCTest

/// The sync-activity capsule in Drive (`ios-sync-activity-indicator.md` §6a), over the dev-stub
/// session with the capsule's input scripted by `-syncCapsuleScript` (`SyncActivityScript`, shared
/// by every app that mounts the capsule). No relay and no real upload is involved: the script
/// replaces the INPUT, and the rule, the debounce and the copy under it are the shipping ones.
///
/// ```
/// xcodebuild test -project drive-ios/DriveIos.xcodeproj -scheme DriveIos-UITests \
///   -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
///   -only-testing:DriveIosUITests/SyncCapsuleUITests
/// ```
///
/// ⚠ The script's steps are read from its whole record once it is done, not caught one by one: an
/// accessibility query takes about a second, and a step that is current when it starts may not be
/// when it returns.
final class SyncCapsuleUITests: XCTestCase {

    private var app: XCUIApplication!

    private func launch(script: String) {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["DRIVE_DEV_PROVISION_CONTROL"] = "1"
        app.launchArguments = ["-syncCapsuleScript", script]
        app.launch()
    }

    private var capsule: XCUIElement { app.descendants(matching: .any)["sync-activity-capsule"] }
    private var stepProbe: XCUIElement { app.descendants(matching: .any)["sync-script-step"] }
    private var recordProbe: XCUIElement { app.descendants(matching: .any)["sync-script-record"] }

    private func waitForStep(_ name: String, timeout: TimeInterval) {
        XCTAssertTrue(stepProbe.waitForExistence(timeout: 30), "the script's readout")
        expectation(for: NSPredicate(format: "value == %@", name), evaluatedWith: stepProbe)
        waitForExpectations(timeout: timeout)
    }

    /// The finished script's record, `name=value` joined by `|`.
    private func record() -> [String] {
        (recordProbe.value as? String ?? "").split(separator: "|").map(String.init)
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// A run with a count, its tail with none, and a pull that outlasts both: uploading wins while
    /// there is anything to send, and hands over to the pull without the capsule going away.
    func testARun_itsTail_thenThePull_thenNothing() {
        launch(script: "upload")

        waitForStep("tail", timeout: 60)
        XCTAssertTrue(capsule.waitForExistence(timeout: 2), "transfers in flight keep the capsule up")
        // ⚠ No count in what VoiceOver reads: the element updates often.
        XCTAssertEqual(capsule.label, "Uploading to your other devices")
        attach("capsule-tail")

        waitForStep("done", timeout: 60)
        XCTAssertEqual(record(), [
            "idle=-",
            "run-1=Uploading 1 of 3",
            "run-2-with-pull=Uploading 2 of 3",       // a pull under a run changes nothing
            "tail=Uploading…",                        // the run is done, its chunks are not
            "handover-at-once=Syncing…",              // 400 ms later: no idle, no show delay
            "pull-after-upload=Syncing…",
            "idle-after=-",
        ])
        XCTAssertFalse(capsule.exists, "nothing is held once everything has ended")
    }

    /// The capsule sits above the tab bar, in every tab, from one observation.
    func testTheCapsuleIsAboveTheTabBar_inBothTabs() {
        launch(script: "upload-hold")

        XCTAssertTrue(capsule.waitForExistence(timeout: 30), "a counting run shows the capsule")
        let filesTab = app.tabBars.buttons["Files"]
        XCTAssertTrue(filesTab.waitForExistence(timeout: 5))
        XCTAssertLessThanOrEqual(capsule.frame.maxY, filesTab.frame.minY, "clear of the tab bar")
        attach("capsule-files")

        app.tabBars.buttons["Shared"].tap()
        // ⚠ At once: the observation is the root's, so a tab that is built now draws what is
        // already known. A mount per tab would start again and sit out the 700 ms show delay.
        XCTAssertTrue(capsule.waitForExistence(timeout: 0.5), "the other tab draws it without a new wait")
        XCTAssertLessThanOrEqual(capsule.frame.maxY, app.tabBars.buttons["Shared"].frame.minY)
        attach("capsule-shared")
    }

    /// A storage banner that is discarded takes its Kotlin collectors with it.
    ///
    /// ⚠⚠ This is the assertion that fails on the model's old shape: it started two detached tasks
    /// behind a `started` flag, nothing cancelled them, and the count stayed two higher after the
    /// banner was gone.
    func testADiscardedStorageBanner_leavesNoCollectorBehind() {
        launch(script: "lifetime")

        waitForStep("done", timeout: 60)
        let values = Dictionary(uniqueKeysWithValues: record().compactMap { entry -> (String, String)? in
            let parts = entry.split(separator: "=", maxSplits: 1).map(String.init)
            return parts.count == 2 ? (parts[0], parts[1]) : nil
        })
        func storage(_ step: String) -> Int? {
            values[step]?.split(separator: ",").first?.split(separator: ":").last.flatMap { Int($0) }
        }
        // Drive's own banner (text and tone) plus the capsule's one reading of the banner's text.
        XCTAssertEqual(storage("mounted"), 3, "one banner and one capsule observation")
        XCTAssertEqual(storage("banner-added"), 5, "a second banner observes too")
        XCTAssertEqual(storage("banner-removed"), 3, "and stops when it is discarded")
        // One capsule observation holds two collectors of the scripted input, however many tabs.
        XCTAssertEqual(values["mounted"]?.hasSuffix("activity:2"), true, "one observation for the whole app")
    }
}
