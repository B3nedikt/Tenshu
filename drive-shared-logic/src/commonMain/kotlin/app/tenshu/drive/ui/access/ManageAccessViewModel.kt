package app.tenshu.drive.ui.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.domain.StorageGate
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.ingest.backup.StorageStatusModel
import org.tenshu.contact.ContactInvite
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * **One Share entry (§11):** this screen is the folder's only sharing surface. A
 * folder that does not back a shared realm yet is shared on the first add —
 * granting a contact or inviting someone runs [FilesRepository.shareFolder] first
 * ([ensureSharedRealm]) — so there is no separate "share the folder" step to forget.
 */
class ManageAccessViewModel(
    private val filesRepository: FilesRepository,
    private val contactsRepository: ContactsRepository,
    private val membershipRepository: MembershipRepository,
    private val folderPath: String,
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

    // Tier-1 storage signal (storage-capacity-gating.md §2): sharing a folder creates
    // synced content, so the first add is gated exactly like the old *Share* row was.
    private val storageStatus: StateFlow<StorageStatusModel?> =
        filesRepository.storageStatus()
            .catch { emit(null) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Serialises [ensureSharedRealm] so a double tap can never mint two realms. */
    private val shareMutex = Mutex()

    /**
     * Whether this folder already backs a realm I own (see [lookUpRealm]).
     * [ensureSharedRealm] awaits it, so an add tapped before the lookup lands
     * cannot share the folder a second time.
     */
    private val realmLookup: Deferred<String?> = viewModelScope.async { lookUpRealm() }

    /**
     * Match this folder against my `sharedFolders()` rows; on a hit, capture the
     * realm and load its roster. Returns `null` once the question is answered, or
     * the failure's message — a failed read must never be taken as "not shared":
     * sharing on that basis would mint a second realm for an already-shared folder.
     */
    private suspend fun lookUpRealm(): String? {
        val result = filesRepository.sharedFolders()
        if (result is RepositoryResult.Error) return result.error.toString()
        if (result is RepositoryResult.Success) {
            val match = result.value.firstOrNull { folder ->
                val path = if (folder.parentPath == "/") {
                    "/${folder.name}"
                } else {
                    "${folder.parentPath}/${folder.name}"
                }
                path == folderPath
            }
            if (match != null) {
                _uiState.update {
                    it.copy(backsSharedRealm = true, sharedRealmId = match.sharedRealmId)
                }
                loadMembers()
            }
        }
        return null
    }

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
        // Contacts are offered whether or not the folder is shared yet: granting one
        // is what shares it (§11).
        loadGrantableContacts()
    }

    /**
     * *Invite someone*: share the folder first if it isn't yet, then open the link
     * sheet. The invite only connects two people; the deferred grant offer that
     * follows the handshake (`SharedScreenViewModel.recomputeGrantOffers`) needs an
     * owned realm to offer, so without this an invite on an unshared folder never
     * led anywhere.
     */
    fun onInviteSomeoneClicked() {
        if (_uiState.value.sharing) return
        viewModelScope.launch {
            ensureSharedRealm() ?: return@launch
            _uiState.update { it.copy(showInviteSheet = true) }
        }
    }

    fun onStorageGateDismissed() {
        _uiState.update { it.copy(storageGateBlock = null) }
    }

    /**
     * The realm this folder backs, sharing the folder into a fresh one first if it
     * backs none. `null` when the storage gate blocked it ([ManageAccessUiState.storageGateBlock]
     * is raised) or sharing failed ([ManageAccessUiState.shareError]).
     */
    private suspend fun ensureSharedRealm(): String? {
        val openingFailure = realmLookup.await()
        return shareMutex.withLock {
            _uiState.value.sharedRealmId?.let { return@withLock it }
            // The opening lookup failed: ask again rather than guess "not shared".
            if (openingFailure != null) {
                lookUpRealm()?.let { failure ->
                    _uiState.update { it.copy(shareError = failure) }
                    return@withLock null
                }
            }
            _uiState.value.sharedRealmId?.let { return@withLock it }
            val decision = StorageGate.evaluate(storageStatus.value)
            if (decision != StorageGateDecision.Allow) {
                _uiState.update { it.copy(storageGateBlock = decision) }
                return@withLock null
            }
            _uiState.update { it.copy(sharing = true, shareError = null) }
            when (val result = filesRepository.shareFolder(folderPath)) {
                is RepositoryResult.Success -> {
                    val realmId = result.value.sharedRealmId
                    _uiState.update {
                        it.copy(sharing = false, backsSharedRealm = true, sharedRealmId = realmId)
                    }
                    realmId
                }
                is RepositoryResult.Error -> {
                    _uiState.update { it.copy(sharing = false, shareError = result.error.toString()) }
                    null
                }
            }
        }
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
     * GR-4: grant [personId] membership of the folder's realm, sharing the folder
     * first if it backs no realm yet (§11). Owner-only and same-session (the realm
     * master key is in-memory) — a failure surfaces in
     * [ManageAccessUiState.grantError] and keeps the picker open to retry.
     */
    fun onGrantToContact(personId: String) {
        if (_uiState.value.grantInProgress) return
        val label = _uiState.value.grantableContacts
            .firstOrNull { it.personId == personId }?.displayName.orEmpty()
        _uiState.update { it.copy(grantInProgress = true, grantError = null) }
        viewModelScope.launch {
            val realmId = ensureSharedRealm()
            if (realmId == null) {
                // Storage-gated (the dialog is up) or the share failed (its message
                // is shown in the picker, where the user is looking).
                _uiState.update { it.copy(grantInProgress = false, grantError = it.shareError) }
                return@launch
            }
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
            // RO D7: a member appears once an owner device picks up their acceptance — poll first, so an
            // acceptance that arrived since the last poll moves its row from "invited" to the members now.
            // Best-effort: a failed poll still shows the last-known state.
            membershipRepository.pollPendingGrants()
            val result = membershipRepository.members(realmId)
            if (result is RepositoryResult.Success) {
                members = result.value.filterNot { it.isOwner }
                _uiState.update { it.copy(members = members, invites = displayedInvites()) }
            }
            val invitations = membershipRepository.invitations(realmId)
            if (invitations is RepositoryResult.Success) {
                val memberIds = members.map { it.memberId }.toSet()
                _uiState.update { it.copy(invited = invitations.value.filterNot { inv -> inv.memberId in memberIds }) }
            }
        }
    }

    private fun loadGrantableContacts() {
        viewModelScope.launch {
            val result = contactsRepository.connectedContacts()
            if (result is RepositoryResult.Success) {
                _uiState.update {
                    it.copy(
                        grantableContacts = result.value.filter { c -> c.grantable },
                        contactNames = result.value.associate { c -> c.fingerprint to c.displayName },
                    )
                }
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
