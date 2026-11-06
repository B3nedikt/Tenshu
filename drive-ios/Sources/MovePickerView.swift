import SwiftUI
import TenshuSdk

// "Move to" destination picker, bound to the real `MovePickerViewModel`.
// Presented as a sheet from the file detail screen; the user browses folders and taps "Move Here".
// Each level is its own `MovePickerLevel` (fresh VM + paging presenter), drilled via push-nav —
// mirroring the Files list.
struct MovePickerView: View {
    let sourcePath: String
    var onMoved: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            MovePickerLevel(sourcePath: sourcePath, folderPath: "/", onClose: { dismiss() }, onMoved: handleMoved)
                .navigationDestination(for: MoveRoute.self) { route in
                    MovePickerLevel(sourcePath: sourcePath, folderPath: route.path, onClose: { dismiss() }, onMoved: handleMoved)
                }
        }
    }

    private func handleMoved() {
        dismiss()
        onMoved()
    }
}

struct MovePickerLevel: View {
    let sourcePath: String
    let folderPath: String
    var onClose: () -> Void
    var onMoved: () -> Void

    @StateObject private var model: MoveModel

    init(sourcePath: String, folderPath: String, onClose: @escaping () -> Void, onMoved: @escaping () -> Void) {
        self.sourcePath = sourcePath
        self.folderPath = folderPath
        self.onClose = onClose
        self.onMoved = onMoved
        _model = StateObject(wrappedValue: MoveModel(sourcePath: sourcePath, folderPath: folderPath))
    }

    var body: some View {
        List {
            // Enumerated so each row can record its access — see `MoveModel.onRowAppear`;
            // without it a destination deeper than the first page is unreachable.
            ForEach(Array(model.folders.enumerated()), id: \.element.path) { index, folder in
                NavigationLink(value: MoveRoute(path: folder.path, name: folder.name)) {
                    Label(folder.name, systemImage: "folder").foregroundStyle(.primary)
                }
                .onAppear { model.onRowAppear(index) }
            }
        }
        .listStyle(.plain)
        .overlay {
            if model.folders.isEmpty {
                Text("No subfolders here").foregroundStyle(.secondary)
            }
        }
        // A refused move says why, above the toolbar — the Compose twin renders the same tokens in
        // its bottom bar. Without it a refusal would only re-enable "Move Here".
        .safeAreaInset(edge: .bottom) {
            if let message = errorMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(.bar)
                    .accessibilityIdentifier("move-error")
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { onClose() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Move Here") { model.move() }
                    .disabled(!model.canMoveHere || model.isMoving)
            }
        }
        .task { await model.observe() }
        .onChange(of: model.moved) { moved in if moved { onMoved() } }
    }

    /// The view model reports a stable token; the UI owns the copy (ADR 0016). The Compose twin
    /// resolves the same tokens against `move_picker_*`.
    private var errorMessage: String? {
        switch model.errorMessage {
        case nil: return nil
        case MovePickerViewModel.companion.ERROR_ALREADY_EXISTS:
            return "An item with this name already exists here."
        case MovePickerViewModel.companion.ERROR_STILL_SYNCING:
            return "This item is still syncing to this device. Try again shortly."
        case MovePickerViewModel.companion.ERROR_MOVE_NEEDS_LOCAL_BYTES:
            return "You're offline and this item isn't downloaded. Download it first, or move it once you're online."
        default:
            return "Couldn't move the item. Please try again."
        }
    }
}

/// A drill-in hop into a candidate destination folder.
struct MoveRoute: Hashable {
    let path: String
    let name: String
}

@MainActor
final class MoveModel: ObservableObject {
    @Published var folders: [FileUiModel] = []
    @Published var canMoveHere: Bool
    @Published var isMoving: Bool = false
    @Published var moved: Bool = false
    @Published var errorMessage: String?
    let title: String

    private let bound: DriveMutationSurface.MoveBound

    init(sourcePath: String, folderPath: String) {
        let bound = DriveMutationSurface.shared.movePicker(sourcePath: sourcePath, folderPath: folderPath)
        self.bound = bound
        let state = bound.viewModel.uiState.value
        self.canMoveHere = state.canMoveHere
        self.title = folderPath == "/" ? "Move \u{201C}\(state.sourceName)\u{201D}" : state.currentFolderName
    }

    func observe() async {
        async let f: Void = observeFolders()
        async let s: Void = observeState()
        _ = await (f, s)
    }

    private func observeFolders() async {
        for await items in bound.presenter.snapshot { folders = items }
    }

    /// Paging's load-next trigger, same contract as `FilesModel.onRowAppear` — the snapshot
    /// only grows once an access near the end of the loaded range is recorded via `get`.
    func onRowAppear(_ index: Int) {
        _ = bound.presenter.get(index: Int32(index))
    }

    private func observeState() async {
        for await state in bound.viewModel.uiState {
            canMoveHere = state.canMoveHere
            isMoving = state.isMoving
            moved = state.moved
            errorMessage = state.errorMessage
        }
    }

    func move() { bound.viewModel.move() }
}
