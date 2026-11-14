package org.tenshu.sdk.ios

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.SharedFolderModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.ContactInvite
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reactive coverage of the inviter-side completion-prompt VM over the fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPromptTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun redeemedInvite(
        inviteId: String = "inv1",
        intentKind: String = "",
        intentLabel: String = "",
        fingerprint: String = "FP-RAW",
    ) = ContactInviteModel(
        inviteId = inviteId,
        status = ContactInvite.Status.REDEEMED,
        intentKind = intentKind,
        intentLabel = intentLabel,
        createdAtMillis = 0,
        expiresAtMillis = 0,
        claimedName = "Bob",
        claimedFingerprint = fingerprint,
        claimedSas = "12 34 56",
    )

    /** Takes the test's [OwnedViewModels] so no call site can build a prompt it does not own. */
    private fun prompt(vms: OwnedViewModels, contacts: FakeContactsRepository, inviteId: String = "inv1") =
        vms.own(DriveShareSurface.contactPrompt(contacts, inviteId))

    @Test
    fun the_redeemed_row_seeds_the_claim_and_sas() = vmTest(dispatcher) { vms ->
        val contacts = FakeContactsRepository(invites = listOf(redeemedInvite()))
        val vm = prompt(vms, contacts)
        advanceUntilIdle()

        assertEquals("Bob", vm.uiState.value.claimedName)
        assertEquals("Bob", vm.uiState.value.petname) // pre-filled once
        assertEquals("12 34 56", vm.uiState.value.claimedSas)
    }

    @Test
    fun accept_completes_the_handshake_with_the_confirmed_sas_and_names() = vmTest(dispatcher) { vms ->
        val contacts = FakeContactsRepository(invites = listOf(redeemedInvite()))
        val vm = prompt(vms, contacts)
        advanceUntilIdle()

        vm.onSasConfirmedChanged(confirmed = true)
        vm.onPetnameChanged(value = "Bobby")
        vm.onOwnNameChanged(value = "Alice")
        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals(listOf("Alice"), contacts.ownNames)
        val done = contacts.completedHandshakes.single()
        assertEquals(true, done.accept)
        assertEquals(true, done.sasConfirmed)
        assertEquals("Bobby", done.newPersonName)
        assertEquals("Alice", done.selfAssertedName)
        assertTrue(vm.uiState.value.done) // no grant offer for a plain invite
    }

    @Test
    fun decline_completes_the_handshake_rejected() = vmTest(dispatcher) { vms ->
        val contacts = FakeContactsRepository(invites = listOf(redeemedInvite()))
        val vm = prompt(vms, contacts)
        advanceUntilIdle()

        vm.onDeclineClicked()
        advanceUntilIdle()

        assertEquals(false, contacts.completedHandshakes.single().accept)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun accepting_a_share_folder_invite_just_completes() = vmTest(dispatcher) { vms ->
        // ⚠ The grant offer used to appear here; it is now a deferred card on the Shared
        // screen, derived from the COMPLETED row (`contacts-app.md` §9.1). That is what
        // makes this view model pure — and therefore movable to the Contacts app.
        val contacts = FakeContactsRepository(
            invites = listOf(redeemedInvite(intentKind = ContactInvite.IntentKind.SHARE_FOLDER, intentLabel = "Photos")),
            contacts = listOf(ContactModel(personId = "p1", displayName = "Bob", fingerprint = "FP-RAW", grantable = true)),
        )
        val vm = prompt(vms, contacts)
        advanceUntilIdle()

        vm.onSaveClicked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.done)
        assertEquals(1, contacts.completedHandshakes.size)
    }
}
