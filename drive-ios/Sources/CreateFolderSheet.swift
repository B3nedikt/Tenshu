import SwiftUI
import TenshuSdk

// New-folder dialog (full-parity UI, step B), bound to the real `CreateFolderDialogViewModel`.
// Presented from the Files toolbar "+" menu for the folder currently being browsed.
//
// The sheet stays up until the write settles, and a name collision offers **Keep both** — the same
// remedy the Compose dialog has. It previously showed the view model's own English string and no
// remedy at all, so a collision here was a dead end.
struct CreateFolderSheet: View {
    let folderPath: String

    @Environment(\.dismiss) private var dismiss
    @StateObject private var model: CreateFolderModel

    init(folderPath: String) {
        self.folderPath = folderPath
        _model = StateObject(wrappedValue: CreateFolderModel(folderPath: folderPath))
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Folder name", text: $model.name)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                if let message = errorMessage {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("create-folder-error")
                }
                if model.state.canKeepBoth {
                    Button("Keep both") { model.keepBoth() }
                        .disabled(model.state.isCreating)
                        .accessibilityIdentifier("create-folder-keep-both")
                }
            }
            .navigationTitle("New Folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { model.create() }
                        .disabled(!model.state.canCreate || model.state.isCreating)
                }
            }
            .task { await model.observe() }
            // Close on the write, not on the tap.
            .onChange(of: model.state.created) { created in
                if created { dismiss() }
            }
        }
    }

    /// The view model reports a stable token; the UI owns the copy (ADR 0016). The Compose twin
    /// resolves the same tokens against `create_folder_dialog_error_*`.
    private var errorMessage: String? {
        switch model.state.errorMessage {
        case nil: return nil
        case CreateFolderDialogViewModel.companion.ERROR_ALREADY_EXISTS:
            return "Name already taken. Keep both or choose a different name."
        case CreateFolderDialogViewModel.companion.ERROR_NAME_HAS_SEPARATOR:
            return "A name cannot contain a slash (/). Create the folder, then open it to add folders inside."
        case CreateFolderDialogViewModel.companion.ERROR_INSUFFICIENT_STORAGE:
            return "Not enough storage to create this folder."
        default:
            return "Couldn't create the folder. Please try again."
        }
    }
}

@MainActor
final class CreateFolderModel: ObservableObject {
    @Published var state: CreateFolderDialogUiState
    /// The text field's binding. Writes through to the view model, which owns `canCreate` and the
    /// as-typed validation.
    var name: String {
        get { state.folderName }
        set { vm.onFolderNameChanged(folderName: newValue) }
    }

    private let vm: CreateFolderDialogViewModel

    init(folderPath: String) {
        let vm = DriveMutationSurface.shared.createFolder(folderPath: folderPath)
        self.vm = vm
        self.state = vm.uiState.value
    }

    func observe() async {
        for await state in vm.uiState { self.state = state }
    }

    // Both writes run on the view model's own scope, so they outlive this sheet — dismissing no
    // longer cancels the folder being created.
    func create() { vm.createFolder() }
    func keepBoth() { vm.keepBoth() }
}
