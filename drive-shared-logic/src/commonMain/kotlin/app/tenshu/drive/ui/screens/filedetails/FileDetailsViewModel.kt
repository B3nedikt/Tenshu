package app.tenshu.drive.ui.screens.filedetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.model.toUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * View model for [FileDetailsScreen].
 *
 * Seeds the header from [filePath] (name from the last segment, generic icon) so
 * the screen has a title before any fetch lands, then streams the real metadata
 * from [FilesRepository.fileDetails] (header + size / created / last-modified)
 * and the modification history from [FilesRepository.fileActivity] — two
 * independent reactive streams, each updating its slice of the state.
 *
 * The header preview rides the SAME details stream as a deferred property
 * (`core-deferred-properties.md`): metadata renders instantly, the preview
 * bytes patch in when the proxy resolves them. No separate loader fetch —
 * this screen is the first producer-side @defer consumer; list surfaces keep
 * the per-row loader (proposal §6).
 */
class FileDetailsViewModel(
    private val filePath: String,
    private val filesRepository: FilesRepository,
) : ViewModel() {

    val uiState: StateFlow<FileDetailsUiState> get() = _uiState
    private val _uiState: MutableStateFlow<FileDetailsUiState> = MutableStateFlow(
        FileDetailsUiState(
            file = FileUiModel(
                path = filePath,
                name = filePath.substringAfterLast("/"),
                subLine = "",
                previewIcon = FileIcon.GenericFile,
            )
        )
    )

    init {
        viewModelScope.launch {
            filesRepository.fileDetails(filePath).collect { details ->
                if (details == null) return@collect
                _uiState.update { state ->
                    // The preview arrives via the DEFERRED stream patch
                    // (`core-deferred-properties.md`): the first emission carries
                    // metadata with previewBytes null, the patch re-emits the row
                    // with bytes. A later full re-evaluation (parent-folder churn)
                    // transiently re-emits null-before-repatch — keep the last
                    // known bytes so the header never flickers back to the icon.
                    val incoming = details.file.toUiModel()
                    val merged = if (incoming.previewBytes == null) {
                        incoming.copy(previewBytes = state.file.previewBytes)
                    } else {
                        incoming
                    }
                    state.copy(
                        file = merged,
                        size = details.size,
                        created = details.created,
                        lastModified = details.lastModified,
                    )
                }
            }
        }
        viewModelScope.launch {
            filesRepository.fileActivity(filePath).collect { activity ->
                _uiState.update { it.copy(activity = activity) }
            }
        }
    }
}
