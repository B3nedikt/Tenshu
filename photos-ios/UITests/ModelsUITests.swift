import XCTest

/// Settings → Models (ios-on-device-inference.md §14.18.15) over the `-photosModels <state>` fixture:
/// the shared view model and the real on-device repository, over a source the test moves. No model,
/// no network. ⚠ Run by hand, as the other files of this target are (M10): no gate runs it.
///
/// ⚠⚠ The fake moves only when `models-probe-advance` is pressed. A download or a cancel that was
/// asked for is in `models-probe-calls` and changes no row, which is what these cases rest on.
final class ModelsUITests: XCTestCase {

    private var app: XCUIApplication!
    private let model = "siglip-base-patch16-256-multilingual"

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ state: String?, _ more: [String] = []) {
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"] + (state.map { ["-photosModels", $0] } ?? []) + more
        app.launch()
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Library → the toolbar's Settings button → Models.
    private func openModels() {
        let settings = app.buttons["library-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 20), "the Library's Settings button")
        XCTAssertEqual(settings.label, "Settings")
        settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10), "Settings")
        element("settings-models").tap()
        XCTAssertTrue(app.navigationBars["Models"].waitForExistence(timeout: 10), "Settings → Models")
    }

    /// The identifiers of the action buttons on screen, top to bottom, without the model's id.
    private func actions() -> [String] {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'models-action-'")).allElementsBoundByIndex
            .map { $0.identifier.replacingOccurrences(of: "models-action-", with: "").replacingOccurrences(of: "-\(model)", with: "") }
    }

    private func action(_ kind: String) -> XCUIElement { app.buttons["models-action-\(kind)-\(model)"] }

    private func calls() -> String { (element("models-probe-calls").value as? String) ?? "<no probe>" }

    private func advance() { app.buttons["models-probe-advance"].tap() }

    private func wait(_ what: String, timeout: TimeInterval = 10, _ condition: @escaping () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
        XCTAssertTrue(condition(), what)
    }

    private func row() -> XCUIElement { element("models-row-\(model)") }

    // MARK: - The flag

    /// ⚠ Settings is in every build (it holds the privacy-policy row); only Models is flagged.
    func testWithoutTheFlagAndWithoutTheFixtureSettingsHasNoModelsRow() {
        launch(nil)
        XCTAssertTrue(app.tabBars.buttons["Library"].waitForExistence(timeout: 20), "the Library")
        let settings = app.buttons["library-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "the Library's Settings button")
        settings.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10), "Settings")
        XCTAssertTrue(app.descendants(matching: .any)["Privacy policy"].firstMatch.waitForExistence(timeout: 10), "the privacy-policy link")
        XCTAssertFalse(element("settings-models").exists, "a Models row in a build without on-device AI")
    }

    // MARK: - Every main state: its sentence, and exactly its actions

    private struct Expected {
        let state: String
        /// Sentences of the notice (no list), each of which must be on screen.
        var notice: [String] = []
        /// Sentences of the model's row.
        var row: [String] = []
        var actions: [String] = []
        var tryAgain = false
        /// The feature row's status, or `nil` where no feature row may be drawn.
        var feature: String? = "Off for this iPhone"
        var labels: [String: String] = [:]
    }

    private let table: [Expected] = [
        Expected(state: "loading", notice: ["Loading…"]),
        Expected(state: "notConnected", notice: ["Photos can't reach its library right now."], tryAgain: true),
        Expected(state: "installedUnreadable", notice: ["Couldn't read what's installed on this iPhone."], tryAgain: true),
        Expected(state: "catalogUnavailable", notice: ["The list of models couldn't be loaded."], tryAgain: true),
        Expected(state: "nothingPublished", notice: ["No model is available for this iPhone yet."]),
        Expected(state: "notOfferedOs", notice: ["Search by description isn't available on this iPhone.", "It needs iOS 17 or later."], feature: nil),
        Expected(state: "notOfferedMemory", notice: ["Search by description isn't available on this iPhone.", "This iPhone doesn't have enough memory for the model."], feature: nil),
        Expected(state: "absent", row: ["SigLIP base (multilingual)", "743 MB download", "about 2.6 GB on this iPhone", "Apache-2.0"],
                 actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"], labels: ["DOWNLOAD": "Download", "DOWNLOAD_WITH_MOBILE_DATA": "Use mobile data"]),
        Expected(state: "needsSpace", row: ["Needs 3.4 GB free. This iPhone has 1.2 GB."], actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"]),
        Expected(state: "downloadFailed", row: ["The download didn't finish."], actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"], labels: ["DOWNLOAD": "Try again"]),
        // ⚠ Not worded as a retry: asking again fetches the same file.
        Expected(state: "integrityFailed", row: ["The download didn't match what was published. Trying again will fetch the same file."],
                 actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"], labels: ["DOWNLOAD": "Download"]),
        Expected(state: "fetching", row: ["372 MB of 743 MB", "You can leave Photos; the download continues."], actions: ["CANCEL"]),
        Expected(state: "fetchingNoWifi", row: ["Waiting for Wi-Fi.", "To use mobile data, cancel and start again."], actions: ["CANCEL"]),
        Expected(state: "pausedForSpace", row: ["Paused. Needs 3.4 GB free; this iPhone has 1.2 GB."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "journalUnreadable", notice: ["Couldn't read the download's record."], row: ["Ready"], actions: ["REMOVE"], tryAgain: true),
        Expected(state: "preparing", row: ["Preparing… Keep Photos open. About ten seconds."], actions: ["REMOVE"]),
        Expected(state: "prepareFailed", row: ["Couldn't prepare the model.", "Needs 3.4 GB free. This iPhone has 1.2 GB."], actions: ["TRY_AGAIN", "REMOVE"]),
        Expected(state: "incomplete", row: ["Part of the model is missing."], actions: ["DOWNLOAD", "REMOVE"], labels: ["DOWNLOAD": "Download again"]),
        Expected(state: "checking", row: ["Checking this iPhone…"], actions: ["REMOVE"]),
        Expected(state: "checkPaused", row: ["Checking paused. Photos will try again the next time you open it.", "iPhone is warm."], actions: ["REMOVE"]),
        // ⚠ M12: the way out is the removal, and no download is offered.
        Expected(state: "installFailed", row: ["The model didn't work on this iPhone after it was installed. Remove it and download it again."], actions: ["REMOVE"]),
        Expected(state: "ready", row: ["Ready", "about 2.6 GB on this iPhone"], actions: ["REMOVE"]),
        Expected(state: "readyOn", row: ["Ready"], actions: ["REMOVE"], feature: "On for this iPhone"),
        Expected(state: "updateAvailable", row: ["Ready", "Update available · 766 MB"], actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA", "REMOVE"],
                 labels: ["DOWNLOAD": "Update", "DOWNLOAD_WITH_MOBILE_DATA": "Update using mobile data"]),
        Expected(state: "updating", row: ["Ready", "213 MB of 743 MB"], actions: ["CANCEL", "REMOVE"]),
        // ⚠ No download of the bytes this iPhone could not run.
        Expected(state: "unsuitable", row: ["This iPhone couldn't run the model well enough, so search by description stays off."], actions: ["REMOVE"]),
        // The sub-rows of §14.18.3.
        Expected(state: "freeSpaceUnknown", row: ["Couldn't check the free space."], actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"]),
        Expected(state: "pausedFreeSpaceUnknown", row: ["Paused. Photos couldn't check the free space."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "pausedNoTransport", row: ["Paused. Photos couldn't continue the download just now."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "pausedTransfersUnknown", row: ["Paused. Photos couldn't tell what is still downloading."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "pausedStoreRefused", row: ["Paused. Photos couldn't save the model."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "fetchFailed", row: ["The download didn't finish."], actions: ["TRY_AGAIN", "CANCEL"]),
        Expected(state: "preparingUnreadable", row: ["Couldn't read the model's files."], actions: ["TRY_AGAIN", "REMOVE"]),
        Expected(state: "checkRetried", row: ["Checking this iPhone…"], actions: ["REMOVE"]),
        Expected(state: "noProbe", row: ["This version of Photos can't check this model. Update Photos."], actions: ["REMOVE"]),
        Expected(state: "checkUnreadable", row: ["Couldn't read the result of the check."], actions: ["TRY_AGAIN", "REMOVE"]),
        Expected(state: "catalogFailedReady", row: ["Ready"], actions: ["REMOVE"]),
        // §14.5 item 0: loads Photos did not survive have a way out; a download the key would answer is not offered.
        Expected(state: "stoppedLoading", row: ["Photos stopped trying after it closed twice while loading the model on this iPhone."],
                 actions: ["LOAD_AGAIN", "REMOVE"], labels: ["LOAD_AGAIN": "Try again"]),
        Expected(state: "absentUnsuitable", row: ["This iPhone couldn't run the model well enough, so search by description stays off."]),
        Expected(state: "absentStoppedLoading", row: ["Photos stopped trying after it closed twice while loading the model on this iPhone."], actions: ["LOAD_AGAIN"]),
        // A press whose caller stopped waiting: still starting, then what its call answered.
        Expected(state: "stillStarting", row: ["Starting…"]),
        Expected(state: "refusedLate", row: ["The list of models couldn't be loaded."], actions: ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"]),
    ]

    func testEveryMainStateShowsItsSentenceAndExactlyItsActions() {
        for expected in table {
            launch(expected.state)
            openModels()
            let state = expected.state
            if expected.row.isEmpty {
                XCTAssertTrue(element("models-notice").waitForExistence(timeout: 10), "\(state): the notice")
                XCTAssertFalse(row().exists, "\(state): a row with no list")
            } else {
                XCTAssertTrue(row().waitForExistence(timeout: 10), "\(state): the row")
                for sentence in expected.row {
                    XCTAssertTrue(row().label.contains(sentence), "\(state): the row says \"\(row().label)\", not \"\(sentence)\"")
                }
            }
            if !expected.notice.isEmpty {
                let notice = element("models-notice")
                XCTAssertTrue(notice.waitForExistence(timeout: 10), "\(state): the notice")
                for sentence in expected.notice {
                    XCTAssertTrue(notice.label.contains(sentence), "\(state): the notice says \"\(notice.label)\", not \"\(sentence)\"")
                }
            }
            XCTAssertEqual(actions(), expected.actions, "\(state): its actions, in order")
            for (kind, label) in expected.labels { XCTAssertEqual(action(kind).label, label, "\(state): \(kind)") }
            XCTAssertEqual(app.buttons["models-try-again"].exists, expected.tryAgain, "\(state): Try again")
            if expected.tryAgain { XCTAssertEqual(app.buttons["models-try-again"].label, "Try again") }
            let feature = element("models-feature")
            if let status = expected.feature {
                XCTAssertTrue(feature.exists, "\(state): the feature row")
                XCTAssertTrue(feature.label.contains("Search photos by description"), "\(state): \(feature.label)")
                XCTAssertTrue(feature.label.contains(status), "\(state): the feature row says \"\(feature.label)\", not \"\(status)\"")
            } else {
                XCTAssertFalse(feature.exists, "\(state): a switch that changes nothing")
            }
            XCTAssertFalse(element("models-message").exists, "\(state): a message nobody raised")
            XCTAssertEqual(app.alerts.count, 0, "\(state): a question nobody asked")
            attach(state)
            app.terminate()
        }
    }

    // MARK: - Presses

    func testADownloadIsForwardedWithItsChoiceAndTheRowMovesOnlyWhenTheSourceDoes() {
        launch("absent")
        openModels()
        XCTAssertTrue(action("DOWNLOAD").waitForExistence(timeout: 10))
        XCTAssertEqual(calls(), "checkAgain", "the screen's own open reads the list again, and nothing else was asked")

        action("DOWNLOAD").tap()
        wait("the Wi-Fi download was not asked: \(calls())") { self.calls() == "checkAgain · download(\(self.model), wifi)" }
        // ⚠ Accepted, and the fake has not moved: no bar, and the row is as it was.
        wait("the row moved by itself: \(actions())") { self.actions() == ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"] }
        action("DOWNLOAD_WITH_MOBILE_DATA").tap()
        wait("the mobile-data download was not asked: \(calls())") { self.calls().hasSuffix("download(\(self.model), mobile)") }

        advance()
        wait("the download was not shown: \(row().label)") { self.row().label.contains("213 MB of 743 MB") }
        XCTAssertEqual(actions(), ["CANCEL"])
        attach("fetching")
        advance()
        wait("preparing was not shown: \(row().label)") { self.row().label.contains("Preparing…") }
        advance()
        wait("checking was not shown: \(row().label)") { self.row().label.contains("Checking this iPhone…") }
        XCTAssertEqual(app.alerts.count, 0, "asked before the model can do anything")

        // The model has just become usable and the feature is off here: asked once (§38.3).
        advance()
        let offer = app.alerts["Turn on \"Search photos by description\" for this iPhone?"]
        XCTAssertTrue(offer.waitForExistence(timeout: 10), "the feature question")
        XCTAssertTrue(offer.staticTexts["The model is ready. Photos looks through your library when you tap Index now, on this device only."].exists)
        attach("feature-question")
        let before = calls()
        offer.buttons["Not now"].tap()
        wait("the question stayed") { self.app.alerts.count == 0 }
        XCTAssertEqual(calls(), before, "Not now writes nothing")
        XCTAssertTrue(element("models-feature").label.contains("Off for this iPhone"))
        XCTAssertTrue(row().label.contains("Ready"))
    }

    func testYesToTheFeatureQuestionWritesTheRealmThenThisDevice() {
        launch("absent")
        openModels()
        XCTAssertTrue(action("DOWNLOAD").waitForExistence(timeout: 10))
        for _ in 0..<4 { advance() }
        let offer = app.alerts.firstMatch
        XCTAssertTrue(offer.waitForExistence(timeout: 10), "the feature question")
        offer.buttons["Turn on"].tap()
        wait("the consent was not written in order: \(calls())") { self.calls() == "checkAgain · realm(on) · device(on)" }
        wait("the feature row did not move: \(element("models-feature").label)") { self.element("models-feature").label.contains("On for this iPhone") }
        XCTAssertEqual(app.alerts.count, 0)
    }

    func testACancelIsForwardedAndTheRowGoesOnlyWhenTheSourceSaysSo() {
        launch("fetching")
        openModels()
        XCTAssertTrue(action("CANCEL").waitForExistence(timeout: 10))
        action("CANCEL").tap()
        wait("the cancel was not asked: \(calls())") { self.calls() == "checkAgain · cancel(\(self.model))" }
        XCTAssertEqual(actions(), ["CANCEL"], "the row left before the source said the download was gone")
        XCTAssertFalse(element("models-message").exists, "an accepted cancel says nothing")
        advance()
        wait("the row did not return: \(actions())") { self.actions() == ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"] }
    }

    // MARK: - The removal

    func testARemovalAsksFirstAndIsSaidOnceWhenItHasEnded() {
        launch("ready")
        openModels()
        XCTAssertTrue(action("REMOVE").waitForExistence(timeout: 10))
        action("REMOVE").tap()
        let question = app.alerts["Remove SigLIP base (multilingual)?"]
        XCTAssertTrue(question.waitForExistence(timeout: 10), "the removal asks first")
        XCTAssertTrue(question.staticTexts.element(matching: NSPredicate(format: "label CONTAINS 'This frees about 2.6 GB on this iPhone.'")).exists)
        XCTAssertEqual(calls(), "checkAgain", "removed before it was confirmed")
        attach("removal-question")
        question.buttons["Cancel"].tap()
        wait("the question stayed") { self.app.alerts.count == 0 }
        XCTAssertEqual(calls(), "checkAgain")
        XCTAssertEqual(actions(), ["REMOVE"])

        action("REMOVE").tap()
        XCTAssertTrue(question.waitForExistence(timeout: 10))
        question.buttons["Remove"].tap()
        wait("the removal was not asked: \(calls())") { self.calls() == "checkAgain · delete(\(self.model))" }
        wait("the row is not removing: \(row().label)") { self.row().label.contains("Removing…") }
        XCTAssertEqual(actions(), [], "a row being removed offers nothing")
        XCTAssertFalse(element("models-message").exists, "said removed before it was")
        attach("removing")

        advance()
        let message = element("models-message")
        XCTAssertTrue(message.waitForExistence(timeout: 15), "the removal's end is said")
        XCTAssertEqual(message.label, "Removed. The rest of its space is released the next time Photos starts.")
        wait("the row did not go: \(actions())") { self.actions() == ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"] }
        attach("removed")
        app.buttons["models-message-dismiss"].tap()
        wait("the message stayed") { !message.exists }

        // ⚠ Consumed: not said again on a return to the screen.
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 10))
        element("settings-models").tap()
        XCTAssertTrue(row().waitForExistence(timeout: 10))
        XCTAssertFalse(element("models-message").exists, "a consumed message was said again")
        XCTAssertEqual(app.alerts.count, 0)
    }

    /// ⚠⚠ Kotlin's order, not SwiftUI's: a removal asked for comes first, and the feature question
    /// that arrived under it is asked once the removal is answered.
    func testARemovalAskedBeforeTheFeatureQuestionIsAnsweredFirstAndTheQuestionThenComes() {
        launch("removalThenOffer")
        openModels()
        // ⚠ The clock starts when the Models screen is up, and the fixture's own
        // self-advance from the screen's first read of the list: a cold launch is in neither.
        let opened = Date()
        XCTAssertTrue(action("REMOVE").waitForExistence(timeout: 10))
        XCTAssertLessThan(Date().timeIntervalSince(opened), 5, "too slow to ask before the model lands: the case says nothing")
        action("REMOVE").tap()
        let removal = app.alerts["Remove SigLIP base (multilingual)?"]
        XCTAssertTrue(removal.waitForExistence(timeout: 10), "the removal asks first")
        // The model lands under the question (by itself, 8 s after the screen first read the list:
        // the probe's button is under the alert): the feature question is due, and waits.
        RunLoop.current.run(until: opened.addingTimeInterval(11))
        XCTAssertTrue(removal.exists, "the feature question replaced the removal's")
        XCTAssertEqual(app.alerts.count, 1)
        removal.buttons["Cancel"].tap()
        let offer = app.alerts["Turn on \"Search photos by description\" for this iPhone?"]
        XCTAssertTrue(offer.waitForExistence(timeout: 10), "the feature question was not asked after the removal's")
        offer.buttons["Not now"].tap()
        wait("the question stayed") { self.app.alerts.count == 0 }
        XCTAssertFalse(self.calls().contains("delete"), "a removal answered Cancel was made: \(calls())")
    }

    func testTryAgainOnAModelThatStoppedLoadingAsksTheSourceOnceMore() {
        launch("stoppedLoading")
        openModels()
        XCTAssertTrue(action("LOAD_AGAIN").waitForExistence(timeout: 10))
        action("LOAD_AGAIN").tap()
        wait("the source was not asked: \(calls())") { self.calls() == "checkAgain · loadAgain(\(self.model))" }
    }

    func testARemovalThatFailsSaysSoAndTheModelIsStillThere() {
        launch("removeFails")
        openModels()
        XCTAssertTrue(action("REMOVE").waitForExistence(timeout: 10))
        action("REMOVE").tap()
        app.alerts.buttons["Remove"].tap()
        let message = element("models-message")
        XCTAssertTrue(message.waitForExistence(timeout: 15))
        XCTAssertEqual(message.label, "Couldn't remove the model. It is still installed.")
        wait("the row did not come back: \(actions())") { self.actions() == ["REMOVE"] }
        XCTAssertTrue(row().label.contains("Ready"))
    }

    // MARK: - Try again

    func testWhileCheckingAgainTryAgainIsWithheldAndSaidWhy() {
        launch("slowCheck")
        openModels()
        let tryAgain = app.buttons["models-try-again"]
        XCTAssertTrue(tryAgain.waitForExistence(timeout: 10))
        tryAgain.tap()
        let checking = element("models-checking")
        XCTAssertTrue(checking.waitForExistence(timeout: 10), "why there is no Try again")
        XCTAssertTrue(checking.label.contains("Checking again…"), checking.label)
        XCTAssertFalse(tryAgain.exists, "a second Try again beside a check that runs")
        XCTAssertTrue(element("models-notice").exists, "what was shown stays while it is read again")
        attach("checking-again")
        advance()
        wait("the check did not end") { !checking.exists }
        wait("the list was not shown: \(actions())") { self.actions() == ["DOWNLOAD", "DOWNLOAD_WITH_MOBILE_DATA"] }
        XCTAssertTrue(calls().hasSuffix("checkAgain · checkAgain"), calls())
    }

    func testAScreenOpenAcrossAReprovisionIsBuiltAgainOverWhatReplacedItsRuntime() {
        launch("notConnected")
        openModels()
        XCTAssertTrue(app.buttons["models-try-again"].waitForExistence(timeout: 10))
        XCTAssertFalse(row().exists)
        // The runtime the screen was built over is replaced under it.
        app.buttons["models-probe-reprovision"].tap()
        XCTAssertTrue(row().waitForExistence(timeout: 10), "the open screen still shows the replaced runtime")
        XCTAssertTrue(row().label.contains("Ready"))
        XCTAssertFalse(element("models-notice").exists)
    }

    // MARK: - The feature screen

    private func openFeature() {
        openModels()
        let feature = element("models-feature")
        XCTAssertTrue(feature.waitForExistence(timeout: 10))
        feature.tap()
        XCTAssertTrue(app.navigationBars["Search photos by description"].waitForExistence(timeout: 10), "the feature screen")
    }

    private func device(_ key: String) -> XCUIElement { app.switches["feature-device-\(key)"] }

    /// The switch itself: a tap on the row's text does not always reach it.
    private func flip(_ toggle: XCUIElement) { toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap() }

    func testTheFeatureScreenListsTheDevicesAndTurnOffEverywhereAsksFirst() {
        launch("readyOn")
        openFeature()
        XCTAssertTrue(device("this").waitForExistence(timeout: 10))
        XCTAssertTrue(device("this").label.contains("This iPhone"), device("this").label)
        XCTAssertEqual(device("this").value as? String, "1")
        XCTAssertTrue(device("mac").label.contains("Alex's Mac"), device("mac").label)
        XCTAssertEqual(device("mac").value as? String, "1")
        // ⚠ Off only from here: a device that is off has no switch to press.
        XCTAssertEqual(device("pad").value as? String, "0")
        XCTAssertFalse(device("pad").isEnabled)
        XCTAssertTrue(device("pad").label.contains("Off. It is switched on from that device."), device("pad").label)
        attach("feature")

        let everywhere = app.buttons["feature-everywhere"]
        XCTAssertEqual(everywhere.label, "Turn off everywhere")
        everywhere.tap()
        let question = app.alerts["Turn off \"Search photos by description\" everywhere?"]
        XCTAssertTrue(question.waitForExistence(timeout: 10))
        XCTAssertTrue(question.staticTexts["Every device stops indexing, and searching by description stops working."].exists)
        attach("everywhere-question")
        question.buttons["Keep on"].tap()
        wait("the question stayed") { self.app.alerts.count == 0 }
        XCTAssertEqual(calls(), "checkAgain", "Keep on writes nothing")

        everywhere.tap()
        question.buttons["Turn off"].tap()
        // This device first, every other device that holds a yes, the realm last.
        wait("not switched off in order: \(calls())") { self.calls() == "checkAgain · device(off) · clear(mac) · realm(off)" }
        wait("the switches did not move") { (self.device("this").value as? String) == "0" && (self.device("mac").value as? String) == "0" }
        wait("Turn off everywhere stayed with nothing to turn off") { !everywhere.exists }

        flip(device("this"))
        wait("not switched on in order: \(calls())") { self.calls().hasSuffix("realm(on) · device(on)") }
        wait("the switch did not move") { (self.device("this").value as? String) == "1" }
    }

    func testWithARosterThatCannotBeReadThisIPhoneIsAloneAndNothingIsTurnedOffEverywhere() {
        launch("rosterUnreadable")
        openFeature()
        XCTAssertTrue(device("this").waitForExistence(timeout: 10))
        let notice = element("feature-others-notice")
        XCTAssertTrue(notice.waitForExistence(timeout: 10))
        XCTAssertEqual(notice.label, "Couldn't read your other devices, so only this one is shown.")
        XCTAssertFalse(device("mac").exists || device("pad").exists, "a device listed from a roster that could not be read")
        XCTAssertFalse(app.buttons["feature-everywhere"].exists)
        attach("roster-unreadable")
        // Its own switch needs no roster.
        flip(device("this"))
        wait("this device was not switched off: \(calls())") { self.calls() == "checkAgain · device(off)" }
    }

    func testADeviceWithNoIdHasItsSwitchDisabledAndSaysSo() {
        launch("deviceUnknown")
        openFeature()
        XCTAssertTrue(device("this").waitForExistence(timeout: 10))
        XCTAssertFalse(device("this").isEnabled, "a switch that would write under a placeholder id")
        XCTAssertTrue(device("this").label.contains("This iPhone hasn't finished setting up, so the switch can't be saved yet."), device("this").label)
        XCTAssertFalse(app.buttons["feature-everywhere"].exists)
        attach("device-unknown")
    }

    // MARK: - The indexing section (§14.19.6)

    private func indexing() -> XCUIElement { element("models-indexing") }

    /// The indexing section's buttons, top to bottom.
    private func indexActions() -> [String] {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'models-index-'")).allElementsBoundByIndex
            .map { $0.identifier.replacingOccurrences(of: "models-index-", with: "") }
    }

    /// The section scrolled into view: it is under the feature row.
    private func showIndexing(_ state: String) {
        XCTAssertTrue(row().waitForExistence(timeout: 10), "\(state): the row")
        if !indexing().waitForExistence(timeout: 3) { app.swipeUp() }
        XCTAssertTrue(indexing().waitForExistence(timeout: 10), "\(state): the indexing section")
    }

    private struct Indexed {
        let state: String
        let says: [String]
        var actions: [String] = []
    }

    private let indexTable: [Indexed] = [
        Indexed(state: "checking", says: ["You can index your photos once the model is ready."]),
        // The model is ready and the switch is off for every device: no button, the switch named.
        Indexed(state: "indexSwitchOff", says: ["Turn on \"Search photos by description\" above to index your photos.", "It is off for all your devices."]),
        Indexed(state: "indexNotAsked", says: ["1,204 of 1,310 photos on this iPhone can be searched. 106 aren't on this iPhone."],
                actions: ["INDEX_NOW"]),
        Indexed(state: "indexRunning", says: ["Indexing 412 of 1,310", "Keep Photos open. Indexing stops when you leave."], actions: ["PAUSE"]),
        Indexed(state: "indexPaused", says: ["Indexing is paused on this iPhone.", "1,204 of 1,310 photos on this iPhone can be searched."], actions: ["RESUME"]),
        Indexed(state: "indexWarm", says: ["Waiting: this iPhone is warm.", "It goes on by itself once it has cooled down, while Photos stays open."], actions: ["PAUSE"]),
        Indexed(state: "indexLowPower", says: ["Waiting: Low Power Mode is on.", "It goes on by itself when Low Power Mode is off, while Photos stays open."], actions: ["PAUSE"]),
        Indexed(state: "indexBatteryLow", says: ["Waiting: the battery is low.", "It goes on by itself when charging, while Photos stays open."], actions: ["PAUSE"]),
        Indexed(state: "indexLeftPhotos", says: ["Stopped when you left Photos.", "640 photos can be searched."], actions: ["INDEX_NOW"]),
        Indexed(state: "indexLowMemory", says: ["Stopped: iPhone ran low on memory.", "640 photos can be searched."], actions: ["INDEX_NOW"]),
        // ⚠⚠ After an iOS update: why the index is empty, never a bare "0 of N".
        Indexed(state: "indexAfterUpdate", says: ["The index was cleared after an iOS update. Index now to search your photos again."], actions: ["INDEX_NOW"]),
    ]

    func testEveryIndexingStateSaysItsSentencesAndExactlyItsButtons() {
        for expected in indexTable {
            launch(expected.state)
            openModels()
            let state = expected.state
            showIndexing(state)
            for sentence in expected.says {
                XCTAssertTrue(indexing().label.contains(sentence), "\(state): the section says \"\(indexing().label)\", not \"\(sentence)\"")
            }
            XCTAssertFalse(indexing().label.contains(" indexed"), "\(state): the store's count said as indexed: \(indexing().label)")
            XCTAssertEqual(indexActions(), expected.actions, "\(state): its buttons, in order")
            XCTAssertFalse(element("models-message").exists, "\(state): a message nobody raised")
            attach(state)
            app.terminate()
        }
    }

    func testIndexNowIsForwardedAndTheSectionMovesOnlyWhenThePassDoes() {
        launch("indexNotAsked")
        openModels()
        showIndexing("indexNotAsked")
        app.buttons["models-index-INDEX_NOW"].tap()
        wait("Index now was not asked: \(calls())") { self.calls().hasSuffix("indexNow") }
        let message = element("models-message")
        XCTAssertTrue(message.waitForExistence(timeout: 10), "the press was not answered")
        XCTAssertEqual(message.label, "Indexing started. Keep Photos open. Indexing stops when you leave.")
        element("models-message-dismiss").tap()
        // ⚠ Accepted, and the fake has not moved.
        XCTAssertTrue(indexing().label.hasPrefix("1,204 of 1,310 photos"), indexing().label)
        XCTAssertFalse(indexing().label.contains("Not indexing"), indexing().label)

        advance()
        wait("the pass was not shown: \(indexing().label)") { self.indexing().label.contains("Indexing 412 of 1,310") }
        XCTAssertEqual(indexActions(), ["PAUSE"])
        app.buttons["models-index-PAUSE"].tap()
        wait("the pause was not written: \(calls())") { self.calls().hasSuffix("pause(on)") }
        wait("the section did not say paused: \(indexing().label)") { self.indexing().label.contains("Indexing is paused on this iPhone.") }
        XCTAssertEqual(indexActions(), ["RESUME"])
        app.buttons["models-index-RESUME"].tap()
        wait("the resume was not written: \(calls())") { self.calls().hasSuffix("pause(off)") }
    }

    func testAPressThePhoneHoldsBackSaysNoMessageAndTheSectionSaysWhy() {
        launch("indexPressWaits")
        openModels()
        showIndexing("indexPressWaits")
        app.buttons["models-index-INDEX_NOW"].tap()
        wait("Index now was not asked: \(calls())") { self.calls().hasSuffix("indexNow") }
        XCTAssertFalse(element("models-message").waitForExistence(timeout: 2), "a press the phone holds back raised a message")
        advance()
        wait("the wait was not said: \(indexing().label)") { self.indexing().label.contains("Waiting: this iPhone is warm.") }
        XCTAssertEqual(indexActions(), ["PAUSE"], "a pass that goes on by itself is offered again")
    }
}
