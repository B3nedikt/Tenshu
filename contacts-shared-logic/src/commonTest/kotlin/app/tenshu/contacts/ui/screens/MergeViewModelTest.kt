package app.tenshu.contacts.ui.screens

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.model.Contact
import app.tenshu.contacts.model.ContactDetails
import app.tenshu.contacts.model.ContactField
import app.tenshu.contacts.model.ContactIdentity
import app.tenshu.contacts.model.Verification
import app.tenshu.contacts.ui.screens.merge.MergeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.vmTest

/** The duplicate-review flow (D7). Suggests, asks, and only then writes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MergeViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun duplicate(id: String, name: String, connected: Boolean = false) = Contact(
        personId = id,
        displayName = name,
        identities = if (connected) {
            listOf(ContactIdentity("fp-$id", name, Verification.UNVERIFIED))
        } else {
            emptyList()
        },
        details = ContactDetails(phones = listOf(ContactField("mobile", "+44 7700 900123"))),
    )

    private fun repository(vararg contacts: Contact) =
        MockContactsRepository(initial = contacts.toList())

    @Test
    fun `duplicates are suggested but nothing is written`() = vmTest { vms ->
        // ⚠⚠ D7's rule: a resemblance is a question. Opening the screen must not change the
        // address book — the whole reason merge is user-assisted is that a handshake's
        // contents are self-asserted.
        val repository = repository(duplicate("a", "Mira"), duplicate("b", "Mira O"))
        val vm = vms.own(MergeViewModel(repository))

        assertEquals(1, vm.uiState.value.candidates.size)
        assertEquals(2, repository.contacts().first().size, "nothing merged on its own")
    }

    @Test
    fun `confirming a pair merges it`() = vmTest { vms ->
        val repository = repository(duplicate("a", "Mira"), duplicate("b", "Mira O"))
        val vm = vms.own(MergeViewModel(repository))

        vm.onCandidateClicked(vm.uiState.value.candidates.single())
        vm.onMergeConfirmed()

        assertEquals(1, repository.contacts().first().size)
        assertNull(vm.uiState.value.reviewing, "the dialog closes on success")
        assertTrue(vm.uiState.value.candidates.isEmpty(), "and the pair stops being suggested")
    }

    @Test
    fun `the connected contact is kept by default`() = vmTest { vms ->
        // Losing a keyring's contact would be the more surprising outcome, so that side is
        // the default target. It is only a default — both names are put to the user.
        val repository = repository(
            duplicate("a", "Mira", connected = false),
            duplicate("b", "Mira O", connected = true),
        )
        val vm = vms.own(MergeViewModel(repository))

        vm.onCandidateClicked(vm.uiState.value.candidates.single())

        assertEquals("b", vm.uiState.value.reviewing?.targetPersonId)
        assertEquals("Mira O", vm.uiState.value.reviewing?.target?.displayName)
        assertEquals("Mira", vm.uiState.value.reviewing?.source?.displayName)
    }

    @Test
    fun `the user can swap which side survives`() = vmTest { vms ->
        val repository = repository(
            duplicate("a", "Mira", connected = false),
            duplicate("b", "Mira O", connected = true),
        )
        val vm = vms.own(MergeViewModel(repository))
        vm.onCandidateClicked(vm.uiState.value.candidates.single())

        vm.onTargetChanged("a")
        vm.onMergeConfirmed()

        // The survivor's petname is what remains — which is exactly why the choice is asked.
        assertEquals("Mira", repository.contacts().first().single().displayName)
    }

    @Test
    fun `the merged contact holds both keyrings`() = vmTest { vms ->
        // Merge needs no trust logic of its own: the Q2b rule then reads the combined
        // keyring and answers, which is why a mis-merge is cosmetic rather than a security
        // error.
        val repository = repository(
            duplicate("a", "Mira", connected = true),
            duplicate("b", "Mira O", connected = true),
        )
        val vm = vms.own(MergeViewModel(repository))
        vm.onCandidateClicked(vm.uiState.value.candidates.single())

        vm.onMergeConfirmed()

        assertEquals(2, repository.contacts().first().single().identities.size)
    }

    @Test
    fun `dismissing the review writes nothing`() = vmTest { vms ->
        val repository = repository(duplicate("a", "Mira"), duplicate("b", "Mira O"))
        val vm = vms.own(MergeViewModel(repository))

        vm.onCandidateClicked(vm.uiState.value.candidates.single())
        vm.onReviewDismissed()

        assertNull(vm.uiState.value.reviewing)
        assertEquals(2, repository.contacts().first().size)
        // Not dismissed *away*: the pair is still a pair, so it is still suggested. There is
        // no per-pair dismissal state, which is also why nothing has to sync one.
        assertEquals(1, vm.uiState.value.candidates.size)
    }

    @Test
    fun `a pair that stops existing stops being reviewable`() = vmTest { vms ->
        // Merged on another device, or one of them deleted. Holding the dialog open over a
        // stale pair is how a merge ends up hitting a contact that no longer exists.
        val repository = repository(duplicate("a", "Mira"), duplicate("b", "Mira O"))
        val vm = vms.own(MergeViewModel(repository))
        vm.onCandidateClicked(vm.uiState.value.candidates.single())

        repository.deleteContact("b")

        assertNull(vm.uiState.value.reviewing)
    }

    @Test
    fun `no duplicates is a state the screen can render`() = vmTest { vms ->
        val vm = vms.own(MergeViewModel(repository(duplicate("a", "Mira"))))

        assertTrue(vm.uiState.value.loaded)
        assertTrue(!vm.uiState.value.hasCandidates)
    }
}
