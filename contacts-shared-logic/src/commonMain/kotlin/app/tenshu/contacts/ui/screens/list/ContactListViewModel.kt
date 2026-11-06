package app.tenshu.contacts.ui.screens.list

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.findMergeCandidates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactListUiState(
    val contacts: List<Contact> = emptyList(),
    /**
     * People who accepted an invitation and are waiting to be saved (`REDEEMED`). Synced,
     * so any of the user's devices shows them — which is why this is a banner and a badge
     * rather than a one-shot notification.
     */
    val pendingConfirmations: List<InviteConfirmation> = emptyList(),
    val query: String = "",
    /**
     * The search bar is expanded over the screen, showing its own results (Drive's shape —
     * `AppSearchBar` from `:ui-core`). Collapsed, the list below is the whole address book:
     * search is a distinct gesture with its own surface, not a filter applied in place.
     */
    val searchActive: Boolean = false,
    /**
     * The FAB's action sheet — *Add a contact* and *Import contacts*, the app's two ways of
     * acquiring someone who is not arriving through a handshake. Mirrors Drive's
     * `AddFileActionsFloatingActionButton`.
     */
    val actionsSheetVisible: Boolean = false,
    /**
     * False until the first emission, so "still loading" and "you have no contacts" are
     * different states — conflating them shows an empty list as an eternal spinner.
     */
    val loaded: Boolean = false,
    /**
     * How many pairs look like duplicates (D7). Derived from [contacts] on every
     * emission, so a merge on another device simply makes the banner go away — there is no
     * dismissal state to sync and no stale count.
     */
    val duplicateCount: Int = 0,
    /** The manual-add dialog (D5). */
    val addingContact: Boolean = false,
    val draftName: String = "",
    val isSaving: Boolean = false,
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
) {
    /** A contact needs a name — there is nothing else to identify it by. */
    val canSaveNew: Boolean get() = !isSaving && draftName.isNotBlank()

    /**
     * What the expanded search surface shows (D2's local filter).
     *
     * Empty for a blank query rather than "everything": an expanded search bar listing the
     * same rows as the screen behind it is noise, and it is what Drive's search does.
     */
    val searchResults: List<Contact>
        get() = if (query.isBlank()) emptyList() else contacts.filter { it.matches(query) }

    /** True when the user has contacts but this query matches none of them. */
    val isEmptySearch: Boolean
        get() = loaded && query.isNotBlank() && searchResults.isEmpty()

    /**
     * True when there is genuinely nothing to show. A waiting confirmation does not count
     * as a contact — but it does mean the empty state must not be the last word, so the
     * banner renders above it either way.
     */
    val isEmpty: Boolean get() = loaded && contacts.isEmpty()
}

/**
 * The single badged contact list (`docs/proposals/contacts-app.md` D3). One list, not two:
 * connected and import-only contacts sit together and are told apart by the row badge,
 * because users think in terms of people, not key material.
 */
class ContactListViewModel(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactListUiState())
    val uiState: StateFlow<ContactListUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.contacts().collect { v ->
                _uiState.update {
                    it.copy(
                        contacts = v,
                        loaded = true,
                        duplicateCount = findMergeCandidates(v).size,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.pendingConfirmations().collect { v ->
                _uiState.update { it.copy(pendingConfirmations = v) }
            }
        }
    }

    fun onQueryChange(query: String) = _uiState.update { it.copy(query = query) }

    fun clearQuery() = _uiState.update { it.copy(query = "") }

    /** Collapsing drops the query — a stale one behind a collapsed bar has no way to be seen. */
    fun onSearchActiveChange(active: Boolean) = _uiState.update {
        if (active) it.copy(searchActive = true) else it.copy(searchActive = false, query = "")
    }

    // ── the FAB's action sheet ───────────────────────────────────────────────

    fun onAddActionsClicked() = _uiState.update { it.copy(actionsSheetVisible = true) }

    fun onAddActionsDismissed() = _uiState.update { it.copy(actionsSheetVisible = false) }

    // ── manual add (D5) ──────────────────────────────────────────────────────

    fun onAddClicked() = _uiState.update {
        // Closes the sheet on the way: the dialog is what the sheet row leads to.
        it.copy(actionsSheetVisible = false, addingContact = true, draftName = "")
    }

    fun onDraftNameChanged(name: String) = _uiState.update { it.copy(draftName = name) }

    fun onAddDismissed() = _uiState.update { it.copy(addingContact = false, draftName = "") }

    fun onAddConfirmed() {
        val state = _uiState.value
        val name = state.draftName.trim()
        if (name.isEmpty() || state.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.createContact(name)) {
                is RepositoryResult.Success ->
                    // The list re-emits from the repository — nothing is inserted locally,
                    // so what the user sees is what was actually stored.
                    _uiState.update { it.copy(isSaving = false, addingContact = false, draftName = "") }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSaving = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    fun onErrorDismissed() = _uiState.update { it.copy(errorCopyKey = null) }
}
