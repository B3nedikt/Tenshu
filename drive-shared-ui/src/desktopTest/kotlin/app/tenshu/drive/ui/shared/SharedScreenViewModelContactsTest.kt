package app.tenshu.drive.ui.shared

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.data.PendingGrantModel
import app.tenshu.drive.data.PendingRedemptionModel
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.FakeMembershipRepository
import app.tenshu.drive.ui.screens.shared.SharedScreenViewModel
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.ContactInvite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Shared destination's contact-handshake surfaces
 * (`drive-manage-access-ux.md` §2.3 banner + §3.2.4 redemption resume).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedScreenViewModelContactsTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun redeemedInvite(
        id: String,
        // Drive only ever mints SHARE_FOLDER invites (`InviteLinkViewModel`), and since the
        // Contacts app landed that is also the only kind it prompts for — see the bare-invite
        // case below and `contacts-app.md` §9.1.
        intentKind: String = ContactInvite.IntentKind.SHARE_FOLDER,
    ) = ContactInviteModel(
        inviteId = id,
        status = ContactInvite.Status.REDEEMED,
        intentKind = intentKind,
        intentLabel = "Holiday",
        createdAtMillis = 0L,
        expiresAtMillis = 0L,
        claimedName = "Bob",
        claimedFingerprint = "abc",
        claimedSas = "",
    )

    @Test
    fun redeemedInvite_raisesBanner_andBannerTapOpensPrompt() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()
        assertTrue(!vm.uiState.value.hasRedeemedInvites)

        contacts.invites.value = listOf(redeemedInvite("invite-1"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasRedeemedInvites)
        vm.onContactPromptBannerClicked()
        assertEquals("invite-1", vm.uiState.value.promptInviteId)
        vm.onContactPromptDismissed()
        assertNull(vm.uiState.value.promptInviteId)
    }

    @Test
    fun bareRedeemedInvite_doesNotRaiseDrivesBanner() = vmTest { vms ->
        // ⚠ The invite space is partitioned between the two apps (`contacts-app.md` §9.1):
        // a bare invite belongs to Contacts, whose prompt completes it. Drive raising a
        // banner for it too would put the same invitation in front of the user twice — and
        // Drive's prompt would then chain into a grant offer for a share that never existed.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()

        contacts.invites.value = listOf(redeemedInvite("invite-bare", intentKind = ""))
        advanceUntilIdle()

        assertTrue(!vm.uiState.value.hasRedeemedInvites)
        vm.onContactPromptBannerClicked()
        assertNull(vm.uiState.value.promptInviteId)
    }

    @Test
    fun init_resumesEveryPendingRedemption_reinvokingOnPendingBudget() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            pendingRedemptionsResult = RepositoryResult.Success(
                listOf(
                    PendingRedemptionModel("invite-a", 1L, "fp-a", ""),
                    PendingRedemptionModel("invite-b", 2L, "fp-b", ""),
                )
            )
        )
        // invite-a: PENDING once (budget elapse) → re-invoked → ACCEPTED.
        // invite-b: REJECTED straight away. The fake's queue is shared, but
        // the StandardTestDispatcher runs the two waits sequentially.
        contacts.ackResults += InviteAckModel(AwaitInviteAck.Result.PENDING, "", "", "", "", "")
        contacts.ackResults += InviteAckModel(AwaitInviteAck.Result.ACCEPTED, "Alice", "fp-a", "", "", "")
        contacts.ackResults += InviteAckModel(AwaitInviteAck.Result.REJECTED, "", "", "", "", "")

        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, FakeMembershipRepository()))
        advanceUntilIdle()

        // §3.2.4: one awaitInviteAck per pending redemption, re-invoked on PENDING.
        assertEquals(
            listOf("invite-a" to false, "invite-a" to false, "invite-b" to false),
            contacts.awaitAckCalls,
        )
        val rows = vm.uiState.value.resumedRedemptions.associateBy { it.inviteId }
        assertEquals(AwaitInviteAck.Result.ACCEPTED, rows.getValue("invite-a").ack?.status)
        assertEquals(AwaitInviteAck.Result.REJECTED, rows.getValue("invite-b").ack?.status)
    }

    @Test
    fun acceptFromContact_onlyGrantableContactsOffered_acceptCallsRepoByFingerprint() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(
                    ContactModel("p1", "Alice", fingerprint = "fp-alice", grantable = true),
                    ContactModel("p2", "Carol", fingerprint = "fp-carol", grantable = false),
                )
            )
        )
        val membership = FakeMembershipRepository()
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, membership))
        advanceUntilIdle()

        // Only the grantable contact is offered in the picker.
        assertEquals(listOf("p1"), vm.uiState.value.grantableContacts.map { it.personId })

        vm.onAcceptFromContact("fp-alice")
        advanceUntilIdle()

        assertEquals(listOf("fp-alice"), membership.acceptCalls)
        assertNull(vm.uiState.value.acceptMessage)
        assertTrue(!vm.uiState.value.showAcceptPicker)
    }

    @Test
    fun init_pollsPendingGrants_andSurfacesCardsWithResolvedNames() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Alice", fingerprint = "fp-alice", grantable = true))
            )
        )
        val membership = FakeMembershipRepository(
            pollResult = RepositoryResult.Success(
                listOf(PendingGrantModel(grantorFingerprint = "fp-alice", discoveredAt = 1L))
            )
        )
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, membership))
        advanceUntilIdle()

        assertEquals(1, membership.pollCount)
        val cards = vm.uiState.value.pendingGrants
        assertEquals(listOf("fp-alice"), cards.map { it.fingerprint })
        // The grantor's name is resolved from the loaded contacts.
        assertEquals("Alice", cards.single().grantorName)
    }

    @Test
    fun acceptPendingGrant_consumesCard_andRefreshesInbound() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Alice", fingerprint = "fp-alice", grantable = true))
            )
        )
        val membership = FakeMembershipRepository(
            pollResult = RepositoryResult.Success(
                listOf(PendingGrantModel("fp-alice", 1L))
            )
        )
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, membership))
        advanceUntilIdle()
        assertEquals(listOf("fp-alice"), vm.uiState.value.pendingGrants.map { it.fingerprint })

        vm.onAcceptPendingGrant("fp-alice")
        advanceUntilIdle()

        assertEquals(listOf("fp-alice"), membership.acceptCalls)
        // The accepted card is dropped.
        assertTrue(vm.uiState.value.pendingGrants.isEmpty())
    }

    @Test
    fun declinePendingGrant_callsRepo_andDropsCard() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Alice", fingerprint = "fp-alice", grantable = true))
            )
        )
        val membership = FakeMembershipRepository(
            pollResult = RepositoryResult.Success(
                listOf(PendingGrantModel("fp-alice", 1L))
            )
        )
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, membership))
        advanceUntilIdle()

        vm.onDeclinePendingGrant("fp-alice")
        advanceUntilIdle()

        assertEquals(listOf("fp-alice"), membership.declineCalls)
        assertTrue(vm.uiState.value.pendingGrants.isEmpty())
    }

    @Test
    fun acceptFromContact_noBlob_showsFriendlyNothingSharedNotice() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Alice", fingerprint = "fp-alice", grantable = true))
            )
        )
        val membership = FakeMembershipRepository(acceptResult = FakeMembershipRepository.error())
        val vm = vms.own(SharedScreenViewModel(FakeFilesRepository(), contacts, membership))
        advanceUntilIdle()

        vm.onAcceptFromContactClicked()
        vm.onAcceptFromContact("fp-alice")
        advanceUntilIdle()

        // Friendly notice carries the contact's name; the picker stays open.
        assertEquals("Alice", vm.uiState.value.acceptMessage)
        assertTrue(vm.uiState.value.showAcceptPicker)
    }
}
