import EventKit
import SwiftUI
import TenshuSdk
import UIKit

/// *Copy from this iPhone* (`calendar-external-sources.md` CX-2a): pick a calendar iOS already has
/// (iCloud, Google, Exchange) and copy it into Tenshu once. The SwiftUI twin of the Compose
/// `ExternalCopySheet`.
///
/// ⚠ Every rule is the shared `ExternalCopyViewModel`'s: an empty calendar is not an error, events
/// none of which could be read is one, skipped events are named, and copying again updates the
/// earlier copy. This file renders them and asks for access; it re-implements none of them.
///
/// ⚠ *Keep in sync* (CX-2b) is offered only where the view model says it can be
/// (`canKeepInSync`: a real repository is installed); otherwise a tap copies once, as in CX-2a.
struct ExternalCopySheet: View {
    @ObservedObject var model: ExternalCopyModel

    var body: some View {
        NavigationStack {
            Form {
                if let step = model.state?.step {
                    switch onEnum(of: step) {
                    case .closed:
                        EmptyView()
                    case .loading:
                        Working(text: "Reading this \(ExternalCopyModel.device)'s calendars…")
                    case .choosing(let choosing):
                        ChooserSection(choosing: choosing, model: model)
                    case .deciding(let deciding):
                        DecidingSection(deciding: deciding, model: model)
                    case .copying(let copying):
                        Working(text: "Copying “\(copying.calendar.name)”…")
                    case .done(let done):
                        Message(text: done.result.message, model: model)
                    case .mirrored(let mirrored):
                        Message(text: mirrored.message, model: model)
                    case .failed(let failed):
                        FailedSection(problem: failed.problem, model: model)
                    }
                }
            }
            .navigationTitle("Copy from this \(ExternalCopyModel.device)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { model.viewModel.dismiss() }
                        .disabled(model.isCopying)
                }
            }
        }
        // ⚠ The view model ignores a dismiss while a copy runs (the write carries on regardless);
        // without this the swipe would close the sheet anyway and hide whether it finished.
        .interactiveDismissDisabled(model.isCopying)
    }
}

private struct Working: View {
    let text: String

    var body: some View {
        Section {
            HStack(spacing: 12) {
                ProgressView()
                Text(text)
            }
        }
    }
}

private struct ChooserSection: View {
    let choosing: ExternalCopyStepChoosing
    @ObservedObject var model: ExternalCopyModel

    var body: some View {
        if choosing.accounts.isEmpty {
            // ⚠ A real state with its own copy, not an error: the answer is to add an account.
            Section {
                Text(
                    "There are no calendars on this \(ExternalCopyModel.device) yet. Add your iCloud, Google or work account in Settings, let it sync, then come back here."
                )
            }
        } else {
            Section {
                EmptyView()
            } footer: {
                if model.viewModel.canKeepInSync {
                    Text("Pick a calendar to bring into Tenshu. Next you choose whether to copy it once or keep it in sync.")
                } else {
                    Text("Pick a calendar to copy into Tenshu. It is copied once — changes made later in the other app are not.")
                }
            }
            ForEach(choosing.accounts, id: \.accountKey) { account in
                Section(account.accountName.isEmpty ? "On this \(ExternalCopyModel.device)" : account.accountName) {
                    ForEach(account.calendars, id: \.sourceKey) { calendar in
                        Button {
                            model.viewModel.choose(calendar: calendar)
                        } label: {
                            CalendarChoice(
                                calendar: calendar,
                                copiedInto: choosing.copiedInto[calendar.sourceKey],
                                mirrored: choosing.mirroredInto[calendar.sourceKey]
                            )
                        }
                        .disabled(!calendar.holdsEvents)
                    }
                }
            }
        }
    }
}

private struct CalendarChoice: View {
    let calendar: ExternalCalendar
    let copiedInto: String?
    let mirrored: MirroredSource?

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(calendar.colorArgb.map { Color(dot: $0.int32Value) } ?? .secondary)
                .frame(width: 12, height: 12)
            VStack(alignment: .leading, spacing: 2) {
                Text(calendar.name).foregroundStyle(.primary)
                // ⚠ Copying again *updates* the earlier copy — a row promising a new calendar
                // would make the user expect two.
                // ⚠⚠ The mirror line wins: a source a mirror already brings in must not read as
                // new, or the user copies it and gets every event twice.
                Text(mirrored.map(\.note) ?? copiedInto.map { "Copied before into “\($0)”" } ?? "Bring into a new Tenshu calendar")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// *Copy once* or *Keep in sync* (CX-2b).
///
/// ⚠ Asked every time rather than defaulted: the two leave very different calendars behind — one
/// editable and frozen, one read-only and live — and a user who got the wrong one would only find
/// out a week later. The Compose sheet asks the same question in the same words.
///
/// ⚠⚠ A source a mirror already brings in gets no *Copy once* (the view model refuses it too): a
/// copy beside the mirror would hold every event a second time.
private struct DecidingSection: View {
    let deciding: ExternalCopyStepDeciding
    @ObservedObject var model: ExternalCopyModel
    /// ⚠ Off unless asked for (§5.7): the source's own app already reminds. A rebind starts from
    /// the mirror's own switch, which "off" would otherwise turn off.
    @State private var remind: Bool

    init(deciding: ExternalCopyStepDeciding, model: ExternalCopyModel) {
        self.deciding = deciding
        self.model = model
        _remind = State(initialValue: deciding.mirrored?.remindersEnabled ?? false)
    }

    private var calendar: ExternalCalendar { deciding.calendar }

    var body: some View {
        let device = ExternalCopyModel.device
        Section {
            if let mirrored = deciding.mirrored {
                if mirrored.boundHere {
                    Text("Already kept in sync from this \(device), in “\(mirrored.calendarName)”.")
                    Button {
                        model.viewModel.keepInSync(calendar: calendar, remindersEnabled: remind)
                    } label: {
                        Choice(title: "Update now", detail: "Reads it again from this \(device).")
                    }
                } else {
                    Text("Already kept in sync from another device, in “\(mirrored.calendarName)”. Its events are in Tenshu already.")
                    Button {
                        model.viewModel.keepInSync(calendar: calendar, remindersEnabled: remind)
                    } label: {
                        Choice(
                            title: "Keep in sync from this \(device) instead",
                            detail: "The same calendar, updated each time you open Calendar on this \(device) instead of from the other device. Nothing is copied twice."
                        )
                    }
                }
            } else {
                Button {
                    model.viewModel.doCopy(calendar: calendar)
                } label: {
                    Choice(
                        title: "Copy once",
                        detail: deciding.copiedInto.map { "Updates your earlier copy, “\($0)”. Changes made later in the other app are not copied." }
                            ?? "An editable copy in Tenshu. Changes made later in the other app are not copied."
                    )
                }
                Button {
                    model.viewModel.keepInSync(calendar: calendar, remindersEnabled: remind)
                } label: {
                    Choice(
                        // ⚠ Says *when* it updates: iOS gives Calendar no background refresh, so a
                        // promise of "whenever it changes" would be untrue here, unlike on Android.
                        title: "Keep in sync",
                        detail: "Read-only in Tenshu, and updated each time you open Calendar on this \(device). It stays up to date only while this \(device) has the account."
                            + (deciding.copiedInto.map { " It is a new calendar: your copy “\($0)” stays, and you can delete it afterwards." } ?? "")
                    )
                }
            }
        } header: {
            Text("“\(calendar.name)”")
        }
        Section {
            Toggle("Also remind me from Tenshu", isOn: $remind)
        } footer: {
            Text("For a kept-in-sync calendar. Your other calendar app already reminds you, so this is usually off.")
        }
        Section {
            Button("Back") { model.viewModel.backToList() }
        }
    }
}

private struct Choice: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(.primary)
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
    }
}

private struct Message: View {
    let text: String
    @ObservedObject var model: ExternalCopyModel

    var body: some View {
        Section {
            Text(text)
            Button("Done") { model.viewModel.dismiss() }
        }
    }
}

private struct FailedSection: View {
    let problem: ExternalCopyProblem
    @ObservedObject var model: ExternalCopyModel

    var body: some View {
        Section {
            Text(problem.message)
            // ⚠⚠ Once refused, asking again returns at once without a prompt, so the only way
            // forward is Settings — a retry button here would fail silently forever.
            if problem == .permissionDenied {
                Button {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    UIApplication.shared.open(url)
                } label: {
                    Label("Open Settings", systemImage: "gear")
                }
            }
            Button("Close") { model.viewModel.dismiss() }
        }
    }
}

@MainActor
final class ExternalCopyModel: ObservableObject {
    let viewModel = CalendarSurface.shared.externalCopyViewModel()

    @Published var state: ExternalCopyUiState?

    /// "iPhone" or "iPad": the copy names the device in the user's hand.
    static let device = UIDevice.current.model

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here.
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    /// Presentation is derived from the shared state, never a local flag.
    var visibleBinding: Binding<Bool> {
        Binding(get: { self.state?.isOpen == true }, set: { if !$0 { self.viewModel.dismiss() } })
    }

    var isCopying: Bool {
        guard let step = state?.step else { return false }
        if case .copying = onEnum(of: step) { return true }
        return false
    }

    /// The button: ask for access here, at the one place it is needed, then open the chooser.
    ///
    /// ⚠⚠ The prompt shows once per install. A refusal opens the sheet on a reason with a route to
    /// Settings rather than doing nothing — a button that silently does nothing reads as broken.
    func start() {
        Task {
            if await Self.requestAccess() {
                viewModel.open()
            } else {
                viewModel.permissionDenied()
            }
        }
    }

    /// ⚠ Both paths, because the deployment target is iOS 16: `requestFullAccessToEvents` exists
    /// only from 17, and on 17 the old `requestAccess(to:)` grants nothing readable. Write-only
    /// access (iOS 17) cannot read events, so it counts as a refusal.
    private static func requestAccess() async -> Bool {
        let store = EKEventStore()
        if #available(iOS 17.0, *) {
            if EKEventStore.authorizationStatus(for: .event) == .fullAccess { return true }
            return (try? await store.requestFullAccessToEvents()) ?? false
        } else {
            if EKEventStore.authorizationStatus(for: .event) == .authorized { return true }
            return await withCheckedContinuation { continuation in
                store.requestAccess(to: .event) { granted, _ in continuation.resume(returning: granted) }
            }
        }
    }
}

extension MirroredSource {
    /// The chooser row's line — the Compose sheet's `mirroredNote`.
    var note: String {
        let from = boundHere ? "this \(ExternalCopyModel.device)" : "another device"
        return "Kept in sync from \(from), in “\(calendarName)”"
    }
}

extension ExternalAccountGroup {
    /// Two accounts may share a name across types ("Work" on Exchange and on CalDAV).
    var accountKey: String { "\(accountType)|\(accountName)" }
}

extension ExternalCopyProblem {
    var message: String {
        let device = ExternalCopyModel.device
        switch self {
        case .permissionDenied:
            return "Calendar is not allowed to read this \(device)'s calendars. You can allow it in Settings, under Tenshu Calendar › Calendars."
        case .unreadable:
            return "This \(device)'s calendars could not be read."
        case .nothingToCopy:
            return "None of this calendar's events could be read, so nothing was copied."
        case .copyFailed:
            return "The events could not be saved. Check that Tenshu Control is set up, then try again."
        case .syncFailed:
            return "Keeping this calendar in sync could not start. Check that Tenshu Control is set up, then try again."
        }
    }
}

extension ExternalCopyStepMirrored {
    /// The Compose sheet's wording. ⚠ An empty or not-yet-read source says so rather than
    /// claiming "0 events": the refresher never applies an empty read (it could be an account
    /// mid-resync), so the calendar honestly has nothing yet.
    var message: String {
        let name = "“\(calendarName)”"
        let device = ExternalCopyModel.device
        func count(_ n: Int32) -> String { n == 1 ? "1 event" : "\(n) events" }
        guard let outcome else {
            return "\(name) is now kept in sync from this \(device). Its events will arrive with the next update."
        }
        switch onEnum(of: outcome) {
        case .synced(let synced):
            let total = synced.result.written + synced.result.unchanged
            let skipped = synced.result.skipped
            let main = "\(name) is now kept in sync from this \(device) — \(count(total)) so far."
            return skipped > 0 ? "\(main) \(count(skipped)) could not be read and were left out." : main
        case .notApplied:
            return "\(name) is now kept in sync from this \(device). It has no events to show yet."
        default:
            return "\(name) is now kept in sync from this \(device). Its events will arrive with the next update."
        }
    }
}

extension ExternalCopyResult {
    /// ⚠⚠ The Compose sheet's wording, in its precedence order. A second copy reports what was
    /// *new*: claiming "copied 412 events" again would make a user wonder whether they now have two
    /// of each. Skipped events are named, never swallowed.
    var message: String {
        func count(_ n: Int32) -> String { n == 1 ? "1 event" : "\(n) events" }
        let name = "“\(calendarName)”"
        let main: String
        if copied == 0 {
            main = "\(name) has no events to copy yet. It was added, empty."
        } else if added == 0 {
            main = "\(name) is up to date — \(count(copied)) checked, none new."
        } else if added < copied {
            main = "Copied \(count(added)) into \(name) (\(copied - added) were already there)."
        } else {
            main = "Copied \(count(copied)) into \(name)."
        }
        return skipped > 0 ? "\(main) \(count(skipped)) could not be read and were left out." : main
    }
}
