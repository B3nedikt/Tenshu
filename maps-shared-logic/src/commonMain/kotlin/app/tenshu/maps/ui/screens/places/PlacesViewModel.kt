package app.tenshu.maps.ui.screens.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.maps.data.PlaceRepository
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.PlaceSection
import app.tenshu.maps.model.SavedList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Places surface's four secondary tabs (`maps-agent.md` §1.1). */
enum class MapsTab { Yours, Saved, ForYou, Contribute }

data class PlacesUiState(
    val tab: MapsTab = MapsTab.Yours,
    val yours: List<Place> = emptyList(),
    val saved: List<Place> = emptyList(),
    val savedLists: List<SavedList> = emptyList(),
    val recommended: List<Place> = emptyList(),
)

/**
 * Places view model (Ph3, docs/proposals/maps-app.md §6). Owns all four tabs — Your places /
 * Saved / For you / Contribute are tabs *inside* the single Places surface (D1), so they fold
 * into one VM rather than separate destinations. The section split is derived from the one
 * `places()` stream; `setSaved` writes through the repo, which re-emits so every tab updates.
 * Contribute carries no mock data — it is a queued stub (D3), rendered as a static surface.
 */
class PlacesViewModel(
    private val repository: PlaceRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlacesUiState())
    val uiState: StateFlow<PlacesUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.places().collect { places ->
                _uiState.update {
                    it.copy(
                        yours = places.filter { p -> p.section == PlaceSection.Yours },
                        saved = places.filter { p -> p.section == PlaceSection.Saved },
                        recommended = places.filter { p -> p.section == PlaceSection.Recommended },
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.savedLists().collect { lists -> _uiState.update { it.copy(savedLists = lists) } }
        }
    }

    fun selectTab(tab: MapsTab) = _uiState.update { it.copy(tab = tab) }

    /** Bookmark toggle — the Saved-tab toggle, the detail header, and "For you" Save. */
    fun setSaved(id: String, saved: Boolean) {
        viewModelScope.launch { repository.setSaved(id, saved) }
    }
}
