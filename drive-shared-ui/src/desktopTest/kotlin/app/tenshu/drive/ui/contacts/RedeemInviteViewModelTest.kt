package app.tenshu.drive.ui.contacts

import app.tenshu.drive.data.InviteAckModel
import app.tenshu.drive.data.RedeemOutcome
import app.tenshu.drive.ui.FakeContactsRepository
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.ContactInvite
import org.tenshu.core.common.util.InviteLinkPayload
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §3.2 redeem-flow coverage: typed paste errors, the loud already-used (Gone)
 * mapping (T-CI-01), the waiting → terminal transitions, and the
 * PENDING-budget re-invoke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedeemInviteViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun validLink(): String =
        InviteLinkPayload.mint(ByteArray(32) { 7 }).toMessagingLink()

    private fun ack(status: String) = InviteAckModel(
        status = status,
        peerName = "Alice",
        peerFingerprint = "abcdef0123456789",
        intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
        intentLabel = "Holiday",
        sas = "482913",
    )

    @Test
    fun paste_malformed_andUnsupportedVersion_areTypedErrors() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = vms.own(RedeemInviteViewModel(FakeContactsRepository(), initialLink = null))

        vm.onPastedLinkChanged("not a link")
        vm.onLinkEntered()
        assertEquals(PasteError.MALFORMED, vm.uiState.value.pasteError)
        assertEquals(RedeemStage.EnterLink, vm.uiState.value.stage)

        vm.onPastedLinkChanged("https://tenshu.app/i#v9.AAAA")
        vm.onLinkEntered()
        assertEquals(PasteError.UNSUPPORTED_VERSION, vm.uiState.value.pasteError)
    }

    @Test
    fun validLink_showsInviterFingerprint_thenRedeemAndAcceptedCard() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            redeemResult = RedeemOutcome.Redeemed("invite-1"),
        )
        contacts.ackResults += ack(AwaitInviteAck.Result.ACCEPTED)
        val link = validLink()
        val vm = vms.own(RedeemInviteViewModel(contacts, initialLink = link))

        assertEquals(RedeemStage.ConfirmName, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.inviterFingerprint.isNotEmpty())

        vm.onSelfNameChanged("Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(listOf(link to "Bob"), contacts.redeemCalls.map { it })
        assertEquals(listOf("invite-1" to false), contacts.awaitAckCalls)
        val stage = assertIs<RedeemStage.Accepted>(vm.uiState.value.stage)
        assertEquals("Alice", stage.ack.peerName)
        assertEquals("Holiday", stage.ack.intentLabel)
        // The self-asserted name is remembered for next time.
        assertEquals(listOf("Bob"), contacts.savedOwnNames)
    }

    @Test
    fun alreadyUsedSlot_isLoud_notGeneric() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(redeemResult = RedeemOutcome.AlreadyUsed)
        val vm = vms.own(RedeemInviteViewModel(contacts, initialLink = validLink()))

        vm.onSelfNameChanged("Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        // T-CI-01: the taken response slot maps to the Gone stage (its copy
        // carries the "if you didn't use this link…" warning), never to the
        // generic inline error.
        assertEquals(RedeemStage.Gone, vm.uiState.value.stage)
        assertEquals(null, vm.uiState.value.errorCopyKey)
    }

    @Test
    fun pendingBudget_reinvokesTheAckWait() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            redeemResult = RedeemOutcome.Redeemed("invite-1"),
        )
        contacts.ackResults += ack(AwaitInviteAck.Result.PENDING)
        contacts.ackResults += ack(AwaitInviteAck.Result.REJECTED)
        val vm = vms.own(RedeemInviteViewModel(contacts, initialLink = validLink()))

        vm.onSelfNameChanged("Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(listOf("invite-1" to false, "invite-1" to false), contacts.awaitAckCalls)
        assertEquals(RedeemStage.Rejected, vm.uiState.value.stage)
    }

    @Test
    fun sasConfirm_duringWaiting_restartsTheWaitWithSasConfirmed() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            redeemResult = RedeemOutcome.Redeemed("invite-1"),
            pendingRedemptionsResult = app.tenshu.drive.data.RepositoryResult.Success(
                listOf(
                    app.tenshu.drive.data.PendingRedemptionModel("invite-1", 1L, "fp", "482913"),
                )
            ),
        )
        // First wait: no scripted ack → errors out (transport-failure path),
        // leaving the stage at Waiting; the SAS confirm then restarts it.
        val vm = vms.own(RedeemInviteViewModel(contacts, initialLink = validLink()))
        vm.onSelfNameChanged("Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(RedeemStage.Waiting, vm.uiState.value.stage)
        assertEquals("482913", vm.uiState.value.sas)
        assertEquals(listOf("invite-1" to false), contacts.awaitAckCalls)

        contacts.ackResults += ack(AwaitInviteAck.Result.ACCEPTED)
        vm.onSasConfirmedChanged(true)
        advanceUntilIdle()

        // §5.3: the restarted wait carries sasConfirmed — and only because of
        // the explicit check.
        assertEquals(
            listOf("invite-1" to false, "invite-1" to true),
            contacts.awaitAckCalls,
        )
        assertIs<RedeemStage.Accepted>(vm.uiState.value.stage)
    }

    @Test
    fun resumeWait_skipsRedeem_andLandsOnTheTerminalState() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        contacts.ackResults += ack(AwaitInviteAck.Result.GONE)
        val vm = vms.own(RedeemInviteViewModel(contacts, initialLink = null))

        vm.resumeWait("invite-9")
        advanceUntilIdle()

        assertTrue(contacts.redeemCalls.isEmpty())
        assertEquals(listOf("invite-9" to false), contacts.awaitAckCalls)
        assertEquals(RedeemStage.Gone, vm.uiState.value.stage)
    }
}
