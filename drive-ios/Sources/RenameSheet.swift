import SwiftUI
import TenshuSdk

// Rename dialog (full-parity UI, step B), bound to the real `RenameFileDialogViewModel`.
// Presented from the file detail screen and from the Files row menu.
//
// The sheet stays up until the write settles, because a rename can be REFUSED — `MoveFile`
// rejects a target the parent already holds. It used to dismiss on the tap, so a collision was
// indistinguishable from success: the sheet closed and the file kept its name, silently.
struct RenameSheet: View {
    let filePath: String
    /// Handed the in-flight rename view model when the user confirms, so a host that addresses
    /// the file **by path** can retain it and watch for the new one. The sheet reports it rather
    /// than the outcome itself: on success it is dismissing at that very moment, and its
    /// `@StateObject` goes with it — the ownership problem `FilesModel.lastActionsVm` also solves.
    var onRenameStarted: ((RenameFileDialogViewModel) -> Void)?

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: RenameModel

    init(filePath: String, onRenameStarted: ((RenameFileDialogViewModel) -> Void)? = nil) {
        self.filePath = filePath
        self.onRenameStarted = onRenameStarted
        _model = StateObject(wrappedValue: RenameModel(filePath: filePath))
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $model.name)
                    .submitLabel(.done)
                if let message = errorMessage {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("rename-error")
                }
            }
            .navigationTitle("Rename")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Rename") {
                        onRenameStarted?(model.rename())
                    }
                    // `canRename` covers blank AND unchanged — submitting the current name would
                    // be reported as "already exists" about the file itself.
                    .disabled(!model.state.canRename || model.state.isRenaming)
                }
            }
            .task { await model.observe() }
            // Close on the COMMIT, never on the tap.
            .onChange(of: model.state.renamedPath) { newPath in
                if newPath != nil { dismiss() }
            }
        }
    }

    /// The view model reports a stable token; the UI owns the copy (ADR 0016). The Compose twin
    /// resolves the same two tokens against `rename_file_dialog_error_*`.
    private var errorMessage: String? {
        switch model.state.errorMessage {
        case nil: return nil
        case RenameFileDialogViewModel.companion.ERROR_ALREADY_EXISTS:
            return "An item with this name already exists here."
        case RenameFileDialogViewModel.companion.ERROR_NAME_HAS_SEPARATOR:
            return "A name cannot contain a slash (/). Use Move to put the item in another folder."
        case RenameFileDialogViewModel.companion.ERROR_STILL_SYNCING:
            return "This item is still syncing to this device. Try again shortly."
        case RenameFileDialogViewModel.companion.ERROR_MOVE_NEEDS_LOCAL_BYTES:
            return "You're offline and this item isn't downloaded. Download it first, or rename it once you're online."
        default:
            return "Couldn't rename the item. Please try again."
        }
    }
}

@MainActor
final class RenameModel: ObservableObject {
    @Published var state: RenameFileDialogUiState
    /// The text field's binding. Writes through to the view model, which owns `canRename` and
    /// clears any refusal message as the name is edited.
    var name: String {
        get { state.newName }
        set { vm.onNewNameChanged(name: newValue) }
    }

    private let vm: RenameFileDialogViewModel

    init(filePath: String) {
        let vm = DriveMutationSurface.shared.rename(filePath: filePath)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await state in vm.uiState { self.state = state }
    }

    /// Starts the rename and returns the VM driving it, so the caller can hand it to a host that
    /// outlives this sheet (see `RenameSheet.onRenameStarted`).
    @discardableResult
    func rename() -> RenameFileDialogViewModel {
        vm.renameFile()
        return vm
    }
}
