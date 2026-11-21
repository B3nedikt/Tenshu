package app.tenshu.mia.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.mia.data.MiaGrantsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.tenshu.mia.MiaSource

/**
 * The MQ-D6 grant switches. A `null` [repository] is the not-connected shell (Mia launches
 * standalone) — the section then reports every source as on and the switches are disabled,
 * which is truthful: with no proxy there is nothing to grant *or* revoke.
 */
class GrantsViewModel(
    private val repository: MiaGrantsRepository? = null,
) : ViewModel() {

    private val state = MutableStateFlow(
        GrantsUiState(
            granted = MiaSource.entries.toSet(),
            editable = repository != null,
        ),
    )

    val uiState: StateFlow<GrantsUiState> = state.asStateFlow()

    init {
        if (repository != null) {
            viewModelScope.launch {
                repository.granted().collect { granted ->
                    state.value = state.value.copy(granted = granted)
                }
            }
        }
    }

    fun setGranted(source: MiaSource, granted: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch { repo.setGranted(source, granted) }
    }
}

data class GrantsUiState(
    val granted: Set<MiaSource>,
    /** False with no proxy: the switches render but cannot be meaningfully changed. */
    val editable: Boolean,
)
