import XCTest

/// The Contacts store screenshots (`docs/how-to/store-screenshots.md` §3c). Run only through
/// `scripts/store-screenshots.sh contacts`, which owns the simulator, the status bar, Control's
/// genesis on the same simulator, the fixture (`store/fixtures/contacts/generate_contacts.py`) and
/// the export.
///
/// ⚠⚠ **What makes these screens genuine.** The scheme builds the shipping app (`Debug`, no
/// `DEV_HARNESS`, so never the `-contactsScreen` probes or their mock), over the route-1 runtime and
/// the realm Control created. The one dev hook is `-seedFixture`, a separate launch that imports the
/// fixture's vCards through the shared repository's `importVcards` and renders nothing captured.
/// Every capture comes from the next launch, which has no arguments, and every screen is reached
/// by taps — the search is typed, the name is entered through *Edit*.
///
/// Attachment names are the output file names (`NN-<screen>`), listed in
/// `store/contacts/screens.json`. A new capture takes the next free number, so existing ones never
/// shift.
final class ContactsScreenshots: XCTestCase {

    private var app: XCUIApplication!

    /// The contact whose detail is captured: two numbers, two email addresses, a home address, a note.
    private let detailContact = "Amara Okafor"
    /// An organisation three people share — the search shows it finds more than names.
    private let searchQuery = "Lumen"
    /// The name the user gives themselves on *You*.
    private let ownName = "Alex Rivera"

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
    }

    func testStoreScreenshots() throws {
        let fixture = try XCTUnwrap(
            ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"],
            "STORE_FIXTURE_DIR is unset — run this through scripts/store-screenshots.sh"
        )
        let devLab = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        app.launchEnvironment = devLab

        // 1. Seed. Data only; nothing on this screen is captured.
        app.launchArguments = ["-seedFixture", fixture]
        app.launch()
        let result = app.staticTexts[l: "seed-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
        expectation(for: NSPredicate(format: "label BEGINSWITH 'seed: ' "), evaluatedWith: result)
        waitForExpectations(timeout: 300)
        XCTAssertTrue(result.label.hasPrefix("seed: OK"), "seeding failed: \(result.label)")
        app.terminate()

        // 2. The shipping launch: no arguments.
        app.launchArguments = []
        app.launch()
        let firstRow = app.buttons.containing(.staticText, identifier: "Aiko Tanaka").firstMatch
        XCTAssertTrue(firstRow.waitForExistence(timeout: 90), "the imported contacts never reached the list")
        let duplicates = app.buttons.matching(ShotStrings.labelEnds("possible duplicates")).firstMatch
        XCTAssertTrue(duplicates.waitForExistence(timeout: 30), "the duplicate pairs were not found")
        settle(seconds: 4)

        // 01 — the list.
        shot("01-contacts")

        // 02 — one contact.
        let contactRow = app.buttons.containing(.staticText, identifier: detailContact).firstMatch
        XCTAssertTrue(contactRow.waitForExistence(timeout: 10), detailContact)
        contactRow.tap()
        // ⚠ By its navigation title, not the "Contact info" header: a list section header's
        // accessibility label is not guaranteed to keep the case it was written in.
        XCTAssertTrue(app.navigationBars[l: "Contact"].firstMatch.waitForExistence(timeout: 20),
                      "the contact's detail; on screen: \(app.debugDescription)")
        XCTAssertTrue(app.staticTexts[l: "Riverside Community Clinic"].firstMatch.waitForExistence(timeout: 20),
                      "the contact's details; on screen: \(app.debugDescription)")
        settle(seconds: 2)
        shot("02-contact")
        app.navigationBars.buttons.firstMatch.tap() // back

        // 03 — search, typed, with the keyboard put away by its own Search key.
        let search = app.searchFields[l: "Search your contacts"].firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10), "the search field")
        search.tap()
        search.typeText(searchQuery)
        let notAMatch = app.staticTexts[l: "Amara Okafor"].firstMatch
        let until = Date().addingTimeInterval(10)
        while notAMatch.exists && Date() < until { settle(seconds: 0.5) }
        XCTAssertFalse(notAMatch.exists, "the search filtered nothing")
        XCTAssertTrue(app.staticTexts[l: "Grace Liu"].firstMatch.waitForExistence(timeout: 5),
                      "the search lost a match; on screen: \(app.debugDescription)")
        let searchKey = app.keyboards.buttons.matching(NSPredicate(format: "label IN[c] %@", ShotStrings.both("search"))).firstMatch
        if searchKey.exists { searchKey.tap() }
        // ⚠ Wait for the keyboard to be gone before the capture, not a fixed delay.
        // ⚠ On the iPad the large title sits flush against the screen edge in every searching
        // state — typing, and 10 s after the Search key alike (iPadOS 27 simulator, 2026-10-24):
        // that is iPadOS's searching layout, not a transition, so it is captured as it is
        // (store-screenshots.md §3e).
        let keyboardGone = Date().addingTimeInterval(10)
        while app.keyboards.firstMatch.exists && Date() < keyboardGone { settle(seconds: 0.5) }
        settle(seconds: 2)
        shot("03-search")
        // Out of the search as a user does. ⚠ iOS 26+ draws no "Cancel" beside a field whose
        // keyboard has gone, only the field's own clear (x) button — so clear, then close if offered.
        let clear = search.buttons.firstMatch
        if clear.exists { clear.tap() }
        for label in ["Cancel", "Close"] where app.buttons[l: label].firstMatch.exists {
            app.buttons[l: label].firstMatch.tap()
            break
        }

        // 04 — possible duplicates, 05 — the merge question for the first pair (answered Cancel).
        XCTAssertTrue(duplicates.waitForExistence(timeout: 10),
                      "the duplicates row after the search; on screen: \(app.debugDescription)")
        duplicates.tap()
        let pair = app.buttons.containing(NSPredicate(format: "label CONTAINS 'Priya R.'")).firstMatch
        XCTAssertTrue(pair.waitForExistence(timeout: 20), "the duplicate pairs")
        settle(seconds: 2)
        shot("04-duplicates")
        pair.tap()
        let keep = app.buttons[any: ShotStrings.format("Keep \"%@\"", "Priya Raman")].firstMatch
        XCTAssertTrue(keep.waitForExistence(timeout: 10), "the merge question")
        settle(seconds: 2)
        shot("05-merge")
        // ⚠ On iOS 26+ the dialog is a popover from the row with no Cancel button: a user dismisses
        // it by tapping outside, which is what this does when there is no Cancel.
        let cancelMerge = app.buttons[l: "Cancel"].firstMatch
        let dismissRegion = app.otherElements[l: "PopoverDismissRegion"].firstMatch
        if cancelMerge.exists {
            cancelMerge.tap()
        } else if dismissRegion.exists {
            dismissRegion.tap()
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75)).tap()
        }
        XCTAssertTrue(keep.waitForNonExistence(timeout: 10), "the merge question stayed up")
        settle(seconds: 1)

        // 06 — You, after giving yourself a name through Edit.
        tab(1).tap()
        let edit = app.buttons[l: "Edit"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 20), "You")
        edit.tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the name field")
        field.tap()
        if let current = field.value as? String, !current.isEmpty, current != "Name" {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
        }
        field.typeText(ownName)
        app.alerts.buttons[l: "Save"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts[l: ownName].firstMatch.waitForExistence(timeout: 20), "the saved name")
        settle(seconds: 2)
        shot("06-you")
    }

    /// A shell tab, by its position in the app's `AppDestination` list (`ContactsIosApp`: 0 Contacts, 1 You).
    ///
    /// By index, in every placement (iPhone tab bar, iPad top pill, sidebar): the shared `shellTab`
    /// (`ios-shell/UITestSupport/ShellTabs.swift` says why a title or `tabBars` lookup does not do).
    /// Resolved when called, so call it once the shell is up.
    private func tab(_ index: Int) -> XCUIElement {
        app.shellTab(index)
    }

    private func settle(seconds: TimeInterval = 2) {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
    }

    private func shot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
