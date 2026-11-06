package app.tenshu.drive.ui

import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After Phase 3a the folder-upload loop lives in the app-scoped
 * [FolderUploadController] (see [FolderUploadControllerTest] for the loop
 * behaviour). These verify the thin contract that remains in the ViewModel:
 * it mirrors the controller's state into `uiState.folderUpload` and forwards
 * user actions to the controller — so the existing App.kt / FolderUploadUi
 * surface is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddFileActionsViewModelFolderUploadTest {

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun viewModel_mirrorsControllerProgress_andClearsOnCompletion() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val repo = FakeFilesRepository { onProgress, _, _ ->
            onProgress(UploadProgress("photo.jpg", 3, 10))
            gate.await()
            RepositoryResult.Success(FolderUploadSummary(10, 10, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )
        val vm = vms.own(AddFileActionsViewModel(repo, controller))

        vm.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("photo.jpg", vm.uiState.value.folderUpload?.progress?.currentFileName)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.folderUpload)
    }

    @Test
    fun viewModel_surfacesConflict_andForwardsResolution() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val resolutions = mutableListOf<ConflictResolution>()
        val repo = FakeFilesRepository { _, onConflict, _ ->
            resolutions += onConflict(FileConflict("a.txt", "/dest"))
            RepositoryResult.Success(FolderUploadSummary(1, 0, 1, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )
        val vm = vms.own(AddFileActionsViewModel(repo, controller))

        vm.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("a.txt", vm.uiState.value.folderUpload?.conflict?.fileName)

        vm.resolveFolderConflict(ConflictResolution.Skip, applyToAll = false)
        advanceUntilIdle()
        assertEquals(listOf<ConflictResolution>(ConflictResolution.Skip), resolutions)
        assertEquals(null, vm.uiState.value.folderUpload)
    }

    @Test
    fun cancelFolderUpload_isForwarded_andOutcomeMirrored() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val blocker = CompletableDeferred<Unit>() // never completed
        var resumedAfterBlock = false
        val repo = FakeFilesRepository { onProgress, _, _ ->
            onProgress(UploadProgress("big.bin", 0, 3))
            blocker.await()
            resumedAfterBlock = true
            RepositoryResult.Success(FolderUploadSummary(3, 3, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )
        val vm = vms.own(AddFileActionsViewModel(repo, controller))

        vm.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("big.bin", vm.uiState.value.folderUpload?.progress?.currentFileName)

        vm.cancelFolderUpload()
        runCurrent() // let the mirror collector observe the controller's Cancelled state
        val outcome = vm.uiState.value.folderUpload?.outcome
        assertTrue(outcome is FolderUploadOutcome.Cancelled, "expected Cancelled, was $outcome")

        advanceUntilIdle()
        assertEquals(false, resumedAfterBlock)
        assertEquals(null, vm.uiState.value.folderUpload)
    }
}
