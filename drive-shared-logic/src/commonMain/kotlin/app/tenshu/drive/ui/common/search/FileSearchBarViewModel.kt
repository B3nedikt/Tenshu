package app.tenshu.drive.ui.common.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.ui.model.toUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Search-bar surface. Owns the query/active UI state and, unlike the earlier
 * stub, sources results from [FilesRepository.searchFiles] (case-insensitive
 * name substring across the whole drive). Per-row offline-glyph inputs
 * (`syncRules` / `localDataLoader`) stay hoisted from the host screen — those
 * are device/screen state, not file data (see [FilesSearchBar]).
 *
 * Results are search-as-you-type: [onQueryChanged] feeds a debounced pipeline
 * so a burst of keystrokes triggers a single drive-wide scan, not one per key.
 */
class FilesSearchBarViewModel(
    private val filesRepository: FilesRepository,
) : ViewModel() {

    val uiState: StateFlow<FileSearchBarUiState> get() = _uiState
    private val _uiState: MutableStateFlow<FileSearchBarUiState> = MutableStateFlow(
        FileSearchBarUiState()
    )

    /** Debounce source — the raw text; the pipeline trims + de-dups downstream. */
    private val queryFlow: MutableStateFlow<String> = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchPipeline = queryFlow
        .debounce(SEARCH_DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { query -> filesRepository.searchFiles(query) }
        .onEach { load ->
            _uiState.update { state ->
                when (load) {
                    ListLoadState.Loading -> state.copy(filesLoad = ListLoadPhase.LOADING)
                    ListLoadState.Failed -> state.copy(filesLoad = ListLoadPhase.FAILED)
                    is ListLoadState.Loaded -> state.copy(
                        files = load.items.map(FileModel::toUiModel),
                        filesLoad = ListLoadPhase.LOADED,
                    )
                }
            }
        }
        .launchIn(viewModelScope)

    fun onQueryChanged(s: String) {
        _uiState.update { it.copy(query = s) }
        queryFlow.value = s
    }

    fun onActiveChanged(active: Boolean) {
        _uiState.update { it.copy(isActive = active) }
        // Collapsing the bar clears the query + results so the next open is clean.
        if (!active) {
            queryFlow.value = ""
            _uiState.update { it.copy(query = "", files = emptyList()) }
        }
    }

    fun onSearchClicked(s: String) {
        // IME "search" action. The as-you-type pipeline has usually already run,
        // but keep the query authoritative in case the action fires first.
        _uiState.update { it.copy(query = s) }
        queryFlow.value = s
    }

    // Result taps and per-result overflow actions are navigation/host concerns:
    // the host screen hoists them (`FilesSearchBar.onResultClicked` /
    // `onResultMoreClicked`) and reuses the same handlers as its main listing,
    // so this Compose-free VM stays navigator-free.

    private companion object {
        /** Collapse a keystroke burst into one drive-wide scan (each is an IPC traverse). */
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
