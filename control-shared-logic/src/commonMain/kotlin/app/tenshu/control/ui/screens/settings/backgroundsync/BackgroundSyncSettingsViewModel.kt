package app.tenshu.control.ui.screens.settings.backgroundsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.core.common.TenshuClient
import org.tenshu.preference.ControlPreferences
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.preferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set

data class BackgroundSyncUiState(
    val wifiOnly: Boolean = ControlPreferences.WifiOnly.default,
)

/**
 * Backs the Background-sync settings screen. Reads the realm-wide
 * [ControlPreferences.WifiOnly] reactively (so a toggle on another paired device
 * shows up here) and writes it back through [PreferenceService.set].
 *
 * Writes optimistically update [uiState] before the round-trip lands, so the
 * switch feels immediate; the reactive [read] flow then re-confirms with the
 * persisted value.
 */
class BackgroundSyncSettingsViewModel(
    tenshuClient: TenshuClient,
) : ViewModel() {

    private val service: PreferenceService = tenshuClient.preferenceService

    private val _uiState = MutableStateFlow(BackgroundSyncUiState())
    val uiState: StateFlow<BackgroundSyncUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            service.read(ControlPreferences.WifiOnly).collect { value ->
                _uiState.update { it.copy(wifiOnly = value) }
            }
        }
    }

    fun setWifiOnly(value: Boolean) {
        _uiState.update { it.copy(wifiOnly = value) }
        viewModelScope.launch { service.set(ControlPreferences.WifiOnly, value) }
    }
}
