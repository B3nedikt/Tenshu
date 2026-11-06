package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.ContactsMockData
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.TrustState
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.contacts.ui.screens.profile.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactsViewModelsTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the list loads every contact whether connected or not`() = vmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))
        val state = vm.uiState.value

        assertTrue(state.loaded)
        assertEquals(ContactsMockData.contacts.size, state.contacts.size)
        assertTrue(state.contacts.any { it.isConnected }, "D3: one list holds both kinds")
        assertTrue(state.contacts.any { !it.isConnected })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `the fixture covers every badge state`() {
        // A screen that has never rendered NeedsReverification has not really been built.
        val states = ContactsMockData.contacts.map { it.trust }.toSet()
        assertTrue(TrustState.Verified in states)
        assertTrue(TrustState.Unverified in states)
        assertTrue(TrustState.NeedsReverification in states)
        assertTrue(TrustState.NotConnected in states)
    }

    @Test
    fun `search answers in its own surface and leaves the list whole`() = vmTest { vms ->
        // §9.6: results are `searchResults`, the list stays `contacts`. Two separate things,
        // so leaving search has a whole address book to go back to.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))

        vm.onQueryChange("mira")
        assertEquals(listOf("Mira Okonkwo"), vm.uiState.value.searchResults.map { it.displayName })
        assertEquals(ContactsMockData.contacts.size, vm.uiState.value.contacts.size)

        vm.onQueryChange("nobody here")
        assertTrue(vm.uiState.value.isEmptySearch)
        assertFalse(vm.uiState.value.isEmpty, "an unmatched query is not an empty address book")

        vm.clearQuery()
        // A blank query answers with nothing rather than everything: an expanded search bar
        // listing the same rows as the screen behind it is noise.
        assertTrue(vm.uiState.value.searchResults.isEmpty())
        assertFalse(vm.uiState.value.isEmptySearch, "and a blank query is not a failed search")
    }

    @Test
    fun `collapsing search drops the query`() = vmTest { vms ->
        // Otherwise a stale query sits invisible behind a collapsed bar, and the next time
        // search is opened it answers a question the user has forgotten asking.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))

        vm.onSearchActiveChange(true)
        vm.onQueryChange("mira")
        vm.onSearchActiveChange(false)

        assertEquals("", vm.uiState.value.query)
        assertFalse(vm.uiState.value.searchActive)
    }

    @Test
    fun `the add sheet closes on the way to the dialog`() = vmTest { vms ->
        // The sheet row is a route to the dialog, not a place to leave open behind it.
        val vm = vms.own(ContactListViewModel(MockContactsRepository()))

        vm.onAddActionsClicked()
        assertTrue(vm.uiState.value.actionsSheetVisible)

        vm.onAddClicked()
        assertFalse(vm.uiState.value.actionsSheetVisible)
        assertTrue(vm.uiState.value.addingContact)
    }

    @Test
    fun `the empty list is distinguishable from a loading one`() = vmTest { vms ->
        val vm = vms.own(ContactListViewModel(MockContactsRepository(initial = emptyList())))
        assertTrue(vm.uiState.value.loaded)
        assertTrue(vm.uiState.value.isEmpty)
    }

    @Test
    fun `detail resolves a contact and its keyring`() = vmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-sara"))
        val contact = assertNotNull(vm.uiState.value.contact)

        assertEquals("Sara Lindqvist", contact.displayName)
        assertEquals(2, contact.identities.size)
        assertEquals(TrustState.NeedsReverification, contact.trust)
    }

    @Test
    fun `detail reports a missing person rather than spinning`() = vmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(MockContactsRepository(), personId = "person-gone"))
        assertTrue(vm.uiState.value.isMissing)
    }

    @Test
    fun `editing the own name saves through the repository`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ProfileViewModel(repository))

        assertEquals("Kae", vm.uiState.value.profile.displayName)
        assertEquals("Kae", vm.uiState.value.draftName)

        vm.startEditing()
        vm.onDraftChange("  Kae Sato  ")
        assertTrue(vm.uiState.value.canSave)
        vm.save()

        assertEquals("Kae Sato", vm.uiState.value.profile.displayName, "trimmed on save")
        assertFalse(vm.uiState.value.editing)
    }

    @Test
    fun `a blank name is not saveable`() = vmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockContactsRepository()))

        vm.startEditing()
        vm.onDraftChange("   ")
        assertFalse(vm.uiState.value.canSave)

        vm.save()
        assertEquals("Kae", vm.uiState.value.profile.displayName, "the name is unchanged")
        assertTrue(vm.uiState.value.editing, "and the editor stays open")
    }

    @Test
    fun `a re-emission does not clobber what the user is typing`() = vmTest { vms ->
        val repository = MockContactsRepository()
        val vm = vms.own(ProfileViewModel(repository))

        vm.startEditing()
        vm.onDraftChange("half-typed")
        // Another device (or another surface) writes the name while the editor is open.
        repository.setOwnDisplayName("Remote")

        assertEquals("half-typed", vm.uiState.value.draftName)
        assertEquals("Remote", vm.uiState.value.profile.displayName)
    }

    @Test
    fun `cancelling restores the stored name`() = vmTest { vms ->
        val vm = vms.own(ProfileViewModel(MockContactsRepository()))

        vm.startEditing()
        vm.onDraftChange("scratch")
        vm.cancelEditing()

        assertFalse(vm.uiState.value.editing)
        assertEquals("Kae", vm.uiState.value.draftName)
    }
}
