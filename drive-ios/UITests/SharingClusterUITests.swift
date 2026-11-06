import XCTest

/// XCUITest coverage of the Drive-on-iOS **sharing cluster** SwiftUI — the layer the Kotlin
/// `iosSimulatorArm64Test` cannot reach (it exercises view models + fakes, never the views).
/// These tests navigate the real screens over the fakes and assert the controls render + the
/// sheets present, catching runtime SwiftUI breakage (broken bindings, crash-on-present) that
/// neither the VM test nor `xcodebuild` would surface.
///
/// The app runs in its DEV_HARNESS config; `DRIVE_DEV_PROVISION_CONTROL=1` provisions the stub
/// Control session at launch so the app boots straight into `PairedAppView` (Files + Shared) over
/// the fakes — no real Control, relay, or camera (those stay an on-device maintainer pass).
///
/// Screenshots are attached with `.deleteOnSuccess` lifetime: they live only in the `.xcresult`
/// (a gitignored build artifact) and only survive a failing run. Nothing is committed.
final class SharingClusterUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["DRIVE_DEV_PROVISION_CONTROL"] = "1"
        app.launch()
    }

    /// The Shared destination renders: both tabs of the segmented control + the Redeem toolbar entry.
    func test_shared_destination_renders() {
        openSharedTab()

        XCTAssertTrue(app.buttons["My Shares"].waitForExistence(timeout: 5), "My Shares segment missing")
        XCTAssertTrue(app.buttons["Shared with Me"].exists, "Shared with Me segment missing")
        XCTAssertTrue(app.buttons["shared-redeem"].exists, "Redeem-invite toolbar button missing")

        attach(name: "shared-destination")
    }

    /// Redeem sheet: the EnterLink stage renders (scan + paste + Continue) and a malformed link
    /// drives the VM through to the typed parse error — proving the view ↔ VM wiring end to end.
    func test_redeem_sheet_surfaces_a_parse_error() {
        openSharedTab()
        app.buttons["shared-redeem"].tap()

        XCTAssertTrue(app.buttons["redeem-scan"].waitForExistence(timeout: 5), "Scan button missing")
        let field = app.textFields["redeem-link-field"]
        XCTAssertTrue(field.exists, "Paste field missing")

        field.tap()
        field.typeText("not-a-link")
        app.buttons["redeem-continue"].tap()

        XCTAssertTrue(
            app.staticTexts["redeem-paste-error"].waitForExistence(timeout: 5),
            "Malformed link did not surface the parse error"
        )
        attach(name: "redeem-parse-error")
    }

    /// Invite-someone: Files → a folder → Manage Access → Invite someone → Generate renders a QR
    /// (the headline `CIQRCodeGenerator` path) plus the copyable link, over the fake's demo invite.
    func test_invite_someone_generates_a_qr() {
        // Default tab is App → Files. Open the seeded "Photos" folder (a non-root folder has the
        // Manage Access toolbar entry). List rows surface as static text, not buttons — tapping the
        // name activates the row's NavigationLink.
        let photos = app.staticTexts["Photos"].firstMatch
        XCTAssertTrue(photos.waitForExistence(timeout: 5), "Seeded Photos folder missing")
        photos.tap()

        app.buttons["manage-access"].tap()
        let invite = app.buttons["manage-invite-someone"]
        XCTAssertTrue(invite.waitForExistence(timeout: 5), "Invite-someone entry missing")
        invite.tap()

        let generate = app.buttons["invite-generate"]
        XCTAssertTrue(generate.waitForExistence(timeout: 5), "Generate-invite button missing")
        generate.tap()

        XCTAssertTrue(
            app.images["Invite QR code"].waitForExistence(timeout: 5),
            "Generating an invite did not render the QR image"
        )
        XCTAssertTrue(app.buttons["Copy Link"].exists, "Copy Link button missing after generate")
        XCTAssertTrue(app.buttons["Share"].exists, "Share button missing after generate")
        attach(name: "invite-qr")
    }

    /// Share-link: Files → a file → Share Link → Generate renders the URL with **both** the
    /// system-share hand-off and Copy Link. The pairing is the point — handing the link to a
    /// messenger and putting it on the clipboard are different intents, so neither may replace
    /// the other (`drive-shared-ui/.../ShareText.kt`).
    func test_share_link_offers_both_share_and_copy() {
        // A row tap opens the preview (P4), so Share Link is reached through the row's
        // overflow menu. The identifier is shared by every row; the label is not.
        let overflow = app.buttons
            .matching(NSPredicate(format: "label == %@", "Actions for budget.pdf"))
            .firstMatch
        XCTAssertTrue(overflow.waitForExistence(timeout: 5), "Seeded file's overflow missing")
        overflow.tap()

        let shareLink = app.buttons["Share Link"]
        XCTAssertTrue(shareLink.waitForExistence(timeout: 5), "Share Link entry missing")
        shareLink.tap()

        let generate = app.buttons["Generate Link"]
        XCTAssertTrue(generate.waitForExistence(timeout: 5), "Generate-link button missing")
        generate.tap()

        let share = app.buttons["Share"]
        XCTAssertTrue(share.waitForExistence(timeout: 5), "Share button missing after generate")
        XCTAssertTrue(app.buttons["Copy Link"].exists, "Copy Link button missing after generate")
        attach(name: "share-link-actions")
    }

    /// Deleting from the detail screen lands back on the **folder**, not on the deleted file's
    /// preview. The file chain is two hops (row → preview → ⓘ detail), so the `dismiss()` this
    /// screen used to call popped one of them and left the user staring at a preview that then
    /// reported "Couldn't load this file" about the file they had just deleted on purpose.
    func test_delete_from_detail_returns_to_the_folder() {
        let file = app.staticTexts["budget.pdf"].firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5), "Seeded file missing")
        file.tap()

        let info = app.buttons["file-info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5), "Preview's ⓘ toolbar button missing")
        info.tap()

        let delete = app.buttons["Delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "Delete action missing on the detail screen")
        delete.tap()

        // Root-only toolbar entry ⇒ proves BOTH hops unwound. Asserting the preview's ⓘ is gone
        // is the direct regression check: pre-fix it was still on screen.
        XCTAssertTrue(
            app.buttons["settings"].waitForExistence(timeout: 5),
            "Delete did not return to the Files root"
        )
        XCTAssertFalse(info.exists, "Delete left the user on the deleted file's preview")
        attach(name: "delete-returns-to-folder")
    }

    /// Renaming from the detail screen keeps the user on the file and re-addresses the chain to
    /// its new path. A rename is a `MoveFile`, so the old path dies with it: before the re-key,
    /// the detail screen and the preview beneath it both went on addressing an entry that no
    /// longer existed — the next action on either would have missed.
    func test_rename_from_detail_retargets_the_chain() {
        let file = app.staticTexts["budget.pdf"].firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5), "Seeded file missing")
        file.tap()

        let info = app.buttons["file-info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5), "Preview's ⓘ toolbar button missing")
        info.tap()

        app.buttons["Rename"].tap()
        let field = app.textFields["Name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Rename field missing")
        field.tap()
        field.typeText(".2026")   // budget.pdf → budget.pdf.2026
        app.buttons["Rename"].firstMatch.tap()

        // The detail screen is still up (a rename is not a departure) and now titled by the new
        // name — the chain was rebuilt on the new path rather than left on the dead one.
        XCTAssertTrue(
            app.navigationBars["budget.pdf.2026"].waitForExistence(timeout: 5),
            "Detail screen did not re-address itself to the renamed file"
        )
        XCTAssertTrue(app.buttons["Delete"].exists, "Detail actions missing after rename")

        // …and the hop beneath it moved too: going back lands on the renamed file's preview.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(
            app.navigationBars["budget.pdf.2026"].waitForExistence(timeout: 5),
            "Preview beneath the detail screen kept the pre-rename path"
        )
        attach(name: "rename-retargets-chain")
    }

    /// A refused rename says so and keeps the sheet up. `MoveFile` rejects a target the parent
    /// already holds, and the sheet used to dismiss on the tap — so a collision looked exactly
    /// like a rename that worked: the sheet closed and the file kept its name.
    func test_rename_onto_an_existing_name_is_reported() {
        let file = app.staticTexts["notes.txt"].firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5), "Seeded file missing")
        file.tap()

        let info = app.buttons["file-info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5), "Preview's ⓘ toolbar button missing")
        info.tap()

        app.buttons["Rename"].tap()
        let field = app.textFields["Name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Rename field missing")
        field.tap()
        // Clear, then take a name the root already holds (the seeded Photos folder).
        field.press(forDuration: 1.0)
        app.menuItems["Select All"].tap()
        field.typeText("Photos")
        app.buttons["Rename"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["rename-error"].waitForExistence(timeout: 5),
            "A refused rename was not reported"
        )
        XCTAssertTrue(field.exists, "The sheet closed on a refusal — indistinguishable from success")
        attach(name: "rename-refused")

        // Backing out leaves the file exactly as it was, under its original name.
        app.buttons["Cancel"].tap()
        XCTAssertTrue(
            app.navigationBars["notes.txt"].waitForExistence(timeout: 5),
            "The refused rename did not leave the file on its original path"
        )
    }

    /// A `/` is path structure, not text: joined to the parent it would turn the rename into a
    /// move, into a folder that need not exist. Rejected as typed, with its own message.
    func test_rename_rejects_a_name_containing_a_separator() {
        let file = app.staticTexts["sunset.jpg"].firstMatch
        XCTAssertTrue(file.waitForExistence(timeout: 5), "Seeded file missing")
        file.tap()

        let info = app.buttons["file-info"]
        XCTAssertTrue(info.waitForExistence(timeout: 5), "Preview's ⓘ toolbar button missing")
        info.tap()

        app.buttons["Rename"].tap()
        let field = app.textFields["Name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Rename field missing")
        field.tap()
        field.press(forDuration: 1.0)
        app.menuItems["Select All"].tap()
        field.typeText("sub/sunset.jpg")

        XCTAssertTrue(
            app.staticTexts["rename-error"].waitForExistence(timeout: 5),
            "A slash in the name was not reported"
        )
        XCTAssertFalse(
            app.buttons["Rename"].firstMatch.isEnabled,
            "A name containing a slash was still submittable"
        )
        attach(name: "rename-separator-rejected")
    }

    /// New Folder onto a name the folder already holds: reported, and offering the same *Keep
    /// both* remedy the Compose dialog has. iOS previously printed the view model's own English
    /// string and offered no remedy at all, so a collision here was a dead end.
    func test_create_folder_onto_an_existing_name_offers_keep_both() {
        let add = app.buttons["add-file"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Add menu missing")
        add.tap()
        app.buttons["New Folder"].tap()

        let field = app.textFields["Folder name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Folder-name field missing")
        field.tap()
        field.typeText("Photos")   // the seeded root folder
        app.buttons["Create"].tap()

        XCTAssertTrue(
            app.staticTexts["create-folder-error"].waitForExistence(timeout: 5),
            "A refused create was not reported"
        )
        let keepBoth = app.buttons["create-folder-keep-both"]
        XCTAssertTrue(keepBoth.exists, "Keep both was not offered for a name collision")
        attach(name: "create-folder-collision")

        keepBoth.tap()

        // Keep both lands "Photos (1)" and closes the sheet.
        XCTAssertTrue(
            app.staticTexts["Photos (1)"].firstMatch.waitForExistence(timeout: 5),
            "Keep both did not create the numbered folder"
        )
    }

    /// A `/` in a folder name is path structure: it would nest the new folder under a parent that
    /// need not exist. Rejected as typed, with its own message.
    func test_create_folder_rejects_a_name_containing_a_separator() {
        let add = app.buttons["add-file"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Add menu missing")
        add.tap()
        app.buttons["New Folder"].tap()

        let field = app.textFields["Folder name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Folder-name field missing")
        field.tap()
        field.typeText("a/b")

        XCTAssertTrue(
            app.staticTexts["create-folder-error"].waitForExistence(timeout: 5),
            "A slash in the folder name was not reported"
        )
        XCTAssertFalse(
            app.buttons["Create"].firstMatch.isEnabled,
            "A folder name containing a slash was still submittable"
        )
        attach(name: "create-folder-separator-rejected")
    }

    // MARK: - Helpers

    /// Switch to the inner Files/Shared TabView's "Shared" tab. "Shared" is unique to that inner
    /// tab bar (the outer harness tabs are App/Pair).
    private func openSharedTab() {
        let shared = app.buttons["Shared"]
        XCTAssertTrue(shared.waitForExistence(timeout: 10), "Shared tab missing — app did not reach PairedAppView")
        shared.tap()
    }

    /// Attach the current screen as a debugging aid that is discarded on success.
    private func attach(name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .deleteOnSuccess
        add(shot)
    }
}
