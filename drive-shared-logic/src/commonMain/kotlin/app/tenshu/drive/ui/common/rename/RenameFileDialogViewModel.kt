package app.tenshu.drive.ui.common.rename

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs the rename dialog for the file or folder at [filePath].
 *
 * ⚠ A rename is a `MoveFile` — it **changes the path**, because the path is the file's identity
 * (`file-content-addressing.md` §3.3.1). Two consequences shape this view model:
 *
 *  - the write can be **refused** (the parent already holds that name), and the entry then keeps
 *    its old path — so a host must not act as though it succeeded;
 *  - on success the file has a **new address**, which a host that renders it by path needs
 *    ([RenameFileDialogUiState.renamedPath]).
 *
 * Both ride the ui state rather than being discarded, which is what this used to do: the result
 * of the write was dropped on the floor, so a name collision closed the dialog and changed
 * nothing, on every surface except the web (which re-implements this in TypeScript and reports
 * it). Shaped after [app.tenshu.drive.ui.screens.movepicker.MovePickerViewModel] — the sibling
 * with the same act-then-close interaction — down to the error tokens.
 */
class RenameFileDialogViewModel(
    private val filesRepository: FilesRepository,
    private val filePath: String
) : ViewModel() {

    private val currentName = filePath.substringAfterLast("/")

    val uiState: StateFlow<RenameFileDialogUiState> get() = _uiState
    private val _uiState = MutableStateFlow(
        RenameFileDialogUiState(newName = currentName, canRename = false)
    )

    fun onNewNameChanged(name: String) {
        _uiState.update {
            it.copy(
                newName = name,
                canRename = canRename(name),
                // Recomputed, not merely cleared: a refusal describes the name that was
                // submitted, so editing it drops the message (as the create-folder dialog does),
                // while an invalid name reports itself as it is typed rather than waiting for a
                // submit that is disabled anyway.
                errorMessage = validate(name),
            )
        }
    }

    fun renameFile() {
        val state = _uiState.value
        // Blank, unchanged, or already in flight — nothing to write. Unchanged is the load-bearing
        // one: `MoveFile` rejects a target that exists, and the file's OWN path exists, so
        // submitting the current name would report "already exists" about the file itself. The
        // move picker disables "Move Here" over its no-op for exactly this reason; hosts bind
        // [RenameFileDialogUiState.canRename] to the confirm button so it cannot be reached, and
        // this guard makes that a property of the view model rather than of each UI.
        if (!state.canRename || state.isRenaming) return

        _uiState.update { it.copy(isRenaming = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = filesRepository.renameFile(filePath, state.newName)) {
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(isRenaming = false, renamedPath = result.value)
                }

                is RepositoryResult.Error -> _uiState.update {
                    it.copy(isRenaming = false, errorMessage = result.error.toMessageKey())
                }
            }
        }
    }

    private fun canRename(name: String): Boolean =
        name.isNotBlank() && name != currentName && validate(name) == null

    /**
     * Reject a name that is not a name.
     *
     * ⚠⚠ A `/` is **path structure, not text**: the new path is this name joined to the parent, so
     * `sub/b.txt` silently turns a rename into a *move* — and into a folder that need not exist,
     * since `MoveFileImplementation` validates only that the TARGET is free, never that its parent
     * has a row. The file would be re-keyed somewhere reachable by path and listed in nothing.
     *
     * This is the Dropbox / OneDrive / Windows answer (reject the separator with a message), and
     * it is the one that fits: Google Drive can afford to allow `/` because a Drive "name" is a
     * label on an ID, while here the path *is* the identity (`file-content-addressing.md`
     * §3.3.1). Moving by typing a path would be a deliberate feature — a destination field, not a
     * side effect of string concatenation — and the move picker is what we have instead.
     */
    private fun validate(name: String): String? =
        if (name.contains('/')) ERROR_NAME_HAS_SEPARATOR else null

    /**
     * Map the repository error to a stable message token the screen resolves to a localised
     * string. Kept as a token (not the freetext message) so the UI owns the user-facing copy
     * per ADR 0016.
     */
    private fun RepositoryError.toMessageKey(): String = when (this) {
        RepositoryError.AlreadyExists -> ERROR_ALREADY_EXISTS
        RepositoryError.StillSyncing -> ERROR_STILL_SYNCING
        RepositoryError.MoveNeedsLocalBytes -> ERROR_MOVE_NEEDS_LOCAL_BYTES
        is RepositoryError.UnexpectedError -> ERROR_FAILED
        is RepositoryError.Unknown -> ERROR_FAILED
        is RepositoryError.InsufficientStorage -> ERROR_FAILED
    }

    companion object {
        const val ERROR_ALREADY_EXISTS = "already_exists"

        /** CR-2 B5 §4: a provisional slice-snapshot row; transient, so the copy says to retry. */
        const val ERROR_STILL_SYNCING = "still_syncing"

        /** CR-2 B6 D3: offline, and this device does not hold the bytes — download first, or go online. */
        const val ERROR_MOVE_NEEDS_LOCAL_BYTES = "move_needs_local_bytes"
        const val ERROR_FAILED = "failed"

        /** Client-side validation, not a repository outcome — see [validate]. */
        const val ERROR_NAME_HAS_SEPARATOR = "name_has_separator"
    }
}

/**
 * @property newName the edited name, seeded with the file's current one.
 * @property canRename whether the name is submittable — non-blank and actually different.
 * @property isRenaming a write is in flight.
 * @property renamedPath the file's **new path**, set once the rename commits; null until then and
 *   after a refusal. A host addressing the file by path re-addresses itself to this — iOS's detail
 *   screen sits on top of that file's preview and both hops carry the path.
 * @property errorMessage a stable token (see the view model's companion), resolved to localised
 *   copy by the screen; null when there is nothing to report.
 */
data class RenameFileDialogUiState(
    val newName: String,
    val canRename: Boolean = false,
    val isRenaming: Boolean = false,
    val renamedPath: String? = null,
    val errorMessage: String? = null,
)
