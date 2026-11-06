package app.tenshu.home.ui.screens.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.home.data.HomeActivityRepository
import app.tenshu.home.model.ActivityEvent
import app.tenshu.home.model.ActivityKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityUiState(
    val events: List<ActivityEvent> = emptyList(),
    /** null = All. */
    val filter: ActivityKind? = null,
    val loaded: Boolean = false,
) {
    /** The feed after the kind filter — what the screen renders. */
    val filteredEvents: List<ActivityEvent>
        get() = if (filter == null) events else events.filter { it.kind == filter }
}

/**
 * Activity feed view model (docs/proposals/home-and-mia-apps.md §3, M2): the merged
 * chronological stream with one kind filter — Devices / Automations / Voice — mirroring
 * how Google Home's Activity tab slices the same feed.
 */
class ActivityViewModel(
    repository: HomeActivityRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.events().collect { v -> _uiState.update { it.copy(events = v, loaded = true) } }
        }
    }

    /** Selecting the active filter again clears it back to All. */
    fun toggleFilter(kind: ActivityKind) =
        _uiState.update { it.copy(filter = if (it.filter == kind) null else kind) }
}
