package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.detail.DetailFieldKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/** The Q6 field editor's behaviour. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetailEditorViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun repository() = MockContactsRepository(
        initial = listOf(
            Contact(
                personId = "person-mira",
                displayName = "Mira Okonkwo",
                details = ContactDetails(
                    phones = listOf(ContactField("mobile", "+44 7700 900123")),
                    organisation = "Tenshu",
                ),
            ),
        ),
    )

    @Test
    fun `the editor opens on the current values`() = vmTest { vms ->
        val vm = vms.own(ContactDetailViewModel(repository(), personId = "person-mira"))

        vm.onEditDetailsClicked()

        val editing = assertNotNull(vm.uiState.value.editingDetails)
        assertEquals(listOf("+44 7700 900123"), editing.phones.map { it.value })
        assertEquals("Tenshu", editing.organisation)
    }

    @Test
    fun `editing a value and saving stores it`() = vmTest { vms ->
        val repository = repository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onEditDetailsClicked()
        vm.onDetailFieldChanged(DetailFieldKind.PHONE, 0, "+44 7700 900999")
        vm.onEditDetailsSaved()

        val stored = repository.contacts().first().single().details
        assertEquals(listOf("+44 7700 900999"), stored.phones.map { it.value })
        assertNull(vm.uiState.value.editingDetails, "the editor closes on success")
    }

    @Test
    fun `adding a row then leaving it blank stores nothing`() = vmTest { vms ->
        // A blank row is an abandoned intention, not an empty phone number.
        val repository = repository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onEditDetailsClicked()
        vm.onDetailFieldAdded(DetailFieldKind.EMAIL)
        assertEquals(1, vm.uiState.value.editingDetails?.emails?.size)
        vm.onEditDetailsSaved()

        assertTrue(repository.contacts().first().single().details.emails.isEmpty())
    }

    @Test
    fun `clearing a value removes it`() = vmTest { vms ->
        val repository = repository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onEditDetailsClicked()
        vm.onDetailFieldChanged(DetailFieldKind.PHONE, 0, "")
        vm.onEditDetailsSaved()

        assertTrue(repository.contacts().first().single().details.phones.isEmpty())
    }

    @Test
    fun `dismissing discards the working copy`() = vmTest { vms ->
        val repository = repository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onEditDetailsClicked()
        vm.onDetailFieldChanged(DetailFieldKind.PHONE, 0, "scratch")
        vm.onEditDetailsDismissed()

        assertEquals(
            listOf("+44 7700 900123"),
            repository.contacts().first().single().details.phones.map { it.value },
        )
        assertNull(vm.uiState.value.editingDetails)
    }

    @Test
    fun `organisation and note are trimmed`() = vmTest { vms ->
        val repository = repository()
        val vm = vms.own(ContactDetailViewModel(repository, personId = "person-mira"))

        vm.onEditDetailsClicked()
        vm.onOrganisationChanged("  Tenshu Ltd  ")
        vm.onNoteChanged("  met at the conference  ")
        vm.onEditDetailsSaved()

        val stored = repository.contacts().first().single().details
        assertEquals("Tenshu Ltd", stored.organisation)
        assertEquals("met at the conference", stored.note)
    }
}
