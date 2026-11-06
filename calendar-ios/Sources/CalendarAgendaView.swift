import SwiftUI
import TenshuSdk

/// The agenda — a forward-looking list of what is coming up, grouped by day (CA-8a).
///
/// ⭐ The cheapest of the four views to render natively and the most useful one to ship first: it
/// is a `List` of sections over the *same* `CalendarViewModel` the Compose shells drive, in its
/// `AGENDA` mode.
///
/// ⚠⚠ **No date arithmetic, no formatting, no grouping happens in this file.** Which days appear,
/// in what order, with what heading, and what the time column says are all `CalendarAgenda`'s —
/// a pure, unit-tested object in `commonMain`. That is not tidiness: no Swift in this repo can be
/// compiled or tested in the environment it is written in, so anything that could be wrong belongs
/// on the other side of the line. The same discipline CA-7c's widget used, and it has already paid
/// once here (the epoch-day type was wrong, and the Kotlin compiler said so).
///
/// ⚠ The `+` creates on today here — an agenda has no selection to create on.
struct CalendarAgendaView: View {
    @StateObject private var model = CalendarScreenModel(view: .agenda)
    @State private var creating = false

    var body: some View {
        List {
            // ⚠⚠ Three states, not two. `nil` is "the first read has not returned"; empty is "it
            // returned and there is nothing"; and neither is the unreachable case, which the
            // banner one level up owns. Rendering the first as the second is the eternal-empty
            // conflation `ios-drive-parity.md` P1a found in Drive's `FilesView` — and CA-7c's
            // widget hit again.
            if model.state == nil {
                ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden)
            } else if model.sections.isEmpty {
                CalendarEmptyState()
            } else {
                ForEach(model.sections, id: \.epochDay) { section in
                    Section(section.heading) {
                        ForEach(section.occurrences, id: \.recurrenceId) { occurrence in
                            NavigationLink {
                                EventDetailView(occurrence: occurrence)
                            } label: {
                                AgendaRow(occurrence: occurrence)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Calendar")
        .refreshable { model.viewModel.refresh() }
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { creating = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("New event")
            }
        }
        // ⚠ Today, because an agenda has no selection — it is a forward list, and its scroll
        // position is the navigation (`CalendarViewModel`'s own note on why AGENDA does not page).
        .sheet(isPresented: $creating, onDismiss: { model.viewModel.refresh() }) {
            NavigationStack {
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

/// One occurrence in the agenda list.
struct AgendaRow: View {
    let occurrence: ResolvedOccurrence

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            // ⚠ Fixed-width so the titles line up down the list — the column is what makes an
            // agenda scannable, and a ragged left edge undoes most of that.
            Text(CalendarAgenda.shared.timeLabel(occurrence: occurrence))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 78, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text(CalendarAgenda.shared.titleOf(occurrence: occurrence))
                if !occurrence.location.isEmpty {
                    Text(occurrence.location)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            // ⚠ Only the recurring marker, and only here. A per-row "overridden" badge would be
            // noise: a moved occurrence already shows its moved time, which is the fact the user
            // came for.
            if occurrence.isRecurring {
                Image(systemName: "repeat")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Repeats")
            }
        }
        .padding(.vertical, 2)
    }
}

/// Nothing scheduled — a **result**, and it must read as one.
///
/// ⚠ It says nothing about Control. An empty agenda and an unreachable proxy are different
/// situations, the banner above owns the second, and copy that tried to explain both would be
/// wrong half the time.
struct CalendarEmptyState: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("Nothing coming up").font(.headline)
            Text("Events from the next few weeks appear here.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .listRowSeparator(.hidden)
    }
}
