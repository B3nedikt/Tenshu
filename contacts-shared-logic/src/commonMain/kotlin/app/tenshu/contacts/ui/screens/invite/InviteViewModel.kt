package app.tenshu.contacts.ui.screens.invite

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.OutstandingInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InviteUiState(
    val isCreating: Boolean = false,
    /**
     * The one-session link, set once the op succeeds and **never re-derivable**. It carries
     * `K_link`, which is never persisted and never reaches the relay, so leaving this screen
     * discards it — outstanding invites offer *Revoke*, never *Show link again*.
     */
    val link: String? = null,
    val outstanding: List<OutstandingInvite> = emptyList(),
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
) {
    /** True once there is a link to hand over — the sheet switches to the QR view. */
    val hasLink: Boolean get() = link != null
}

/**
 * *Invite someone* from the own profile card (`docs/proposals/contacts-app.md` D6). Mints a
 * **bare** invitation — `intentKind = ""`, which `ContactInvite` already accepts — so the
 * handshake carries no share intent and the result is simply a contact.
 *
 * Drive's `InviteLinkViewModel` is the sibling for `SHARE_FOLDER` invites. The two are not
 * merged: Drive's chains into the membership-grant offer on completion and therefore needs Drive's
 * file domain, which is exactly what keeps the invite space partitioned (§9.1).
 */
class InviteViewModel(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteUiState())
    val uiState: StateFlow<InviteUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.outstandingInvites()
                .catch { /* keep last-known */ }
                .collect { invites -> _uiState.update { it.copy(outstanding = invites) } }
        }
    }

    fun onCreateClicked() {
        val state = _uiState.value
        // Guard the in-flight case and the already-minted one: a second tap would strand the
        // first link, which cannot be recovered.
        if (state.isCreating || state.link != null) return
        _uiState.update { it.copy(isCreating = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.createBareInvite()) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isCreating = false, link = result.value) }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isCreating = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    fun onRevokeClicked(inviteId: String) {
        viewModelScope.launch {
            when (val result = repository.revokeInvite(inviteId)) {
                is RepositoryResult.Success -> Unit // the flow re-emits without the row
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(errorCopyKey = result.copyKey) }
            }
        }
    }

    /** Clears the session link when the sheet closes — it must not survive the screen. */
    fun onDismissed() = _uiState.update { it.copy(link = null, errorCopyKey = null) }
}
