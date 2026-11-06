package app.tenshu.drive.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.PersonModel
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.copyKey
import app.tenshu.error.ErrorCopyKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.contact.ContactInvite

/**
 * State for [ContactPromptDialog] (`docs/proposals/drive-manage-access-ux.md`
 * §2.3). [claimedName] renders as *their claim* (quoted) — the petname field
 * is pre-filled with it but user-owned from there on (contact doc §4.1).
 */
data class ContactPromptUiState(
    val inviteId: String,
    /** The responder's self-asserted name from the synced invite row. */
    val claimedName: String = "",
    /** Display-form fingerprint of their claimed key. */
    val claimedFingerprint: String = "",
    /**
     * The RAW claimed fingerprint on screen — what completion sends as `shownFingerprint`
     * (contact-service v2, threat model T-CI-10). The display form above is for eyes only.
     */
    val shownFingerprint: String = "",
    /** Proxy-derived §5.3 SAS code (`""` until the row claim lands). */
    val claimedSas: String = "",
    /** §5.2 *Codes match* — flows into `sasConfirmed` only on explicit check. */
    val sasConfirmed: Boolean = false,
    /** The user-owned petname field (pre-filled with [claimedName] once). */
    val petname: String = "",
    /** Link-to-existing candidates — plain list in v1, no ranking. */
    val persons: List<PersonModel> = emptyList(),
    val linkExpanded: Boolean = false,
    /** `null` = create a new contact; a personId = re-point that aggregate. */
    val selectedPersonId: String? = null,
    /** The user's own display name riding the ack (prompted inline, remembered). */
    val ownName: String = "",
    val isSubmitting: Boolean = false,
    /**
     * Why the last submit failed, as a catalogue **key** (`core-error-copy-catalogue.md` §5.0) —
     * the prompt resolves it through the shared catalogue. ⚠⚠ It used to be
     * `result.error.toString()`, which put `Unknown(copyKey=ErrorCopyKey(value=…))` on screen; and
     * for T-CI-10's refusals the catalogue's sentence ("decline this invite and send a new one")
     * is the whole point of failing.
     */
    val errorCopyKey: ErrorCopyKey? = null,
    /** Set once the handshake completed (either decision) — host dismisses. */
    val done: Boolean = false,
) {
    /**
     * ⚠⚠ Accept needs a loaded claim: the proxy refuses an ACCEPT whose `shownFingerprint` is
     * `""` (threat model T-CI-10), and the initial state — before the synced row lands — is
     * exactly that. Decline carries no such requirement.
     */
    val canSave: Boolean get() = !isSubmitting && shownFingerprint.isNotEmpty()
}

/**
 * ⚠⚠ **This view model is deliberately pure again** — one repository, synced rows in, one
 * op out. It used to also take a `FilesRepository` and a `MembershipRepository` so that
 * accepting a `SHARE_FOLDER` invite could chain straight into the GR-4 grant offer
 * (*"Add {petname} to '{label}'?"*).
 *
 * That chain is what quietly invalidated `drive-manage-access-ux.md` decision 2 — *"the
 * prompt is a pure consumer of synced rows + one op, it moves to the Contacts app
 * unchanged"* — because a prompt that reaches into the file domain cannot move to an app
 * that has none. The offer now lives on the Shared screen as a **deferred** card derived
 * from `COMPLETED` invites (`SharedScreenViewModel.recomputeGrantOffers`), which restores
 * decision 2 and makes this class movable.
 *
 * See `contacts-app.md` §9.1 for the alternative this implements and what it costs.
 */
class ContactPromptViewModel(
    private val contactsRepository: ContactsRepository,
    inviteId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactPromptUiState(inviteId = inviteId))
    val uiState: StateFlow<ContactPromptUiState> get() = _uiState

    private var petnamePrefilled = false
    private var ownNamePrefilled = false

    init {
        viewModelScope.launch {
            contactsRepository.contactInvites()
                .catch { /* keep last-known */ }
                .collect { invites ->
                    val row = invites.firstOrNull {
                        it.inviteId == _uiState.value.inviteId &&
                            it.status == ContactInvite.Status.REDEEMED
                    } ?: return@collect
                    _uiState.update {
                        it.copy(
                            claimedName = row.claimedName,
                            claimedFingerprint = displayFingerprint(row.claimedFingerprint),
                            shownFingerprint = row.claimedFingerprint,
                            claimedSas = row.claimedSas,
                            // ⚠⚠ The tick belongs to the code it was made against; a claim that
                            // moves under the prompt (threat model T-CI-10) clears it.
                            sasConfirmed = it.sasConfirmed &&
                                it.shownFingerprint == row.claimedFingerprint,
                            petname = if (petnamePrefilled) it.petname else row.claimedName,
                        )
                    }
                    petnamePrefilled = true
                }
        }
        viewModelScope.launch {
            contactsRepository.persons()
                .catch { /* keep last-known */ }
                .collect { persons -> _uiState.update { it.copy(persons = persons) } }
        }
        viewModelScope.launch {
            contactsRepository.ownDisplayName()
                .catch { /* keep last-known */ }
                .collect { name ->
                    if (!ownNamePrefilled && name.isNotEmpty()) {
                        ownNamePrefilled = true
                        _uiState.update { it.copy(ownName = name) }
                    }
                }
        }
    }

    fun onPetnameChanged(value: String) {
        petnamePrefilled = true
        _uiState.update { it.copy(petname = value) }
    }

    fun onOwnNameChanged(value: String) {
        ownNamePrefilled = true
        _uiState.update { it.copy(ownName = value.take(MAX_NAME_LENGTH)) }
    }

    fun onLinkExpandedToggled() {
        _uiState.update { it.copy(linkExpanded = !it.linkExpanded) }
    }

    /** §5.2: the explicit *Codes match* check — the only `sasConfirmed` source. */
    fun onSasConfirmedChanged(confirmed: Boolean) {
        _uiState.update { it.copy(sasConfirmed = confirmed) }
    }

    /** Selecting a person re-points instead of creating; re-tap deselects. */
    fun onPersonSelected(personId: String) {
        _uiState.update {
            it.copy(selectedPersonId = if (it.selectedPersonId == personId) null else personId)
        }
    }

    fun onSaveClicked() {
        submit(accept = true)
    }

    fun onDeclineClicked() {
        submit(accept = false)
    }

    private fun submit(accept: Boolean) {
        val state = _uiState.value
        if (state.isSubmitting) return
        // The button is gated on [ContactPromptUiState.canSave]; this is the same rule at the
        // seam, so a caller that skips the UI cannot submit the blank initial state.
        if (accept && !state.canSave) return
        _uiState.update { it.copy(isSubmitting = true, errorCopyKey = null) }
        viewModelScope.launch {
            if (accept && state.ownName.isNotEmpty()) {
                contactsRepository.setOwnDisplayName(state.ownName)
            }
            val result = contactsRepository.completeHandshake(
                inviteId = state.inviteId,
                accept = accept,
                shownFingerprint = state.shownFingerprint,
                personId = state.selectedPersonId.orEmpty(),
                newPersonName = if (state.selectedPersonId == null) state.petname else "",
                selfAssertedName = state.ownName,
                sasConfirmed = accept && state.sasConfirmed,
            )
            when (result) {
                is RepositoryResult.Success ->
                    // The handshake is the whole job now. A `SHARE_FOLDER` invite's grant
                    // offer resurfaces on the Shared screen as a deferred card, derived
                    // from the row this call just flipped to `COMPLETED`.
                    _uiState.update { it.copy(isSubmitting = false, done = true) }
                is RepositoryResult.Error ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorCopyKey = result.error.copyKey())
                    }
            }
        }
    }

    private companion object {
        /** Handshake doc §5: self-asserted names cap at 64 chars. */
        const val MAX_NAME_LENGTH = 64
    }
}
