import SwiftUI
import TenshuSdk

// The batch-import history (`fit-data-sources-restructure.md` §4) — the SwiftUI twin of
// `BatchImportScreen`.
//
// ⚠ No auto-import switch and no "connected" state, because Takeout is an **act** rather than a
// standing connection: it has no source row, and everything here derives from
// `HealthImportRun(kind = TAKEOUT)` (D11).
//
// ⚠⚠ The picker is **Swift's**, not Kotlin's. `BatchImportViewModel.canImportFiles` is false on
// iOS because no `HealthFilePicker` is bound — the shell has owned `.fileImporter` since P3a, and
// routing it back through Kotlin would duplicate a seam SwiftUI already owns. So this view
// ignores that flag and renders its own button, exactly as Profile used to.
struct BatchImportView: View {
    @StateObject private var model = BatchImportModel()
    @State private var showingImporter = false

    var body: some View {
        List {
            Section {
                Button {
                    showingImporter = true
                } label: {
                    Label("Import again", systemImage: "square.and.arrow.down")
                }
                .disabled(model.importing)

                if model.importing {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(model.importDetail).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } footer: {
                Text("Pick the .zip Google emailed you, or a single .gpx / .tcx workout file.")
            }

            Section("Import history") {
                if model.state?.history.isEmpty ?? true {
                    // ⚠ "No imports yet", never "no data" — the user has imported nothing, which
                    // is a fact about their actions rather than about their health.
                    Text("No imports yet. Pick a Google Takeout export and its history lands here.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                ForEach(Array((model.state?.history ?? []).enumerated()), id: \.offset) { _, entry in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(importRunHeadline(entry)).font(.callout)
                        Text(importRunDetail(entry)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Google Takeout")
        .navigationBarTitleDisplayMode(.inline)
        // `.item` rather than a tight UTType list: providers label a Takeout export
        // inconsistently (some report octet-stream), and a filter that misses it hides the file
        // the user came here for.
        .fileImporter(isPresented: $showingImporter, allowedContentTypes: [.item]) { result in
            model.importPicked(result)
        }
        .task { await model.observe() }
    }
}

@MainActor
final class BatchImportModel: ObservableObject {
    @Published var state: BatchImportUiState?
    /// Set while a pick is being read + written, so the button cannot be fired twice.
    @Published var importing = false
    @Published var importDetail = ""

    private let vm = FitSurface.shared.batchImportViewModel()

    func observe() async {
        for await v in vm.uiState { state = v }
    }

    /// Copies the picked document into our own container, then hands the copy to Kotlin.
    ///
    /// ⚠ The copy is not incidental (the Drive P3a lesson): the security scope is released the
    /// moment this callback returns, and the import runs async for minutes afterwards — reading the
    /// original there fails for any out-of-sandbox document, invisibly, because the picker itself
    /// succeeded. A Takeout export is large, so this does cost a second copy on disk; it is deleted
    /// as soon as the import finishes.
    func importPicked(_ result: Result<URL, Error>) {
        guard case let .success(url) = result else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let copy = dir.appendingPathComponent(url.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: url, to: copy)
        } catch {
            importDetail = "Could not read that file"
            return
        }

        importing = true
        importDetail = "Importing \(url.lastPathComponent)…"
        Task {
            // `try?` matches the existing suspend-bridge convention (`FitBackgroundRefresh`):
            // SKIE surfaces Kotlin cancellation as a Swift throw, and a cancelled import is not an
            // error worth surfacing — the repository's own `importStatus` already reports outcomes.
            let outcome = (try? await FitSurface.shared.importPickedFile(fileUrl: copy.absoluteString))
                ?? "file import: cancelled"
            try? FileManager.default.removeItem(at: dir)
            importing = false
            importDetail = outcome
        }
    }
}
