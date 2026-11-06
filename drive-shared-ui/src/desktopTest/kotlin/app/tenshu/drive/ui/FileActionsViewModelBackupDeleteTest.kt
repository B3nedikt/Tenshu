package app.tenshu.drive.ui

import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.ingest.backup.BackupDestination
import app.tenshu.drive.ui.common.details.FileActionsViewModel
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting a Synced-Folders destination explains itself before it happens
 * (`drive-synced-folders.md` §3.9).
 *
 * ⚠ The load-bearing assertion is not that a dialog appears — it is that **nothing is
 * deleted** while it is up. A warning that renders over a delete that already ran would
 * look identical in a screenshot and be worthless, so every case here asserts on
 * `deleteFileCalls`, not just on the state flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileActionsViewModelBackupDeleteTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val noopOfflinePrefs = object : OfflinePreferences {
        override fun observe(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    private fun OwnedViewModels.viewModel(repo: FakeFilesRepository, path: String, isFolder: Boolean) =
        own(FileActionsViewModel(repo, noopOfflinePrefs, NoopSyncRulesPreferences, path, isFolder))

    @Test
    fun deletingABackupDestinationWarnsAndDeletesNothingYet() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.viewModel(repo, "/Backup/Pixel 9/Camera", isFolder = true)
        advanceUntilIdle()

        vm.onDeleteClicked()
        advanceUntilIdle()

        assertEquals(
            BackupDestination.Bucket(deviceLabel = "Pixel 9", bucketLabel = "Camera"),
            vm.uiState.value.backupDeleteWarning,
            "The warning must name the device and folder — a bare 'are you sure' answers " +
                "neither thing users get wrong about backup deletes",
        )
        assertTrue(
            repo.deleteFileCalls.isEmpty(),
            "Nothing may be deleted while the warning is up, or the dialog is theatre",
        )
    }

    @Test
    fun confirmingRunsTheDeleteAndClearsTheWarning() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.viewModel(repo, "/Backup/Pixel 9/Camera", isFolder = true)
        advanceUntilIdle()

        vm.onDeleteClicked()
        vm.onBackupDeleteConfirmed()
        advanceUntilIdle()

        assertEquals(listOf("/Backup/Pixel 9/Camera"), repo.deleteFileCalls)
        assertNull(vm.uiState.value.backupDeleteWarning)
    }

    @Test
    fun dismissingDeletesNothing() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.viewModel(repo, "/Backup/Pixel 9/Camera", isFolder = true)
        advanceUntilIdle()

        vm.onDeleteClicked()
        vm.onBackupDeleteDismissed()
        advanceUntilIdle()

        assertTrue(repo.deleteFileCalls.isEmpty(), "Cancel must mean cancel")
        assertNull(vm.uiState.value.backupDeleteWarning)
    }

    @Test
    fun anOrdinaryFileDeletesImmediatelyAsBefore() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.viewModel(repo, "/Documents/report.pdf", isFolder = false)
        advanceUntilIdle()

        vm.onDeleteClicked()
        advanceUntilIdle()

        assertNull(
            vm.uiState.value.backupDeleteWarning,
            "Drive has no confirm on ordinary deletes and this must not introduce one",
        )
        assertEquals(listOf("/Documents/report.pdf"), repo.deleteFileCalls)
    }

    @Test
    fun aFileInsideABackedUpFolderDeletesImmediately() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository()
        val vm = vms.viewModel(repo, "/Backup/Pixel 9/Camera/IMG_0001.jpg", isFolder = false)
        advanceUntilIdle()

        vm.onDeleteClicked()
        advanceUntilIdle()

        assertEquals(
            listOf("/Backup/Pixel 9/Camera/IMG_0001.jpg"),
            repo.deleteFileCalls,
            "Warning on every photo would train the user to dismiss the one that matters",
        )
    }
}
