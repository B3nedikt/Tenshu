import SwiftUI
import TenshuSdk

/// The event editor (CA-8d) — the iOS app's first *write* surface, over the shared
/// `EventEditorViewModel`.
///
/// ⚠⚠ **Every rule here is the view model's.** Which recurrence choices exist and what they are
/// called, whether a save is allowed, whether the end is before the start, whether editing a
/// recurring event has to ask *this occurrence or all of them*, which reminder offsets a timed
/// event offers versus an all-day one, and what happens to the default reminder when all-day is
/// ticked — all of it is shared and unit-tested. This file renders it.
///
/// ⚠ **Dates cross as integers.** SwiftUI's `DatePicker` binds a `Date`; Foundation turns that
/// into components; the view model takes them as `setStartDateFromParts` / `setStartTimeFromParts`.
/// The shell never names a kotlinx-datetime constructor, because nothing in this repo's
/// environment could check that it named one correctly.
struct EventEditorScreen: View {
    @StateObject private var model: EventEditorModel
    let onClose: () -> Void

    /// Create a new event on [date].
    init(startYear: Int, startMonth: Int, startDay: Int, onClose: @escaping () -> Void) {
        _model = StateObject(
            wrappedValue: EventEditorModel(year: startYear, month: startMonth, day: startDay)
        )
        self.onClose = onClose
    }

    /// Edit an existing occurrence.
    init(occurrence: ResolvedOccurrence, onClose: @escaping () -> Void) {
        _model = StateObject(wrappedValue: EventEditorModel(occurrence: occurrence))
        self.onClose = onClose
    }

    var body: some View {
        Form {
            if let state = model.state {
                detailsSection(state)
                timingSection(state)
                recurrenceSection(state)
                remindersSection(state)
                if state.isReadOnly {
                    // ⚠ A subscribed or mirrored calendar: its next refresh would undo any change.
                    Section {
                        Text("This calendar is updated from elsewhere, so its events can't be changed here.")
                            .foregroundStyle(.secondary)
                    }
                } else if state.isEditing {
                    Section {
                        Button("Delete event", role: .destructive) { model.viewModel.delete() }
                    }
                }
                if let error = state.errorMessage {
                    Section { Text(error).foregroundStyle(.red) }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .navigationTitle(model.state?.isEditing == true ? "Edit event" : "New event")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { onClose() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if model.state?.isReadOnly != true {
                    Button("Save") { model.viewModel.save() }
                        .disabled(model.state?.canSave != true || model.state?.isSaving == true)
                }
            }
        }
        // ⚠⚠ Closes on the COMMIT, never on the tap. A refused save (no calendar, proxy
        // unreachable) would otherwise look exactly like a successful one: the screen closes and
        // nothing was written. The Compose editor carries the same guard and the same reason — the
        // `RenameFileDialog` precedent, which learned it the hard way.
        .onChange(of: model.state?.saved ?? false) { if $0 { onClose() } }
        .onChange(of: model.state?.deleted ?? false) { if $0 { onClose() } }
        .confirmationDialog(
            "This is a repeating event",
            isPresented: model.scopeBinding,
            titleVisibility: .visible
        ) {
            Button("Save this occurrence") { model.viewModel.commit(scope: .thisOccurrence) }
            Button("Save all occurrences") { model.viewModel.commit(scope: .allOccurrences) }
            Button("Cancel", role: .cancel) { model.viewModel.dismissScopePrompt() }
        }
        .confirmationDialog(
            "Delete which occurrences?",
            isPresented: model.deleteScopeBinding,
            titleVisibility: .visible
        ) {
            Button("Delete this occurrence", role: .destructive) {
                model.viewModel.commitDelete(scope: .thisOccurrence)
            }
            Button("Delete all occurrences", role: .destructive) {
                model.viewModel.commitDelete(scope: .allOccurrences)
            }
            Button("Cancel", role: .cancel) { model.viewModel.dismissScopePrompt() }
        }
        .task { await model.observe() }
    }

    // MARK: - sections

    @ViewBuilder
    private func detailsSection(_ state: EventEditorUiState) -> some View {
        Section {
            TextField("Title", text: model.titleBinding)
            TextField("Location", text: model.locationBinding)
            TextField("Notes", text: model.notesBinding, axis: .vertical)
                .lineLimit(1...5)

            // ⚠ Writable calendars only; none for an event in a read-only calendar.
            if !state.isReadOnly && state.writableCalendars.count > 1 {
                Picker("Calendar", selection: model.calendarBinding) {
                    ForEach(state.writableCalendars, id: \.calendarId) { calendar in
                        Text(calendar.name.isEmpty ? "Untitled calendar" : calendar.name)
                            .tag(calendar.calendarId)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func timingSection(_ state: EventEditorUiState) -> some View {
        Section {
            Toggle("All-day", isOn: model.allDayBinding)

            DatePicker("Date", selection: model.dateBinding, displayedComponents: .date)

            if !state.allDay {
                DatePicker("Starts", selection: model.startTimeBinding, displayedComponents: .hourAndMinute)
                DatePicker("Ends", selection: model.endTimeBinding, displayedComponents: .hourAndMinute)
            }
        } footer: {
            // ⚠ Shown rather than enforced by clamping the picker: the view model already refuses
            // the save (`canSave`), and a picker that silently refused to move would be a worse
            // way to learn why.
            if state.endBeforeStart {
                Text("The end is before the start.").foregroundStyle(.red)
            }
        }
    }

    @ViewBuilder
    private func recurrenceSection(_ state: EventEditorUiState) -> some View {
        Section {
            if state.recurrenceIsReadOnly {
                // ⚠⚠ An imported rule this editor cannot offer as a choice. §3 D2: *refuse to
                // edit rather than silently rewrite* — showing a picker here would let a user turn
                // "every second Tuesday" into "weekly" by opening the screen and saving.
                LabeledContent("Repeats") { Text(state.recurrence.label()) }
            } else {
                Picker("Repeats", selection: model.recurrenceBinding) {
                    ForEach(RecurrencePreset.companion.OFFERABLE, id: \.self) { preset in
                        Text(preset.label()).tag(preset)
                    }
                }
            }
        } footer: {
            if state.recurrenceIsReadOnly {
                Text("This event repeats on a schedule Tenshu can show but not change here.")
            }
        }
    }

    @ViewBuilder
    private func remindersSection(_ state: EventEditorUiState) -> some View {
        Section("Reminders") {
            // ⚠⚠ Two lists, because a timed event and an all-day event are not asking the same
            // question — `ReminderPresets` carries the reasoning. Ticking all-day swaps the offers
            // *and* re-reads the default, which is the view model's job, not this view's.
            ForEach(ReminderPresets.shared.forEvent(allDay: state.allDay), id: \.self) { offset in
                let minutes = Int32(truncating: offset)
                let chosen = state.reminderMinutes.contains(offset)
                Button {
                    model.viewModel.toggleReminder(minutesBefore: minutes)
                } label: {
                    HStack {
                        Text(ReminderPresets.shared.label(minutesBefore: minutes, allDay: state.allDay))
                            .foregroundStyle(.primary)
                        Spacer()
                        if chosen {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }
        }
    }
}

@MainActor
final class EventEditorModel: ObservableObject {
    let viewModel: EventEditorViewModel

    @Published var state: EventEditorUiState?

    init(year: Int, month: Int, day: Int) {
        viewModel = CalendarSurface.shared.eventEditorViewModel(
            startYear: Int32(year),
            startMonth: Int32(month),
            startDay: Int32(day)
        )
    }

    init(occurrence: ResolvedOccurrence) {
        // ⚠ The start parts are the occurrence's own, but they are immediately overwritten by
        // `load` — they exist only because the factory has no "editing" variant. Passing today
        // instead would work identically and read as a lie.
        let parts = CalendarHostInput.shared.dateParts(date: occurrence.startLocal.date)
        viewModel = CalendarSurface.shared.eventEditorViewModel(
            startYear: parts.year,
            startMonth: parts.month,
            startDay: parts.day
        )
        viewModel.load(occurrence: occurrence)
    }

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here.
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    // MARK: - bindings

    var titleBinding: Binding<String> {
        Binding(get: { self.state?.title ?? "" }, set: { self.viewModel.setTitle(value: $0) })
    }

    var locationBinding: Binding<String> {
        Binding(get: { self.state?.location ?? "" }, set: { self.viewModel.setLocation(value: $0) })
    }

    var notesBinding: Binding<String> {
        Binding(get: { self.state?.notes ?? "" }, set: { self.viewModel.setNotes(value: $0) })
    }

    var calendarBinding: Binding<String> {
        Binding(get: { self.state?.calendarId ?? "" }, set: { self.viewModel.setCalendar(id: $0) })
    }

    var allDayBinding: Binding<Bool> {
        Binding(get: { self.state?.allDay ?? false }, set: { self.viewModel.setAllDay(value: $0) })
    }

    var recurrenceBinding: Binding<RecurrencePreset> {
        Binding(
            // ⚠ The first offerable rather than naming an enum case: K/N's Swift spelling of a
            // Kotlin entry is exactly the sort of thing nothing here can check.
            get: { self.state?.recurrence ?? RecurrencePreset.companion.OFFERABLE[0] },
            set: { self.viewModel.setRecurrence(preset: $0) }
        )
    }

    /// ⚠⚠ The one place a `Date` exists in this screen, and it goes no further: Foundation's
    /// picker speaks `Date`, the shared layer speaks civil time (`calendar-app.md` §3 D3), and the
    /// conversion happens here so the two never meet anywhere else.
    var dateBinding: Binding<Date> {
        Binding(
            get: {
                let parts = CalendarHostInput.shared.dateParts(date: self.state?.startDate)
                return EventEditorModel.date(
                    year: Int(parts.year), month: Int(parts.month), day: Int(parts.day),
                    hour: 12, minute: 0
                )
            },
            set: { newValue in
                let parts = isoCalendar.dateComponents([.year, .month, .day], from: newValue)
                self.viewModel.setStartDateFromParts(
                    year: Int32(parts.year ?? 1970),
                    month: Int32(parts.month ?? 1),
                    day: Int32(parts.day ?? 1)
                )
            }
        )
    }

    var startTimeBinding: Binding<Date> { timeBinding(isStart: true) }

    var endTimeBinding: Binding<Date> { timeBinding(isStart: false) }

    private func timeBinding(isStart: Bool) -> Binding<Date> {
        Binding(
            get: {
                let time = isStart ? self.state?.startTime : self.state?.endTime
                let parts = CalendarHostInput.shared.timeParts(time: time)
                // ⚠ A fixed anchor date. Only the time components are displayed, and using
                // *today* would make the control jump if the picker were open across midnight.
                return EventEditorModel.date(
                    year: 2000, month: 1, day: 1,
                    hour: Int(parts.hour), minute: Int(parts.minute)
                )
            },
            set: { newValue in
                let parts = isoCalendar.dateComponents([.hour, .minute], from: newValue)
                let hour = Int32(parts.hour ?? 0)
                let minute = Int32(parts.minute ?? 0)
                if isStart {
                    self.viewModel.setStartTimeFromParts(hour: hour, minute: minute)
                } else {
                    self.viewModel.setEndTimeFromParts(hour: hour, minute: minute)
                }
            }
        )
    }

    /// ⚠ Presentation derived from the shared state rather than a local `@State` flag — the
    /// prompt *is* the state, and a second boolean beside it is one more thing that can disagree
    /// with the view model about whether a dialog is open.
    var scopeBinding: Binding<Bool> {
        Binding(
            get: { self.state?.askingScope ?? false },
            set: { if !$0 { self.viewModel.dismissScopePrompt() } }
        )
    }

    var deleteScopeBinding: Binding<Bool> {
        Binding(
            get: { self.state?.askingDeleteScope ?? false },
            set: { if !$0 { self.viewModel.dismissScopePrompt() } }
        )
    }

    private static func date(year: Int, month: Int, day: Int, hour: Int, minute: Int) -> Date {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        return isoCalendar.date(from: components) ?? Date()
    }
}
