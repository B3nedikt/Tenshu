package app.tenshu.drive.ui.common.createfolder

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
 * Backs the new-folder dialog for a folder created inside [folderPath].
 *
 * Shaped exactly like [app.tenshu.drive.ui.common.rename.RenameFileDialogViewModel] — the two
 * dialogs write the same kind of thing (a path built from a name the user typed) and fail the same
 * way (`CreateFolderImplementation` rejects a path that already exists), so they answer with the
 * same ui state: a terminal [CreateFolderDialogUiState.created] the host closes on, and an
 * [CreateFolderDialogUiState.errorMessage] **token** the screen resolves to localised copy.
 *
 * ⚠ This used to hold English copy *in the view model* (`"A folder with this name already
 * exists."`), which ADR 0016 forbids. Compose never displayed it — the dialog read it only as a
 * presence flag and rendered its own localised string — but iOS printed it verbatim, and either
 * way a non-collision failure (out of storage, unknown) rendered the *collision* message and
 * offered *Keep both*, a remedy for a problem the user did not have.
 */
class CreateFolderDialogViewModel(
    private val filesRepository: FilesRepository,
    private val folderPath: String = ""
) : ViewModel() {

    val uiState: StateFlow<CreateFolderDialogUiState> get() = _uiState
    private val _uiState = MutableStateFlow(CreateFolderDialogUiState())

    fun onFolderNameChanged(folderName: String) {
        _uiState.update {
            it.copy(
                folderName = folderName,
                canCreate = canCreate(folderName),
                // Recomputed, not merely cleared: a refusal describes the name that was submitted,
                // so editing it drops the message, while an invalid name reports itself as typed.
                errorMessage = validate(folderName),
            )
        }
    }

    fun createFolder() {
        val state = _uiState.value
        if (!state.canCreate || state.isCreating) return

        _uiState.update { it.copy(isCreating = true, errorMessage = null) }
        viewModelScope.launch {
            finish(filesRepository.createFolder(pathFor(state.folderName)))
        }
    }

    /**
     * The collision remedy: create `name (1)`, `name (2)`, … until one lands. Offered only when
     * the refusal was [ERROR_ALREADY_EXISTS] — it answers nothing else, which is exactly what the
     * old freetext-as-presence-flag could not express.
     */
    fun keepBoth() {
        val state = _uiState.value
        if (!state.canKeepBoth || state.isCreating) return

        _uiState.update { it.copy(isCreating = true, errorMessage = null) }
        viewModelScope.launch {
            var counter = 1
            while (counter < KEEP_BOTH_LIMIT) {
                val result = filesRepository.createFolder(pathFor("${state.folderName} ($counter)"))
                // Only a collision justifies another attempt; anything else is reported as it is,
                // rather than burned through 98 more times.
                if (result is RepositoryResult.Error &&
                    result.error is RepositoryError.AlreadyExists
                ) {
                    counter++
                    continue
                }
                finish(result)
                return@launch
            }
            _uiState.update { it.copy(isCreating = false, errorMessage = ERROR_FAILED) }
        }
    }

    private fun finish(result: RepositoryResult<*>) {
        _uiState.update {
            when (result) {
                is RepositoryResult.Success -> it.copy(isCreating = false, created = true)
                is RepositoryResult.Error ->
                    it.copy(isCreating = false, errorMessage = result.error.toMessageKey())
            }
        }
    }

    // "" and "/" are both the root (the default parameter is the empty string), and a trailing
    // slash on a folder path must not double up.
    private fun pathFor(name: String): String = "${folderPath.trimEnd('/')}/$name"

    private fun canCreate(name: String): Boolean = name.isNotBlank() && validate(name) == null

    /**
     * ⚠⚠ A `/` is **path structure, not text**: the new folder's path is this name joined to
     * [folderPath], so `a/b` creates a row at a nested path whose *parent* need not exist —
     * `CreateFolderImplementation` checks only that the path itself is free, never that its parent
     * has a row. The folder would be reachable by path and listed in nothing. Same rule, and the
     * same reason, as the rename dialog's.
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
        is RepositoryError.InsufficientStorage -> ERROR_INSUFFICIENT_STORAGE
        // ⚠ Never produced by a create today — only moves refuse provisional rows (CR-2 B5 §4) or
        // offline moves of undownloaded content (B6 D3).
        RepositoryError.StillSyncing,
        RepositoryError.MoveNeedsLocalBytes,
        is RepositoryError.UnexpectedError -> ERROR_FAILED
        is RepositoryError.Unknown -> ERROR_FAILED
    }

    companion object {
        const val ERROR_ALREADY_EXISTS = "already_exists"
        const val ERROR_INSUFFICIENT_STORAGE = "insufficient_storage"
        const val ERROR_FAILED = "failed"

        /** Client-side validation, not a repository outcome — see [validate]. */
        const val ERROR_NAME_HAS_SEPARATOR = "name_has_separator"

        /** Safety stop for [keepBoth]'s counter. */
        private const val KEEP_BOTH_LIMIT = 100
    }
}

/**
 * @property folderName the typed name.
 * @property canCreate whether it is submittable — non-blank and free of path structure.
 * @property isCreating a write is in flight.
 * @property created terminal success; the host closes the dialog on it.
 * @property errorMessage a stable token (see the view model's companion), resolved to localised
 *   copy by the screen; null when there is nothing to report.
 */
data class CreateFolderDialogUiState(
    val folderName: String = "",
    val canCreate: Boolean = false,
    val isCreating: Boolean = false,
    val created: Boolean = false,
    val errorMessage: String? = null,
) {
    /** *Keep both* answers a name collision and nothing else. */
    val canKeepBoth: Boolean
        get() = errorMessage == CreateFolderDialogViewModel.ERROR_ALREADY_EXISTS
}
