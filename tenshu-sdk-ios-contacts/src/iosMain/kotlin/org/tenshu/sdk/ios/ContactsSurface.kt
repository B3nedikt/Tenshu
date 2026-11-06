package org.tenshu.sdk.ios

import app.tenshu.error.ErrorCopyKey
import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.data.RepositoryResult
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.OutstandingInvite
import app.tenshu.contacts.model.OwnProfile
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.contacts.ui.screens.merge.MergeViewModel
import app.tenshu.contacts.ui.screens.profile.ProfileViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Wires the **real** Contacts view-model layer (`:contacts-shared-logic`) to iOS so the SwiftUI
 * `contacts-ios` app can resolve each screen's view model and observe its `StateFlow` through
 * SKIE (`ios-proxy-architecture.md` §9.3 — "share view models + repos, native UI"). The
 * Contacts twin of [MapsSurface] / [TimelineSurface] / [FitSurface].
 *
 * Contacts' Koin DI lives in `:contacts-shared-ui` (Compose, not on iOS), so this object is the
 * minimal hand-wiring the SwiftUI client needs instead of `contactsModules()`.
 *
 * One seam, not Maps' two: every Contacts screen reads the same [ContactsRepository].
 *
 * The runtime default is the honest **not-connected** repository: an unprovisioned app is empty
 * and says why, rather than full of fabricated people. That matters more here than anywhere — a
 * fake contact list is a list of people the user does not know, with badges implying keys that
 * do not exist.
 * [MockContactsRepository] survives for tests and render probes only ([installMock]).
 */
object ContactsSurface {

    private var repository: ContactsRepository = NotConnectedContactsRepository()

    /**
     * Install the repository subsequently-built view models bind. Called by the tier that owns a
     * live client — [ContactsLocalRuntime] (route 1); an EPC tier would call the same seam.
     */
    fun installRepository(contactsRepository: ContactsRepository) {
        repository = contactsRepository
    }

    /**
     * Restore the honest **not-connected** surface — the teardown / fallback target: empty reads
     * plus `controlReachable = false`, which is what drives the shell's banner.
     */
    fun installNotConnected() {
        repository = NotConnectedContactsRepository()
    }

    /**
     * Install the deterministic mock — the same fixture the Compose previews use. **Tests and
     * render probes only**; no shipping path reaches it.
     */
    fun installMock() {
        repository = MockContactsRepository()
    }

    /**
     * Whether the installed repository can actually reach Control — `false` only on the
     * not-connected surface. The Swift shell renders its banner off this, mirroring the Compose
     * shell's `repository.controlReachable()` read.
     */
    fun controlReachable(): Flow<Boolean?> = repository.controlReachable()

    /**
     * The repository itself, for the Swift shell's few direct reads.
     *
     * The badge on the Contacts tab is one: it has to be visible from the *other* tab, so it
     * cannot come from the list's view model, which only exists while that tab is on screen —
     * the same reasoning the Compose shell records.
     */
    fun repository(): ContactsRepository = repository

    // ── view models, one factory each ────────────────────────────────────────
    //
    // Built fresh per call rather than cached: SwiftUI owns their lifetime through
    // `@StateObject`, and a cached instance would outlive its screen and keep collecting.

    fun contactListViewModel(): ContactListViewModel = ContactListViewModel(repository)

    fun contactDetailViewModel(personId: String): ContactDetailViewModel =
        ContactDetailViewModel(repository, personId)

    fun profileViewModel(): ProfileViewModel = ProfileViewModel(repository)

    fun mergeViewModel(): MergeViewModel = MergeViewModel(repository)
}

/**
 * The **honest empty** repository: no contacts, no invitations, and `controlReachable = false`.
 *
 * ⚠ Every read is empty and every write fails with a stated reason. The alternative — a mock
 * behind the shipping gate — would put invented people in front of a user who has connected
 * nothing, complete with trust badges implying key material that does not exist. That is worse
 * here than in any sibling app, because the whole point of the contact graph is that its
 * contents were confirmed by a human.
 *
 * ⚠ `controlReachable` emits `false` rather than `null`: `null` means *not yet known* and the
 * shell shows nothing while it waits. Not-connected is a known state, and the banner is the
 * only thing that explains an empty screen.
 */
private class NotConnectedContactsRepository : ContactsRepository {

    private fun <T> unavailable(): RepositoryResult<T> =
        // ⚠ `CONTROL_NOT_RUNNING`, not a sentence: `RepositoryResult.Failure` carries an
        // `ErrorCopyKey` so no code below the app layer authors localised copy
        // (`core-error-copy-catalogue.md` §5.0). This key names exactly this state: the proxy is
        // absent, so the action was never attempted.
        RepositoryResult.Failure(ErrorCopyKey.CONTROL_NOT_RUNNING)

    override fun contacts(): Flow<List<Contact>> = flowOf(emptyList())

    override fun contact(personId: String): Flow<Contact?> = flowOf(null)

    override fun ownProfile(): Flow<OwnProfile> = flowOf(OwnProfile())

    override suspend fun setOwnDisplayName(name: String) = Unit

    override suspend fun createContact(displayName: String): RepositoryResult<String> =
        unavailable()

    override suspend fun renameContact(
        personId: String,
        displayName: String,
    ): RepositoryResult<Unit> = unavailable()

    override suspend fun deleteContact(personId: String): RepositoryResult<Unit> = unavailable()

    override suspend fun unlinkIdentity(fingerprint: String): RepositoryResult<Unit> =
        unavailable()

    override suspend fun mergeContacts(
        sourcePersonId: String,
        targetPersonId: String,
    ): RepositoryResult<Unit> = unavailable()

    override suspend fun relinkIdentity(
        fingerprint: String,
        personId: String,
    ): RepositoryResult<Unit> = unavailable()

    override suspend fun clearAvatar(personId: String): RepositoryResult<Unit> = unavailable()

    override suspend fun saveContactDetails(
        personId: String,
        details: ContactDetails,
    ): RepositoryResult<Unit> = unavailable()

    override suspend fun importVcards(text: String): RepositoryResult<Int> = unavailable()

    override suspend fun exportVcard(personId: String): RepositoryResult<String> = unavailable()

    override suspend fun contactSas(fingerprint: String): RepositoryResult<String> = unavailable()

    override suspend fun setVerified(
        fingerprint: String,
        verified: Boolean,
    ): RepositoryResult<Unit> = unavailable()

    override fun outstandingInvites(): Flow<List<OutstandingInvite>> = flowOf(emptyList())

    override fun pendingConfirmations(): Flow<List<InviteConfirmation>> = flowOf(emptyList())

    override suspend fun createBareInvite(): RepositoryResult<String> = unavailable()

    override suspend fun revokeInvite(inviteId: String): RepositoryResult<Unit> = unavailable()

    override suspend fun completeHandshake(
        inviteId: String,
        accept: Boolean,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RepositoryResult<Unit> = unavailable()

    override fun controlReachable(): Flow<Boolean?> = flowOf(false)
}
