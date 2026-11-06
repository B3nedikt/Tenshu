package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
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

/** The contact edit surface: manual add, rename, delete, and dropping a key. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactCrudViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // ── manual add ───────────────────────────────────────────────────────────

    @Test
    fun `adding a contact stores a keyless person`() = vmTest { vms ->
        // D5: manual entry is the ordinary path minus a handshake.
        val repository = MockContactsRepository(initial = emptyList())
        val vm = vms.own(ContactListViewModel(repository))

        vm.onAddClicked()
        vm.onDraftNameChanged("  Dad  ")
        assertTrue(vm.uiState.value.canSaveNew)
        vm.onAddConfirmed()

        val added = repository.contacts().first().single()
        assertEquals("Dad", added.displayName, "trimmed on save")
        assertEquals(TrustState.NotConnected, added.trust)
        assertFalse(vm.uiState.value.addingContact, "the dialog closes on success")
    }

    @Test
    fun `a blank name cannot be saved`() = vmTest { vms ->
        val repository = MockContactsRepository(initial = emptyList())
        val vm = vms.own(ContactListViewModel(repository))

        vm.onAddClicked()
        vm.onDraftNameChanged("   ")
        assertFalse(vm.uiState.value.canSaveNew)

        vm.onAddConfirmed()
        assertTrue(repository.contacts().first().isEmpty())
        assertTrue(vm.uiState.value.addingContact, "and the dialog stays open")
    }

    @Test
    fun `dismissing the add dialog stores nothing`() = vmTest { vms ->
        val repository = MockContactsRepository(initial = emptyList())
        val vm = vms.own(ContactListViewModel(repository))

        vm.onAddClicked()
        vm.onDraftNameChanged("Dad")
        vm.onAddDismissed()

        assertTrue(repository.contacts().first().isEmpty())
        assertEquals("", vm.uiState.value.draftName)
    }

    // ── rename ───────────────────────────────────────────────────────────────

    @Test
    fun `renaming changes the petname and leaves the keys claiming what they claimed`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onRenameClicked()
        assertEquals("Mira Okonkwo", vm.uiState.value.draftName, "pre-filled from the contact")
        vm.onDraftNameChanged("Mira O")
        vm.onRenameConfirmed()

        val renamed = repository.contacts().first().single { it.personId == "person-mira" }
        assertEquals("Mira O", renamed.displayName)
        // The name the key asserted is evidence, not identity — a rename must not touch it.
        assertEquals("Mira", renamed.identities.single().selfAssertedName)
        assertFalse(vm.uiState.value.renaming)
    }

    @Test
    fun `renaming to blank is refused`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onRenameClicked()
        vm.onDraftNameChanged("  ")
        assertFalse(vm.uiState.value.canSaveName)

        vm.onRenameConfirmed()
        assertEquals(
            "Mira Okonkwo",
            repository.contacts().first().single { it.personId == "person-mira" }.displayName,
        )
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `deleting takes the contact and its keys`() = vmTest { vms ->
        // Q7a: delete means delete — leaving the keyring would strand a peer's public key
        // on every device in rows no surface lists.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))

        vm.onDeleteClicked()
        assertTrue(vm.uiState.value.confirmingDelete, "delete is always confirmed, never immediate")
        vm.onDeleteConfirmed()

        assertNull(repository.contacts().first().firstOrNull { it.personId == "person-sara" })
        assertTrue(vm.uiState.value.deleted, "the screen signals the host to pop")
    }

    @Test
    fun `dismissing the delete confirmation keeps the contact`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))

        vm.onDeleteClicked()
        vm.onDeleteDismissed()

        assertNotNull(repository.contacts().first().firstOrNull { it.personId == "person-sara" })
        assertFalse(vm.uiState.value.deleted)
    }

    @Test
    fun `a deleted contact does not render as missing`() = vmTest { vms ->
        // `isMissing` is for a row that vanished under the user (another device, a stale
        // back stack). Their own delete is not a "contact not found" situation.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))

        vm.onDeleteClicked()
        vm.onDeleteConfirmed()

        assertTrue(vm.uiState.value.deleted)
        assertFalse(vm.uiState.value.isMissing)
    }

    // ── unlink a key ─────────────────────────────────────────────────────────

    @Test
    fun `dropping a stale key resolves the key-changed demotion`() = vmTest { vms ->
        // Sara has an old verified key beside a new unverified one, so she reads as
        // "key changed" (Q2b). Removing the stale one is one of the two remedies; the
        // other is verifying the new key.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-sara"))
        assertEquals(TrustState.NeedsReverification, vm.uiState.value.contact?.trust)

        vm.onUnlinkClicked("7e5f0c39a1bb24d806fe93cc5a17d240b8e6f091")
        assertNotNull(vm.uiState.value.confirmingUnlink, "dropping a key is confirmed too")
        vm.onUnlinkConfirmed()

        val sara = repository.contacts().first().single { it.personId == "person-sara" }
        assertEquals(1, sara.identities.size)
        assertEquals(TrustState.Unverified, sara.trust)
    }

    @Test
    fun `dropping the last key leaves an ordinary contact`() = vmTest { vms ->
        // D5 again: a contact is not defined by having a key.
        val repository = MockContactsRepository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onUnlinkClicked("3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1")
        vm.onUnlinkConfirmed()

        val mira = repository.contacts().first().single { it.personId == "person-mira" }
        assertTrue(mira.identities.isEmpty())
        assertEquals(TrustState.NotConnected, mira.trust)
    }
}
