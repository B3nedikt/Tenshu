import SwiftUI
import TenshuSdk
import UIKit

/// The month tab: the grid, and the selected day's events beneath it (CA-8b).
///
/// ⚠ The two halves share one view model, and that is what makes "tap the 20th" mean something —
/// `CalendarUiState.selectedDate` is the grid's selection *and* the list's day, deliberately one
/// field (see its KDoc: two would have to be kept in step by hand, and the bug when they drift is
/// silent).
struct CalendarMonthScreen: View {
    @StateObject private var model = CalendarScreenModel(view: .month)
    @State private var creating = false

    var body: some View {
        // ⚠⚠ The grid is the list's FIRST ROW, not a fixed band above it, so the whole month
        // always shows and the day's events scroll up beneath it. It used to sit above the list
        // capped at 380 pt, which cut every month's last week off (on every iPhone and iPad), and
        // a six-week month at its natural ~540 pt would leave a 667 pt iPhone SE no room for the
        // events at all. (store-screenshots.md §3e)
        SelectedDayList(model: model) {
            CalendarMonthView(
                dots: model.dots,
                selectedKey: model.selectedKey,
                monthKey: model.monthKey,
                jump: model.monthJump,
                onSelect: { year, month, day in
                    model.viewModel.selectDay(year: Int32(year), month: Int32(month), day: Int32(day))
                },
                onMonthChange: { year, month in
                    model.viewModel.showMonth(year: Int32(year), month: Int32(month))
                }
            )
            .fixedSize(horizontal: false, vertical: true)
            // ⚠⚠ On an iPad, `UICalendarView` draws the NEIGHBOURING months beside the one it
            // shows. Its month page is ~390 pt wide whatever the view's width, centred; on an
            // iPhone the rest of the width stays blank, but on an iPad (iOS 27 simulator,
            // 2026-10-23) the previous and next months' day columns fill it — at 1032 pt three
            // months of day numbers sat in one row under one month's title and weekday header,
            // and capped at 440 pt their outer digits still showed, cut, in the ~25 pt side
            // margins (store-screenshots.md §3e). Keyed on the DEVICE, not the size class: a
            // compact-width trait override changed nothing, so it is the iPad idiom that draws
            // them — in a Slide Over, Split View or Stage Manager window too — and a landscape
            // iPhone never does.
            .modifier(MonthGridWidth(pad: UIDevice.current.userInterfaceIdiom == .pad))
        }
        .navigationTitle(model.monthTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                // ⚠ Present even when today is already visible. It is the standard affordance in
                // every calendar app, and hiding it would make the toolbar shift as you scroll.
                Button("Today") { model.goToToday() }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { creating = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New event")
            }
        }
        .sheet(isPresented: $creating, onDismiss: { model.viewModel.refresh() }) {
            NavigationStack {
                // ⚠⚠ Creates on the **selected** day, not on today. Tapping the 20th and then `+`
                // means "something on the 20th"; opening on today would silently discard the one
                // thing the user had already told us.
                EventEditorScreen(
                    startYear: model.selectedParts.year,
                    startMonth: model.selectedParts.month,
                    startDay: model.selectedParts.day
                ) { creating = false }
            }
        }
        .task { await model.observe() }
    }
}

/// What is on the selected day.
private struct SelectedDayList<Grid: View>: View {
    @ObservedObject var model: CalendarScreenModel
    /// The month grid, drawn as the list's first row (see `CalendarMonthScreen.body`).
    @ViewBuilder var grid: () -> Grid

    var body: some View {
        List {
            grid()
                .frame(maxWidth: .infinity)
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
            if model.state == nil {
                ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden)
            } else if model.selectedDayOccurrences.isEmpty {
                // ⚠ Says nothing about Control. An empty day and an unreachable proxy are
                // different situations; the banner above owns the second.
                Text("Nothing on this day")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .listRowSeparator(.hidden)
            } else {
                Section {
                    ForEach(model.selectedDayOccurrences, id: \.recurrenceId) { occurrence in
                        NavigationLink {
                            EventDetailView(occurrence: occurrence)
                        } label: {
                            AgendaRow(occurrence: occurrence)
                        }
                    }
                } header: {
                    // ⚠ Identified for `MonthGridUITests`, which checks the grid's last week ends
                    // above whatever comes first beneath it — this header, when the day has events.
                    Text(model.selectedDayHeading).accessibilityIdentifier("selected-day-heading")
                }
            }
        }
        .listStyle(.plain)
    }
}

/// Which of the shared view model's views a screen drives.
enum CalendarScreenView {
    case month
    case agenda
}

/// Shared between the month tab and the agenda tab: one `CalendarViewModel`, one observation, and
/// the derived reads each screen needs.
///
/// ⚠ Two instances exist at once — one per tab — because SwiftUI owns each screen's lifetime
/// through `@StateObject` and `CalendarSurface` builds a fresh view model per call by design. They
/// load the same rows twice, which is the honest cost of tabs; sharing one would mean fighting
/// `show(...)`, which reloads on every view switch precisely so a view never renders the previous
/// view's window.
@MainActor
final class CalendarScreenModel: ObservableObject {
    let viewModel: CalendarViewModel

    /// `nil` until the first emission — the screen shows a spinner rather than a fabricated empty.
    @Published var state: CalendarUiState?

    init(view: CalendarScreenView) {
        // ⚠⚠ `today` and the week start come from the host and are passed in as plain integers —
        // see `CalendarSurface.calendarViewModel`. Every layer beneath takes them as parameters so
        // none of them can disagree about what today is (`calendar-app.md` §3 D3).
        let now = isoCalendar.dateComponents([.year, .month, .day], from: Date())
        viewModel = CalendarSurface.shared.calendarViewModel(
            todayYear: Int32(now.year ?? 1970),
            todayMonth: Int32(now.month ?? 1),
            todayDay: Int32(now.day ?? 1),
            weekStartIsoDay: Int32(CalendarScreenModel.isoWeekStart())
        )
        switch view {
        case .month: viewModel.showMonth()
        case .agenda: viewModel.showAgenda()
        }
    }

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here
        // (the unreachable-`catch` warning is a known SKIE artefact — parity doc §6).
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    // MARK: - month

    var dots: [CalendarDayDot] {
        guard let state else { return [] }
        return CalendarMonthDots.shared.of(occurrences: state.occurrences, calendars: state.calendars)
    }

    /// The last Today press, as the month the grid must show — the one move the grid takes from
    /// outside (`CalendarMonthView.updateUIView` says why the model's month alone is not one).
    @Published private(set) var monthJump: MonthJump?

    /// Today: the model jumps (month and selection), and the grid is told to, explicitly.
    func goToToday() {
        viewModel.goToToday()
        // ⚠ The model's own today, not the clock's: they differ after midnight until the next
        // launch, and the grid must land where the model's title does.
        guard let day = state?.today else { return }
        let today = CalendarHostInput.shared.dateParts(date: day)
        monthJump = MonthJump(id: (monthJump?.id ?? 0) + 1, monthKey: Int(today.year) * 100 + Int(today.month))
    }

    /// The month the view model is on (`202610`), which a NEWLY BUILT grid opens on; `nil` before
    /// the first emission, so a grid built then opens on today's month as it always did.
    var monthKey: Int? {
        guard let state, state.year != 0, (1...12).contains(Int(state.month)) else { return nil }
        return Int(state.year) * 100 + Int(state.month)
    }

    /// ⚠ `NO_DAY` (0) means "nothing selected", which the grid must render as *cleared* rather
    /// than as some day. The packing is `CalendarMonthDots`', where it is tested.
    var selectedKey: Int? {
        guard let state else { return nil }
        let key = CalendarMonthDots.shared.keyOf(date: state.selectedDate)
        return key == CalendarMonthDots.shared.NO_DAY ? nil : Int(key)
    }

    var selectedDayOccurrences: [ResolvedOccurrence] {
        guard let state, let selected = state.selectedDate else { return [] }
        return state.occurrencesOn(date: selected)
    }

    /// The day a `+` creates on: the selection, falling back to today.
    ///
    /// ⚠ The parts come from `CalendarHostInput`, not from reaching into the bridged date — the
    /// same reason the editor takes integers.
    var selectedParts: (year: Int, month: Int, day: Int) {
        let parts = CalendarHostInput.shared.dateParts(date: state?.selectedDate ?? state?.today)
        return (Int(parts.year), Int(parts.month), Int(parts.day))
    }

    var selectedDayHeading: String {
        guard let state, let selected = state.selectedDate, let today = state.today else { return "" }
        return CalendarAgenda.shared.heading(day: selected, today: today)
    }

    /// The nav-bar title: the month the grid is showing.
    ///
    /// ⚠ From `CalendarDateFormat`, the same localised month names the Compose header uses, rather
    /// than a `DateFormatter` built here — one table, one answer.
    var monthTitle: String {
        guard let state, state.year != 0 else { return "Calendar" }
        return "\(CalendarDateFormat.shared.monthName(month: state.month)) \(state.year)"
    }

    // MARK: - agenda

    var sections: [CalendarAgendaSection] {
        guard let state, let today = state.today else { return [] }
        return CalendarAgenda.shared.sections(occurrences: state.occurrences, today: today)
    }

    /// Foundation `firstWeekday` (1 = Sunday … 7 = Saturday) → ISO (1 = Monday … 7 = Sunday).
    /// ⚠ `Calendar.current` on purpose: the week start is the user's preference, and it is the one
    /// field `isoCalendar` copies from it.
    private static func isoWeekStart() -> Int {
        let foundation = Calendar.current.firstWeekday
        return foundation == 1 ? 7 : foundation - 1
    }
}

/// The month grid's width on an iPad — see the ⚠⚠ at its use in `CalendarMonthScreen`. An
/// iPhone, in either orientation, keeps the grid exactly as it always was.
/// ⚠ Measured on a full-screen 13" iPad only: in a narrower iPad window the 440 pt cap does
/// nothing, and whether 12 pt still covers the neighbours there is unchecked.
private struct MonthGridWidth: ViewModifier {
    let pad: Bool

    func body(content: Content) -> some View {
        if pad {
            content
                .frame(maxWidth: 440)
                .mask(Rectangle().padding(.horizontal, 12))
                .frame(maxWidth: .infinity)
        } else {
            content
        }
    }
}
