package app.tenshu.drive.ui.common.addfileactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.keepBothCandidateName
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AddFileActionsViewModel(
    private val filesRepository: FilesRepository,
    private val folderUpload: FolderUploadController,
) : ViewModel() {

    val uiState: StateFlow<AddFileActionsUiState> get() = _uiState
    private val _uiState: MutableStateFlow<AddFileActionsUiState> = MutableStateFlow(
        AddFileActionsUiState()
    )

    init {
        // The folder-upload loop lives in the app-scoped FolderUploadController
        // (Phase 3a — it must outlive this ViewModel). The ViewModel only
        // mirrors the controller's state into the FAB UI state; a recreated
        // ViewModel re-attaches to an in-flight upload via this collector.
        viewModelScope.launch {
            folderUpload.state.collect { fu ->
                _uiState.update { it.copy(folderUpload = fu) }
            }
        }
    }

    fun showAddFileActions() {
        _uiState.update { it.copy(showAddFileActions = true) }
    }

    fun hideAddFileActions() {
        _uiState.update { it.copy(showAddFileActions = false) }
    }

    fun showCreateFolder() {
        _uiState.update { it.copy(showAddFolder = true) }
    }

    fun hideCreateFolder() {
        _uiState.update { it.copy(showAddFolder = false) }
    }

    fun uploadFile(uriString: String, parentPath: String) {
        viewModelScope.launch {
            val fileName = filesRepository.getFileName(uriString) ?: "Unknown"
            val result = filesRepository.uploadFile(uriString, parentPath, fileName)
            handleUploadResult(result, uriString, parentPath, fileName)
        }
    }

    /** Dismiss the failure message raised by a blocked or failed upload. */
    fun dismissUploadError() {
        _uiState.update { it.copy(uploadError = null) }
    }

    /**
     * Report an upload that failed **before** the repository was reached — the host could not
     * turn the user's pick into bytes at all (an unreadable document, a photo whose file
     * representation would not load).
     *
     * Without this the host's only option is to drop the pick on the floor, which is the same
     * silence [uploadFile]'s own failure branch used to have: the user chose a file and nothing
     * whatsoever happened.
     */
    fun reportUploadFailed() {
        _uiState.update { it.copy(uploadError = ERROR_UPLOAD_FAILED) }
    }

    private fun handleUploadResult(
        result: RepositoryResult<Unit>,
        uriString: String,
        parentPath: String,
        fileName: String
    ) {
        when (result) {
            is RepositoryResult.Success -> {
                _uiState.update { it.copy(uploadConflict = null) }
            }

            is RepositoryResult.Error -> when (result.error) {
                is RepositoryError.AlreadyExists -> _uiState.update {
                    it.copy(uploadConflict = UploadConflict(uriString, parentPath, fileName))
                }
                // Subscribed but over quota (ADR 0019): surface the storage dialog
                // instead of silently dropping the failure. A *missing* subscription
                // doesn't reach here — local-first uploads succeed locally.
                is RepositoryError.InsufficientStorage -> _uiState.update {
                    it.copy(storageGateBlock = StorageGateDecision.Full)
                }
                // ⚠ This was `else -> Unit`: an upload that failed for any other reason left no
                // trace at all — the user picked a file and it silently never appeared.
                RepositoryError.StillSyncing,
                RepositoryError.MoveNeedsLocalBytes,
                is RepositoryError.UnexpectedError,
                is RepositoryError.Unknown -> _uiState.update {
                    it.copy(uploadError = ERROR_UPLOAD_FAILED)
                }
            }
        }
    }

    fun resolveConflictKeepBoth() {
        val conflict = _uiState.value.uploadConflict ?: return
        viewModelScope.launch {
            var counter = 1
            while (counter < KEEP_BOTH_LIMIT) {
                val candidateName = keepBothCandidateName(conflict.fileName, counter)
                val result = filesRepository.uploadFile(
                    uriString = conflict.uriString,
                    parentPath = conflict.parentPath,
                    fileName = candidateName
                )
                // Only a collision justifies another candidate name.
                if (result is RepositoryResult.Error &&
                    result.error is RepositoryError.AlreadyExists
                ) {
                    counter++
                    continue
                }
                // ⚠ Anything else ends the loop AND is reported. The quota gate has its own
                // dialog; every other failure used to end the loop saying nothing, so *Keep
                // both* looked like it had worked.
                if (result is RepositoryResult.Error) {
                    _uiState.update {
                        when (result.error) {
                            is RepositoryError.InsufficientStorage ->
                                it.copy(storageGateBlock = StorageGateDecision.Full)

                            else -> it.copy(uploadError = ERROR_UPLOAD_FAILED)
                        }
                    }
                }
                _uiState.update { it.copy(uploadConflict = null) }
                return@launch
            }
            // Ran out of candidate names — say so, rather than reporting a generic failure after
            // 99 silent attempts.
            _uiState.update {
                it.copy(uploadConflict = null, uploadError = ERROR_TOO_MANY_COPIES)
            }
        }
    }

    fun cancelConflict() {
        _uiState.update { it.copy(uploadConflict = null) }
    }

    /** Dismiss the storage (over-quota) dialog raised by a blocked upload. */
    fun dismissStorageGate() {
        _uiState.update { it.copy(storageGateBlock = null) }
    }

    // --- Folder upload -----------------------------------------------------
    //
    // Thin delegation to the app-scoped FolderUploadController. The loop,
    // blanket-choice / pending-conflict state, and the auto-dismiss timer all
    // live there so an in-flight upload + its cancel lever outlive this
    // ViewModel (Phase 3a). The UI surface (App.kt / FolderUploadUi) is
    // unchanged — it still calls these methods and observes
    // `uiState.folderUpload`, now mirrored from the controller (see `init`).

    fun uploadFolder(uriString: String, parentPath: String) =
        folderUpload.uploadFolder(uriString, parentPath)

    fun cancelFolderUpload() = folderUpload.cancel()

    fun dismissFolderUpload() = folderUpload.dismiss()

    fun resolveFolderConflict(resolution: ConflictResolution, applyToAll: Boolean) =
        folderUpload.resolveConflict(resolution, applyToAll)

    fun resolveFolderCloudPreflight(decision: CloudPreflightDecision) =
        folderUpload.resolvePreflight(decision)

    companion object {
        /** Stable tokens for [AddFileActionsUiState.uploadError]; the screen owns the copy. */
        const val ERROR_UPLOAD_FAILED = "upload_failed"
        const val ERROR_TOO_MANY_COPIES = "too_many_copies"

        /** Safety stop for [resolveConflictKeepBoth]'s counter. */
        private const val KEEP_BOTH_LIMIT = 100
    }
}
