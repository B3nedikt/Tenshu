import XCTest

/// The photo editor (`photos-editing.md` ED-1), driven by real taps on the `-photosFixture` library:
/// the viewer's bottom bar opens it, ✕ over an edit asks first (ED-D6), *Save as Copy* (⋯) opens the
/// copy in a viewer (ED-D1), and *Save* records the edit on the photo itself and returns to its
/// viewer, where ⋯ offers *Revert to Original* (ED-3). The screenshots it keeps are the PR's.
final class EditorUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-photosFixture"]
        app.launch()
    }

    private func attach(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    private func openEditorOnThree() {
        let tile = app.descendants(matching: .any)["IMG_10003.jpg"]
        XCTAssertTrue(tile.waitForExistence(timeout: 15), "the grid shows #3")
        tile.tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 15))
        // ED-D7: Edit · Add to · Bin, and no Share.
        XCTAssertTrue(app.buttons["Add to"].exists, "Add to is in the bar")
        XCTAssertTrue(app.buttons["Bin"].exists, "Bin is in the bar")
        XCTAssertFalse(app.buttons["Share"].exists, "no Share slot until Share exists")
        attach("viewer-bottom-bar")
        app.buttons["Edit"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["editorCanvas"].waitForExistence(timeout: 15), "the editor shows the photo")
    }

    func testCloseAsksOverAnEdit_andSaveOpensTheCopy() {
        openEditorOnThree()
        let save = app.buttons["editorSave"]
        XCTAssertFalse(save.isEnabled, "nothing to save yet")
        attach("editor-open")

        app.buttons["Rotate"].tap()
        XCTAssertTrue(save.isEnabled)
        app.buttons["Square"].tap()
        attach("editor-rotated-square")

        app.buttons["Straighten"].tap()
        let dial = app.descendants(matching: .any)["Straighten"].firstMatch
        let canvas = app.descendants(matching: .any)["editorCanvas"]
        XCTAssertTrue(canvas.exists)
        let strip = app.windows.firstMatch
        strip.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.86))
            .press(forDuration: 0.05, thenDragTo: strip.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.86)))
        _ = dial
        attach("editor-straighten")

        app.buttons["Perspective"].tap()
        attach("editor-perspective")

        // ✕ over unsaved changes asks; keeping them keeps them.
        app.buttons["Close Editor"].tap()
        XCTAssertTrue(app.staticTexts["Discard your edits?"].waitForExistence(timeout: 5))
        attach("editor-discard-confirm")
        app.buttons["Keep Editing"].tap()
        XCTAssertTrue(save.isEnabled, "still edited")

        // ED-D11: *Save as Copy* is in ⋯ now.
        app.buttons["editorMore"].tap()
        app.buttons["editorSaveCopy"].tap()
        // The copy opens in a viewer; the editor is gone.
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 20), "the copy is shown")
        XCTAssertFalse(app.descendants(matching: .any)["editorCanvas"].exists)
        attach("copy-in-viewer")
    }

    /// ED-2: a slider and a look, saved — the copy opens in a viewer.
    func testAdjustAndFilter_thenSave() {
        openEditorOnThree()
        let save = app.buttons["editorSave"]
        app.buttons["editorTab-adjust"].tap()
        app.buttons["editorAdjust-contrast"].tap()
        let dial = app.descendants(matching: .any)["Contrast"].firstMatch
        XCTAssertTrue(dial.waitForExistence(timeout: 5), "the dial opens")
        dial.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: dial.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.5)))
        XCTAssertTrue(save.isEnabled, "a slider alone is an edit")
        attach("editor-adjust-contrast")
        app.buttons["editorAdjustDone"].tap()
        XCTAssertTrue(app.buttons["editorAdjust-vignette"].waitForExistence(timeout: 5), "Done goes back to the list")

        app.buttons["editorTab-filter"].tap()
        app.buttons["editorLook-ember"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["Intensity"].waitForExistence(timeout: 5), "a look offers its intensity")
        attach("editor-filter-ember")

        save.tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 20), "back on the photo")
        attach("edit-adjusted-in-viewer")
    }

    /// ED-4: pen, redact and a text label through the Markup tab, undo, then save — the copy opens.
    func testMarkup_penRedactAndText_thenSave() {
        openEditorOnThree()
        let save = app.buttons["editorSave"]
        app.buttons["editorTab-markup"].tap()
        let canvas = app.descendants(matching: .any)["editorCanvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 5))

        // Pen, green, thick: one stroke across the photo.
        app.buttons["editorMarkup-pen"].tap()
        app.buttons["editorInk-green"].tap()
        app.buttons["Thick"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.4))
            .press(forDuration: 0.05, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)))
        XCTAssertTrue(save.isEnabled, "a stroke alone is an edit")
        XCTAssertTrue(app.buttons["Undo"].isEnabled)

        // Undo takes it away; redo brings it back.
        app.buttons["Undo"].tap()
        XCTAssertFalse(save.isEnabled, "all of it undone is nothing to save")
        app.buttons["Redo"].tap()
        XCTAssertTrue(save.isEnabled)

        // Highlighter over it.
        app.buttons["editorMarkup-highlighter"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.2, dy: 0.6))
            .press(forDuration: 0.05, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.6)))

        // Redact: a box.
        app.buttons["editorMarkup-redact"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.25))
            .press(forDuration: 0.05, thenDragTo: canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.38)))

        // Text: tap, type, Done.
        app.buttons["editorMarkup-text"].tap()
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.7)).tap()
        // ⚠ An alert's text field does not carry the SwiftUI identifier: found through its alert.
        let alert = app.alerts["Add Text"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5), "a tap opens the label's field")
        // The simulator's first-keyboard tip, if it is up, would swallow the typing.
        if app.buttons["Continue"].exists { app.buttons["Continue"].tap() }
        alert.textFields.firstMatch.typeText("Tenshu")
        alert.buttons["Done"].tap()
        XCTAssertFalse(alert.waitForExistence(timeout: 2))
        // Committed: a tap on the label opens it again, as "Edit Text", with its text.
        canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.7)).tap()
        let edit = app.alerts["Edit Text"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5), "the label was committed and is tappable")
        XCTAssertEqual(edit.textFields.firstMatch.value as? String, "Tenshu")
        edit.buttons["Cancel"].tap()
        attach("editor-markup")

        // ED-3: *Save* — the photo itself, back in its viewer, now edited.
        save.tap()
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 20), "back on the photo")
        attach("edit-marked-up-in-viewer")
        app.buttons["More"].tap()
        let revert = app.buttons["viewerRevertEdit"]
        XCTAssertTrue(revert.waitForExistence(timeout: 5), "an edited photo offers Revert to Original")
        revert.tap()
        XCTAssertTrue(app.alerts["Revert to Original?"].waitForExistence(timeout: 5))
        app.alerts["Revert to Original?"].buttons["Revert"].firstMatch.tap()
        app.buttons["More"].tap()
        XCTAssertFalse(app.buttons["viewerRevertEdit"].waitForExistence(timeout: 2), "reverted: nothing to revert")
    }

    func testAnUntouchedEditClosesWithoutAsking() {
        openEditorOnThree()
        app.buttons["Close Editor"].tap()
        XCTAssertFalse(app.staticTexts["Discard your edits?"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.descendants(matching: .any)["viewerPhoto"].waitForExistence(timeout: 5), "back on the viewer")
    }
}
