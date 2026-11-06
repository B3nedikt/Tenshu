package app.tenshu.contacts.model

import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.tenshu.viewmodel.test.vmTest

/** The contact photo, on the app side of the seam. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ContactAvatarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun withPhoto(bytes: ByteArray?) = Contact(
        personId = "p1",
        displayName = "Mira",
        avatar = bytes,
    )

    @Test
    fun `two contacts with the same photo bytes are equal`() {
        // ⚠ ByteArray equality is reference identity, so the generated data-class equals
        // would call these different and every re-read of the store would look like a
        // change. Compose would recompose the whole list on each emission.
        assertEquals(withPhoto(byteArrayOf(1, 2, 3)), withPhoto(byteArrayOf(1, 2, 3)))
        assertEquals(
            withPhoto(byteArrayOf(1, 2, 3)).hashCode(),
            withPhoto(byteArrayOf(1, 2, 3)).hashCode(),
        )
    }

    @Test
    fun `a changed photo is a changed contact`() {
        assertNotEquals(withPhoto(byteArrayOf(1)), withPhoto(byteArrayOf(2)))
        assertNotEquals(withPhoto(byteArrayOf(1)), withPhoto(null))
    }

    @Test
    fun `most contacts have none`() {
        assertNull(Contact(personId = "p1", displayName = "Dad").avatar)
    }

    @Test
    fun `removing a photo leaves the contact`() = vmTest { vms ->
        val repository = MockContactsRepository(
            initial = listOf(withPhoto(byteArrayOf(1, 2, 3))),
        )
        val vm = vms.own(ContactDetailViewModel(repository, personId = "p1"))

        vm.onRemovePhotoClicked()

        val contact = repository.contacts().first().single()
        assertNull(contact.avatar)
        assertEquals("Mira", contact.displayName, "the contact itself is untouched")
    }
}
