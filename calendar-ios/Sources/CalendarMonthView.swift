import SwiftUI
import TenshuSdk
import UIKit

/// ⚠⚠ The shared layer's civil dates (`LocalDate`, and the year/month/day parts this app passes
/// around) are ISO, so every conversion to or from a `Date` goes through this Gregorian calendar —
/// `Calendar.current` under a Buddhist, Japanese or Islamic system calendar reads them as a different
/// day. It carries the user's locale, time zone and first weekday, so the month grid still lays out
/// and names its days the way the user expects; pickers present the resulting `Date` in the user's
/// own calendar regardless. Computed, so each new use sees the current time zone and locale (a grid
/// already on screen keeps the calendar it was built with).
var isoCalendar: Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.locale = isoLocale
    calendar.timeZone = TimeZone.current
    calendar.firstWeekday = Calendar.current.firstWeekday
    return calendar
}

/// The user's locale with its calendar keyword set to Gregorian. ⚠ `Locale.current` itself can carry
/// `@calendar=buddhist`, and a grid on a Gregorian calendar under that locale titles its month
/// "November 2026 AD" — the era shown because it is not the locale's own.
var isoLocale: Locale {
    var components = Locale.Components(locale: Locale.current)
    components.calendar = .gregorian
    return Locale(components: components)
}

/// The month grid, over UIKit's `UICalendarView` (CA-8b, Q7 ✅).
///
/// ⭐⭐ **The one place iOS gets a whole view for free.** `calendar-app.md` §5.4 sized the month
/// grid as a component that has to be hand-rolled on every platform — twice, since no Compose UI
/// ships here. `UICalendarView` (iOS 16+, which every Tenshu shell already pins) is a real month
/// grid with correct locale handling, a first weekday that follows the system, VoiceOver, Dynamic
/// Type and the platform's own paging. What is written here is the *binding*, not the grid.
///
/// ⚠ It arrives through `UIViewRepresentable` because there is no SwiftUI equivalent — the
/// SwiftUI `DatePicker(.graphical)` looks similar and is not the same thing: it has no per-day
/// decorations, which is the entire point of a calendar's month view.
///
/// ⚠⚠ Every decision this file could get wrong is somewhere else: which days carry a dot, what
/// colour it is, which days need redrawing, and how ARGB unpacks are all `CalendarMonthDots`, in
/// tested `commonMain`. No Swift here compiles in this repo's environment, so what is left is
/// UIKit plumbing and two re-entrancy guards.
struct CalendarMonthView: UIViewRepresentable {
    /// Every day that carries a marker, from the shared `CalendarMonthDots`.
    let dots: [CalendarDayDot]
    /// The selected day, as a `CalendarDayDot`-style key (`20260809`). `nil` clears the selection.
    let selectedKey: Int?
    /// The month the view model is on, as `year * 100 + month` (`202610`); `nil` before the first
    /// emission. Read only when the grid is BUILT — see `makeUIView`.
    let monthKey: Int?
    /// The last explicit "show this month" request (Today). The only thing that moves an existing
    /// grid — see `updateUIView` for why the model's month alone does not.
    let jump: MonthJump?
    /// The user tapped a day: year, month, day.
    let onSelect: (Int, Int, Int) -> Void
    /// The grid settled on a month: year, month.
    let onMonthChange: (Int, Int) -> Void

    func makeUIView(context: Context) -> UICalendarView {
        let view = UICalendarView()
        // ⚠⚠ ISO, not `Calendar.current`: the grid's DateComponents ARE the shared model's civil
        // dates (selection, dots, paging), so under a Buddhist system calendar a `.current` grid
        // handed the model year 2569. `isoCalendar` keeps the user's locale and first weekday.
        view.calendar = isoCalendar
        view.locale = isoLocale
        view.delegate = context.coordinator
        let selection = UICalendarSelectionSingleDate(delegate: context.coordinator)
        view.selectionBehavior = selection
        context.coordinator.selection = selection
        // ⚠⚠ A fresh view opens on TODAY's month, whatever the model says. The List recycles its
        // rows, so this runs again for a grid the user had paged — and without this line the grid
        // snapped back to today under a title still naming the paged month. Not animated: this is
        // a view being built, not a user moving through months. ⚠ The jump is marked consumed
        // FIRST, as in `updateUIView`: the model's month already includes it, and the assignment
        // below can call the delegate back.
        context.coordinator.lastJump = jump?.id
        if let monthKey {
            view.visibleDateComponents = CalendarMonthView.monthComponents(monthKey)
        }
        // ⚠ The month screen sizes the grid to its natural height as the first row of the day's
        // list (`CalendarMonthScreen`), so it never competes with the list for height; a host
        // that gives it less must clip, because the grid keeps its row height and drops weeks.
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UICalendarView, context: Context) {
        let coordinator = context.coordinator
        let previous = coordinator.dots
        coordinator.dots = dots
        coordinator.index = Dictionary(dots.map { (Int($0.key), $0) }, uniquingKeysWith: { first, _ in first })
        coordinator.onSelect = onSelect
        coordinator.onMonthChange = onMonthChange

        // ⚠ Redraw only the days that actually changed — `reloadDecorations` re-asks the delegate
        // for every date handed to it, so passing the whole visible range on each state emission
        // makes a scroll stutter. The diff itself is `CalendarMonthDots.changedKeys`, which is
        // where its one interesting property (a removal counts too) is tested.
        let changed = CalendarMonthDots.shared.changedKeys(previous: previous, current: dots)
        if !changed.isEmpty {
            view.reloadDecorations(
                forDateComponents: changed.map { CalendarMonthView.components(Int(truncating: $0)) },
                animated: false
            )
        }

        // ⚠⚠ An existing grid moves on an explicit JUMP (Today), never because the model's month
        // differs from it. The model's month is mostly the grid's own report coming back: while
        // the user pages, an emission can still carry an earlier month (two quick pages, or a
        // there-and-back), and following it dragged the grid back mid-gesture. Telling those
        // echoes apart from a real move by bookkeeping went wrong at the edges (a there-and-back
        // left a stale entry that later swallowed Today), so the one model-side move says so.
        if let jump, jump.id != coordinator.lastJump {
            coordinator.lastJump = jump.id
            let visible = CalendarMonthView.monthKey(view.visibleDateComponents)
            if visible != jump.monthKey {
                // ⚠ Animated for a neighbouring month only. Animating across several months walks
                // through them, and each one the delegate reports would page the model too.
                let adjacent = visible.map { abs(CalendarMonthView.monthIndex($0) - CalendarMonthView.monthIndex(jump.monthKey)) == 1 } ?? false
                view.setVisibleDateComponents(CalendarMonthView.monthComponents(jump.monthKey), animated: adjacent)
            }
        }

        // ⚠⚠ Guarded on a real difference: assigning the selection unconditionally re-enters the
        // delegate, which calls the view model, which emits — a loop. Its twin is on the Kotlin
        // side, in `CalendarViewModel.showMonth`, and for the same reason.
        if let selection = coordinator.selection {
            let current = selection.selectedDate.flatMap(CalendarMonthView.key)
            if current != selectedKey {
                selection.setSelected(selectedKey.map(CalendarMonthView.components), animated: false)
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    /// The grid's height is the height `UICalendarView` lays itself out at for the width offered,
    /// asked again on every layout pass.
    ///
    /// ⚠⚠ Not a constant and not a guess at weeks × row height. On iOS 27 the view keeps one height
    /// for a given width and re-spaces its rows (a six-week month is tighter, a five-week one
    /// looser); earlier releases were reported to grow and shrink with the week count instead.
    /// Either way the List row is re-measured here: the month is one of this view's inputs, so
    /// every page is a new layout pass, and its answer is UIKit's own. An unconstrained height
    /// (`fixedSize`) never reaches this as a real proposal — only the width matters.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UICalendarView, context: Context) -> CGSize? {
        guard let width = proposal.width, width.isFinite, width > 0 else { return nil }
        let fitted = uiView.systemLayoutSizeFitting(
            CGSize(width: width, height: UIView.layoutFittingCompressedSize.height),
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        return CGSize(width: width, height: ceil(fitted.height))
    }

    // MARK: - key ⇄ DateComponents

    /// `20260809` → `DateComponents(year: 2026, month: 8, day: 9)`.
    ///
    /// ⚠ The same packing `CalendarDayDot.key` uses. It is arithmetic rather than a date so both
    /// sides can build it without a calendar, and so the delegate — which must answer
    /// synchronously, once per visible cell — does a dictionary lookup.
    static func components(_ key: Int) -> DateComponents {
        DateComponents(calendar: isoCalendar, year: key / 10_000, month: (key / 100) % 100, day: key % 100)
    }

    /// `202610` → the first of October 2026, in the grid's calendar.
    static func monthComponents(_ monthKey: Int) -> DateComponents {
        DateComponents(calendar: isoCalendar, year: monthKey / 100, month: monthKey % 100, day: 1)
    }

    /// `202610` → months since year 0, so two keys subtract to a month distance.
    static func monthIndex(_ monthKey: Int) -> Int { (monthKey / 100) * 12 + monthKey % 100 - 1 }

    /// The month a `visibleDateComponents` names, as `year * 100 + month`.
    static func monthKey(_ components: DateComponents) -> Int? {
        guard let year = components.year, let month = components.month else { return nil }
        return year * 100 + month
    }

    static func key(_ components: DateComponents) -> Int? {
        guard let year = components.year, let month = components.month, let day = components.day else {
            return nil
        }
        return year * 10_000 + month * 100 + day
    }

    final class Coordinator: NSObject, UICalendarViewDelegate, UICalendarSelectionSingleDateDelegate {
        var dots: [CalendarDayDot] = []
        var index: [Int: CalendarDayDot] = [:]
        var onSelect: (Int, Int, Int) -> Void = { _, _, _ in }
        var onMonthChange: (Int, Int) -> Void = { _, _ in }
        weak var selection: UICalendarSelectionSingleDate?
        /// The id of the last `MonthJump` this grid has applied (or was built with).
        var lastJump: Int?

        func calendarView(
            _ calendarView: UICalendarView,
            decorationFor dateComponents: DateComponents
        ) -> UICalendarView.Decoration? {
            guard
                let key = CalendarMonthView.key(dateComponents),
                let dot = index[key]
            else { return nil }
            // ⚠ One dot, in the calendar's colour — `CalendarMonthDots` records why a day mixing
            // calendars shows only its earliest event's colour, and why that is the cost of the
            // single decoration slot rather than an oversight.
            return .default(color: dot.uiColor, size: .small)
        }

        func calendarView(
            _ calendarView: UICalendarView,
            didChangeVisibleDateComponentsFrom previousDateComponents: DateComponents
        ) {
            let visible = calendarView.visibleDateComponents
            guard let year = visible.year, let month = visible.month else { return }
            onMonthChange(year, month)
        }

        func dateSelection(
            _ selection: UICalendarSelectionSingleDate,
            didSelectDate dateComponents: DateComponents?
        ) {
            guard
                let dateComponents,
                let year = dateComponents.year,
                let month = dateComponents.month,
                let day = dateComponents.day
            else { return }
            onSelect(year, month, day)
        }
    }
}

/// "Show this month" from outside the grid — today's month, from the Today button. `id` grows with
/// every request, so pressing Today twice is two requests even when the month is the same.
struct MonthJump: Equatable {
    let id: Int
    let monthKey: Int
}

extension CalendarDayDot {
    /// ⚠ The channels come pre-unpacked from Kotlin. The stored colour is ARGB — what Android's
    /// `Color` packs, and therefore what the schema carries — while UIKit wants the alpha last, so
    /// a consumer has to shift. That shift is `CalendarDayDot`'s, where it is tested; this is only
    /// the assembly.
    var uiColor: UIColor {
        UIColor(
            red: CGFloat(red) / 255.0,
            green: CGFloat(green) / 255.0,
            blue: CGFloat(blue) / 255.0,
            alpha: CGFloat(alpha) / 255.0
        )
    }
}
