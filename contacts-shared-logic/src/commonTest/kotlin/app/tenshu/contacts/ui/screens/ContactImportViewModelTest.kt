package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.ui.screens.importing.ContactImportProblem
import app.tenshu.contacts.ui.screens.importing.ContactImportViewModel
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/**
 * The import flow's logic half. Everything a platform picker does is producing `.vcf` text; what happens
 * to that text is here, and is the part that can be tested without a device.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactImportViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun card(name: String) =
        "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$name\r\nEND:VCARD\r\n"

    @Test
    fun `importing cards creates a contact each and reports the count`() = vmTest { vms ->
        val repository = MockContactsRepository(initial = emptyList())
        val vm = vms.own(ContactImportViewModel(repository))

        vm.onVcardText(card("Mira") + card("Dad"))

        assertEquals(2, vm.uiState.value.importedCount)
        assertEquals(2, repository.contacts().first().size)
        assertNull(vm.uiState.value.problem)
        assertFalse(vm.uiState.value.isImporting)
    }

    @Test
    fun `the same file twice gives you everyone twice`() = vmTest { vms ->
        // D7: import never merges. Pinned as behaviour rather than left to be discovered,
        // because the alternative — folding two cards together because they share a name —
        // is the thing the proposal forbids doing without the user.
        val repository = MockContactsRepository(initial = emptyList())
        val vm = vms.own(ContactImportViewModel(repository))

        vm.onVcardText(card("Mira"))
        vm.onVcardText(card("Mira"))

        assertEquals(2, repository.contacts().first().size)
    }

    @Test
    fun `text holding no cards is reported as such`() = vmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onVcardText("this is not an address book")

        assertEquals(ContactImportProblem.NoCards, vm.uiState.value.problem)
        assertNull(vm.uiState.value.importedCount)
    }

    @Test
    fun `empty text never reaches the repository`() = vmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onVcardText("   ")

        assertEquals(ContactImportProblem.NoCards, vm.uiState.value.problem)
    }

    @Test
    fun `cancelling a picker is not a failure`() = vmTest { vms ->
        // Backing out of the OS picker is an ordinary thing to do; showing an error for it
        // would train people to ignore the error line that matters.
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onImportClicked()
        vm.onPickStarted()
        vm.onPickCancelled()

        assertNull(vm.uiState.value.problem)
        assertFalse(vm.uiState.value.isImporting)
        assertTrue(vm.uiState.value.showingSources, "the source list comes back")
    }

    @Test
    fun `a denied permission is surfaced and leaves the sheet usable`() = vmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onImportClicked()
        vm.onPickStarted()
        vm.onPickFailed(ContactImportProblem.PermissionDenied)

        assertEquals(ContactImportProblem.PermissionDenied, vm.uiState.value.problem)
        assertTrue(vm.uiState.value.visible, "the sheet stays open so another source can be tried")
    }

    @Test
    fun `acknowledging a result returns to the source list`() = vmTest { vms ->
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onImportClicked()
        vm.onVcardText(card("Mira"))
        vm.onResultAcknowledged()

        assertTrue(vm.uiState.value.showingSources)
        assertTrue(vm.uiState.value.visible)
    }

    @Test
    fun `dismissing clears the last outcome`() = vmTest { vms ->
        // Reopening the sheet must not show the previous run's "Imported 2 contacts."
        val vm = vms.own(ContactImportViewModel(MockContactsRepository(initial = emptyList())))

        vm.onImportClicked()
        vm.onVcardText(card("Mira"))
        vm.onDismissed()
        vm.onImportClicked()

        assertNull(vm.uiState.value.importedCount)
        assertNull(vm.uiState.value.problem)
    }
}
