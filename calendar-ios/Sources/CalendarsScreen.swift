import SwiftUI
import TenshuSdk
import UIKit

/// The calendars tab: what exists, what this device shows, which one new events land in, and the
/// CA-7 subscribed feeds (CA-8b). The SwiftUI twin of the Compose `CalendarsScreen`.
///
/// ⚠ Every rule here is the shared `CalendarManagementViewModel`'s — the last calendar cannot be
/// deleted, a duplicate name is refused, visibility is optimistic and device-scoped, a subscribed
/// calendar has no plain *Delete*. This file renders them; it re-implements none of them.
struct CalendarsScreen: View {
    @StateObject private var model = CalendarsModel()
    /// ⚠ Held here rather than per row: one sheet, whichever row opened it — the same reason the
    /// Compose screen renders `CalendarInteropSheet` once, outside its list.
    @StateObject private var interop = CalendarInteropModel()
    /// *Copy from this iPhone* (CX-2a). One sheet for the screen, like `interop`.
    @StateObject private var externalCopy = ExternalCopyModel()
    /// The name the delete alert was opened for. ⚠ The alert's title cannot come from `presenting:`
    /// (SwiftUI derives only the actions and message from the item), and the live
    /// `confirmingDelete` is already nil while the alert animates out — so without this the title
    /// would flash `Delete ""?` on the way out.
    @State private var deletingName = ""

    var body: some View {
        List {
            if let state = model.state {
                Section {
                    ForEach(state.calendars, id: \.calendarId) { calendar in
                        CalendarRow(calendar: calendar, model: model, state: state, interop: interop)
                    }
                } footer: {
                    // ⚠ Says *device*, because visibility is device-scoped and the synced-vs-local
                    // split is invisible otherwise: hiding a calendar on a phone and finding it
                    // still on a laptop reads as a sync failure unless something said so.
                    Text("Hiding a calendar affects this device only.")
                }

                Section {
                    Button { model.viewModel.startCreating() } label: {
                        Label("New calendar", systemImage: "plus")
                    }
                    Button { model.viewModel.startSubscribing() } label: {
                        Label("Subscribe to a calendar", systemImage: "link")
                    }
                    // ⚠ Asks for calendar access on tap, not before: the prompt shows once per
                    // install and should arrive with the reason the user just gave it.
                    if externalCopy.viewModel.supported {
                        Button { externalCopy.start() } label: {
                            Label("Copy from this \(ExternalCopyModel.device)", systemImage: "iphone.and.arrow.forward")
                        }
                    }
                }

                // ⚠ Last, and absent entirely once reminders work (CA-8c). It is a one-time
                // setup step, not a setting — putting it above the calendars would make the
                // screen's subject look like permissions.
                RemindersPermissionSection()
            } else {
                ProgressView().frame(maxWidth: .infinity).listRowSeparator(.hidden)
            }

            // ⚠ Calendar has no settings screen; this management list is the nearest thing, so the
            // legal entries sit at its end: the in-app privacy policy (App Store guideline 5.1.1(i);
            // ios-release/Sources), then the open-source notices (docs/oss-licences.md).
            Section {
                PrivacyPolicyLink()
                OpenSourceLicencesLink()
            }
        }
        .navigationTitle("Calendars")
        .refreshable { model.viewModel.refresh() }
        .task { await model.observe() }
        .task { await interop.observe() }
        .task { await externalCopy.observe() }
        .sheet(isPresented: interop.visibleBinding) { CalendarInteropSheet(model: interop) }
        .sheet(isPresented: externalCopy.visibleBinding) { ExternalCopySheet(model: externalCopy) }
        // ⚠ `item:`-style presentation off the shared state rather than a local `@State` flag: the
        // draft *is* the presentation condition, and a second boolean beside it is one more thing
        // that can disagree with the view model about whether a sheet is open.
        .sheet(isPresented: model.draftBinding) { CalendarDraftSheet(model: model) }
        .sheet(isPresented: model.subscribeBinding) { SubscribeSheet(model: model) }
        .alert(
            // ⚠ Names the calendar in the title, as the Compose dialog does — the same words on
            // both platforms (`DeleteCalendarDialog`).
            Text("Delete \"\(model.state?.confirmingDelete?.name ?? deletingName)\"?"),
            isPresented: model.deleteBinding,
            presenting: model.state?.confirmingDelete
        ) { _ in
            Button("Delete", role: .destructive) { model.viewModel.confirmDelete() }
            Button("Cancel", role: .cancel) { model.viewModel.dismissDelete() }
        } message: { _ in
            // ⚠ Names the cascade. Deleting a calendar deletes every event in it, and a
            // confirmation that only says "delete?" has not asked the real question.
            Text("Every event in this calendar will be deleted on all your devices. This cannot be undone.")
        }
        .onChange(of: model.state?.confirmingDelete?.name) { name in
            if let name { deletingName = name }
        }
        .alert(
            "Something went wrong",
            isPresented: model.errorBinding,
            presenting: model.state?.errorMessage
        ) { _ in
            Button("OK") { model.viewModel.dismissError() }
        } message: { message in
            Text(message)
        }
    }
}

private struct CalendarRow: View {
    let calendar: CalendarSummary
    @ObservedObject var model: CalendarsModel
    let state: CalendarManagementUiState
    @ObservedObject var interop: CalendarInteropModel

    var body: some View {
        HStack(spacing: 12) {
            Button {
                model.viewModel.setVisible(calendarId: calendar.calendarId, visible: !calendar.visible)
            } label: {
                Image(systemName: calendar.visible ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(calendar.visible ? Color(dot: calendar.colorArgb) : .secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(calendar.visible ? "Hide \(calendar.name)" : "Show \(calendar.name)")

            VStack(alignment: .leading, spacing: 2) {
                Text(calendar.name.isEmpty ? "Untitled calendar" : calendar.name)
                if let note = model.note(for: calendar, in: state) {
                    Text(note).font(.caption).foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: 0)

            Menu {
                Button("Edit") { model.viewModel.startEditing(calendar: calendar) }
                if state.defaultCalendarId != calendar.calendarId && !calendar.readOnly {
                    Button("Make default") { model.viewModel.setDefault(calendarId: calendar.calendarId) }
                }
                // ⚠ Offered for a subscribed calendar too, unlike *Delete*. Importing into one is
                // pointless rather than destructive — the next refresh replaces it — but
                // *exporting* one is the whole reason a user would reach for this here.
                Button("Import or export…") { interop.open(calendar) }
                if let mirror = state.mirrorFor(calendarId: calendar.calendarId) {
                    // ⚠ *Refresh now* only where the source is: no other device can read it.
                    if mirror.boundHere {
                        Button("Refresh now") { model.viewModel.refreshMirrorNow(mirrorId: mirror.mirrorId) }
                    }
                    Button(mirror.remindersEnabled ? "Stop Tenshu reminders" : "Remind me from Tenshu too") {
                        model.viewModel.setMirrorReminders(mirrorId: mirror.mirrorId, enabled: !mirror.remindersEnabled)
                    }
                    // ⚠ Keeps the events — the calendar becomes an ordinary, editable one. *Delete*
                    // below still removes calendar and sync together.
                    Button("Stop syncing (keep events)") { model.viewModel.stopMirror(mirrorId: mirror.mirrorId) }
                }
                if let subscription = state.subscriptionsByCalendar[calendar.calendarId] {
                    Button(subscription.enabled ? "Pause updates" : "Resume updates") {
                        model.viewModel.setSubscriptionEnabled(
                            subscriptionId: subscription.subscriptionId,
                            enabled: !subscription.enabled
                        )
                    }
                    Button("Refresh now") { model.viewModel.refreshSubscriptionsNow() }
                    Button("Unsubscribe", role: .destructive) {
                        model.viewModel.removeSubscription(subscriptionId: subscription.subscriptionId)
                    }
                } else {
                    // ⚠ No plain *Delete* for a subscribed calendar — unsubscribing is what
                    // removes it, and offering both would leave a feed writing into a calendar the
                    // user just deleted. The Compose screen makes the same omission.
                    Button("Delete", role: .destructive) { model.viewModel.askDelete(calendar: calendar) }
                }
            } label: {
                Image(systemName: "ellipsis.circle").foregroundStyle(.secondary)
            }
            .accessibilityLabel("Options for \(calendar.name)")
        }
    }
}

/// Create or rename, with the colour picker.
private struct CalendarDraftSheet: View {
    @ObservedObject var model: CalendarsModel

    var body: some View {
        NavigationStack {
            Form {
                if let draft = model.state?.draft {
                    Section {
                        TextField("Name", text: model.draftNameBinding)
                        Toggle("Hide details on the lock screen", isOn: model.draftSensitiveBinding)
                    } footer: {
                        // ⚠ Says what it *does*, not what it is. "Sensitive" is a flag name; what
                        // the user is choosing is whether a reminder shows the event's title.
                        // ⚠ "Reminder" is quoted verbatim: it is `ReminderPlanner.REDACTED_TITLE`,
                        // which is not localized, so the German copy quotes it in English too.
                        Text("Reminders for this calendar show only \"Reminder\" — no title, no location.")
                    }

                    Section("Colour") {
                        ColourGrid(selected: draft.colorArgb) { model.viewModel.setDraftColor(colorArgb: $0) }
                    }

                    if let error = model.state?.draftError {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                }
            }
            .navigationTitle(model.state?.draft?.calendarId.isEmpty == false ? "Edit calendar" : "New calendar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.viewModel.dismissDraft() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { model.viewModel.saveDraft() }
                }
            }
        }
    }
}

/// The CA-7 subscribe sheet.
///
/// ⚠⚠ The copy names the three consent facts, because **creating the subscription is the consent**
/// (§2.4 / Q9): from here the device fetches a third party on a schedule, disclosing its IP and the
/// fact that this user holds that calendar. The Compose sheet says the same three things.
private struct SubscribeSheet: View {
    @ObservedObject var model: CalendarsModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://…", text: model.subscribeUrlBinding)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Name", text: model.subscribeNameBinding)
                } footer: {
                    // ⚠ One literal, not a `+` chain: a concatenated `String` renders verbatim and
                    // never reaches the catalogue. "About every 6 hours" is
                    // `SUBSCRIPTION_REFRESH_PERIOD_MS`. ⚠ No "on Wi-Fi", unlike the Compose sheet: the
                    // schedule asks for NETWORK_UNMETERED, but iOS's in-process drain does not enforce
                    // it (tech-debt.md "iOS ignores schedule network constraints").
                    Text("Your device will download this calendar directly from its publisher, about every 6 hours. The publisher will see your IP address. Only https:// and webcal:// addresses are accepted.")
                }

                if let error = model.state?.subscribeDraft?.error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Subscribe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.viewModel.dismissSubscribe() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Subscribe") { model.viewModel.commitSubscribe() }
                        .disabled(model.state?.subscribeDraft?.isSaving == true)
                }
            }
        }
    }
}

/// The fixed palette, from the shared `CalendarPalette` — the same eight colours the Compose
/// screen offers, so a calendar made on one platform looks the same on the other.
private struct ColourGrid: View {
    let selected: Int32
    let onPick: (Int32) -> Void

    private let columns = [GridItem(.adaptive(minimum: 44))]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(CalendarPalette.shared.COLORS, id: \.self) { colour in
                let value = Int32(truncating: colour)
                Button { onPick(value) } label: {
                    Circle()
                        .fill(Color(dot: value))
                        .frame(width: 32, height: 32)
                        .overlay(
                            // ⚠ Alpha-blind, as every renderer is: an old row's `0x4285F4` (alpha 0)
                            // is the palette's blue and must show as selected.
                            Circle().strokeBorder(
                                .primary,
                                lineWidth: CalendarPalette.shared.sameColour(a: value, b: selected) ? 3 : 0
                            )
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }
}

@MainActor
final class CalendarsModel: ObservableObject {
    let viewModel = CalendarSurface.shared.calendarManagementViewModel()

    /// `nil` until the first emission — a spinner rather than a fabricated empty list.
    @Published var state: CalendarManagementUiState?

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here.
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    /// The supporting line under a calendar: default, subscribed, paused.
    ///
    /// ⚠ At most one line, and the *actionable* one wins. A row that lists every fact about itself
    /// is a row nobody reads, and "paused" is the state a user is looking for when a feed has
    /// stopped updating.
    func note(for calendar: CalendarSummary, in state: CalendarManagementUiState) -> String? {
        if let subscription = state.subscriptionsByCalendar[calendar.calendarId] {
            // ⚠ `String(localized:)`: the row renders this through `Text(String)`, which is verbatim.
            return subscription.enabled
                ? String(localized: "Subscribed")
                : String(localized: "Subscription paused")
        }
        // ⚠ CX-2b: where it is kept in sync from and how fresh — on every device, because only
        // one can refresh it and the others must be able to see it going stale.
        if let mirror = state.mirrorFor(calendarId: calendar.calendarId) {
            return Self.mirrorNote(mirror)
        }
        if state.defaultCalendarId == calendar.calendarId { return String(localized: "Default for new events") }
        return nil
    }

    /// The Compose `mirrorNote`, in the device's own words ("this iPhone").
    static func mirrorNote(_ mirror: CalendarMirrorSummary) -> String {
        let from = mirror.boundHere
            ? String(localized: "Kept in sync from this \(UIDevice.current.model)")
            : String(localized: "Kept in sync from another device")
        guard mirror.lastRefreshedEpochMs > 0 else { return from + " · " + String(localized: "not updated yet") }
        let when = Date(timeIntervalSince1970: TimeInterval(mirror.lastRefreshedEpochMs) / 1000)
            .formatted(date: .abbreviated, time: .shortened)
        return from + " · " + String(localized: "updated \(when)")
    }

    // MARK: - bindings
    //
    // ⚠ Presentation is derived from the shared state, never from a local `@State` flag: the draft
    // *is* the condition. A separate boolean is one more thing that can disagree with the view
    // model about whether a sheet is open — and the one that is wrong is invisible.

    var draftBinding: Binding<Bool> {
        Binding(get: { self.state?.draft != nil }, set: { if !$0 { self.viewModel.dismissDraft() } })
    }

    var subscribeBinding: Binding<Bool> {
        Binding(
            get: { self.state?.subscribeDraft != nil },
            set: { if !$0 { self.viewModel.dismissSubscribe() } }
        )
    }

    var deleteBinding: Binding<Bool> {
        Binding(
            get: { self.state?.confirmingDelete != nil },
            set: { if !$0 { self.viewModel.dismissDelete() } }
        )
    }

    var errorBinding: Binding<Bool> {
        Binding(get: { self.state?.errorMessage != nil }, set: { if !$0 { self.viewModel.dismissError() } })
    }

    var draftNameBinding: Binding<String> {
        Binding(
            get: { self.state?.draft?.name ?? "" },
            set: { self.viewModel.setDraftName(name: $0) }
        )
    }

    var draftSensitiveBinding: Binding<Bool> {
        Binding(
            get: { self.state?.draft?.sensitive ?? false },
            set: { self.viewModel.setDraftSensitive(sensitive: $0) }
        )
    }

    var subscribeUrlBinding: Binding<String> {
        Binding(
            get: { self.state?.subscribeDraft?.url ?? "" },
            set: { self.viewModel.setSubscribeUrl(url: $0) }
        )
    }

    var subscribeNameBinding: Binding<String> {
        Binding(
            get: { self.state?.subscribeDraft?.name ?? "" },
            set: { self.viewModel.setSubscribeName(name: $0) }
        )
    }
}

extension Color {
    /// ⚠ ARGB → SwiftUI colour, through the same channel unpacking the month dots use. Kotlin owns
    /// the shift (`CalendarDayDot`), because the stored value is Android-packed and a swapped red
    /// and blue is invisible until someone looks at a screen.
    init(dot argb: Int32) {
        let dot = CalendarDayDot(year: 0, month: 1, day: 1, count: 0, colorArgb: argb)
        self.init(
            .sRGB,
            red: Double(dot.red) / 255.0,
            green: Double(dot.green) / 255.0,
            blue: Double(dot.blue) / 255.0,
            opacity: Double(dot.alpha) / 255.0
        )
    }
}
