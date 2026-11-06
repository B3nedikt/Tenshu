package app.tenshu.drive.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [CreateShareLinkSheet]
 * (`docs/file-sharing.md`). Drives the *Generate link* op
 * + holds the resulting [ShareLinkUrl] so the *Copy link* button has both
 * halves (`shareId` + URL fragment) to assemble the canonical URL.
 */
class CreateShareLinkViewModel(
    private val filesRepository: FilesRepository,
    filePath: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CreateShareLinkUiState(
            filePath = filePath,
            fileName = filePath.substringAfterLast("/"),
        )
    )
    val uiState: StateFlow<CreateShareLinkUiState> get() = _uiState

    /**
     * *Generate link* button. Drives [FilesRepository.shareLink] with the
     * current expiry / cap state and maps the typed [ShareLinkPayload] into
     * a [ShareLinkUrl] the sheet renders + *Copy link* writes to clipboard.
     */
    fun onGenerateLinkClicked() {
        val state = _uiState.value
        if (state.isCreating) return
        _uiState.update { it.copy(isCreating = true, errorMessage = null) }
        viewModelScope.launch {
            val result = filesRepository.shareLink(
                path = state.filePath,
                expiresAtMillis = state.expiresAtMillis ?: 0L,
                maxOpens = state.maxOpens ?: 0,
            )
            when (result) {
                is RepositoryResult.Success -> _uiState.update {
                    it.copy(
                        isCreating = false,
                        link = ShareLinkUrl(
                            shareId = result.value.shareId,
                            urlFragment = result.value.urlFragment,
                        ),
                        errorMessage = null,
                    )
                }
                is RepositoryResult.Error -> _uiState.update {
                    it.copy(
                        isCreating = false,
                        errorMessage = result.error.toString(),
                    )
                }
            }
        }
    }

    /** *Copy link* button. Returns the string the platform clipboard should hold. */
    fun onCopyLinkRequested(): String? = _uiState.value.link?.toCopyableString()

    /** Optional expiry picker — `null` clears. */
    fun onExpirySelected(epochMillis: Long?) {
        _uiState.value = _uiState.value.copy(expiresAtMillis = epochMillis)
    }

    /** Optional use-cap picker — `null` clears. */
    fun onMaxOpensSelected(cap: Int?) {
        _uiState.value = _uiState.value.copy(maxOpens = cap)
    }
}
