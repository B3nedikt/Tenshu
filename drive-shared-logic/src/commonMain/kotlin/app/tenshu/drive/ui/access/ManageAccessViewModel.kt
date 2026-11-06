package app.tenshu.drive.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.RepositoryResult
import org.tenshu.contact.ContactInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [ManageAccessScreen] (`docs/proposals/drive-manage-access-ux.md`
 * §10, row semantics from §2.1). Projects the folder's invite rows (the
 * `ContactInvite` relation filtered by `intentKind == SHARE_FOLDER &&
 * intentLabel == <folder name>` — the documented v1 approximation) and drives
 * *Revoke* + the *Invite someone* / completion-prompt host flags.
 *
 * **GR-4:** when the folder backs a shared realm I own, the list also carries
 * cross-user [ManageAccessUiState.members] (`getRealmMembers`) and a
 * grant-to-contact action (`grantSharedRealmMembership`). Per **D7**, a
 * `COMPLETED` invite whose contact is now a realm member is dropped from the
 * invite rows (the member row — keyed off the realm, not the fragile label —
 * supersedes it).
 */
class ManageAccessViewModel(
    private val filesRepository: FilesRepository,
    private val contactsRepository: ContactsRepository,
    private val membershipRepository: MembershipRepository,
    folderPath: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ManageAccessUiState(
            folderPath = folderPath,
            folderName = folderPath.substringAfterLast("/").ifEmpty { folderPath },
        )
    )
    val uiState: StateFlow<ManageAccessUiState> get() = _uiState

    /** Last-seen REDEEMED ids, to detect a fresh arrival for the §5.2 advance. */
    private var redeemedIds: Set<String> = emptySet()

    /** Raw label-filtered invites + members, recombined into the displayed list (D7). */
    private var visibleInvites: List<ContactInviteModel> = emptyList()
    private var members: List<MemberModel> = emptyList()

    init {
        viewModelScope.launch {
            contactsRepository.contactInvites()
                .catch { /* keep last-known rows */ }
                .collect { invites ->
                    val name = _uiState.value.folderName
                    visibleInvites = invites.filter { row ->
                        row.intentKind == ContactInvite.IntentKind.SHARE_FOLDER &&
                            row.intentLabel == name &&
                            row.status in VISIBLE_STATUSES
                    }
                    val nowRedeemed = visibleInvites
                        .filter { it.status == ContactInvite.Status.REDEEMED }
                        .map { it.inviteId }
                        .toSet()
                    // §5.2 in-person case: a redemption arriving while the
                    // invite-link sheet is open (QR on screen) advances
                    // directly into the completion prompt with its SAS panel.
                    val fresh = (nowRedeemed - redeemedIds).firstOrNull()
                    redeemedIds = nowRedeemed
                    _uiState.update {
                        if (it.showInviteSheet && fresh != null) {
                            it.copy(
                                invites = displayedInvites(),
                                showInviteSheet = false,
                                promptInviteId = fresh,
                            )
                        } else {
                            it.copy(invites = displayedInvites())
                        }
                    }
                }
        }
        viewModelScope.launch {
            // Members section visibility: shown only when this folder backs a
            // Mode-B shared realm (one of my owned `sharedFolders()` rows). When
            // it does, capture the realm id and load the roster + grant pickers.
            val state = _uiState.value
            val result = filesRepository.sharedFolders()
            if (result is RepositoryResult.Success) {
                val match = result.value.firstOrNull { folder ->
                    val path = if (folder.parentPath == "/") {
                        "/${folder.name}"
                    } else {
                        "${folder.parentPath}/${folder.name}"
                    }
                    path == state.folderPath
                }
                if (match != null) {
                    _uiState.update {
                        it.copy(backsSharedRealm = true, sharedRealmId = match.sharedRealmId)
                    }
                    loadMembers()
                    loadGrantableContacts()
                }
            }
        }
    }

    fun onInviteSomeoneClicked() {
        _uiState.update { it.copy(showInviteSheet = true) }
    }

    fun onInviteSheetDismissed() {
        _uiState.update { it.copy(showInviteSheet = false) }
    }

    /** A `PENDING` row's *Revoke* affordance → `RevokeContactInvite`. */
    fun onRevokeInviteClicked(inviteId: String) {
        viewModelScope.launch {
            contactsRepository.revokeInvite(inviteId)
        }
    }

    /** An *Awaiting your confirmation* (`REDEEMED`) row opens the §2.3 prompt. */
    fun onInviteRowClicked(inviteId: String) {
        val row = _uiState.value.invites.firstOrNull { it.inviteId == inviteId } ?: return
        if (row.status == ContactInvite.Status.REDEEMED) {
            _uiState.update { it.copy(promptInviteId = inviteId) }
        }
    }

    fun onPromptDismissed() {
        _uiState.update { it.copy(promptInviteId = null) }
    }

    /** GR-4: open the contact picker to grant an existing contact realm access. */
    fun onGrantAccessClicked() {
        _uiState.update { it.copy(showGrantPicker = true, grantError = null) }
    }

    fun onGrantPickerDismissed() {
        _uiState.update { it.copy(showGrantPicker = false) }
    }

    /**
     * GR-4: grant [personId] membership of the folder's realm. Owner-only and
     * same-session (the realm master key is in-memory) — a failure surfaces in
     * [ManageAccessUiState.grantError] and keeps the picker open to retry.
     */
    fun onGrantToContact(personId: String) {
        val realmId = _uiState.value.sharedRealmId ?: return
        if (_uiState.value.grantInProgress) return
        val label = _uiState.value.grantableContacts
            .firstOrNull { it.personId == personId }?.displayName.orEmpty()
        _uiState.update { it.copy(grantInProgress = true, grantError = null) }
        viewModelScope.launch {
            when (val result = membershipRepository.grant(realmId, personId, label)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(grantInProgress = false, showGrantPicker = false) }
                    loadMembers()
                }
                is RepositoryResult.Error ->
                    _uiState.update {
                        it.copy(grantInProgress = false, grantError = result.error.toString())
                    }
            }
        }
    }

    /**
     * GR-6: remove member [memberId] from the realm (`removeSharedRealmMember`).
     * Owner-only; durable across sessions. The row shows progress; on success the
     * roster reloads, on failure [ManageAccessUiState.removeError] surfaces it.
     */
    fun onRemoveMember(memberId: String) {
        val realmId = _uiState.value.sharedRealmId ?: return
        if (_uiState.value.removingMemberId != null) return
        _uiState.update { it.copy(removingMemberId = memberId, removeError = null) }
        viewModelScope.launch {
            when (val result = membershipRepository.remove(realmId, memberId)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(removingMemberId = null) }
                    loadMembers()
                }
                is RepositoryResult.Error ->
                    _uiState.update {
                        it.copy(removingMemberId = null, removeError = result.error.toString())
                    }
            }
        }
    }

    private fun loadMembers() {
        val realmId = _uiState.value.sharedRealmId ?: return
        viewModelScope.launch {
            val result = membershipRepository.members(realmId)
            if (result is RepositoryResult.Success) {
                members = result.value.filterNot { it.isOwner }
                _uiState.update { it.copy(members = members, invites = displayedInvites()) }
            }
        }
    }

    private fun loadGrantableContacts() {
        viewModelScope.launch {
            val result = contactsRepository.connectedContacts()
            if (result is RepositoryResult.Success) {
                _uiState.update { it.copy(grantableContacts = result.value.filter { c -> c.grantable }) }
            }
        }
    }

    /**
     * D7: the displayed invite rows. A `COMPLETED` invite whose contact is now a
     * realm member is represented by that member row, so it's dropped to avoid a
     * double-listing; all other statuses (the handshake lifecycle) always show.
     */
    private fun displayedInvites(): List<ContactInviteModel> {
        val memberFingerprints = members
            .map { it.contactFingerprint }
            .filter { it.isNotEmpty() }
            .toSet()
        return visibleInvites.filterNot { invite ->
            invite.status == ContactInvite.Status.COMPLETED &&
                invite.claimedFingerprint in memberFingerprints
        }
    }

    private companion object {
        /** §2.1 shows live lifecycle states; revoked/expired/rejected rows drop out. */
        val VISIBLE_STATUSES = setOf(
            ContactInvite.Status.PENDING,
            ContactInvite.Status.REDEEMED,
            ContactInvite.Status.COMPLETED,
            ContactInvite.Status.COMPROMISED,
        )
    }
}
