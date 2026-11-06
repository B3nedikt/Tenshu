package app.tenshu.control.ui.screens.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.AppRegistry
import app.tenshu.control.data.InstalledApp
import app.tenshu.control.data.OpenHandlerChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the Apps list. */
data class AppsUiState(
    val apps: List<InstalledApp> = emptyList(),
    /**
     * The *"opens by default"* rows (`control-cross-app-open.md` TT-4b).
     *
     * ⚠ Empty is the ordinary case, not a loading state: only a type with two-or-more candidates
     * installed **here** is a choice, and today only `place` has two candidates at all. The section
     * is absent rather than empty-stated — an explanation of a setting the user cannot make is
     * noise on the main Apps screen.
     */
    val openHandlerChoices: List<OpenHandlerChoice> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Exposes the installed-app list (newest install first) from [AppRegistry] as a
 * [StateFlow]. Mirrors `BackgroundSyncSettingsViewModel`: collects the repo flow
 * in [viewModelScope] and projects it into a UI-state holder.
 */
class AppsViewModel(
    private val appRegistry: AppRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            appRegistry.installedApps().collect { apps ->
                _uiState.update { it.copy(apps = apps, loading = false) }
            }
        }
        // ⚠⚠ A second collector rather than a `combine`: the choices flow depends on a preference
        // relation that stays silent on a realm that never held the row, and folding it into the
        // app list would make the whole Apps screen wait on it (`core-preference-service.md` §6.5).
        viewModelScope.launch {
            appRegistry.openHandlerChoices().collect { choices ->
                _uiState.update { it.copy(openHandlerChoices = choices) }
            }
        }
    }

    /**
     * Set — or with a `null` [appId] clear — the user's primary handler for [type].
     *
     * ⚠ No optimistic update: the write goes to a synced realm row and the read flow brings the
     * result back, so echoing the tap locally would race the store and could show a selection the
     * resolver would not honour.
     */
    fun onPrimaryHandlerChosen(type: String, appId: String?) {
        viewModelScope.launch { appRegistry.setPrimaryHandler(type, appId) }
    }
}
