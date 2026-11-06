package app.tenshu.control.ui.screens.settings.ingest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.control.data.BackgroundIngestManager
import app.tenshu.control.data.IngestSourceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackgroundIngestUiState(
    val loading: Boolean = true,
    val sources: List<IngestSourceModel> = emptyList(),
)

/**
 * Backs the "Background ingest" management screen (§8): the inventory of
 * registered ingest sources + their live status, with a per-source kill switch.
 *
 * Reads the [BackgroundIngestManager.sources] relation reactively (so a source
 * an app registers — or a status update it pushes — shows up live). The toggle
 * optimistically flips the row, then fires [BackgroundIngestManager.setEnabled];
 * the reactive read re-confirms with the synced value (mirrors
 * `BackgroundSyncSettingsViewModel`).
 */
class BackgroundIngestViewModel(
    private val manager: BackgroundIngestManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackgroundIngestUiState())
    val uiState: StateFlow<BackgroundIngestUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            manager.sources().collect { sources ->
                _uiState.update { it.copy(loading = false, sources = sources) }
            }
        }
    }

    fun onEnabledChange(source: IngestSourceModel, enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                sources = state.sources.map {
                    if (it.key == source.key) it.copy(enabled = enabled) else it
                },
            )
        }
        viewModelScope.launch { manager.setEnabled(source.sourceId, source.deviceId, enabled) }
    }
}
