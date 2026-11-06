import XCTest

/// Drives the **photo-picker → upload** path that `SharingClusterUITests` cannot reach.
///
/// ⚠ The picker is an out-of-process remote view: its chrome ("Photos", "Cancel") shows up in the
/// host app's element tree, but the **photo grid does not** — `app.cells` returns the Files list
/// underneath it. So the photo is selected by a **coordinate tap**, the standard escape hatch for
/// un-queryable remote views. That pins these tests to one simulator geometry (iPhone 16), which
/// is why they live in their own suite: it can be skipped without taking the sharing-cluster
/// coverage with it.
///
/// What this buys: the upload conflict + failure alerts (`FilesView`) were the one part of the
/// mutation-feedback work with no automated coverage at all, precisely because the entry point is
/// a system picker.
///
/// ⚠⚠ **Prerequisite: the simulator needs at least one photo.** The grid is invisible to the test,
/// so an empty library does not fail with "no photo found" — the blind tap lands on nothing and
/// the assertions time out. Seed one first:
///
///     xcrun simctl addmedia booted some.png
final class UploadConflictUITests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["DRIVE_DEV_PROVISION_CONTROL"] = "1"
        app.launch()
    }

    /// A photo uploads under the **asset's own filename** (`IMG_0111.jpeg`), not a constant.
    ///
    /// It used to be `photo.jpg` for every photo — `item.itemIdentifier` is nil unless the picker
    /// is built with `photoLibrary: .shared()`, so the name was always the fallback and the second
    /// photo a user ever picked collided with the first.
    func test_a_photo_uploads_under_its_original_name() {
        let uploaded = uploadFirstPhoto()

        XCTAssertNotEqual(uploaded, "photo.jpg", "the name is a constant again")
        XCTAssertTrue(
            uploaded.contains("."),
            "expected a real filename with an extension, got \(uploaded)"
        )
    }

    /// Uploading the same photo twice: the second lands on a taken name, and the app says so and
    /// offers *Keep both* — which then lands the numbered copy.
    func test_uploading_the_same_photo_twice_reports_the_conflict() {
        let uploaded = uploadFirstPhoto()

        pickFirstPhoto()

        // The alert is the app's own, so it IS queryable — only the picker was opaque.
        let alert = app.alerts["File already exists"]
        XCTAssertTrue(
            alert.waitForExistence(timeout: 15),
            "a second upload of the same name did not report the conflict"
        )
        // It names the file, which is only meaningful now that the name is the photo's own.
        XCTAssertTrue(
            alert.staticTexts.allElementsBoundByIndex.contains { $0.label.contains(uploaded) },
            "the alert did not name \(uploaded)"
        )
        XCTAssertTrue(alert.buttons["Keep both"].exists, "Keep both was not offered")

        alert.buttons["Keep both"].tap()

        XCTAssertTrue(
            app.staticTexts[keepBothName(of: uploaded)].firstMatch.waitForExistence(timeout: 15),
            "Keep both did not land \(keepBothName(of: uploaded))"
        )
    }

    /// Cancelling the conflict leaves the original alone and adds nothing.
    func test_cancelling_the_conflict_adds_nothing() {
        let uploaded = uploadFirstPhoto()

        pickFirstPhoto()
        let alert = app.alerts["File already exists"]
        XCTAssertTrue(alert.waitForExistence(timeout: 15))

        alert.buttons["Cancel"].tap()

        XCTAssertFalse(alert.exists, "the alert stayed up after Cancel")
        XCTAssertFalse(
            app.staticTexts[keepBothName(of: uploaded)].firstMatch.exists,
            "cancelling still produced a copy"
        )
    }

    // MARK: - Helpers

    /// Uploads the first photo and returns the name it landed under. Derived rather than
    /// hardcoded: the name now comes from the asset, so it differs per photo library.
    private func uploadFirstPhoto() -> String {
        let before = Set(app.staticTexts.allElementsBoundByIndex.map { $0.label })
        pickFirstPhoto()

        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            let added = Set(app.staticTexts.allElementsBoundByIndex.map { $0.label })
                .subtracting(before)
                .filter { $0.contains(".") }
            if let name = added.first { return name }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("the photo upload did not land in the listing")
        return ""
    }

    /// `keepBothCandidateName`'s shape: the counter goes before the extension.
    private func keepBothName(of fileName: String) -> String {
        let base = fileName.contains(".")
            ? String(fileName[fileName.startIndex..<fileName.lastIndex(of: ".")!])
            : fileName
        let ext = fileName.contains(".")
            ? String(fileName[fileName.index(after: fileName.lastIndex(of: ".")!)...])
            : ""
        return ext.isEmpty ? "\(base) (1)" : "\(base) (1).\(ext)"
    }

    /// Opens the photo picker and taps the first thumbnail by position (see the class note).
    private func pickFirstPhoto() {
        let addButton = app.buttons["add-file"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 10), "Add menu missing")
        addButton.tap()
        app.buttons["Choose Photo"].tap()

        // The segmented control is proof the remote view is up before we tap into it blind.
        XCTAssertTrue(
            app.buttons["Photos"].waitForExistence(timeout: 10),
            "the photo picker did not present"
        )

        // First cell of the 3-wide grid, below the "Private Access to Photos" banner.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.16, dy: 0.34)).tap()

        // Selecting dismisses the picker (maxSelectionCount: 1); wait for it to go before
        // driving the app again, or the next tap lands on the picker's chrome.
        XCTAssertTrue(
            app.buttons["Photos"].waitForNonExistence(timeout: 10),
            "the picker did not dismiss after selecting a photo"
        )
    }
}
