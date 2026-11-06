package app.tenshu.home.ui.screens.automations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.home.data.AutomationsRepository
import app.tenshu.home.data.HomeDevicesRepository
import app.tenshu.home.model.Action
import app.tenshu.home.model.Automation
import app.tenshu.home.model.Condition
import app.tenshu.home.model.Device
import app.tenshu.home.model.Trigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutomationEditorUiState(
    val name: String = "",
    val trigger: Trigger? = null,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** Pickers: the household's devices and the other automations (for RunAutomation). */
    val devices: List<Device> = emptyList(),
    val otherAutomations: List<Automation> = emptyList(),
    /** Flips true after a successful save — the screen pops on it. */
    val saved: Boolean = false,
) {
    /** §5: an automation is 1 trigger + 0..n conditions + 1..n actions, and needs a name. */
    val canSave: Boolean
        get() = name.isNotBlank() && trigger != null && actions.isNotEmpty()
}

/**
 * Automation editor view model (docs/proposals/home-and-mia-apps.md §5, M2). One VM for
 * create ([automationId] == null) and edit — edit pre-fills the draft from the repository.
 * The screen builds the [Trigger]/[Condition]/[Action] values in its pickers; this VM owns
 * the draft and the save.
 */
class AutomationEditorViewModel(
    private val repository: AutomationsRepository,
    devicesRepository: HomeDevicesRepository,
    private val automationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AutomationEditorUiState())
    val uiState: StateFlow<AutomationEditorUiState> get() = _uiState

    /** Preserved across an edit so save() keeps the enabled flag + lastRunAt. */
    private var editing: Automation? = null

    init {
        viewModelScope.launch {
            devicesRepository.devices().collect { v -> _uiState.update { it.copy(devices = v) } }
        }
        viewModelScope.launch {
            val all = repository.automations().first()
            val existing = automationId?.let { id -> all.firstOrNull { it.id == id } }
            editing = existing
            _uiState.update { state ->
                state.copy(
                    name = existing?.name ?: state.name,
                    trigger = existing?.trigger ?: state.trigger,
                    conditions = existing?.conditions ?: state.conditions,
                    actions = existing?.actions ?: state.actions,
                    otherAutomations = all.filterNot { it.id == automationId },
                )
            }
        }
    }

    fun setName(name: String) = _uiState.update { it.copy(name = name) }

    fun setTrigger(trigger: Trigger) = _uiState.update { it.copy(trigger = trigger) }

    fun addCondition(condition: Condition) =
        _uiState.update { it.copy(conditions = it.conditions + condition) }

    fun removeCondition(index: Int) = _uiState.update {
        if (index !in it.conditions.indices) it
        else it.copy(conditions = it.conditions.filterIndexed { i, _ -> i != index })
    }

    fun addAction(action: Action) =
        _uiState.update { it.copy(actions = it.actions + action) }

    fun removeAction(index: Int) = _uiState.update {
        if (index !in it.actions.indices) it
        else it.copy(actions = it.actions.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        val state = _uiState.value
        val trigger = state.trigger
        if (!state.canSave || trigger == null) return
        viewModelScope.launch {
            val base = editing
            if (base == null) {
                repository.addAutomation(
                    Automation(
                        id = "",
                        name = state.name.trim(),
                        enabled = true,
                        trigger = trigger,
                        conditions = state.conditions,
                        actions = state.actions,
                        lastRunAt = null,
                    ),
                )
            } else {
                repository.updateAutomation(
                    base.copy(
                        name = state.name.trim(),
                        trigger = trigger,
                        conditions = state.conditions,
                        actions = state.actions,
                    ),
                )
            }
            _uiState.update { it.copy(saved = true) }
        }
    }
}
