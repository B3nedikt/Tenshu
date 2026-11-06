import XCTest

/// The month grid's height and month, as the List row it is (`CalendarMonthScreen`): a six-week
/// month shows its last week whole and above the day's first row, a five-week month after it leaves
/// no week-sized gap, and the grid keeps the month the title names when its row is rebuilt.
///
/// ⚠⚠ **Needs a provisioned simulator** — Calendar shows the set-up screen otherwise (ADR 0022).
/// Run it on one `scripts/store-screenshots.sh calendar --keep-sims` has just walked (provisioned
/// and seeded), or provision one with Control's onboarding test first. With `STORE_FIXTURE_DIR`
/// in the runner's environment (`TEST_RUNNER_STORE_FIXTURE_DIR`) it seeds that fixture first, the
/// screenshot walk's `-seedFixture` launch; without it, it reads whatever the realm holds — the
/// assertions are about layout, and the row beneath the grid is an event or "Nothing on this day".
///
/// ⚠ Which months have six weeks depends on the simulator's first weekday, so the test works them
/// out from `Calendar.current` (the app's own calendar) rather than naming them: November 2026 with
/// a Monday start, August 2026 / January 2027 with a Sunday one.
final class MonthGridUITests: XCTestCase {

    private var app: XCUIApplication!
    private let calendar = Calendar.current

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        XCUIDevice.shared.orientation = .portrait
        app.launchEnvironment = ProcessInfo.processInfo.environment.filter { $0.key.hasPrefix("TENSHU_") }
        if let fixture = ProcessInfo.processInfo.environment["STORE_FIXTURE_DIR"] {
            app.launchArguments = ["-seedFixture", fixture]
            app.launch()
            let result = app.staticTexts["seed-result"]
            XCTAssertTrue(result.waitForExistence(timeout: 30), "the seed view did not appear")
            expectation(for: NSPredicate(format: "label BEGINSWITH 'seed: ' "), evaluatedWith: result)
            waitForExpectations(timeout: 180)
            XCTAssertTrue(result.label.hasPrefix("seed: OK"), "seeding failed: \(result.label)")
            app.terminate()
        }
        app.launchArguments = []
        app.launch()
        // The grid, or the shared set-up screen an unprovisioned Calendar shows instead (ADR 0022):
        // the second is this simulator's state, not a failure of what is tested here.
        let setUp = app.staticTexts["Set Up Tenshu Control"].firstMatch
        let deadline = Date().addingTimeInterval(60)
        while Date() < deadline, !nextMonthButton.exists, !setUp.exists {
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        if !nextMonthButton.exists, setUp.exists {
            throw XCTSkip("Calendar is not provisioned on this simulator — run Control's onboarding first (this class's doc)")
        }
        XCTAssertTrue(nextMonthButton.exists, "neither the month grid nor the set-up screen appeared in 60 s")
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    // MARK: - the tests

    func testASixWeekMonthShowsItsLastWeekAndAFiveWeekMonthLeavesNoGap() throws {
        let today = month(of: Date())
        let six = try XCTUnwrap(nearestMonth(from: today, weeks: 6), "no six-week month within two years")
        // Back towards today: the month before a six-week one is the usual five-week one.
        let five = try XCTUnwrap(nearestMonth(from: six, weeks: 5, step: -1), "no five-week month within two years")

        page(to: six, from: today)
        let sixGap = assertLastWeekWhole(of: six)
        attach("six-week-\(key(six))")

        page(to: five, from: six)
        let fiveGap = assertLastWeekWhole(of: five)
        attach("five-week-\(key(five))")
        // ⚠ A row that kept the six-week height would leave a whole empty week under the five-week
        // month — about one day cell. Less than half of one is the grid's own bottom margin.
        let cell = lastWeekButtons(of: five).first?.frame.height ?? 44
        XCTAssertLessThan(fiveGap, cell / 2,
                          "a week-sized gap under \(key(five)) (\(fiveGap) pt; the six-week \(key(six)) left \(sixGap) pt)")
    }

    func testTheGridKeepsThePagedMonthWhenItsRowIsRebuilt() throws {
        let today = month(of: Date())
        let six = try XCTUnwrap(nearestMonth(from: today, weeks: 6), "no six-week month within two years")
        page(to: six, from: today)

        // Another tab and back, then a rotation: each can rebuild the List's first row, and a
        // rebuilt `UICalendarView` opened on today's month under the paged month's title.
        app.shellTab(1).tap()
        app.shellTab(0).tap()
        XCTAssertTrue(nextMonthButton.waitForExistence(timeout: 10))
        assertShowing(six, "after switching tabs")

        XCUIDevice.shared.orientation = .landscapeLeft
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        XCUIDevice.shared.orientation = .portrait
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        assertShowing(six, "after a rotation")
        _ = assertLastWeekWhole(of: six)

        // Today moves the MODEL; the grid must follow it, not stay on the paged month.
        app.navigationBars.buttons["Today"].firstMatch.tap()
        assertShowing(today, "after Today")
    }

    func testTodayComesBackFromMonthsAwayAndAfterAThereAndBack() throws {
        let today = month(of: Date())

        // Five months out, then Today: one jump, not a walk through four months that each page
        // the model on the way (an animated multi-month move did).
        let far = month(of: calendar.date(byAdding: .month, value: 5, to: date(today))!)
        page(to: far, from: today)
        app.navigationBars.buttons["Today"].firstMatch.tap()
        assertShowing(today, "after Today from five months out")
        RunLoop.current.run(until: Date().addingTimeInterval(3))
        assertShowing(today, "three seconds after Today (nothing dragged it away)")

        // A quick there-and-back (next, previous, next, no waiting), then Today: no page the
        // model never settled on may swallow it.
        let next = month(of: calendar.date(byAdding: .month, value: 1, to: date(today))!)
        nextMonthButton.tap()
        previousMonthButton.tap()
        nextMonthButton.tap()
        assertShowing(next, "after next, previous, next")
        RunLoop.current.run(until: Date().addingTimeInterval(2))
        app.navigationBars.buttons["Today"].firstMatch.tap()
        assertShowing(today, "after Today from a there-and-back")
    }

    // MARK: - paging

    private var nextMonthButton: XCUIElement { app.buttons["DatePicker.NextMonth"].firstMatch }
    private var previousMonthButton: XCUIElement { app.buttons["DatePicker.PreviousMonth"].firstMatch }
    /// `UICalendarView`'s month title: "Show year picker", value "August 2026". ⚠ Not the header's
    /// "Month" label: the tab bar's Month tab answers to that too.
    private var monthHeader: XCUIElement { app.buttons["DatePicker.Show"].firstMatch }

    /// By the grid's own arrows, one month at a time, waiting for each page to settle.
    private func page(to target: DateComponents, from start: DateComponents) {
        var current = start
        while key(current) != key(target) {
            let forward = key(current) < key(target)
            (forward ? nextMonthButton : previousMonthButton).tap()
            current = calendar.dateComponents(
                [.year, .month],
                from: calendar.date(byAdding: .month, value: forward ? 1 : -1, to: date(current))!
            )
            assertShowing(current, "paging to \(key(target))")
        }
        RunLoop.current.run(until: Date().addingTimeInterval(1))
    }

    /// The grid's header AND the navigation title name [month].
    private func assertShowing(_ month: DateComponents, _ when: String) {
        let name = title(month)
        let header = NSPredicate(format: "value == %@", name)
        expectation(for: header, evaluatedWith: monthHeader)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.navigationBars[name].waitForExistence(timeout: 10),
                      "the grid shows \(name) \(when), but the title does not")
    }

    // MARK: - the last week

    /// Asserts the last week's day cells are on screen, hittable, inside the grid's row and above
    /// the first thing beneath it; returns the gap between them and that thing.
    @discardableResult
    private func assertLastWeekWhole(of month: DateComponents, file: StaticString = #filePath, line: UInt = #line) -> CGFloat {
        let days = lastWeekButtons(of: month)
        XCTAssertFalse(days.isEmpty, "no day cells for the last week of \(key(month))\n\(app.debugDescription)",
                       file: file, line: line)
        let grid = gridRow.frame
        let below = topOfRowBelowGrid()
        var lowest: CGFloat = 0
        for day in days {
            XCTAssertTrue(day.isHittable, "\(day.label) is not hittable", file: file, line: line)
            XCTAssertLessThanOrEqual(day.frame.maxY, grid.maxY + 0.5,
                                     "\(day.label) ends below the grid's row (\(day.frame) vs \(grid))", file: file, line: line)
            XCTAssertLessThanOrEqual(day.frame.maxY, below + 0.5,
                                     "\(day.label) runs under the row beneath the grid (\(day.frame.maxY) > \(below))",
                                     file: file, line: line)
            lowest = max(lowest, day.frame.maxY)
        }
        return below - lowest
    }

    /// The day cells of [month]'s last week, by their accessibility labels ("Monday, 31. August").
    private func lastWeekButtons(of month: DateComponents) -> [XCUIElement] {
        let first = date(month)
        let lastDay = calendar.range(of: .day, in: .month, for: first)!.count
        let last = calendar.date(byAdding: .day, value: lastDay - 1, to: first)!
        let weekStart = calendar.dateInterval(of: .weekOfMonth, for: last)!.start
        let firstDay = weekStart < first ? 1 : calendar.component(.day, from: weekStart)
        let monthName = calendar.monthSymbols[month.month! - 1]
        let buttons = gridRow.collectionViews.firstMatch.buttons
        return (firstDay...lastDay).compactMap { day in
            let predicate = NSPredicate(
                format: "label CONTAINS %@ AND (label CONTAINS %@ OR label CONTAINS %@ OR label ENDSWITH %@)",
                monthName, " \(day).", " \(day),", " \(day)"
            )
            let match = buttons.matching(predicate).allElementsBoundByIndex.first {
                $0.frame.intersects(gridRow.frame)
            }
            return match
        }
    }

    /// The List row holding the grid: the cell with the grid's own arrows in it.
    private var gridRow: XCUIElement {
        app.cells.containing(.button, identifier: "DatePicker.NextMonth").firstMatch
    }

    /// The top of whatever the List draws first under the grid: the day's section header, its
    /// first event, "Nothing on this day" — or the screen's bottom if none is on screen.
    private func topOfRowBelowGrid() -> CGFloat {
        let grid = gridRow.frame
        var candidates: [CGRect] = []
        let heading = app.staticTexts["selected-day-heading"].firstMatch
        if heading.exists { candidates.append(heading.frame) }
        let nothing = app.staticTexts["Nothing on this day"].firstMatch
        if nothing.exists { candidates.append(nothing.frame) }
        let second = app.cells.element(boundBy: 1)
        if second.exists { candidates.append(second.frame) }
        let tops = candidates.map(\.minY).filter { $0 >= grid.minY + 1 }
        return tops.min() ?? app.windows.firstMatch.frame.maxY
    }

    // MARK: - months

    private func month(of day: Date) -> DateComponents { calendar.dateComponents([.year, .month], from: day) }

    private func date(_ month: DateComponents) -> Date {
        calendar.date(from: DateComponents(year: month.year, month: month.month, day: 1))!
    }

    private func key(_ month: DateComponents) -> Int { month.year! * 100 + month.month! }

    private func weeks(in month: DateComponents) -> Int {
        calendar.range(of: .weekOfMonth, in: .month, for: date(month))!.count
    }

    /// The first month from [start] (exclusive) with [wanted] rows, forward (`step` 1) or back.
    private func nearestMonth(from start: DateComponents, weeks wanted: Int, step: Int = 1) -> DateComponents? {
        for offset in 1...24 {
            let candidate = month(of: calendar.date(byAdding: .month, value: offset * step, to: date(start))!)
            if weeks(in: candidate) == wanted { return candidate }
        }
        return nil
    }

    /// "August 2026" — what the grid's header and the navigation title both say (English run).
    private func title(_ month: DateComponents) -> String {
        "\(calendar.monthSymbols[month.month! - 1]) \(month.year!)"
    }

    private func attach(_ name: String) {
        // Lets the paged month's events load and its dots draw, so the picture shows the month.
        RunLoop.current.run(until: Date().addingTimeInterval(5))
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }
}
