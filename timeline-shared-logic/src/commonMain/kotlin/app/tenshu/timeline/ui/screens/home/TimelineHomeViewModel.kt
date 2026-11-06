package app.tenshu.timeline.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.timeline.data.TimelineRepository
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Day
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Default personalisation strength (§5) — a tweak/setting later; a constant for v1. */
const val DEFAULT_PERSONALISATION_STRENGTH: Float = 0.7f

/** The Home surface is a single screen with three M3 secondary tabs (§4.1). */
enum class HomeTab { Home, Places, Activities }

data class TimelineHomeUiState(
    val tab: HomeTab = HomeTab.Home,
    val days: List<Day> = emptyList(),
    val suggestion: TripSuggestion? = null,
    val boundTrips: List<Trip> = emptyList(),
    val places: List<Place> = emptyList(),
    val activities: List<ActivityRollup> = emptyList(),
    val personalisationStrength: Float = DEFAULT_PERSONALISATION_STRENGTH,
    /**
     * Whether the store has answered yet (B3).
     *
     * ⚠⚠ **Empty and not-yet-loaded are different, and telling them apart is the whole point.**
     * Every list here starts empty, so a screen that renders "you have no history" from
     * `days.isEmpty()` alone shows that sentence — to a user with ten years of it — for as long as
     * the first read takes. An empty state is a *claim about the user's data*; making it before the
     * data has spoken is how a slow read becomes "Tenshu lost my timeline".
     */
    val daysLoaded: Boolean = false,
    val placesLoaded: Boolean = false,
    val activitiesLoaded: Boolean = false,
) {
    /**
     * Nothing at all — no days, no bound chapters. This is the [import][hasNothing] case B3 turns
     * into the primary call to action.
     *
     * ⚠ Distinct from a *tab* being empty: a user with history but no named places has plenty of
     * data, and "nothing here yet" on the Places tab must not read as "you have no timeline".
     */
    val hasNothing: Boolean get() = daysLoaded && days.isEmpty() && boundTrips.isEmpty()
}

/**
 * Home view model (Ph3, docs/proposals/timeline-app.md §7). Owns all three tabs' data —
 * Places / Activities are tabs *inside* Home, not separate destinations (§4.1), so they
 * fold into one VM rather than the separate places/activities VMs the earlier §7 sketch
 * listed. Interlude accept/dismiss mutate the repo's suggestion state.
 */
class TimelineHomeViewModel(
    private val repository: TimelineRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineHomeUiState())
    val uiState: StateFlow<TimelineHomeUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.homeDays().collect { v -> _uiState.update { it.copy(days = v, daysLoaded = true) } }
        }
        viewModelScope.launch { repository.tripSuggestion().collect { v -> _uiState.update { it.copy(suggestion = v) } } }
        viewModelScope.launch { repository.boundTrips().collect { v -> _uiState.update { it.copy(boundTrips = v) } } }
        viewModelScope.launch {
            repository.places().collect { v -> _uiState.update { it.copy(places = v, placesLoaded = true) } }
        }
        viewModelScope.launch {
            repository.activities()
                .collect { v -> _uiState.update { it.copy(activities = v, activitiesLoaded = true) } }
        }
    }

    fun selectTab(tab: HomeTab) = _uiState.update { it.copy(tab = tab) }

    fun setPersonalisationStrength(strength: Float) =
        _uiState.update { it.copy(personalisationStrength = strength.coerceIn(0f, 1f)) }

    /** "Not a trip" — dismiss the Interlude. */
    fun dismissSuggestion() { viewModelScope.launch { repository.dismissTripSuggestion() } }

    /** "Read as a trip" — accept the Interlude (v1 mock: clears it). */
    fun acceptSuggestion() { viewModelScope.launch { repository.acceptTripSuggestion() } }
}
