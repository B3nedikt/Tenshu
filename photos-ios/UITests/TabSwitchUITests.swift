import XCTest

/// What a return to the Collections tab costs the main thread, on a 5 000-photograph library
/// (photos-ios.md §8.22) — scripted and measured by `TabSwitchProbe`, read here once it is done.
///
/// ⚠⚠ The bound is the main thread's own CPU time, not a wall clock: it does not move with the load
/// on the machine. The switch cost 2 700 ms while every photograph resolved the device's time zone
/// for itself, and 1 850 ms while the six reads (300 ms each here) ran on the main thread; it costs
/// 50–250 ms without either, and 1 000 ms is between the two with room on both sides.
final class TabSwitchUITests: XCTestCase {

    func testAReturnToCollectionsDoesNotHoldTheMainThread() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        // ⚠⚠ `-photosHeldReads`: the mock answers from memory, so reads on the main thread cost
        // nothing without it — which is how a 1.65 s hold on a phone measured 65 ms here.
        app.launchArguments = ["-photosFixture", "-photosArrivals", "-photosLibrarySize", "5000", "-photosHeldReads", "300",
                               "-photosTabSwitch", "4"]
        app.launch()

        let result = app.descendants(matching: .any)["tab-switch-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 30), "the probe")
        expectation(for: NSPredicate(format: "value BEGINSWITH %@", "collections="), evaluatedWith: result)
        waitForExpectations(timeout: 120)

        let value = result.value as? String ?? ""
        var medians: [String: Int] = [:]
        for field in value.split(separator: " ") {
            let parts = field.split(separator: "=")
            if parts.count == 2, let ms = Int(parts[1]) { medians[String(parts[0])] = ms }
        }
        print("[tab-switch] \(value)")
        let collections = try XCTUnwrap(medians["collections"], "unreadable: \(value)")
        let library = try XCTUnwrap(medians["library"], "unreadable: \(value)")
        XCTAssertLessThan(collections, 1_000, "main-thread CPU, in ms, per switch to Collections: \(value)")
        XCTAssertLessThan(library, 1_000, "and per switch back to the Library: \(value)")
    }
}
