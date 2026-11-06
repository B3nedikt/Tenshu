package app.tenshu.mia.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.mia.data.ConversationRepository
import app.tenshu.mia.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MiaHistoryUiState(
    val conversations: List<Conversation> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * History tab view model (docs/proposals/home-and-mia-apps.md §4, M3): the milestone doc's
 * Activity sub-screen promoted to a tab — past conversations, newest-first.
 */
class MiaHistoryViewModel(
    repository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MiaHistoryUiState())
    val uiState: StateFlow<MiaHistoryUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.history().collect { v ->
                _uiState.update { it.copy(conversations = v.sortedByDescending { c -> c.startedAt }, loaded = true) }
            }
        }
    }

    fun conversation(conversationId: String): Conversation? =
        _uiState.value.conversations.firstOrNull { it.id == conversationId }
}
