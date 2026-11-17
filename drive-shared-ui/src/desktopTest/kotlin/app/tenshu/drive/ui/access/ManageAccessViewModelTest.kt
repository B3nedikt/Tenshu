package app.tenshu.drive.ui.access

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.MemberModel
import app.tenshu.drive.data.InvitationModel
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.FakeMembershipRepository
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.ContactInvite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `drive-manage-access-ux.md` §8 coverage for [ManageAccessViewModel]: invite
 * rows filtered by intent label + live statuses; revoke; the REDEEMED-row →
 * completion-prompt flag; the Members-section visibility gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageAccessViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun invite(
        id: String,
        status: String = ContactInvite.Status.PENDING,
        intentKind: String = ContactInvite.IntentKind.SHARE_FOLDER,
        intentLabel: String = "Holiday",
    ) = ContactInviteModel(
        inviteId = id,
        status = status,
        intentKind = intentKind,
        intentLabel = intentLabel,
        createdAtMillis = 0L,
        expiresAtMillis = 0L,
        claimedName = "",
        claimedFingerprint = "",
        claimedSas = "",
    )

    @Test
    fun invites_filterByIntentLabel_andLiveStatuses() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(ManageAccessViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository(), "/Holiday"))
        contacts.invites.value = listOf(
            invite("match-pending"),
            invite("match-redeemed", status = ContactInvite.Status.REDEEMED),
            invite("other-folder", intentLabel = "Work"),
            invite("no-intent", intentKind = "", intentLabel = ""),
            invite("revoked", status = ContactInvite.Status.REVOKED),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("match-pending", "match-redeemed"),
            vm.uiState.value.invites.map { it.inviteId },
        )
    }

    @Test
    fun revoke_callsRepository() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(ManageAccessViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()

        vm.onRevokeInviteClicked("invite-1")
        advanceUntilIdle()

        assertEquals(listOf("invite-1"), contacts.revokeCalls)
    }

    @Test
    fun redeemedRowTap_opensPrompt_pendingRowDoesNot() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(ManageAccessViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository(), "/Holiday"))
        contacts.invites.value = listOf(
            invite("pending-1"),
            invite("redeemed-1", status = ContactInvite.Status.REDEEMED),
        )
        advanceUntilIdle()

        vm.onInviteRowClicked("pending-1")
        assertNull(vm.uiState.value.promptInviteId)

        vm.onInviteRowClicked("redeemed-1")
        assertEquals("redeemed-1", vm.uiState.value.promptInviteId)

        vm.onPromptDismissed()
        assertNull(vm.uiState.value.promptInviteId)
    }

    @Test
    fun membersSection_onlyWhenFolderBacksASharedRealm() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository(
            sharedFoldersResult = listOf(
                SharedFolderModel(sharedRealmId = "r1", name = "Holiday", parentPath = "/"),
            )
        )
        val backed = vms.own(ManageAccessViewModel(files, FakeContactsRepository(), FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()
        assertTrue(backed.uiState.value.backsSharedRealm)

        val unbacked = vms.own(ManageAccessViewModel(files, FakeContactsRepository(), FakeMembershipRepository(), "/Other"))
        advanceUntilIdle()
        assertFalse(unbacked.uiState.value.backsSharedRealm)
    }

    private fun backedFiles() = FakeFilesRepository(
        sharedFoldersResult = listOf(SharedFolderModel("r1", "Holiday", "/")),
    )

    @Test
    fun backedFolder_loadsRealmMembers_andGrantableContacts() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(
                    ContactModel("p1", "Bob", "fp-bob", grantable = true),
                    ContactModel("p2", "Carol", "fp-carol", grantable = false),
                )
            )
        )
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(
                listOf(
                    MemberModel("m-owner", "You", "", role = "owner"),
                    MemberModel("m1", "Bob", "fp-bob", role = "member"),
                )
            )
        )
        val vm = vms.own(ManageAccessViewModel(backedFiles(), contacts, membership, "/Holiday"))
        advanceUntilIdle()

        assertEquals("r1", vm.uiState.value.sharedRealmId)
        // The owner row is the pinned "You — Owner"; only members are listed.
        assertEquals(listOf("m1"), vm.uiState.value.members.map { it.memberId })
        // Only grantable contacts are offered.
        assertEquals(listOf("p1"), vm.uiState.value.grantableContacts.map { it.personId })
        assertEquals(listOf("r1"), membership.membersCalls)
    }

    @Test
    fun grantToContact_callsRepo_closesPicker_andReloadsMembers() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Bob", "fp-bob", grantable = true))
            )
        )
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(backedFiles(), contacts, membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantAccessClicked()
        assertTrue(vm.uiState.value.showGrantPicker)

        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(
            listOf(FakeMembershipRepository.GrantCall("r1", "p1", "Bob")),
            membership.grantCalls,
        )
        assertFalse(vm.uiState.value.showGrantPicker)
        // Members reloaded after the grant (initial load + reload).
        assertEquals(listOf("r1", "r1"), membership.membersCalls)
    }

    @Test
    fun grantFailure_surfacesError_keepsPickerOpen() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Bob", "fp-bob", grantable = true))
            )
        )
        val membership = FakeMembershipRepository(grantResult = FakeMembershipRepository.error())
        val vm = vms.own(ManageAccessViewModel(backedFiles(), contacts, membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantAccessClicked()
        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showGrantPicker)
        assertNotNull(vm.uiState.value.grantError)
    }

    @Test
    fun removeMember_callsRepo_andReloadsMembers() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(
                listOf(MemberModel("m1", "Bob", "fp-bob", role = "member"))
            )
        )
        val vm = vms.own(ManageAccessViewModel(backedFiles(), FakeContactsRepository(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onRemoveMember("m1")
        advanceUntilIdle()

        assertEquals(
            listOf(FakeMembershipRepository.RemoveCall("r1", "m1")),
            membership.removeCalls,
        )
        assertNull(vm.uiState.value.removingMemberId)
        // Members reloaded after the remove (initial load + reload).
        assertEquals(listOf("r1", "r1"), membership.membersCalls)
    }

    @Test
    fun removeMemberFailure_surfacesError() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(
                listOf(MemberModel("m1", "Bob", "fp-bob", role = "member"))
            ),
            removeResult = FakeMembershipRepository.error(),
        )
        val vm = vms.own(ManageAccessViewModel(backedFiles(), FakeContactsRepository(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onRemoveMember("m1")
        advanceUntilIdle()

        assertNull(vm.uiState.value.removingMemberId)
        assertNotNull(vm.uiState.value.removeError)
    }

    @Test
    fun d7_completedInviteForAMember_isDedupedAgainstTheMemberRow() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(
                listOf(MemberModel("m1", "Bob", "fp-bob", role = "member"))
            )
        )
        val vm = vms.own(ManageAccessViewModel(backedFiles(), contacts, membership, "/Holiday"))
        contacts.invites.value = listOf(
            invite("pending-1"),
            ContactInviteModel(
                inviteId = "completed-bob",
                status = ContactInvite.Status.COMPLETED,
                intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
                intentLabel = "Holiday",
                createdAtMillis = 0L,
                expiresAtMillis = 0L,
                claimedName = "Bob",
                claimedFingerprint = "fp-bob",
                claimedSas = "",
            ),
            ContactInviteModel(
                inviteId = "completed-other",
                status = ContactInvite.Status.COMPLETED,
                intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
                intentLabel = "Holiday",
                createdAtMillis = 0L,
                expiresAtMillis = 0L,
                claimedName = "Dave",
                claimedFingerprint = "fp-dave",
                claimedSas = "",
            ),
        )
        advanceUntilIdle()

        // D7: the completed invite for the member (fp-bob) is dropped — that
        // person is represented by the member row. Pending + the other
        // (not-yet-a-member) completed invite stay.
        assertEquals(
            listOf("pending-1", "completed-other"),
            vm.uiState.value.invites.map { it.inviteId },
        )
        assertEquals(listOf("m1"), vm.uiState.value.members.map { it.memberId })
    }

    // ── The "invited" state (RO D7, shared-realm-membership-records.md §3.4) ─────────────────────

    @Test
    fun invitedGrants_areListed_andNamedFromContacts() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(listOf(ContactModel("p1", "Bob", "fp-bob", grantable = true))),
        )
        val membership = FakeMembershipRepository(membersResult = RepositoryResult.Success(emptyList())).also {
            it.invitationsResult = RepositoryResult.Success(listOf(InvitationModel("m-bob", "fp-bob")))
        }
        val vm = vms.own(ManageAccessViewModel(backedFiles(), contacts, membership, "/Holiday"))
        advanceUntilIdle()

        assertEquals(listOf("m-bob"), vm.uiState.value.invited.map { it.memberId })
        assertEquals("Bob", vm.uiState.value.contactNames["fp-bob"])
    }

    /** An invitation whose member already has a row is shown once — as the member. */
    @Test
    fun anAcceptedInvitation_isListedAsAMemberOnly() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository(
            membersResult = RepositoryResult.Success(listOf(MemberModel("m-bob", "", "fp-bob", role = "member"))),
        ).also { it.invitationsResult = RepositoryResult.Success(listOf(InvitationModel("m-bob", "fp-bob"))) }
        val vm = vms.own(ManageAccessViewModel(backedFiles(), FakeContactsRepository(), membership, "/Holiday"))
        advanceUntilIdle()

        assertEquals(listOf("m-bob"), vm.uiState.value.members.map { it.memberId })
        assertEquals(emptyList(), vm.uiState.value.invited)
    }

    /** RO D7: opening Manage access polls first, so an acceptance that just arrived is picked up. */
    @Test
    fun loadingTheMembers_pollsForAcceptancesFirst() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val membership = FakeMembershipRepository(membersResult = RepositoryResult.Success(emptyList()))
        vms.own(ManageAccessViewModel(backedFiles(), FakeContactsRepository(), membership, "/Holiday"))
        advanceUntilIdle()

        assertTrue(membership.pollCount >= 1)
    }

    // ── §11: one Share entry — the folder is shared on the first add ─────────────────────────────

    private fun bob() = FakeContactsRepository(
        connectedContactsResult = RepositoryResult.Success(
            listOf(ContactModel("p1", "Bob", "fp-bob", grantable = true))
        )
    )

    @Test
    fun unsharedFolder_stillOffersContacts() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vms.own(ManageAccessViewModel(FakeFilesRepository(), bob(), FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.backsSharedRealm)
        assertEquals(listOf("p1"), vm.uiState.value.grantableContacts.map { it.personId })
    }

    @Test
    fun grantOnUnsharedFolder_sharesItFirst_thenGrantsIntoTheNewRealm() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository()
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantAccessClicked()
        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(listOf("/Holiday"), files.shareFolderCalls)
        assertEquals(listOf(FakeMembershipRepository.GrantCall("realm-Holiday", "p1", "Bob")), membership.grantCalls)
        assertEquals("realm-Holiday", vm.uiState.value.sharedRealmId)
        assertTrue(vm.uiState.value.backsSharedRealm)
        assertFalse(vm.uiState.value.sharing)
        assertFalse(vm.uiState.value.showGrantPicker)
    }

    @Test
    fun grantOnAlreadySharedFolder_doesNotShareAgain() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = backedFiles()
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(emptyList(), files.shareFolderCalls)
        assertEquals(listOf(FakeMembershipRepository.GrantCall("r1", "p1", "Bob")), membership.grantCalls)
    }

    /** Tapped before the opening lookup lands: it must wait for it, not mint a second realm. */
    @Test
    fun grantBeforeTheLookupLands_waitsForIt_andReusesTheRealm() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = backedFiles()
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))

        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(emptyList(), files.shareFolderCalls)
        assertEquals(listOf("r1"), membership.grantCalls.map { it.realmId })
    }

    /** A failed "is it shared?" read is not "no": it is asked again before sharing. */
    @Test
    fun failedOpeningLookup_isAskedAgain_beforeSharing() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = backedFiles().also { it.sharedFoldersFailures = 1 }
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.backsSharedRealm)

        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(emptyList(), files.shareFolderCalls, "the retry found the realm")
        assertEquals(listOf("r1"), membership.grantCalls.map { it.realmId })
    }

    @Test
    fun failedLookupTwice_neitherSharesNorGrants_andSaysWhy() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = backedFiles().also { it.sharedFoldersFailures = 2 }
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(emptyList(), files.shareFolderCalls)
        assertEquals(emptyList(), membership.grantCalls)
        assertNotNull(vm.uiState.value.shareError)
        assertFalse(vm.uiState.value.grantInProgress)
    }

    @Test
    fun inviteOnUnsharedFolder_sharesItFirst_thenOpensTheSheet() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository()
        val vm = vms.own(ManageAccessViewModel(files, FakeContactsRepository(), FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()

        vm.onInviteSomeoneClicked()
        advanceUntilIdle()

        assertEquals(listOf("/Holiday"), files.shareFolderCalls)
        assertTrue(vm.uiState.value.showInviteSheet)
        assertEquals("realm-Holiday", vm.uiState.value.sharedRealmId)
    }

    @Test
    fun doubleTapOnInvite_sharesOnce() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository()
        val vm = vms.own(ManageAccessViewModel(files, FakeContactsRepository(), FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()

        vm.onInviteSomeoneClicked()
        vm.onInviteSomeoneClicked()
        advanceUntilIdle()

        assertEquals(listOf("/Holiday"), files.shareFolderCalls)
    }

    @Test
    fun firstAdd_withoutStorage_raisesTheGate_andSharesNothing() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository(
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onInviteSomeoneClicked()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.showInviteSheet)
        assertEquals(StorageGateDecision.NeedsStorage, vm.uiState.value.storageGateBlock)

        vm.onStorageGateDismissed()
        vm.onGrantToContact("p1")
        advanceUntilIdle()
        assertEquals(StorageGateDecision.NeedsStorage, vm.uiState.value.storageGateBlock)
        assertFalse(vm.uiState.value.grantInProgress)

        assertEquals(emptyList(), files.shareFolderCalls)
        assertEquals(emptyList(), membership.grantCalls)
    }

    /** An already-shared folder needs no storage to add someone: nothing new is created. */
    @Test
    fun alreadySharedFolder_isNotStorageGated() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository(
            sharedFoldersResult = listOf(SharedFolderModel("r1", "Holiday", "/")),
            storageStatusResult = StorageStatusModel(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
        )
        val vm = vms.own(ManageAccessViewModel(files, FakeContactsRepository(), FakeMembershipRepository(), "/Holiday"))
        advanceUntilIdle()

        vm.onInviteSomeoneClicked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showInviteSheet)
        assertNull(vm.uiState.value.storageGateBlock)
    }

    @Test
    fun shareFailure_surfacesInThePicker_andGrantsNothing() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = FakeFilesRepository().also { it.shareFolderResult = FakeMembershipRepository.error() }
        val membership = FakeMembershipRepository()
        val vm = vms.own(ManageAccessViewModel(files, bob(), membership, "/Holiday"))
        advanceUntilIdle()

        vm.onGrantAccessClicked()
        vm.onGrantToContact("p1")
        advanceUntilIdle()

        assertEquals(emptyList(), membership.grantCalls)
        assertNotNull(vm.uiState.value.shareError)
        assertNotNull(vm.uiState.value.grantError)
        assertTrue(vm.uiState.value.showGrantPicker)
        assertFalse(vm.uiState.value.sharing)
        assertNull(vm.uiState.value.sharedRealmId)
    }
}
