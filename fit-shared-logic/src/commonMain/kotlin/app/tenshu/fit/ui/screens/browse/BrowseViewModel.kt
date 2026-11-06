package app.tenshu.fit.ui.screens.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.model.CategorySummary
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.fit.data.waitingWillFix
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val categories: List<CategorySummary> = emptyList(),
    /**
     * ⚠ Defaults to [ListLoadPhase.LOADING], and that default is the point. An empty
     * [categories] used to be the *only* state Browse could be in before the read answered, and
     * the screen reads "is any store-backed category empty?" to decide whether to show the
     * connect-a-data-source prompt — so a launch, or a failed read, rendered seven "Not set up"
     * cards and told the user to connect a source they may well already have connected.
     */
    val categoriesLoad: ListLoadPhase = ListLoadPhase.LOADING,
)

/** Browse (Ph3, docs/proposals/fit-app.md §2 screen 9): the seven category cards. */
class BrowseViewModel(
    repository: FitRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> get() = _uiState

    /**
     * Whether waiting will fix a failed read (`core-schema-skew-surface.md` §8.6) — see
     * [app.tenshu.fit.data.waitingWillFix]. Browse states the same claim Home does and had the
     * same problem.
     */
    val waitingWillFix: StateFlow<Boolean> = repository.waitingWillFix()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            repository.categories().collect { load ->
                _uiState.update { state ->
                    when (load) {
                        ListLoadState.Loading -> state.copy(categoriesLoad = ListLoadPhase.LOADING)
                        // Keep the cards already on screen: a read that broke after answering
                        // should say it is no longer current, not claim the store emptied.
                        ListLoadState.Failed -> state.copy(categoriesLoad = ListLoadPhase.FAILED)
                        is ListLoadState.Loaded -> state.copy(
                            categories = load.items,
                            categoriesLoad = ListLoadPhase.LOADED,
                        )
                    }
                }
            }
        }
    }
}
