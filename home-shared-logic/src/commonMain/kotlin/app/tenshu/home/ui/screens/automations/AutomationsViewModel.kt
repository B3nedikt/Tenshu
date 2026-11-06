package app.tenshu.home.ui.screens.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.home.data.AutomationsRepository
import app.tenshu.home.data.HomeDevicesRepository
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutomationsUiState(
    val automations: List<Automation> = emptyList(),
    /** For resolving device ids in row summaries. */
    val devices: List<Device> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Automations list view model (docs/proposals/home-and-mia-apps.md §5, M2): the list with
 * enable switches; create/edit push the editor destination, which owns its own VM.
 */
class AutomationsViewModel(
    private val repository: AutomationsRepository,
    devicesRepository: HomeDevicesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationsUiState())
    val uiState: StateFlow<AutomationsUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.automations().collect { v -> _uiState.update { it.copy(automations = v, loaded = true) } }
        }
        viewModelScope.launch {
            devicesRepository.devices().collect { v -> _uiState.update { it.copy(devices = v) } }
        }
    }

    fun setEnabled(automationId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(automationId, enabled) }
    }

    fun removeAutomation(automationId: String) {
        viewModelScope.launch { repository.removeAutomation(automationId) }
    }

    fun deviceName(deviceId: String): String? =
        _uiState.value.devices.firstOrNull { it.id == deviceId }?.name
}
