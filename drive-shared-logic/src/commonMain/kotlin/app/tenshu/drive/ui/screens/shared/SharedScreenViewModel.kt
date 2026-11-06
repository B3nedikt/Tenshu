package app.tenshu.drive.ui.screens.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactsRepository
import app.tenshu.drive.data.FilesRepository
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.liststate.ListLoadState
import app.tenshu.drive.data.MembershipRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.ShareModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.ContactInvite

/**
 * VM for the Drive Shared destination (`docs/file-sharing.md`
 * §6.1). *My shares* projects [FilesRepository.shares] (a reactive stream over
 * the realm-wide `FileService.shares` collection, revoked entries filtered
 * out). *Shared with me* lists inbound realm memberships.
 *
 * Revocation goes through [FilesRepository.revokeShareLink]; the local entity
 * flips `revoked = true` and the reactive `shares()` flow re-emits without
 * the row, so the UI reflects the change without a round-trip.
 *
 * Contact-handshake surfaces (`drive-manage-access-ux.md` §2.3/§3.2.4) also
 * live here: the `REDEEMED`-invite banner (tap → completion prompt) and the
 * invitee-side redemption resume — on init every `listPendingRedemptions` row
 * re-invokes `awaitInviteAck` and re-shows its waiting row.
 */
class SharedScreenViewModel(
    private val filesRepository: FilesRepository,
    private val contactsRepository: ContactsRepository,
    private val membershipRepository: MembershipRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedScreenUiState())
    val uiState: StateFlow<SharedScreenUiState> get() = _uiState

    /** Ids of invites currently at `REDEEMED` — the banner's tap target. */
    private var redeemedInviteIds: List<String> = emptyList()

    /** Fingerprints of grantors with a discovered pending grant (GR-6). */
    private var pendingFingerprints: List<String> = emptyList()

    /** `COMPLETED` `SHARE_FOLDER` invites — the deferred grant offer's raw material. */
    private var completedFolderInvites: List<ContactInviteModel> = emptyList()

    /**
     * Offers the user said *Not now* to.
     *
     * ⚠⚠ **In-memory, and that is the honest cost of decoupling.** The chained offer needed
     * no dismissal state at all because it was modal and transient — declining it ended it.
     * A derived card has to be told to go away, and "go away" is not derivable from any
     * synced row, so it survives neither a restart nor a second device. Making it durable
     * needs a real decision (a device-scoped preference? a synced `GrantOfferDismissed`
     * row?) that this prototype deliberately does not take.
     *
     * The *granted* case needs none of this: granting makes the contact a member, and a
     * member is filtered out structurally.
     */
    private val dismissedOffers = mutableSetOf<String>()

    init {
        loadGrantableContacts()
        pollPendingGrants()
        viewModelScope.launch {
            filesRepository.shares().collect { load ->
                _uiState.value = when (load) {
                    ListLoadState.Loading ->
                        _uiState.value.copy(mySharesLoad = ListLoadPhase.LOADING)
                    ListLoadState.Failed ->
                        _uiState.value.copy(mySharesLoad = ListLoadPhase.FAILED)
                    is ListLoadState.Loaded -> _uiState.value.copy(
                        myShares = load.items.map { it.toRow() },
                        mySharesLoad = ListLoadPhase.LOADED,
                    )
                }
            }
        }
        viewModelScope.launch {
            contactsRepository.contactInvites()
                .catch { /* keep last-known */ }
                .collect { invites ->
                    // ⚠ SHARE_FOLDER only (`contacts-app.md` §9.1): bare invites are the
                    // Contacts app's to complete, and prompting for them here too would
                    // put the same invitation in front of the user twice.
                    redeemedInviteIds = invites
                        .filter {
                            it.status == ContactInvite.Status.REDEEMED &&
                                it.intentKind == ContactInvite.IntentKind.SHARE_FOLDER
                        }
                        .map { it.inviteId }
                    // Completed folder invites are the deferred grant offer's raw material.
                    completedFolderInvites = invites.filter {
                        it.status == ContactInvite.Status.COMPLETED &&
                            it.intentKind == ContactInvite.IntentKind.SHARE_FOLDER &&
                            it.intentLabel.isNotEmpty() &&
                            it.resultFingerprint.isNotEmpty()
                    }
                    _uiState.update {
                        it.copy(hasRedeemedInvites = redeemedInviteIds.isNotEmpty())
                    }
                    recomputeGrantOffers()
                }
        }
        refreshSharedFolders()
        refreshSharedWithMe()
        resumeRedemptions()
    }

    fun onTabSelected(tab: Tab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    /**
     * Re-read the inbound *Shared with me* realms (`getSharedRealmMemberships`
     * filtered to `role = member`, drive-shared-realm-file-model.md §6 Phase 3d.2b).
     * One-shot like [refreshSharedFolders]; re-read on init + on return to the screen.
     */
    fun refreshSharedWithMe() {
        viewModelScope.launch {
            when (val result = filesRepository.sharedWithMe()) {
                is RepositoryResult.Success ->
                    _uiState.value = _uiState.value.copy(sharedWithMe = result.value)
                is RepositoryResult.Error -> Unit // leave the prior list; empty-state otherwise
            }
        }
    }

    /**
     * Re-read the Mode-B shared-folder mounts (`listPlacements` is a one-shot op,
     * not a reactive stream like `shares()`). Called on init and whenever the user
     * returns to this screen so a folder just shared elsewhere shows up.
     */
    fun refreshSharedFolders() {
        viewModelScope.launch {
            when (val result = filesRepository.sharedFolders()) {
                is RepositoryResult.Success -> {
                    _uiState.value = _uiState.value.copy(sharedFolders = result.value)
                    // A folder that just became shared can complete a pending offer.
                    recomputeGrantOffers()
                }
                is RepositoryResult.Error -> Unit // leave the prior list; empty-state otherwise
            }
        }
    }

    /**
     * Per-row tap — P1b deferred. The proposal sketches a share-details sheet
     * (copy URL, see opens count, revoke); v1 ships with the row's trailing
     * Revoke action only, so a row click is a no-op for now.
     */
    fun onShareRowClicked(shareId: String) {
        // No-op until the share-details sheet ships (see KDoc).
    }

    /**
     * Trailing-action Revoke. The op flips the local `Share.revoked` flag
     * (Eager-syncs to other realm devices) and — once the relay-side plumbing
     * is wired — DELETEs the relay-side record so future `GET /share/{id}`
     * returns 410. The reactive `shares()` flow drops the row without
     * additional state nudges.
     */
    fun onRevokeShareClicked(shareId: String) {
        viewModelScope.launch {
            filesRepository.revokeShareLink(shareId)
        }
    }

    /** The `REDEEMED` banner tap → open the completion prompt for the first one. */
    fun onContactPromptBannerClicked() {
        val inviteId = redeemedInviteIds.firstOrNull() ?: return
        _uiState.update { it.copy(promptInviteId = inviteId) }
    }

    fun onContactPromptDismissed() {
        _uiState.update { it.copy(promptInviteId = null) }
    }

    /** GR-4: open the picker to accept a grant waiting from a connected contact. */
    fun onAcceptFromContactClicked() {
        _uiState.update { it.copy(showAcceptPicker = true, acceptMessage = null) }
    }

    fun onAcceptPickerDismissed() {
        _uiState.update { it.copy(showAcceptPicker = false, acceptMessage = null) }
    }

    /**
     * GR-4 (Option A): pick up + accept a grant from [grantorFingerprint]
     * (`acceptSharedRealmGrant`). On success the realm lands in *Shared with me*;
     * on failure (the common case: no grant blob at the derived address) we show a
     * friendly "nothing shared from {name} yet" notice rather than a raw error.
     */
    fun onAcceptFromContact(grantorFingerprint: String) {
        if (_uiState.value.acceptInProgress) return
        val name = _uiState.value.grantableContacts
            .firstOrNull { it.fingerprint == grantorFingerprint }?.displayName.orEmpty()
        _uiState.update { it.copy(acceptInProgress = true, acceptMessage = null) }
        viewModelScope.launch {
            when (membershipRepository.accept(grantorFingerprint)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(acceptInProgress = false, showAcceptPicker = false) }
                    refreshSharedWithMe()
                }
                is RepositoryResult.Error ->
                    _uiState.update { it.copy(acceptInProgress = false, acceptMessage = name) }
            }
        }
    }

    private fun loadGrantableContacts() {
        viewModelScope.launch {
            val result = contactsRepository.connectedContacts()
            if (result is RepositoryResult.Success) {
                _uiState.update { it.copy(grantableContacts = result.value.filter { c -> c.grantable }) }
                recomputePendingCards()
                recomputeGrantOffers()
            }
        }
    }

    /**
     * GR-6 inbound poll: sweep grantable contacts' grant addresses for waiting grants
     * (`pollPendingGrants`) and surface a card per quarantined grant. Run on init and
     * on return to the screen ([refreshSharedWithMe]'s sibling). Out-of-band-free
     * discovery — grants normally follow an in-person handshake, so a failure just
     * leaves the prior cards.
     */
    fun pollPendingGrants() {
        viewModelScope.launch {
            when (val result = membershipRepository.pollPendingGrants()) {
                is RepositoryResult.Success -> {
                    pendingFingerprints = result.value.map { it.grantorFingerprint }
                    recomputePendingCards()
                }
                is RepositoryResult.Error -> Unit // keep prior cards
            }
        }
    }

    /**
     * GR-6: accept a discovered pending grant (the stored sealed blob is consumed by
     * `acceptSharedRealmGrant`). On success the realm lands in *Shared with me* and the
     * card drops; on failure a friendly notice shows.
     */
    fun onAcceptPendingGrant(grantorFingerprint: String) {
        if (_uiState.value.acceptInProgress) return
        val name = grantorName(grantorFingerprint)
        _uiState.update { it.copy(acceptInProgress = true, acceptMessage = null) }
        viewModelScope.launch {
            when (membershipRepository.accept(grantorFingerprint)) {
                is RepositoryResult.Success -> {
                    pendingFingerprints = pendingFingerprints - grantorFingerprint
                    _uiState.update { it.copy(acceptInProgress = false) }
                    recomputePendingCards()
                    refreshSharedWithMe()
                }
                is RepositoryResult.Error ->
                    _uiState.update { it.copy(acceptInProgress = false, acceptMessage = name) }
            }
        }
    }

    /** GR-6: decline (discard) a discovered pending grant (`declinePendingGrant`). */
    fun onDeclinePendingGrant(grantorFingerprint: String) {
        viewModelScope.launch {
            membershipRepository.declinePendingGrant(grantorFingerprint)
            pendingFingerprints = pendingFingerprints - grantorFingerprint
            recomputePendingCards()
        }
    }

    private fun recomputePendingCards() {
        val cards = pendingFingerprints.map { fp ->
            PendingGrantCard(fingerprint = fp, grantorName = grantorName(fp))
        }
        _uiState.update { it.copy(pendingGrants = cards) }
    }

    // ── deferred grant offers (contacts-app.md §9.1 alternative) ─────────────

    /**
     * Grant the contact the realm their finished invite named, then let the recompute drop
     * the card: once they are a member, the offer stops being derivable at all.
     */
    fun onGrantOfferAccepted(inviteId: String) {
        val offer = _uiState.value.grantOffers.firstOrNull { it.inviteId == inviteId } ?: return
        viewModelScope.launch {
            when (membershipRepository.grant(offer.realmId, offer.personId, offer.name)) {
                is RepositoryResult.Success -> recomputeGrantOffers()
                is RepositoryResult.Error ->
                    // Leave the card up: a failed grant is exactly the case where the offer
                    // should still be there to retry.
                    Unit
            }
        }
    }

    /**
     * ⚠ Hides the offer **for this process only**. See [dismissedOffers] — a derived card
     * needs somewhere to remember "no", and the chained design never had to answer that
     * question. Left deliberately shallow so the gap is visible rather than papered over.
     */
    fun onGrantOfferDismissed(inviteId: String) {
        dismissedOffers += inviteId
        recomputeGrantOffers()
    }

    /**
     * Rebuild the offers from what is currently known. Cheap and idempotent, so every input
     * that lands (invites, contacts, shared folders, a completed grant) just calls it.
     *
     * The membership check is a per-realm read, so it runs only for offers that survive the
     * cheap filters — a contact who is already a member must not be offered again, and that
     * is the check that makes the card converge across devices without any stored state.
     */
    private fun recomputeGrantOffers() {
        viewModelScope.launch {
            val contacts = _uiState.value.grantableContacts
            val folders = _uiState.value.sharedFolders
            if (contacts.isEmpty() || folders.isEmpty()) {
                if (_uiState.value.grantOffers.isNotEmpty()) {
                    _uiState.update { it.copy(grantOffers = emptyList()) }
                }
                return@launch
            }

            val candidates = completedFolderInvites.mapNotNull { invite ->
                if (invite.inviteId in dismissedOffers) return@mapNotNull null
                val contact = contacts.firstOrNull { it.fingerprint == invite.resultFingerprint }
                    ?: return@mapNotNull null
                val realmId = folders.firstOrNull { it.name == invite.intentLabel }
                    ?.sharedRealmId
                    ?: return@mapNotNull null
                GrantOfferCard(
                    inviteId = invite.inviteId,
                    personId = contact.personId,
                    realmId = realmId,
                    label = invite.intentLabel,
                    name = contact.displayName.ifEmpty { invite.claimedName },
                )
            }

            // One members() read per distinct realm, not per offer.
            val membersByRealm = candidates.map { it.realmId }.distinct().associateWith { realmId ->
                when (val result = membershipRepository.members(realmId)) {
                    is RepositoryResult.Success -> result.value.map { it.contactFingerprint }
                    // Unknown roster: keep the offer rather than hiding a real one. Offering
                    // a grant twice is harmless (the op is addressed by member); silently
                    // dropping the offer would strand the share.
                    is RepositoryResult.Error -> emptyList()
                }
            }

            val offers = candidates.filter { offer ->
                val fingerprint = completedFolderInvites
                    .first { it.inviteId == offer.inviteId }
                    .resultFingerprint
                fingerprint !in membersByRealm[offer.realmId].orEmpty()
            }
            _uiState.update { it.copy(grantOffers = offers) }
        }
    }

    private fun grantorName(fingerprint: String): String =
        _uiState.value.grantableContacts
            .firstOrNull { it.fingerprint == fingerprint }?.displayName.orEmpty()

    /**
     * §3.2.4 resume: every live invitee-side redemption re-invokes
     * `awaitInviteAck` (re-invoked again on each `PENDING` budget elapse while
     * this VM lives) and shows a waiting row until a terminal state lands.
     */
    private fun resumeRedemptions() {
        viewModelScope.launch {
            val result = contactsRepository.pendingRedemptions()
            val pending = (result as? RepositoryResult.Success)?.value ?: return@launch
            _uiState.update {
                it.copy(
                    resumedRedemptions = pending.map { row ->
                        ResumedRedemption(
                            inviteId = row.inviteId,
                            peerFingerprint = row.peerFingerprint,
                        )
                    }
                )
            }
            pending.forEach { row ->
                launch { awaitAck(row.inviteId) }
            }
        }
    }

    private suspend fun awaitAck(inviteId: String) {
        while (true) {
            val result = contactsRepository.awaitInviteAck(inviteId)
            val ack = (result as? RepositoryResult.Success)?.value
                ?: return // transport failure — resume again next time
            if (ack.status == AwaitInviteAck.Result.PENDING) continue
            _uiState.update { state ->
                state.copy(
                    resumedRedemptions = state.resumedRedemptions.map { row ->
                        if (row.inviteId == inviteId) row.copy(ack = ack) else row
                    }
                )
            }
            return
        }
    }

    private fun ShareModel.toRow(): MyShareRow = MyShareRow(
        shareId = shareId,
        filePath = filePath,
        fileName = fileName,
        createdAtMillis = createdAtMillis,
        expiresAtMillis = expiresAtMillis,
        maxOpens = maxOpens,
        opensSoFar = 0, // TODO(P1b): surface opens count once the relay
        // ShareRecord's `opensSoFar` is queryable from the proxy
        // (the wire piece exists today, the sharer-side read path doesn't —
        // tracked as P1b §9.5 step 1 follow-on).
        revoked = revoked,
    )
}
