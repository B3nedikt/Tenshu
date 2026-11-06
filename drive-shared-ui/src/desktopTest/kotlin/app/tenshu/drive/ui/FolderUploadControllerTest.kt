package app.tenshu.drive.ui

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.CloudPreflight
import app.tenshu.drive.data.CloudPreflightDecision
import app.tenshu.drive.data.ConflictResolution
import app.tenshu.drive.data.FileConflict
import app.tenshu.drive.data.FolderUploadSummary
import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.UploadProgress
import app.tenshu.drive.ui.common.addfileactions.FolderUploadOutcome
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The folder-upload loop moved out of `AddFileActionsViewModel.viewModelScope`
 * into the app-scoped [FolderUploadController] (Phase 3a — it must outlive the
 * request/ViewModel). These exercise the loop where it now lives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderUploadControllerTest {

    @Test
    fun progress_isForwardedToState_andClearedOnCompletion() = vmTest { vms ->
        val gate = CompletableDeferred<Unit>()
        val repo = FakeFilesRepository { onProgress, _, _ ->
            onProgress(UploadProgress("photo.jpg", 3, 10))
            gate.await()
            RepositoryResult.Success(FolderUploadSummary(10, 10, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        val progress = controller.state.value?.progress
        assertEquals("photo.jpg", progress?.currentFileName)
        assertEquals(3, progress?.completed)
        assertEquals(10, progress?.total)

        gate.complete(Unit)
        advanceUntilIdle() // completion + the +5s auto-dismiss elapses
        assertEquals(null, controller.state.value)
    }

    @Test
    fun insufficientStorage_surfacesAsInsufficientStorageOutcome() = vmTest { vms ->
        // The Tier-3 pre-flight (inside the repository) returns InsufficientStorage
        // before any file is uploaded; the controller surfaces the distinct outcome.
        val repo = FakeFilesRepository { _, _, _ ->
            RepositoryResult.Error(
                RepositoryError.InsufficientStorage(requiredBytes = 5_000L, availableBytes = 100L),
            )
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        runCurrent() // loop completes; the +5s auto-dismiss has not elapsed yet

        assertEquals(
            FolderUploadOutcome.InsufficientStorage,
            controller.state.value?.outcome,
        )
    }

    @Test
    fun applyToAll_resolvesSubsequentConflictsWithoutResurfacing() = vmTest { vms ->
        val resolutions = mutableListOf<ConflictResolution>()
        val repo = FakeFilesRepository { _, onConflict, _ ->
            resolutions += onConflict(FileConflict("a.txt", "/dest"))
            resolutions += onConflict(FileConflict("b.txt", "/dest"))
            RepositoryResult.Success(FolderUploadSummary(2, 0, 2, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("a.txt", controller.state.value?.conflict?.fileName)

        controller.resolveConflict(ConflictResolution.KeepBoth, applyToAll = true)
        advanceUntilIdle()

        assertEquals(
            listOf<ConflictResolution>(ConflictResolution.KeepBoth, ConflictResolution.KeepBoth),
            resolutions,
        )
        assertEquals(null, controller.state.value)
    }

    @Test
    fun withoutApplyToAll_eachConflictIsSurfaced() = vmTest { vms ->
        val resolutions = mutableListOf<ConflictResolution>()
        val repo = FakeFilesRepository { _, onConflict, _ ->
            resolutions += onConflict(FileConflict("a.txt", "/dest"))
            resolutions += onConflict(FileConflict("b.txt", "/dest"))
            RepositoryResult.Success(FolderUploadSummary(2, 0, 2, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("a.txt", controller.state.value?.conflict?.fileName)

        controller.resolveConflict(ConflictResolution.Skip, applyToAll = false)
        advanceUntilIdle()
        assertEquals("b.txt", controller.state.value?.conflict?.fileName)

        controller.resolveConflict(ConflictResolution.Skip, applyToAll = false)
        advanceUntilIdle()
        assertEquals(
            listOf<ConflictResolution>(ConflictResolution.Skip, ConflictResolution.Skip),
            resolutions,
        )
        assertEquals(null, controller.state.value)
    }

    @Test
    fun completion_showsSummaryThenAutoDismisses() = vmTest { vms ->
        val repo = FakeFilesRepository { _, _, _ ->
            RepositoryResult.Success(FolderUploadSummary(5, 4, 1, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        runCurrent() // loop completes; +5s auto-dismiss scheduled, not yet run

        val outcome = controller.state.value?.outcome
        assertTrue(outcome is FolderUploadOutcome.Completed, "expected Completed, was $outcome")
        assertEquals(FolderUploadSummary(5, 4, 1, 0), outcome.summary)

        advanceUntilIdle()
        assertEquals(null, controller.state.value)
    }

    @Test
    fun cancel_setsCancelledOutcome_andStopsTheLoop() = vmTest { vms ->
        val blocker = CompletableDeferred<Unit>() // never completed
        var resumedAfterBlock = false
        val repo = FakeFilesRepository { onProgress, _, _ ->
            onProgress(UploadProgress("big.bin", 0, 3))
            blocker.await()
            resumedAfterBlock = true // must NOT run once cancelled
            RepositoryResult.Success(FolderUploadSummary(3, 3, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals("big.bin", controller.state.value?.progress?.currentFileName)
        assertEquals(null, controller.state.value?.outcome)

        controller.cancel()
        val outcome = controller.state.value?.outcome
        assertTrue(outcome is FolderUploadOutcome.Cancelled, "expected Cancelled, was $outcome")

        advanceUntilIdle()
        assertEquals(false, resumedAfterBlock)
        assertEquals(null, controller.state.value)
    }

    @Test
    fun cloudPreflight_surfacesTheSummary_andIncludeProceeds() = vmTest { vms ->
        var decision: CloudPreflightDecision? = null
        val repo = FakeFilesRepository { _, _, onCloudPreflight ->
            decision = onCloudPreflight(CloudPreflight(onlineOnlyCount = 3, onlineOnlyBytes = 3_400_000_000L))
            RepositoryResult.Success(FolderUploadSummary(3, 3, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        // The prompt is awaiting the user's choice — the summary is on state.
        assertEquals(3, controller.state.value?.cloudPreflight?.onlineOnlyCount)
        assertEquals(3_400_000_000L, controller.state.value?.cloudPreflight?.onlineOnlyBytes)

        controller.resolvePreflight(CloudPreflightDecision.Include)
        advanceUntilIdle()
        assertEquals(CloudPreflightDecision.Include, decision)
        assertEquals(null, controller.state.value) // completed + auto-dismissed
    }

    @Test
    fun cloudPreflight_skip_isPlumbedThrough() = vmTest { vms ->
        var decision: CloudPreflightDecision? = null
        val repo = FakeFilesRepository { _, _, onCloudPreflight ->
            decision = onCloudPreflight(CloudPreflight(1, 500L))
            RepositoryResult.Success(FolderUploadSummary(1, 0, 1, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        controller.resolvePreflight(CloudPreflightDecision.Skip)
        advanceUntilIdle()
        assertEquals(CloudPreflightDecision.Skip, decision)
    }

    @Test
    fun cloudUnavailableCount_flowsThroughToTheCompletedOutcome() = vmTest { vms ->
        // Online-only files the provider wouldn't deliver are surfaced separately
        // from generic failures so the summary reads "couldn't download from the
        // cloud" (§11 P4).
        val repo = FakeFilesRepository { _, _, _ ->
            RepositoryResult.Success(
                FolderUploadSummary(total = 3, uploaded = 2, skipped = 0, failed = 0, cloudUnavailable = 1),
            )
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )
        controller.uploadFolder("uri", "/dest")
        runCurrent()
        val outcome = controller.state.value?.outcome
        assertTrue(outcome is FolderUploadOutcome.Completed, "expected Completed, was $outcome")
        assertEquals(1, outcome.summary.cloudUnavailable)
    }

    @Test
    fun cloudPreflight_cancel_dismissesWithoutASummary() = vmTest { vms ->
        val repo = FakeFilesRepository { _, _, onCloudPreflight ->
            // The real repo returns an empty summary on Cancel; the controller
            // dismisses regardless (no terminal bar for a backed-out upload).
            onCloudPreflight(CloudPreflight(2, 1_000L))
            RepositoryResult.Success(FolderUploadSummary(0, 0, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        assertEquals(2, controller.state.value?.cloudPreflight?.onlineOnlyCount)

        controller.resolvePreflight(CloudPreflightDecision.Cancel)
        advanceUntilIdle()
        assertEquals(null, controller.state.value) // dismissed, no outcome bar
    }

    @Test
    fun listingError_showsFailedOutcome() = vmTest { vms ->
        val repo = FakeFilesRepository { _, _, _ ->
            RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        runCurrent()
        assertTrue(
            controller.state.value?.outcome is FolderUploadOutcome.Failed,
            "expected Failed, was ${controller.state.value?.outcome}",
        )
        advanceUntilIdle()
        assertEquals(null, controller.state.value)
    }

    @Test
    fun overwriteResolution_isPlumbedThrough() = vmTest { vms ->
        val resolutions = mutableListOf<ConflictResolution>()
        val repo = FakeFilesRepository { _, onConflict, _ ->
            resolutions += onConflict(FileConflict("a.txt", "/dest"))
            resolutions += onConflict(FileConflict("b.txt", "/dest"))
            RepositoryResult.Success(FolderUploadSummary(2, 2, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        controller.uploadFolder("uri", "/dest")
        advanceUntilIdle()
        controller.resolveConflict(ConflictResolution.Overwrite, applyToAll = true)
        advanceUntilIdle()

        assertEquals(
            listOf<ConflictResolution>(ConflictResolution.Overwrite, ConflictResolution.Overwrite),
            resolutions,
        )
    }

    @Test
    fun loopRunsOnTheControllerScope_notACallerScope() = vmTest { vms ->
        // The distinctive Phase-3a property: the loop is owned by the
        // controller's own scope, so it keeps running after whatever started
        // it (a ViewModel) is gone. Here the caller coroutine returns
        // immediately after `uploadFolder` yet the blocking upload stays in
        // flight and observable on `state`.
        val blocker = CompletableDeferred<Unit>()
        val repo = FakeFilesRepository { onProgress, _, _ ->
            onProgress(UploadProgress("a.bin", 0, 1))
            blocker.await()
            RepositoryResult.Success(FolderUploadSummary(1, 1, 0, 0))
        }
        val controller = FolderUploadController(
            repo, vms.ownScope(CoroutineScope(StandardTestDispatcher(testScheduler)))
        )

        // Caller starts it and "goes away".
        run { controller.uploadFolder("uri", "/dest") }
        advanceUntilIdle()

        assertEquals("a.bin", controller.state.value?.progress?.currentFileName)
        assertEquals(null, controller.state.value?.outcome)

        blocker.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, controller.state.value)
    }
}
