package app.tenshu.drive.ui.screens.home

import app.tenshu.drive.ui.screens.RowPrefetchBounds
import app.tenshu.drive.ui.screens.RowPrefetchLimiter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.model.toUiModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home surface. `recentFiles` is the N most-recently-modified files across all
 * folders, newest first — sourced from [FilesRepository.recentFiles]
 * (`root.files.traverse().sortByDescending(File.LastModifiedAt)`), see
 * docs/proposals/file-service-history.md §5.1 (Phase 6).
 */
class HomeScreenViewModel(
    private val selectionViewModel: SelectionViewModel,
    private val filesRepository: FilesRepository,
) : ViewModel() {

    val uiState: StateFlow<HomeScreenUiState> get() = _uiState
    private val _uiState: MutableStateFlow<HomeScreenUiState> = MutableStateFlow(
        HomeScreenUiState(
            recentFiles = emptyList(),
            searchBarFiles = emptyList(),
        )
    )

    init {
        viewModelScope.launch {
            filesRepository.recentFiles().collect { load ->
                _uiState.update { state ->
                    when (load) {
                        // Keep whatever is on screen: a read that broke after answering should
                        // not blank the list, only say it is no longer current.
                        ListLoadState.Loading -> state.copy(recentFilesLoad = ListLoadPhase.LOADING)
                        ListLoadState.Failed -> state.copy(recentFilesLoad = ListLoadPhase.FAILED)
                        is ListLoadState.Loaded -> state.copy(
                            recentFiles = load.items.map(FileModel::toUiModel),
                            recentFilesLoad = ListLoadPhase.LOADED,
                        )
                    }
                }
            }
        }
    }

    val selectedPaths = selectionViewModel.selectedPaths

    /** This device's effective sync-rule set — each row resolves its offline
     *  glyph against it (`docs/proposals/drive-offline-online.md` §4). */
    val syncRules = selectionViewModel.syncRules

    fun onFileInResentFilesClicked(index: Int) {
        _uiState.update { it.copy(showFile = uiState.value.recentFiles[index]) }
    }

    fun hideFile() {
        _uiState.update { it.copy(showFile = null) }
    }

    fun showFileActions(index: Int) {
        val file = uiState.value.recentFiles[index]
        _uiState.update { it.copy(showFileActionsWithPath = file.path, showFileActionsForFolder = file.isFolder) }
    }

    /**
     * Overflow actions for a [FileUiModel] that isn't a recent-files row — the
     * search-bar results, which index into a different list. Distinct name from
     * the index-based [showFileActions] so the `::showFileActions` method
     * reference on the recent-files list stays unambiguous.
     */
    fun showFileActionsForFile(file: FileUiModel) {
        _uiState.update { it.copy(showFileActionsWithPath = file.path, showFileActionsForFolder = file.isFolder) }
    }

    fun hideFileActions() {
        _uiState.update { it.copy(showFileActionsWithPath = null) }
    }

    fun showRenameFile(path: String) {
        _uiState.update { it.copy(showRenameFileWithPath = path) }
    }

    fun hideRenameFile() {
        _uiState.update { it.copy(showRenameFileWithPath = null) }
    }

    fun toggleSelection(index: Int) {
        val file = uiState.value.recentFiles[index]
        selectionViewModel.toggleSelection(file.path, file.isFolder)
    }

    fun clearSelection() {
        selectionViewModel.clearSelection()
    }

    /** Viewport-scoped prefetch bound — see [RowPrefetchBounds]. */
    private val previewConcurrency = RowPrefetchLimiter(RowPrefetchBounds.PREVIEW)

    /**
     * Per-row preview loader for the Recent-Files list. Returns the raw
     * preview/thumbnail bytes on success, `null` for any failure (no preview,
     * cancellation). The UI decodes them off the UI thread (the VM stays
     * Compose-free for iOS — §9.3). The row's `produceState` cancels this
     * (releasing the permit) when it scrolls out of composition.
     */
    suspend fun loadPreviewBytes(path: String, width: Int, height: Int): ByteArray? {
        return previewConcurrency.withSlot {
            filesRepository.getPreview(path, width, height)
        }
    }

    /** Viewport-scoped prefetch bound — see [RowPrefetchBounds]. */
    private val localDataConcurrency = RowPrefetchLimiter(RowPrefetchBounds.LOCAL_DATA)

    /**
     * Per-row byte-presence probe for the Recent-Files list (the v2 presence
     * signal, `docs/proposals/drive-offline-online.md` §3). `true` when the
     * file's bytes are on local disk. Bounded + cancelled-on-scroll exactly
     * like [loadPreview].
     */
    suspend fun loadLocalData(path: String): Boolean =
        localDataConcurrency.withSlot { filesRepository.hasLocalData(path) }
}
