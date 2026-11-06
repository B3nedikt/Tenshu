import SwiftUI
import TenshuSdk
import UniformTypeIdentifiers

/// `.ics` import and export for one calendar (CA-6b on iOS). The SwiftUI twin of the Compose
/// `CalendarInteropSheet`.
///
/// ⚠⚠ **No `CalendarFileAccess` actual.** That `expect`/`actual` exists because Android's
/// `ActivityResult` launchers may only be registered during composition; SwiftUI has
/// `.fileImporter` / `.fileExporter` as plain view modifiers. The shared half of CA-6b is
/// `CalendarInteropViewModel`, which knows nothing about pickers and takes `.ics` *text* — so
/// this file is the platform layer the seam was drawn to allow, not a way around it.
///
/// ⚠ Every rule here is the shared view model's: zero events out of a non-empty file is a
/// failure, skipped entries are named, and the export text is produced *before* the save
/// dialog opens. This file renders them; it re-implements none of them.
struct CalendarInteropSheet: View {
    @ObservedObject var model: CalendarInteropModel

    var body: some View {
        NavigationStack {
            Form {
                if let state = model.state {
                    // ⚠ Branching on the shared `showingChoices` rather than re-deriving
                    // "nothing has happened yet" here: the Compose sheet does the same, and two
                    // copies of that condition is two things that can disagree about what the
                    // sheet is for.
                    //
                    // ⚠ `choosingCalendar` has no branch. It is the state a file handed over by
                    // the host OS lands in (CA-6c, desktop only today), and iOS reaches this
                    // sheet from a calendar row, which has already answered the question. It
                    // gains one the day an *Open in* handler lands here.
                    if state.isBusy {
                        Section { ProgressView().frame(maxWidth: .infinity) }
                    } else if state.showingChoices {
                        ChoicesSection(model: model)
                    } else {
                        ResultSection(text: state.resultMessage, model: model)
                    }
                }
            }
            .navigationTitle(model.state?.calendarName ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { model.viewModel.dismiss() }
                }
            }
        }
        // ⚠⚠ Nothing is marked busy before this opens. `.fileImporter` is not reliably
        // documented to report a cancellation on every iOS version this app supports, and a
        // spinner shown on a callback that may never arrive is a sheet the user cannot leave.
        // The picker covers this view anyway, so there is nothing for a spinner to say.
        .fileImporter(
            isPresented: model.importingBinding,
            allowedContentTypes: CalendarInteropSheet.importableTypes
        ) { result in
            model.importPicked(result)
        }
        .fileExporter(
            isPresented: model.exportingBinding,
            document: model.exportDocument,
            contentType: .tenshuIcs,
            // ⚠ The extension is stripped: SwiftUI appends the content type's own, and
            // `suggestedFileName` already ends in `.ics` for the Compose savers that do not.
            defaultFilename: model.suggestedBaseName
        ) { result in
            model.exportFinished(result)
        }
    }

    /// ⚠ `.data` is the backstop, and it earns its place for the reason `ICS_MIME_TYPES` names
    /// on Android: a provider that reports a `.ics` as generic data would otherwise render the
    /// file the user is looking at unselectable, which is worse than letting them pick the
    /// wrong thing and be told it holds no events.
    private static let importableTypes: [UTType] = [.tenshuIcs, .calendarEvent, .text, .data]
}

private struct ChoicesSection: View {
    @ObservedObject var model: CalendarInteropModel

    var body: some View {
        Section {
            // ⚠ Not for a subscribed or mirrored calendar: its next refresh would delete the import.
            if model.state?.canImport == true {
                Button {
                    model.startImport()
                } label: {
                    Label("Import from a file", systemImage: "square.and.arrow.down")
                }
            }
            Button {
                model.startExport()
            } label: {
                Label("Export to a file", systemImage: "square.and.arrow.up")
            }
        } footer: {
            Text(
                "Importing adds the events in an .ics file to this calendar. Exporting saves "
                + "this calendar as an .ics file you can open anywhere."
            )
        }
    }
}

private struct ResultSection: View {
    let text: String
    @ObservedObject var model: CalendarInteropModel

    var body: some View {
        Section {
            Text(text)
            Button("Do something else") { model.viewModel.acknowledgeResult() }
        }
    }
}

/// The export's bytes, for `.fileExporter`.
///
/// ⚠ Write-only by intent: reading a `.ics` goes through `CalendarRepository.importIcs`, not
/// through `FileDocument`, because the import is a *write into a calendar* rather than opening a
/// document the app then owns. `init(configuration:)` exists only to satisfy the protocol.
struct IcsDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.tenshuIcs] }

    let text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        // ⚠ UTF-8, which RFC 5545 §6 makes the default charset for `text/calendar`.
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

extension UTType {
    /// ⚠ `com.apple.ical.ics` is the system type for a `.ics` **file**, and the one with a
    /// filename extension. `public.calendar-event` is the abstract type it conforms to and would
    /// leave the exporter with no extension to append. The fallback is for the theoretical
    /// platform that does not declare it.
    static let tenshuIcs = UTType("com.apple.ical.ics") ?? .calendarEvent
}

@MainActor
final class CalendarInteropModel: ObservableObject {
    let viewModel = CalendarSurface.shared.calendarInteropViewModel()

    @Published var state: CalendarInteropUiState?
    @Published var importing = false
    @Published var exporting = false
    @Published private(set) var exportDocument: IcsDocument?

    func observe() async {
        // SKIE renders a `StateFlow` as a throwing `AsyncSequence`; it never actually throws here.
        do { for try await value in viewModel.uiState { state = value } } catch {}
    }

    func open(_ calendar: CalendarSummary) {
        viewModel.open(calendarId: calendar.calendarId, calendarName: calendar.name)
    }

    /// Presentation is derived from the shared state, never a local flag — the same rule
    /// `CalendarsModel`'s bindings follow, and for the same reason.
    var visibleBinding: Binding<Bool> {
        Binding(get: { self.state?.visible == true }, set: { if !$0 { self.viewModel.dismiss() } })
    }

    var importingBinding: Binding<Bool> {
        Binding(get: { self.importing }, set: { self.importing = $0 })
    }

    var exportingBinding: Binding<Bool> {
        Binding(get: { self.exporting }, set: { self.exporting = $0 })
    }

    /// ⚠ Without the `.ics`: see the `defaultFilename` note at the call site.
    var suggestedBaseName: String {
        let name = state?.suggestedFileName ?? "calendar.ics"
        return name.hasSuffix(".ics") ? String(name.dropLast(4)) : name
    }

    // MARK: - import

    func startImport() { importing = true }

    func importPicked(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else {
            // ⚠ A cancelled picker is a decision, not a failure: no message. Anything else is
            // a real read failure and gets one.
            if case .failure(let error) = result, !error.isUserCancelled {
                viewModel.pickFailed(problem: .unreadable)
            }
            return
        }

        Task {
            let outcome = await Self.readText(at: url)
            switch outcome {
            case .some(let text) where !text.isEmpty:
                viewModel.importText(text: text, origin: url.lastPathComponent)
            case .some:
                viewModel.pickFailed(problem: .emptyFile)
            case .none:
                viewModel.pickFailed(problem: .unreadable)
            }
        }
    }

    /// `nil` when the file could not be read at all.
    ///
    /// ⚠⚠ **The security-scoped access is not optional.** A URL from the document picker points
    /// outside the app's container, and reading it without the scope open fails with a
    /// permissions error on a device while working fine in a simulator run from Xcode.
    ///
    /// ⚠ Off the main actor, like the Android and desktop readers: a `.ics` is usually small and
    /// occasionally is not.
    private static func readText(at url: URL) async -> String? {
        await Task.detached(priority: .userInitiated) { () -> String? in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else { return nil }
            return String(decoding: data, as: UTF8.self)
        }.value
    }

    // MARK: - export

    /// ⚠⚠ **Two steps, in this order.** The text is produced *before* the save dialog opens, so
    /// a failure to read the calendar surfaces as an error rather than as a picker that appears
    /// and then writes nothing. The shared view model enforces the order; this honours it.
    func startExport() {
        viewModel.exportText { [weak self] text in
            guard let self else { return }
            self.exportDocument = IcsDocument(text: text)
            // ⚠ Clears the busy flag the export read set, *before* presenting the dialog. Named
            // for the picker it usually follows, but all it does is "stop being busy, say
            // nothing" — which is what this needs, so that a save dialog that dismisses without
            // reporting anything leaves the sheet on its choices rather than on a spinner.
            self.viewModel.pickCancelled()
            self.exporting = true
        }
    }

    func exportFinished(_ result: Result<URL, Error>) {
        exportDocument = nil
        switch result {
        case .success:
            viewModel.exportSaved()
        case .failure(let error):
            if !error.isUserCancelled { viewModel.exportFailed() }
        }
    }
}

private extension Error {
    /// ⚠ The user backing out arrives as an *error* from both pickers. Treating it as a failure
    /// would put "Could not write the file." on screen every time somebody changed their mind.
    var isUserCancelled: Bool {
        (self as? CocoaError)?.code == .userCancelled
            || (self as NSError).code == NSUserCancelledError
    }
}

extension CalendarInteropUiState {
    /// The one line the sheet shows once something has happened — the Swift twin of the Compose
    /// sheet's private `resultMessage()`, in the same precedence order.
    var resultMessage: String {
        if let problem = problem { return problem.message }
        if exported { return "Exported." }
        if let imported = lastImport { return imported.message }
        return ""
    }
}

extension InteropProblem {
    var message: String {
        switch self {
        case .emptyFile: return "That file is empty."
        case .nothingImported: return "Nothing in that file could be read as an event."
        case .exportFailed: return "Could not write the file."
        case .unreadable: return "Could not read that file."
        }
    }
}

extension IcsImportResult {
    /// ⚠⚠ **Skipped events are named, not swallowed.** An import that says "42 events" when the
    /// file held 44 is how a user loses two appointments and never finds out — which is why
    /// `IcsImportResult` carries the count at all. Word for word the Compose sheet's copy.
    var message: String {
        var text = "Imported \(imported) event" + (imported == 1 ? "" : "s")
        if overrides > 0 {
            text += " and \(overrides) changed occurrence" + (overrides == 1 ? "" : "s")
        }
        text += "."
        if skipped > 0 {
            text += " \(skipped) entr" + (skipped == 1 ? "y" : "ies")
            text += " could not be read and " + (skipped == 1 ? "was" : "were") + " left out."
        }
        return text
    }
}
