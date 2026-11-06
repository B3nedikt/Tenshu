package org.tenshu.sdk.ios

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.contact.ContactInvite
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reactive coverage of the *invite someone* VM over the fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteLinkTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun generate_creates_a_share_folder_invite_and_surfaces_the_link() = runTest(dispatcher) {
        val fake = FakeContactsRepository()
        val vm = DriveShareSurface.inviteLink(fake, "Photos")

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertEquals(listOf(ContactInvite.IntentKind.SHARE_FOLDER to "Photos"), fake.createdInvites)
        assertEquals("https://tenshu.app/i/demo-invite#k=demo-key", vm.uiState.value.link)
        assertNull(vm.uiState.value.errorCopyKey)
    }

    @Test
    fun an_empty_folder_name_creates_a_plain_invite() = runTest(dispatcher) {
        val fake = FakeContactsRepository()
        val vm = DriveShareSurface.inviteLink(fake, "")

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertEquals(listOf("" to ""), fake.createdInvites)
    }

    @Test
    fun generate_is_idempotent_once_a_link_exists() = runTest(dispatcher) {
        val fake = FakeContactsRepository()
        val vm = DriveShareSurface.inviteLink(fake, "Photos")

        vm.onGenerateClicked()
        advanceUntilIdle()
        vm.onGenerateClicked() // no-op: a link already exists
        advanceUntilIdle()

        assertEquals(1, fake.createdInvites.size)
    }

    @Test
    fun a_failed_generate_surfaces_an_error_and_no_link() = runTest(dispatcher) {
        val fake = FakeContactsRepository(inviteLinkResult = RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)))
        val vm = DriveShareSurface.inviteLink(fake, "Photos")

        vm.onGenerateClicked()
        advanceUntilIdle()

        assertNull(vm.uiState.value.link)
        assertTrue(vm.uiState.value.errorCopyKey != null)
    }
}
