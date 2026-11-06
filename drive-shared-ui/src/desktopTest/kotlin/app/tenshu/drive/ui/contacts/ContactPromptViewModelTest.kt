package app.tenshu.drive.ui.contacts

import app.tenshu.drive.data.ContactInviteModel
import app.tenshu.drive.data.ContactModel
import app.tenshu.drive.data.PersonModel
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.SharedFolderModel
import app.tenshu.drive.ui.FakeContactsRepository
import app.tenshu.drive.ui.FakeFilesRepository
import app.tenshu.drive.ui.FakeMembershipRepository
import org.tenshu.viewmodel.test.OwnedViewModels
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §2.3 completion-prompt decisions: ACCEPT-as-new vs ACCEPT-link-to-existing
 * vs REJECT, the petname prefill from the (quarantined) claim, and the
 * own-display-name remember-on-accept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPromptViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun redeemedInvite(id: String = "invite-1") = ContactInviteModel(
        inviteId = id,
        status = ContactInvite.Status.REDEEMED,
        intentKind = ContactInvite.IntentKind.SHARE_FOLDER,
        intentLabel = "Holiday",
        createdAtMillis = 0L,
        expiresAtMillis = 0L,
        claimedName = "Bob",
        claimedFingerprint = "abcdef0123456789",
        claimedSas = "482913",
    )

    private fun OwnedViewModels.vm(contacts: FakeContactsRepository) =
        own(ContactPromptViewModel(contacts, "invite-1"))

    @Test
    fun petname_prefillsFromTheClaim_butStaysUserOwned() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        assertEquals("Bob", vm.uiState.value.petname)
        assertEquals("Bob", vm.uiState.value.claimedName)
        assertEquals(displayFingerprint("abcdef0123456789"), vm.uiState.value.claimedFingerprint)

        // A user edit survives later row re-emissions (claim never overwrites).
        vm.onPetnameChanged("Bobby")
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()
        assertEquals("Bobby", vm.uiState.value.petname)
    }

    @Test
    fun save_asNewContact_usesThePetname_andRemembersOwnName() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        vm.onPetnameChanged("Bobby")
        vm.onOwnNameChanged("Alice")
        vm.onSaveClicked()
        advanceUntilIdle()

        val call = contacts.completeCalls.single()
        assertTrue(call.accept)
        assertEquals("invite-1", call.inviteId)
        assertEquals("", call.personId)
        assertEquals("Bobby", call.newPersonName)
        assertEquals("Alice", call.selfAssertedName)
        assertEquals(listOf("Alice"), contacts.savedOwnNames)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun save_linkedToExistingPerson_repointsInsteadOfCreating() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        contacts.personsFlow.value = listOf(PersonModel("person-9", "Bob from work"))
        advanceUntilIdle()

        vm.onPersonSelected("person-9")
        vm.onSaveClicked()
        advanceUntilIdle()

        val call = contacts.completeCalls.single()
        assertTrue(call.accept)
        assertEquals("person-9", call.personId)
        assertEquals("", call.newPersonName)
    }

    @Test
    fun theCompletionSendsTheRawFingerprintThePromptShowed() = vmTest { vms ->
        // contact-service v2 (threat model T-CI-10): the proxy stores the response only if it is
        // the key the prompt displayed — so the RAW claim goes out, never its display form.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        vm.onSaveClicked()
        advanceUntilIdle()

        assertEquals("abcdef0123456789", contacts.completeCalls.single().shownFingerprint)
    }

    @Test
    fun aClaimThatMovesUnderTheOpenPrompt_clearsTheTick() = vmTest { vms ->
        // ⚠⚠ A second inviter device's write replacing the claim by merge must not leave a tick
        // made against the first code standing over the second.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()
        vm.onSasConfirmedChanged(true)

        contacts.invites.value = listOf(
            redeemedInvite().copy(claimedFingerprint = "ffff0000ffff0000", claimedSas = "111222"),
        )
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.sasConfirmed, "the tick must not carry over to a new code")

        vm.onSaveClicked()
        advanceUntilIdle()
        val call = contacts.completeCalls.single()
        assertEquals("ffff0000ffff0000", call.shownFingerprint)
        assertEquals(false, call.sasConfirmed)
    }

    @Test
    fun sasConfirmed_flowsOnlyOnExplicitConfirm() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        // Saving without touching the §5.2 checkbox stays UNVERIFIED.
        vm.onSaveClicked()
        advanceUntilIdle()
        assertEquals(false, contacts.completeCalls.single().sasConfirmed)

        // The explicit *Codes match* check is the only sasConfirmed source.
        contacts.completeCalls.clear()
        val vm2 = vms.vm(contacts)
        advanceUntilIdle()
        vm2.onSasConfirmedChanged(true)
        vm2.onSaveClicked()
        advanceUntilIdle()
        assertEquals(true, contacts.completeCalls.single().sasConfirmed)

        // A decline never carries the confirmation.
        contacts.completeCalls.clear()
        val vm3 = vms.vm(contacts)
        advanceUntilIdle()
        vm3.onSasConfirmedChanged(true)
        vm3.onDeclineClicked()
        advanceUntilIdle()
        assertEquals(false, contacts.completeCalls.single().sasConfirmed)
    }

    @Test
    fun decline_rejects() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        vm.onDeclineClicked()
        advanceUntilIdle()

        val call = contacts.completeCalls.single()
        assertEquals(false, call.accept)
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun beforeTheClaimLoads_saveCannotSubmit_butDeclineStillCan() = vmTest { vms ->
        // T-CI-10: the proxy refuses an ACCEPT with an empty shownFingerprint, and the blank
        // initial state — before the synced row lands — is exactly that.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.vm(contacts)
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.canSave, "no accept before a claim is on screen")
        vm.onSaveClicked()
        advanceUntilIdle()
        assertTrue(contacts.completeCalls.isEmpty(), "the blank state must not reach completion")

        vm.onDeclineClicked()
        advanceUntilIdle()
        assertEquals(false, contacts.completeCalls.single().accept)

        // And once the claim lands, save is offered.
        val loaded = vms.vm(FakeContactsRepository().also { it.invites.value = listOf(redeemedInvite()) })
        advanceUntilIdle()
        assertTrue(loaded.uiState.value.canSave)
    }

    @Test
    fun acceptOfSharedFolderInvite_justCompletes_theGrantOfferIsNotThisScreensJob() = vmTest { vms ->
        // ⚠ This is the behaviour change the decoupling exists for. Accepting used to
        // switch the dialog to "Add Bobby to 'Holiday'?" — which is why this view model
        // needed a FilesRepository and a MembershipRepository, and why the prompt could
        // not move to an app with no file domain (`contacts-app.md` §9.1).
        //
        // The offer now resurfaces on the Shared screen, derived from the COMPLETED row
        // this call writes — see SharedScreenGrantOfferTest.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            connectedContactsResult = RepositoryResult.Success(
                listOf(ContactModel("p1", "Bobby", fingerprint = "abcdef0123456789", grantable = true))
            )
        )
        val vm = vms.vm(contacts)
        contacts.invites.value = listOf(redeemedInvite())
        advanceUntilIdle()

        vm.onPetnameChanged("Bobby")
        vm.onSaveClicked()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.done, "the handshake is the whole job")
        assertEquals(1, contacts.completeCalls.size)
    }
}
