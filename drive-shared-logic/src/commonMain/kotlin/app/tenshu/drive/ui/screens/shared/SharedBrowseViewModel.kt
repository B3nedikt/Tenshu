package app.tenshu.drive.ui.screens.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.SharedFolderEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.file.shared.SharedFile

/**
 * Browses one shared realm's `SharedFile` tree
 * (`docs/proposals/drive-shared-realm-file-model.md` §6 Phase 3d.2b): lists the
 * children at [parentFileId] via [FilesRepository.browseSharedFolder], folders
 * drill in (the screen pushes a nested browse destination) and files open via
 * [FilesRepository.readSharedFile].
 *
 * **Realm-root unwrap.** A folder share is represented in the realm by a single
 * *wrapper* node at the realm root carrying the folder's name (3d.2b-i). So when
 * we open a realm at its root and find exactly one folder, we drill straight into
 * it — the user navigated to "the shared folder", not to a list containing it,
 * and the wrapper's name becomes this screen's title (closing the joiner-naming
 * gap: the name is read from realm content, not from a `Placement` the joiner
 * never sees). A realm whose root isn't a single folder is listed verbatim.
 */
class SharedBrowseViewModel(
    private val realmId: String,
    private val parentFileId: String,
    initialTitle: String,
    private val filesRepository: FilesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedBrowseUiState(title = initialTitle))
    val uiState: StateFlow<SharedBrowseUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = filesRepository.browseSharedFolder(realmId, parentFileId)) {
                is RepositoryResult.Success -> {
                    val entries = result.value
                    val wrapper = entries.singleOrNull()
                    if (parentFileId == SharedFile.ROOT_PARENT && wrapper != null && wrapper.isFolder) {
                        unwrapInto(wrapper)
                    } else {
                        _uiState.update { it.copy(entries = entries, loading = false, error = false) }
                    }
                }
                is RepositoryResult.Error ->
                    _uiState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** Drill straight into the lone realm-root wrapper folder (see KDoc). */
    private suspend fun unwrapInto(wrapper: SharedFolderEntry) {
        when (val inner = filesRepository.browseSharedFolder(realmId, wrapper.fileId)) {
            is RepositoryResult.Success ->
                _uiState.update {
                    it.copy(title = wrapper.name, entries = inner.value, loading = false, error = false)
                }
            is RepositoryResult.Error ->
                _uiState.update { it.copy(loading = false, error = true) }
        }
    }

    /**
     * Open a shared *file* (not a folder): fetch its bytes (OnDemand from the
     * realm's content store, reassembled realm-key-aware) and surface them. v1
     * shows a minimal readout (name + size); a rich in-app preview of shared-file
     * bytes is a follow-up — the personal-FS image preview is path-keyed and a
     * shared file has no personal path.
     */
    fun onFileClicked(entry: SharedFolderEntry) {
        if (entry.isFolder) return
        viewModelScope.launch {
            when (val result = filesRepository.readSharedFile(realmId, entry.fileId)) {
                is RepositoryResult.Success ->
                    _uiState.update {
                        it.copy(openedFile = OpenedSharedFile(entry.name, result.value.size))
                    }
                is RepositoryResult.Error ->
                    _uiState.update { it.copy(error = true) }
            }
        }
    }

    fun onOpenedFileDismissed() {
        _uiState.update { it.copy(openedFile = null) }
    }
}

/** UI state for [SharedBrowseViewModel]. */
data class SharedBrowseUiState(
    val title: String,
    val entries: List<SharedFolderEntry> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val openedFile: OpenedSharedFile? = null,
)

/** A shared file the user just opened (v1 minimal readout — see VM KDoc). */
data class OpenedSharedFile(
    val name: String,
    val sizeBytes: Int,
)
