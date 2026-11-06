package app.tenshu.contacts.ui.screens.detail

import app.tenshu.error.ErrorCopyKey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ContactDetailUiState(
    val contact: Contact? = null,
    val loaded: Boolean = false,
    /** The rename dialog. */
    val renaming: Boolean = false,
    val draftName: String = "",
    /** The delete confirmation — the Q7b warning lives on this screen. */
    val confirmingDelete: Boolean = false,
    /** A key the user is about to drop from the keyring. */
    val confirmingUnlink: String? = null,
    /** The Q2a compare-and-confirm sheet, once a key's code has been fetched. */
    val verifying: VerificationPrompt? = null,
    /** The Q6 field editor, holding the working copy while it is open. */
    val editingDetails: ContactDetails? = null,
    val isSaving: Boolean = false,
    /** ⚠ A catalogue key; the UI renders it. Never a proxy sentence (§5.0). */
    val errorCopyKey: ErrorCopyKey? = null,
    /** Set once the contact is gone — the host pops back to the list. */
    val deleted: Boolean = false,
) {
    /** Loaded and the person is gone — deleted on another device, or a stale back stack. */
    val isMissing: Boolean get() = loaded && contact == null && !deleted

    val canSaveName: Boolean get() = !isSaving && draftName.isNotBlank()
}

/**
 * The Q2a compare-and-confirm state for one key.
 *
 * [sas] is proxy-derived and empty while it is still loading, or when the realm is locked.
 * ⚠ The screen must not offer *confirm* without digits on it — confirming nothing is how a
 * key ends up marked verified by a user who compared nothing.
 */
data class VerificationPrompt(
    val fingerprint: String,
    val sas: String = "",
    val loading: Boolean = true,
) {
    val hasSas: Boolean get() = sas.isNotEmpty()
}

/**
 * One contact's detail (`docs/proposals/contacts-app.md` D3) plus its edit surface: rename,
 * delete, the photo, the field editor, re-verification, and dropping a single key from the
 * keyring.
 *
 * Renders the petname, the derived trust badge, and the keyring underneath — each key with
 * the name it *claims* (quarantined, never promoted to the petname) and its own verification
 * state, because trust lives on the key.
 *
 * Per-contact app actions are deliberately not here: Q1's static list over Drive's deep link
 * must not grow into a general registry ahead of Rei Q16.
 */
class ContactDetailViewModel(
    private val repository: ContactsRepository,
    val personId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactDetailUiState())
    val uiState: StateFlow<ContactDetailUiState> get() = _uiState

    init {
        viewModelScope.launch {
            repository.contact(personId).collect { v ->
                _uiState.update { it.copy(contact = v, loaded = true) }
            }
        }
    }

    // ── rename ───────────────────────────────────────────────────────────────

    fun onRenameClicked() = _uiState.update {
        it.copy(renaming = true, draftName = it.contact?.displayName.orEmpty())
    }

    fun onDraftNameChanged(name: String) = _uiState.update { it.copy(draftName = name) }

    fun onRenameDismissed() = _uiState.update { it.copy(renaming = false, draftName = "") }

    fun onRenameConfirmed() {
        val state = _uiState.value
        val name = state.draftName.trim()
        if (name.isEmpty() || state.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.renameContact(personId, name)) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isSaving = false, renaming = false, draftName = "") }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSaving = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    // ── delete ───────────────────────────────────────────────────────────────

    fun onDeleteClicked() = _uiState.update { it.copy(confirmingDelete = true) }

    fun onDeleteDismissed() = _uiState.update { it.copy(confirmingDelete = false) }

    /**
     * ⚠⚠ The confirmation this gates **must** carry the Q7b warning: deleting a contact
     * revokes nothing (`DeletePerson`), so a user deleting someone in order to cut them off
     * otherwise gets the exact opposite, silently.
     */
    fun onDeleteConfirmed() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.deleteContact(personId)) {
                is RepositoryResult.Success ->
                    _uiState.update {
                        it.copy(isSaving = false, confirmingDelete = false, deleted = true)
                    }
                is RepositoryResult.Failure ->
                    _uiState.update {
                        it.copy(isSaving = false, confirmingDelete = false, errorCopyKey = result.copyKey)
                    }
            }
        }
    }

    // ── photo ────────────────────────────────────────────────────────────────

    /**
     * Remove the photo. No confirmation: nothing is destroyed — an imported card keeps its
     * `PHOTO` and re-importing brings it back — so a dialog would be ceremony over an
     * action that is already reversible.
     */
    fun onRemovePhotoClicked() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.clearAvatar(personId)) {
                // The contact re-emits from the repository, so what disappears from the
                // screen is what actually left the store.
                is RepositoryResult.Success -> _uiState.update { it.copy(isSaving = false) }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSaving = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    // ── unlink a key ─────────────────────────────────────────────────────────

    fun onUnlinkClicked(fingerprint: String) =
        _uiState.update { it.copy(confirmingUnlink = fingerprint) }

    fun onUnlinkDismissed() = _uiState.update { it.copy(confirmingUnlink = null) }

    fun onUnlinkConfirmed() {
        val fingerprint = _uiState.value.confirmingUnlink ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.unlinkIdentity(fingerprint)) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isSaving = false, confirmingUnlink = null) }
                is RepositoryResult.Failure ->
                    _uiState.update {
                        it.copy(isSaving = false, confirmingUnlink = null, errorCopyKey = result.copyKey)
                    }
            }
        }
    }

    // ── re-verification ──────────────────────────────────────────────────────

    /**
     * Open the compare-and-confirm sheet: fetch the six digits, then let the user read them
     * to the contact over whatever channel they already trust (Q2a).
     */
    fun onVerifyClicked(fingerprint: String) {
        _uiState.update { it.copy(verifying = VerificationPrompt(fingerprint = fingerprint)) }
        viewModelScope.launch {
            when (val result = repository.contactSas(fingerprint)) {
                is RepositoryResult.Success -> _uiState.update { state ->
                    // Ignore a late arrival for a sheet the user already closed or changed.
                    if (state.verifying?.fingerprint != fingerprint) return@update state
                    state.copy(verifying = state.verifying.copy(sas = result.value, loading = false))
                }
                is RepositoryResult.Failure -> _uiState.update { state ->
                    if (state.verifying?.fingerprint != fingerprint) return@update state
                    state.copy(verifying = null, errorCopyKey = result.copyKey)
                }
            }
        }
    }

    fun onVerifyDismissed() = _uiState.update { it.copy(verifying = null) }

    /**
     * ⚠⚠ The user has said the digits matched. This is the only call that elevates a key,
     * and it exists solely to record a human judgement — see
     * [ContactsRepository.setVerified].
     */
    fun onVerifyConfirmed() {
        val prompt = _uiState.value.verifying ?: return
        if (!prompt.hasSas || _uiState.value.isSaving) return
        setVerified(prompt.fingerprint, verified = true)
    }

    /**
     * Q2b's way back. No confirmation step: withdrawing trust is the safe direction, and a
     * user who taps it has already decided something is wrong.
     */
    fun onUnverifyClicked(fingerprint: String) = setVerified(fingerprint, verified = false)

    private fun setVerified(fingerprint: String, verified: Boolean) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.setVerified(fingerprint, verified)) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isSaving = false, verifying = null) }
                is RepositoryResult.Failure ->
                    _uiState.update {
                        it.copy(isSaving = false, verifying = null, errorCopyKey = result.copyKey)
                    }
            }
        }
    }

    // ── detail editing (Q6) ──────────────────────────────────────────────────

    fun onEditDetailsClicked() = _uiState.update {
        it.copy(editingDetails = it.contact?.details ?: ContactDetails())
    }

    fun onEditDetailsDismissed() = _uiState.update { it.copy(editingDetails = null) }

    fun onDetailFieldChanged(kind: DetailFieldKind, index: Int, value: String) =
        _uiState.update { state ->
            val editing = state.editingDetails ?: return@update state
            state.copy(editingDetails = editing.withValue(kind, index, value))
        }

    fun onDetailFieldAdded(kind: DetailFieldKind) = _uiState.update { state ->
        val editing = state.editingDetails ?: return@update state
        state.copy(editingDetails = editing.withAdded(kind))
    }

    fun onOrganisationChanged(value: String) = _uiState.update { state ->
        state.copy(editingDetails = state.editingDetails?.copy(organisation = value))
    }

    fun onNoteChanged(value: String) = _uiState.update { state ->
        state.copy(editingDetails = state.editingDetails?.copy(note = value))
    }

    /**
     * ⚠ Blank values are dropped on save rather than written as empty properties — clearing
     * a field is how a user removes it, and an empty `TEL:` is not a phone number.
     */
    fun onEditDetailsSaved() {
        val editing = _uiState.value.editingDetails ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true, errorCopyKey = null) }
        viewModelScope.launch {
            when (val result = repository.saveContactDetails(personId, editing.pruned())) {
                is RepositoryResult.Success ->
                    _uiState.update { it.copy(isSaving = false, editingDetails = null) }
                is RepositoryResult.Failure ->
                    _uiState.update { it.copy(isSaving = false, errorCopyKey = result.copyKey) }
            }
        }
    }

    fun onErrorDismissed() = _uiState.update { it.copy(errorCopyKey = null) }
}

/** The multi-valued field kinds the editor manages. */
enum class DetailFieldKind { PHONE, EMAIL, ADDRESS }

private fun ContactDetails.listOf(kind: DetailFieldKind): List<ContactField> = when (kind) {
    DetailFieldKind.PHONE -> phones
    DetailFieldKind.EMAIL -> emails
    DetailFieldKind.ADDRESS -> addresses
}

private fun ContactDetails.withList(
    kind: DetailFieldKind,
    values: List<ContactField>,
): ContactDetails = when (kind) {
    DetailFieldKind.PHONE -> copy(phones = values)
    DetailFieldKind.EMAIL -> copy(emails = values)
    DetailFieldKind.ADDRESS -> copy(addresses = values)
}

private fun ContactDetails.withValue(
    kind: DetailFieldKind,
    index: Int,
    value: String,
): ContactDetails {
    val values = listOf(kind).toMutableList()
    if (index !in values.indices) return this
    values[index] = values[index].copy(value = value)
    return withList(kind, values)
}

private fun ContactDetails.withAdded(kind: DetailFieldKind): ContactDetails =
    withList(kind, listOf(kind) + ContactField(label = "", value = ""))

/** Drop blanks — clearing a field is how it is removed (see [ContactDetailViewModel]). */
private fun ContactDetails.pruned(): ContactDetails = ContactDetails(
    phones = phones.filter { it.value.isNotBlank() },
    emails = emails.filter { it.value.isNotBlank() },
    addresses = addresses.filter { it.value.isNotBlank() },
    organisation = organisation.trim(),
    note = note.trim(),
)
