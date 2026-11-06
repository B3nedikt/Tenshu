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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reactive coverage of the create-share-link VM over the fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateShareLinkTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun generate_link_calls_the_repo_and_surfaces_the_url() = runTest(dispatcher) {
        val fake = FakeFilesRepository()
        val vm = DriveShareSurface.createShareLink(fake, "/budget.pdf")

        vm.onGenerateLinkClicked()
        advanceUntilIdle()

        assertEquals(listOf("/budget.pdf"), fake.sharedLinks)
        assertEquals("demo-share-id", vm.uiState.value.link?.shareId)
        val copyable = vm.onCopyLinkRequested()
        assertTrue(copyable != null && copyable.contains("demo-share-id") && copyable.contains("#k=demo-fragment"), "copyable=$copyable")
    }

    @Test
    fun a_failed_generate_surfaces_an_error_and_no_link() = runTest(dispatcher) {
        val fake = FakeFilesRepository(shareLinkResult = RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)))
        val vm = DriveShareSurface.createShareLink(fake, "/budget.pdf")

        vm.onGenerateLinkClicked()
        advanceUntilIdle()

        assertNull(vm.uiState.value.link)
        assertTrue(vm.uiState.value.errorMessage != null)
    }
}
