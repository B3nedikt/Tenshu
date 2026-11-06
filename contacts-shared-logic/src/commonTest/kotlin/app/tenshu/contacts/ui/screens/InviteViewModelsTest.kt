package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.RepositoryResult

import app.tenshu.contacts.data.ContactsRepository

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.InviteConfirmation
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.model.Verification
import app.tenshu.contacts.ui.screens.invite.ConfirmContactViewModel
import app.tenshu.contacts.ui.screens.invite.InviteViewModel
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InviteViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun confirmation(inviteId: String = "invite-0", sas: String = "418 902") =
        InviteConfirmation(
            inviteId = inviteId,
            claimedName = "Mira",
            claimedFingerprint = "3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1",
            claimedSas = sas,
        )

    // ── minting ──────────────────────────────────────────────────────────────

    @Test
    fun `creating an invite yields a link and an outstanding row`() = vmTest { vms ->
        val vm = vms.own(InviteViewModel(MockContactsRepository()))

        vm.onCreateClicked()

        assertNotNull(vm.uiState.value.link)
        assertTrue(vm.uiState.value.hasLink)
        assertEquals(1, vm.uiState.value.outstanding.size)
    }

    @Test
    fun `a second tap cannot strand the first link`() = vmTest { vms ->
        // The link is one-session and unrecoverable, so minting a second one over the top
        // would silently orphan an invitation the user may already have sent.
        val vm = vms.own(InviteViewModel(MockContactsRepository()))

        vm.onCreateClicked()
        val first = vm.uiState.value.link
        vm.onCreateClicked()

        assertEquals(first, vm.uiState.value.link)
        assertEquals(1, vm.uiState.value.outstanding.size)
    }

    @Test
    fun `dismissing drops the link but keeps the invitation`() = vmTest { vms ->
        val vm = vms.own(InviteViewModel(MockContactsRepository()))

        vm.onCreateClicked()
        vm.onDismissed()

        assertNull(vm.uiState.value.link, "the one-session link must not survive the sheet")
        assertEquals(1, vm.uiState.value.outstanding.size, "but the invite is still live")
    }

    @Test
    fun `revoking removes the outstanding invitation`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(InviteViewModel(repository))
        vm.onCreateClicked()
        val inviteId = vm.uiState.value.outstanding.single().inviteId

        vm.onRevokeClicked(inviteId)

        assertTrue(vm.uiState.value.outstanding.isEmpty())
    }

    // ── completion ───────────────────────────────────────────────────────────

    @Test
    fun `the prompt pre-fills the petname from the claim and keeps edits`() = vmTest { vms ->
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        assertEquals("Mira", vm.uiState.value.petname)
        assertEquals("3AB19-C7F2E-08D64", vm.uiState.value.claimedFingerprint)
        assertTrue(vm.uiState.value.hasSas)

        vm.onPetnameChanged("Mira O")
        assertEquals("Mira O", vm.uiState.value.petname)
    }

    @Test
    fun `accepting without confirming the code leaves the key unverified`() = vmTest { vms ->
        // ⚠ threat-model A24: verification is never auto-elevated. Saving a contact is not
        // the same act as vouching for their key.
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        vm.onSaveClicked()

        val saved = repository.contacts().first().single { it.displayName == "Mira" }
        assertEquals(Verification.UNVERIFIED, saved.identities.single().verification)
        assertEquals(TrustState.Unverified, saved.trust)
    }

    @Test
    fun `ticking the code check elevates the key`() = vmTest { vms ->
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        vm.onSasConfirmedChanged(true)
        vm.onSaveClicked()

        val saved = repository.contacts().first().single { it.displayName == "Mira" }
        assertEquals(Verification.VERIFIED, saved.identities.single().verification)
        assertEquals(TrustState.Verified, saved.trust)
    }

    @Test
    fun `declining never elevates anything even with the box ticked`() = vmTest { vms ->
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        vm.onSasConfirmedChanged(true)
        vm.onDeclineClicked()

        assertTrue(vm.uiState.value.done)
        assertTrue(repository.pendingConfirmations().first().isEmpty())
        assertFalse(repository.contacts().first().any { it.displayName == "Mira" })
    }

    @Test
    fun `linking to an existing contact adds the key without transferring trust`() = vmTest { vms ->
        // The linked contact is already VERIFIED on another key. The new key must land
        // UNVERIFIED — re-pointing is a cosmetic act, trust lives on the key — which then
        // shows up as the Q2b mixed-keyring demotion.
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        vm.onContactSelected("person-mira")
        vm.onSaveClicked()

        val mira = repository.contacts().first().single { it.personId == "person-mira" }
        assertEquals(2, mira.identities.size)
        assertEquals(TrustState.NeedsReverification, mira.trust)
    }

    @Test
    fun `the save is gated on a name to send`() = vmTest { vms ->
        val repository = MockContactsRepository(
            initialProfile = app.tenshu.contacts.model.OwnProfile(displayName = ""),
            initialConfirmations = listOf(confirmation()),
        )
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        assertFalse(vm.uiState.value.canSave, "a name rides inside the ack")

        vm.onOwnNameChanged("Kae")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `a confirmation with no code yet hides the check`() = vmTest { vms ->
        // The SAS is proxy-derived and empty while the realm is locked; offering an
        // unticked box with no digits beside it would be asking the user to confirm nothing.
        val repository = MockContactsRepository(
            initialConfirmations = listOf(confirmation(sas = "")),
        )
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))

        assertFalse(vm.uiState.value.hasSas)
    }

    // ── the badge source ─────────────────────────────────────────────────────

    @Test
    fun `pending confirmations surface on the list`() = vmTest { vms ->
        val repository = MockContactsRepository(initialConfirmations = listOf(confirmation()))
        val vm = vms.own(ContactListViewModel(repository))

        assertEquals(1, vm.uiState.value.pendingConfirmations.size)
    }

    @Test
    fun `an empty address book with a waiting confirmation is still empty`() = vmTest { vms ->
        // The banner renders above the empty state rather than replacing it — the very
        // first contact often arrives exactly this way.
        val repository = MockContactsRepository(
            initial = emptyList(),
            initialConfirmations = listOf(confirmation()),
        )
        val vm = vms.own(ContactListViewModel(repository))

        assertTrue(vm.uiState.value.isEmpty)
        assertEquals(1, vm.uiState.value.pendingConfirmations.size)
    }

    // ── what was shown travels with the completion (threat model T-CI-10) ──────

    /** The mock, with the confirmation stream and the completion call under the test's control. */
    private class ScriptedConfirmations(
        initial: InviteConfirmation,
        private val delegate: MockContactsRepository = MockContactsRepository(),
    ) : ContactsRepository by delegate {
        val rows = kotlinx.coroutines.flow.MutableStateFlow(listOf(initial))
        val calls = mutableListOf<Pair<String, Boolean>>() // (shownFingerprint, sasConfirmed)
        override fun pendingConfirmations() = rows
        override suspend fun completeHandshake(
            inviteId: String,
            accept: Boolean,
            shownFingerprint: String,
            personId: String,
            newPersonName: String,
            selfAssertedName: String,
            sasConfirmed: Boolean,
        ): RepositoryResult<Unit> {
            calls += shownFingerprint to sasConfirmed
            return RepositoryResult.Success(Unit)
        }
    }

    @Test
    fun `the completion sends the RAW fingerprint the prompt showed and not its display form`() = vmTest { vms ->
        val repository = ScriptedConfirmations(confirmation())
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))
        vm.onOwnNameChanged("Alice")

        vm.onSaveClicked()

        assertEquals("3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1" to false, repository.calls.single())
    }

    @Test
    fun `a claim that moves under the open prompt clears the tick`() = vmTest { vms ->
        // ⚠⚠ The tick was a statement about the code on screen when it was made. A second inviter
        // device's write replacing the claim by merge must not let it stand over the new code.
        val repository = ScriptedConfirmations(confirmation())
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "invite-0"))
        vm.onOwnNameChanged("Alice")
        vm.onSasConfirmedChanged(true)

        repository.rows.value = listOf(
            confirmation(sas = "111 222").copy(claimedFingerprint = "ffff0000ffff0000ffff0000ffff0000ffff0000"),
        )
        assertEquals(false, vm.uiState.value.sasConfirmed, "the tick must not carry over to a new code")

        vm.onSaveClicked()
        assertEquals("ffff0000ffff0000ffff0000ffff0000ffff0000" to false, repository.calls.single())
    }

    @Test
    fun `before the claim loads the save is not offered and cannot submit`() = vmTest { vms ->
        // The proxy refuses an ACCEPT with an empty shownFingerprint (T-CI-10); the blank state
        // before the row lands is exactly that, so the prompt must not submit it.
        val repository = ScriptedConfirmations(confirmation())
        val vm = vms.own(ConfirmContactViewModel(repository, inviteId = "not-loaded-yet"))
        vm.onOwnNameChanged("Alice")
        vm.onPetnameChanged("Bob")

        assertEquals(false, vm.uiState.value.canSave)
        vm.onSaveClicked()
        assertTrue(repository.calls.isEmpty())
    }
}
