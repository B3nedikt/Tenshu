import SwiftUI
import TenshuSdk

// File detail + actions (full-parity UI, step B). A SwiftUI screen bound to the real
// `FileDetailsViewModel` (header + metadata + activity history) and `FileActionsViewModel`
// (offline pin, delete) from `:drive-shared-logic`, wired for iOS by `DriveDetailSurface` over the
// injected repository. Pushed from the Files list when a file row is tapped.
struct FileDetailView: View {
    let path: String
    let name: String
    let isFolder: Bool
    /// How to leave once this file is no longer at `path` (deleted or moved away). The shell
    /// supplies a pop that unwinds the *whole* file chain — see `dismissDeadPath()`. Optional so
    /// the DEV_HARNESS probe, which mounts this screen without a `FilesRouter`, still builds.
    let onFileGone: (() -> Void)?
    /// Where the file went after a **rename**. Unlike delete/move this is not a departure: the
    /// shell re-addresses the chain to `newPath` so the user stays on the file they renamed.
    let onFileRenamed: ((String) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: FileDetailModel
    @State private var showingRename = false
    @State private var showingMove = false
    @State private var showingShareLink = false

    init(
        path: String,
        name: String,
        isFolder: Bool,
        onFileGone: (() -> Void)? = nil,
        onFileRenamed: ((String) -> Void)? = nil
    ) {
        self.path = path
        self.name = name
        self.isFolder = isFolder
        self.onFileGone = onFileGone
        self.onFileRenamed = onFileRenamed
        _model = StateObject(wrappedValue: FileDetailModel(path: path, isFolder: isFolder))
    }

    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: model.details.file.previewIcon.sfSymbol)
                        .font(.largeTitle)
                        .foregroundStyle(isFolder ? Color.accentColor : .secondary)
                        .frame(width: 44)
                    Text(model.details.file.name).font(.title3).bold()
                }
                .padding(.vertical, 4)
            }

            Section("Details") {
                detailRow("Size", model.details.size)
                detailRow("Created", model.details.created)
                detailRow("Modified", model.details.lastModified)
            }

            if !model.details.activity.isEmpty {
                Section("Activity") {
                    ForEach(Array(model.details.activity.enumerated()), id: \.offset) { _, entry in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(activityLabel(entry)).font(.body)
                            Text(entry.deviceLabel).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section {
                Button {
                    model.toggleOffline()
                } label: {
                    Label(
                        model.actions.isAvailableOffline ? "Remove download" : "Make available offline",
                        systemImage: model.actions.isAvailableOffline ? "icloud.slash" : "arrow.down.circle"
                    )
                }
                Button { showingRename = true } label: {
                    Label("Rename", systemImage: "pencil")
                }
                Button { showingMove = true } label: {
                    Label("Move", systemImage: "folder")
                }
                Button { showingShareLink = true } label: {
                    Label("Share Link", systemImage: "link")
                }
                Button(role: .destructive) {
                    model.delete()
                    dismissDeadPath()
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .sheet(isPresented: $showingRename) {
            RenameSheet(filePath: path, onRenameStarted: { model.trackRename($0) })
        }
        // A rename is a `MoveFile`, so `path` dies with it — this screen's every action (delete,
        // move, share link, offline pin) and the preview beneath it would go on addressing an
        // entry that no longer exists. Re-address instead of leaving: the rename succeeded, so
        // the file is still there to look at, just under a new name. Fires only once the write
        // COMMITTED (`renamedPath`), never on the tap — re-addressing optimistically would point
        // the preview at a path the move had not created yet, which is the same dead-path read
        // by a different route. A failed rename leaves this nil and the screen correctly stays.
        .onChange(of: model.renamedPath) { newPath in
            if let newPath { onFileRenamed?(newPath) }
        }
        .sheet(isPresented: $showingMove) {
            MovePickerView(sourcePath: path, onMoved: { dismissDeadPath() })
        }
        .sheet(isPresented: $showingShareLink) {
            CreateShareLinkSheet(filePath: path)
        }
    }

    /// Leaves this screen after an action that invalidated `path` (delete, move).
    ///
    /// A bare `dismiss()` pops ONE hop, and the hop below the detail screen is that same file's
    /// `FilePreviewView` — so deleting a file landed the user back on its preview, which then
    /// failed to load its bytes and reported "Couldn't load this file" over a file the user had
    /// just deleted on purpose. The shell's `onFileGone` pops the preview with it, back to the
    /// folder the file was opened from.
    private func dismissDeadPath() {
        if let onFileGone {
            onFileGone()
        } else {
            dismiss()
        }
    }

    private func detailRow(_ label: String, _ value: String?) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value ?? "—")
        }
    }

    private func activityLabel(_ entry: FileActivity) -> String {
        let kind: String
        switch entry.kind {
        case .created: kind = "Created"
        case .modified: kind = "Modified"
        case .renamed: kind = "Renamed"
        case .moved: kind = "Moved"
        @unknown default: kind = "Changed"
        }
        if let detail = entry.detail, !detail.isEmpty { return "\(kind) · \(detail)" }
        return kind
    }
}

// Bridges the detail + actions view models to SwiftUI. Both `uiState` StateFlows are seeded
// synchronously and then observed (SKIE renders them as `AsyncSequence`s); the action handlers are
// fire-and-forget on the VMs' `viewModelScope`.
@MainActor
final class FileDetailModel: ObservableObject {
    @Published var details: FileDetailsUiState
    @Published var actions: FileActionsUiState
    /// The file's new path once a rename has committed (nil until then, and after a failed one).
    @Published var renamedPath: String?

    private let bound: DriveDetailSurface.Bound
    // Retains the in-flight rename VM so its `viewModelScope` coroutine outlives the sheet that
    // spawned it (the `FilesModel.lastActionsVm` precedent), plus the task watching its outcome.
    private var renameVm: RenameFileDialogViewModel?
    private var renameWatch: Task<Void, Never>?

    init(path: String, isFolder: Bool) {
        let bound = DriveDetailSurface.shared.create(path: path, isFolder: isFolder)
        self.bound = bound
        self.details = bound.details.uiState.value
        self.actions = bound.actions.uiState.value
    }

    func observe() async {
        async let d: Void = observeDetails()
        async let a: Void = observeActions()
        _ = await (d, a)
    }

    private func observeDetails() async {
        for await state in bound.details.uiState { details = state }
    }

    private func observeActions() async {
        for await state in bound.actions.uiState { actions = state }
    }

    func toggleOffline() { bound.actions.onDownloadClicked() }
    func delete() { bound.actions.onDeleteClicked() }

    /// Adopts a rename started by the sheet and publishes the file's new path when it commits —
    /// only then, never on the tap, since the write can be refused (`uiState.errorMessage`, which
    /// the sheet renders while staying up). `uiState` is a `StateFlow` and never completes, hence
    /// the explicit cancellation.
    func trackRename(_ vm: RenameFileDialogViewModel) {
        renameVm = vm
        renameWatch?.cancel()
        renameWatch = Task { [weak self] in
            for await state in vm.uiState {
                guard let newPath = state.renamedPath else { continue }
                self?.renamedPath = newPath
                return
            }
        }
    }

    deinit { renameWatch?.cancel() }
}
