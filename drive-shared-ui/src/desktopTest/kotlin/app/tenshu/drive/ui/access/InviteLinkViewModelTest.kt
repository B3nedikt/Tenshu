package app.tenshu.drive.ui.access

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.FakeContactsRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class InviteLinkViewModelTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun generate_carriesTheShareFolderIntent_andHoldsTheLink() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            createInviteResult = RepositoryResult.Success("https://tenshu.app/i#v1.abc"),
        )
        val vm = vms.own(InviteLinkViewModel(contacts, folderName = "Holiday"))

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertEquals(
            listOf(ContactInvite.IntentKind.SHARE_FOLDER to "Holiday"),
            contacts.createInviteCalls,
        )
        assertEquals("https://tenshu.app/i#v1.abc", vm.uiState.value.link)
        assertNull(vm.uiState.value.errorCopyKey)

        // The raw link lives only in this VM's session state — nothing was
        // pushed into any persistence surface (handshake doc §3 / §8 check).
        assertTrue(contacts.savedOwnNames.isEmpty())
    }

    @Test
    fun generate_withoutFolderContext_sendsNoIntent() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository()
        val vm = vms.own(InviteLinkViewModel(contacts, folderName = ""))

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertEquals(listOf("" to ""), contacts.createInviteCalls)
    }

    @Test
    fun generate_failure_surfacesInlineError() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val contacts = FakeContactsRepository(
            createInviteResult = RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)),
        )
        val vm = vms.own(InviteLinkViewModel(contacts, folderName = "Holiday"))

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertNull(vm.uiState.value.link)
        assertNotNull(vm.uiState.value.errorCopyKey)
    }

    @Test
    fun truncateMiddle_keepsBothEnds_andFullLinkStaysIntact() {
        val link = "https://tenshu.app/i#v1." + "a".repeat(80)
        val shown = truncateMiddle(link)
        assertTrue(shown.length < link.length)
        assertTrue(shown.startsWith("https://tenshu.app/i#v1"))
        assertTrue(shown.endsWith("a"))
        // Short links pass through untruncated.
        assertEquals("short", truncateMiddle("short"))
    }
}
