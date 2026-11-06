package app.tenshu.drive.ui.screens.movepicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.model.toUiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.paging.filter as pagingFilter
import androidx.paging.map as pagingMap

/**
 * Backs the "Move to" destination picker for one or more files/folders.
 *
 * [sourcePaths] are the items being moved (a single item for the per-file
 * *Move* action, the whole selection for the multi-select *Move*); [folderPath]
 * is the folder currently being browsed (the host pushes a fresh VM per level as
 * the user drills in). The list is folders-only — you can only land *in* a
 * folder — and the sources' own rows are excluded so they can't be moved into
 * themselves (which also rules out descendants, since they're only reachable
 * through a source folder).
 */
class MovePickerViewModel(
    private val filesRepository: FilesRepository,
    val sourcePaths: List<String>,
    val folderPath: String,
) : ViewModel() {

    /** Single-source convenience — the per-file *Move* action and the iOS surface. */
    constructor(
        filesRepository: FilesRepository,
        sourcePath: String,
        folderPath: String,
    ) : this(filesRepository, listOf(sourcePath), folderPath)

    // Common parent iff every source lives in the same folder; null when the
    // selection spans folders. Moving into that shared parent is a pure no-op.
    private val commonParent: String? =
        sourcePaths.map { it.substringBeforeLast("/").ifEmpty { "/" } }.distinct().singleOrNull()

    val uiState: StateFlow<MovePickerUiState> get() = _uiState
    private val _uiState = MutableStateFlow(
        MovePickerUiState(
            sourceName = sourcePaths.singleOrNull()?.substringAfterLast("/") ?: "",
            sourceCount = sourcePaths.size,
            currentFolderName = if (folderPath == "/") "" else folderPath.substringAfterLast("/"),
            // Disabled when the browsed folder is the sources' shared parent (a
            // no-op move) or is itself one of the sources (can't move into self).
            canMoveHere = folderPath != commonParent && folderPath !in sourcePaths,
        )
    )

    /**
     * Folders in [folderPath], minus the sources' own rows.
     *
     * `cachedIn` for the same reason as
     * [app.tenshu.drive.ui.screens.files.FilesScreenViewModel.pagedFiles] — read that one for the
     * ban this used to carry and why it no longer applies. ⚠ The host pushes a **fresh VM per
     * level**, so each level's cache dies with its own `viewModelScope` rather than accumulating.
     */
    val pagedFolders: Flow<PagingData<FileUiModel>> =
        filesRepository.getFilesPaged(folderPath)
            .map { paging ->
                paging
                    .pagingMap(FileModel::toUiModel)
                    .pagingFilter { it.isFolder && it.path !in sourcePaths }
            }
            .cachedIn(viewModelScope)

    /**
     * Move every source into [folderPath], sequentially. Best-effort: a per-item
     * failure (e.g. a name clash) is recorded but doesn't abort the rest, so a
     * partial batch still lands. Succeeds only if every source moved.
     */
    fun move() {
        if (_uiState.value.isMoving) return
        _uiState.update { it.copy(isMoving = true, errorMessage = null) }
        viewModelScope.launch {
            var firstError: RepositoryError? = null
            for (source in sourcePaths) {
                when (val result = filesRepository.moveFile(source, folderPath)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Error ->
                        if (firstError == null) firstError = result.error
                }
            }
            _uiState.update {
                if (firstError == null) it.copy(isMoving = false, moved = true)
                else it.copy(isMoving = false, errorMessage = firstError.toMessageKey())
            }
        }
    }

    fun showNewFolder() {
        _uiState.update { it.copy(showNewFolder = true) }
    }

    fun hideNewFolder() {
        _uiState.update { it.copy(showNewFolder = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Map the repository error to a stable message token the screen resolves
     * to a localised string. Kept as a token (not the freetext message) so the
     * UI owns the user-facing copy per ADR 0016.
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
    }
}
