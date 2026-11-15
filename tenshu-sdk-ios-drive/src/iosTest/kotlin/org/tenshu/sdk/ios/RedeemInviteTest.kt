package org.tenshu.sdk.ios

import app.tenshu.drive.data.RedeemOutcome
import app.tenshu.drive.ui.contacts.PasteError
import app.tenshu.drive.ui.contacts.RedeemStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.core.common.util.InviteLinkPayload
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Reactive coverage of the invitee-side redeem VM over the fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class RedeemInviteTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // A structurally valid v1 messaging link (16-byte id + 16-byte secret + 32-byte pub).
    private val validLink =
        InviteLinkPayload(ByteArray(16), ByteArray(16), ByteArray(32)).toMessagingLink()

    @Test
    fun a_valid_link_parses_to_confirm_name() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveShareSurface.redeemInvite(FakeContactsRepository(), null))
        advanceUntilIdle()

        vm.onPastedLinkChanged(value = validLink)
        vm.onLinkEntered(link = validLink)
        advanceUntilIdle()

        assertEquals(RedeemStage.ConfirmName, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.inviterFingerprint.isNotEmpty())
    }

    @Test
    fun a_scanned_qr_uri_also_parses_to_confirm_name() = vmTest(dispatcher) { vms ->
        // The scanner feeds the `tenshu-invite:` QR form (not the messaging link) straight to onLinkEntered.
        val qrUri = InviteLinkPayload(ByteArray(16), ByteArray(16), ByteArray(32)).toQrUri()
        val vm = vms.own(DriveShareSurface.redeemInvite(FakeContactsRepository(), null))
        advanceUntilIdle()

        vm.onLinkEntered(link = qrUri)
        advanceUntilIdle()

        assertEquals(RedeemStage.ConfirmName, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.inviterFingerprint.isNotEmpty())
    }

    @Test
    fun a_malformed_link_surfaces_a_paste_error() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveShareSurface.redeemInvite(FakeContactsRepository(), null))
        advanceUntilIdle()

        vm.onLinkEntered(link = "not-a-link")
        advanceUntilIdle()

        assertEquals(PasteError.MALFORMED, vm.uiState.value.pasteError)
        assertEquals(RedeemStage.EnterLink, vm.uiState.value.stage)
    }

    @Test
    fun a_newer_version_link_asks_to_update() = vmTest(dispatcher) { vms ->
        val vm = vms.own(DriveShareSurface.redeemInvite(FakeContactsRepository(), null))
        advanceUntilIdle()

        vm.onLinkEntered(link = "https://tenshu.app/i#v2.AAAA")
        advanceUntilIdle()

        assertEquals(PasteError.UNSUPPORTED_VERSION, vm.uiState.value.pasteError)
    }

    @Test
    fun confirming_redeems_and_waits_to_accepted() = vmTest(dispatcher) { vms ->
        val contacts = FakeContactsRepository()
        val vm = vms.own(DriveShareSurface.redeemInvite(contacts, validLink))
        advanceUntilIdle()

        vm.onSelfNameChanged(value = "Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(listOf(validLink to "Bob"), contacts.redeemedLinks)
        assertEquals(listOf("Bob"), contacts.ownNames)
        assertEquals("redeemed-invite-id" to false, contacts.ackWaits.first())
        val stage = vm.uiState.value.stage
        assertIs<RedeemStage.Accepted>(stage)
        assertEquals("Alice", stage.ack.peerName)
    }

    @Test
    fun an_already_used_invite_lands_on_gone() = vmTest(dispatcher) { vms ->
        val contacts = FakeContactsRepository(redeemOutcome = RedeemOutcome.AlreadyUsed)
        val vm = vms.own(DriveShareSurface.redeemInvite(contacts, validLink))
        advanceUntilIdle()

        vm.onSelfNameChanged(value = "Bob")
        vm.onConfirmClicked()
        advanceUntilIdle()

        assertEquals(RedeemStage.Gone, vm.uiState.value.stage)
    }
}
