package app.tenshu.drive.ui.common.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.common.deletePreviewTempFile
import app.tenshu.drive.ui.common.writePreviewTempFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the byte fetch + decrypt-to-temp-file step for "open in another app"
 * (`docs/proposals/drive-content-preview.md` §6, Decision D3) — the residual
 * fallback for documents / archives / unhandled types the [UnsupportedPreviewScreen]
 * can't render. Unlike the image/PDF/video viewers it does **no** work up front: the
 * fetch only runs when the user taps the action ([prepareOpen]), since most files
 * reaching this screen are never opened externally.
 *
 * On tap it fetches the decrypted bytes via [FilesRepository.getRemoteFile] (the same
 * path image/PDF/video use), writes them to a private temp file, and surfaces the
 * path as a **one-shot** [OpenExternallyUiState.readyToOpen] signal the screen consumes
 * to hand the file to the OS. A second tap reuses the already-written file rather than
 * re-fetching.
 *
 * ⚠ The temp file is transient decrypted plaintext on disk, and handing it to another
 * app is a deliberate user-initiated disclosure (threat-model A5 amendment). The file
 * is deleted in [onCleared] when the screen leaves the back stack.
 */
class OpenExternallyViewModel(
    private val filePath: String,
    private val filesRepository: FilesRepository,
) : ViewModel() {

    val uiState: StateFlow<OpenExternallyUiState> get() = _uiState
    private val _uiState = MutableStateFlow(OpenExternallyUiState())

    private var tempFilePath: String? = null

    /** Fetch + materialise the file (if not already done), then signal the screen to open it. */
    fun prepareOpen() {
        if (_uiState.value.isPreparing) return
        // Already materialised on a prior tap → just re-fire the open signal.
        tempFilePath?.let {
            _uiState.value = OpenExternallyUiState(readyToOpen = it)
            return
        }
        _uiState.value = OpenExternallyUiState(isPreparing = true)
        viewModelScope.launch {
            when (val result = filesRepository.getRemoteFile(filePath)) {
                is RepositoryResult.Success -> {
                    val extension = filePath.substringAfterLast('.', "")
                    val path = withContext(Dispatchers.Default) {
                        writePreviewTempFile(result.value, extension)
                    }
                    if (path != null) {
                        tempFilePath = path
                        _uiState.value = OpenExternallyUiState(readyToOpen = path)
                    } else {
                        _uiState.value = OpenExternallyUiState(failed = true)
                    }
                }

                is RepositoryResult.Error ->
                    _uiState.value = OpenExternallyUiState(failed = true)
            }
        }
    }

    /** Acknowledge that the screen has handed [OpenExternallyUiState.readyToOpen] to the OS. */
    fun onOpenLaunched() {
        _uiState.value = _uiState.value.copy(readyToOpen = null)
    }

    override fun onCleared() {
        tempFilePath?.let { deletePreviewTempFile(it) }
        tempFilePath = null
    }
}

/**
 * @property isPreparing the byte fetch + temp-file write is in flight (show a spinner).
 * @property readyToOpen absolute temp-file path to hand to the OS, consumed once then
 *   cleared via [OpenExternallyViewModel.onOpenLaunched].
 * @property failed the fetch or temp-file write failed (show an error).
 */
data class OpenExternallyUiState(
    val isPreparing: Boolean = false,
    val readyToOpen: String? = null,
    val failed: Boolean = false,
)
