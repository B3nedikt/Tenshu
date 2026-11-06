import SwiftUI
import TenshuSdk

/// One occurrence, read-only (CA-8a).
///
/// ⚠ It renders what the row already carries — no second read — so nothing here can be stale
/// relative to the list it was pushed from. *Edit* (CA-8d) opens the shared editor over the same
/// occurrence, which is also the only thing it needs to hand over.
struct EventDetailView: View {
    let occurrence: ResolvedOccurrence

    @State private var editing = false

    var body: some View {
        List {
            Section {
                Text(CalendarAgenda.shared.titleOf(occurrence: occurrence))
                    .font(.title3.weight(.semibold))
                    .listRowSeparator(.hidden)

                LabeledContent {
                    Text(CalendarAgenda.shared.spanLabel(occurrence: occurrence))
                } label: {
                    Label("When", systemImage: "clock")
                }

                if occurrence.isRecurring {
                    LabeledContent {
                        // ⚠ The editor's own classifier and words (`RecurrencePreset`), so the two
                        // screens never disagree — and only for the exact shapes it maps back to
                        // a preset. Anything else (`CUSTOM`, or a rule it cannot parse) stays the
                        // raw `RRULE`: a *wrong* phrase on a read-only screen is worse than an
                        // honest one the user can at least recognise.
                        // ⚠ `of(occurrence)`, the editor's own call: it classifies against the
                        // SERIES' anchor, so a moved occurrence still says "Weekly" here too.
                        let preset = RecurrencePreset.companion.of(occurrence: occurrence)
                        if preset != .custom && preset != .never {
                            Text(preset.label())
                        } else {
                            Text(occurrence.recurrenceRule)
                                .font(.footnote.monospaced())
                        }
                    } label: {
                        Label("Repeats", systemImage: "repeat")
                    }
                }
            }

            if !occurrence.location.isEmpty {
                Section {
                    Label(occurrence.location, systemImage: "mappin.and.ellipse")
                }
            }

            if !occurrence.notes.isEmpty {
                Section("Notes") {
                    Text(occurrence.notes)
                }
            }

        }
        .navigationTitle("Event")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Edit") { editing = true }
            }
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                EventEditorScreen(occurrence: occurrence) { editing = false }
            }
        }
    }
}
