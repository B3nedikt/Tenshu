package app.tenshu.contacts.ui.screens.invite

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.displayFingerprint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConfirmContactUiState(
    val inviteId: String,
    /** Their claim, rendered quoted — never promoted to the petname on its own. */
    val claimedName: String = "",
    /** Display-form fingerprint of the key they claim. */
    val claimedFingerprint: String = "",
    /**
     * The RAW claimed fingerprint on screen — what completion sends as `shownFingerprint`
     * (threat model T-CI-10). The display form above is for eyes only.
     */
    val shownFingerprint: String = "",
    /** The §5.3 six-digit code; `""` until the claim lands. */
    val claimedSas: String = "",
    /** The explicit *codes match* check — the only thing that may set `sasConfirmed`. */
    val sasConfirmed: Boolean = false,
    /** User-owned; pre-filled once from [claimedName], theirs to change from then on. */
    val petname: String = "",
    /** Link-to-existing candidates. */
    val contacts: List<Contact> = emptyList(),
    val linkExpanded: Boolean = false,
    /** `null` = create a new contact; a personId = re-point that aggregate. */
    val selectedPersonId: String? = null,
    /** The user's own name, sent inside the ack. Prompted inline when unset. */
    val ownName: String = "",
    val isSubmitting: Boolean = false,
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
    /** Set once the handshake completed either way — the host dismisses. */
    val done: Boolean = false,
) {
    val hasSas: Boolean get() = claimedSas.isNotEmpty()

    /**
     * A name must ride the ack, so the inline own-name prompt gates the save — and so does a
     * loaded claim: the proxy refuses an ACCEPT whose `shownFingerprint` is `""` (T-CI-10).
     */
    val canSave: Boolean get() = !isSubmitting && ownName.isNotBlank() &&
        shownFingerprint.isNotEmpty() &&
        (selectedPersonId != null || petname.isNotBlank())
}

/**
 * The completion prompt (`drive-manage-access-ux.md` §2.3) for **bare** invites.
 *
 * ⚠ Drive's twin chains into the grant offer ("Add {petname} to '{label}'?") and so depends
 * on `FilesRepository` + `MembershipRepository`.
 * This one deliberately has neither — a bare invite has no share to grant — which is why the
 * two exist side by side rather than one being deleted (§9.1).
 *
 * Trust rules this must not break, all from `contact-service-identity-model.md` §4.2:
 *
 *  - the claimed name is a **claim**; the petname is the user's;
 *  - linking to an existing contact re-points the key and **never transfers trust**;
 *  - `sasConfirmed` comes only from the explicit check (threat-model A24 — verification is
 *    never auto-elevated).
 */
class ConfirmContactViewModel(
    private val repository: ContactsRepository,
    inviteId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConfirmContactUiState(inviteId = inviteId))
    val uiState: StateFlow<ConfirmContactUiState> get() = _uiState

    private var petnamePrefilled = false
    private var ownNamePrefilled = false

    init {
        viewModelScope.launch {
            repository.pendingConfirmations()
                .catch { /* keep last-known */ }
                .collect { confirmations ->
                    val row = confirmations.firstOrNull { it.inviteId == inviteId }
                        ?: return@collect
                    apply(row)
                }
        }
        viewModelScope.launch {
            repository.contacts()
                .catch { /* keep last-known */ }
                .collect { contacts -> _uiState.update { it.copy(contacts = contacts) } }
        }
        viewModelScope.launch {
            repository.ownProfile()
                .catch { /* keep last-known */ }
                .collect { profile ->
                    if (!ownNamePrefilled && profile.displayName.isNotEmpty()) {
                        ownNamePrefilled = true
                        _uiState.update { it.copy(ownName = profile.displayName) }
                    }
                }
        }
    }

    private fun apply(row: InviteConfirmation) {
        _uiState.update {
            it.copy(
                claimedName = row.claimedName,
                claimedFingerprint = displayFingerprint(row.claimedFingerprint),
                shownFingerprint = row.claimedFingerprint,
                claimedSas = row.claimedSas,
                // ⚠⚠ A tick is a statement about the code that was on screen when it was made. If
                // the claim moves under the prompt (a second inviter device's write arriving by
                // merge — threat model T-CI-10), the tick must not carry over to the new code.
                sasConfirmed = it.sasConfirmed && it.shownFingerprint == row.claimedFingerprint,
                petname = if (petnamePrefilled) it.petname else row.claimedName,
            )
        }
        petnamePrefilled = true
    }

    fun onPetnameChanged(value: String) {
        petnamePrefilled = true
        _uiState.update { it.copy(petname = value) }
    }

    fun onOwnNameChanged(value: String) {
        ownNamePrefilled = true
        _uiState.update { it.copy(ownName = value.take(MAX_NAME_LENGTH)) }
    }

    fun onLinkExpandedToggled() =
        _uiState.update { it.copy(linkExpanded = !it.linkExpanded) }

    /** The explicit *codes match* check — the only `sasConfirmed` source. */
    fun onSasConfirmedChanged(confirmed: Boolean) =
        _uiState.update { it.copy(sasConfirmed = confirmed) }

    /** Selecting re-points instead of creating; re-tapping deselects. */
    fun onContactSelected(personId: String) = _uiState.update {
        it.copy(selectedPersonId = if (it.selectedPersonId == personId) null else personId)
    }

    fun onSaveClicked() = submit(accept = true)

    fun onDeclineClicked() = submit(accept = false)

    private fun submit(accept: Boolean) {
        val state = _uiState.value
        if (state.isSubmitting) return
        if (accept && !state.canSave) return
        _uiState.update { it.copy(isSubmitting = true, errorCopyKey = null) }
        viewModelScope.launch {
            val ownName = state.ownName.trim()
            // Remember the name that rides the ack — this is often where it is set for the
            // first time, and it is the platform-level one (D6), so it lands everywhere.
            if (accept && ownName.isNotEmpty()) {
                repository.setOwnDisplayName(ownName)
            }
            val result = repository.completeHandshake(
                inviteId = state.inviteId,
                accept = accept,
                shownFingerprint = state.shownFingerprint,
                personId = state.selectedPersonId.orEmpty(),
                newPersonName = if (state.selectedPersonId == null) state.petname.trim() else "",
                selfAssertedName = ownName,
                // Only on accept, and only from the explicit check.
                sasConfirmed = accept && state.sasConfirmed,
            )
            when (result) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isSubmitting = false, done = true) }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSubmitting = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    private companion object {
        /** Handshake doc §5: self-asserted names cap at 64 chars. */
        const val MAX_NAME_LENGTH = 64
    }
}
