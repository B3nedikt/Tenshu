package org.tenshu.sdk.ios

import app.tenshu.drive.data.FileActivity
import app.tenshu.drive.data.FileActivityKind
import app.tenshu.drive.data.FileDetailsModel
import app.tenshu.drive.data.FileIcon
import app.tenshu.drive.data.FileModel
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reactive coverage of the file detail + actions view models over [FakeFilesRepository]. The VMs use
 * `viewModelScope` (= `Dispatchers.Main`), which the in-app smoke can't pump (it blocks Main); here
 * [Dispatchers.setMain] points Main at the test scheduler so the metadata/history streams and the
 * action handlers actually run. `setMain` is installed in [setUp] before any VM is built, so each
 * `viewModelScope` captures the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveDetailSurfaceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun file(path: String) = FileModel.File(path, path.substringAfterLast('/'), "", FileIcon.Document)

    @Test
    fun details_uiState_reflects_the_repository_metadata_and_activity() = runTest(dispatcher) {
        val fake = FakeFilesRepository(
            initialListing = listOf(file("/a.txt")),
            detailsFor = { FileDetailsModel(file = file(it), size = "1 KB", created = "Jun 1", lastModified = "Jun 2") },
            activityFor = {
                listOf(FileActivity(FileActivityKind.Created, detail = null, timestampMillis = 0, deviceId = "d1", deviceName = "iPhone"))
            },
        )

        val bound = DriveDetailSurface.create(fake, "/a.txt", isFolder = false)
        advanceUntilIdle()

        val state = bound.details.uiState.value
        assertEquals("1 KB", state.size)
        assertEquals("Jun 1", state.created)
        assertEquals("Jun 2", state.lastModified)
        assertEquals(1, state.activity.size)
        assertEquals("iPhone", state.activity.first().deviceLabel)
    }

    @Test
    fun delete_action_calls_the_repository() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val bound = DriveDetailSurface.create(fake, "/a.txt", isFolder = false)

        bound.actions.onDeleteClicked()
        advanceUntilIdle()

        assertEquals(listOf("/a.txt"), fake.deletedPaths)
    }

    @Test
    fun offline_toggle_flips_state_and_downloads() = runTest(dispatcher) {
        val fake = FakeFilesRepository(initialListing = listOf(file("/a.txt")))
        val bound = DriveDetailSurface.create(fake, "/a.txt", isFolder = false)
        advanceUntilIdle()
        assertFalse(bound.actions.uiState.value.isAvailableOffline)

        bound.actions.onDownloadClicked()
        advanceUntilIdle()

        assertTrue(bound.actions.uiState.value.isAvailableOffline)
        assertEquals(listOf("/a.txt"), fake.downloaded)
    }
}
