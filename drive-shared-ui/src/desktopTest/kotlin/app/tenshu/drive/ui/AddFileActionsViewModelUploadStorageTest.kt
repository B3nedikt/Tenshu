package app.tenshu.drive.ui

import app.tenshu.error.ErrorCopyKey

import app.tenshu.drive.data.RepositoryError
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.domain.StorageGateDecision
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Local-first single-file upload (ADR 0019): a missing subscription never blocks
 * an upload (it succeeds locally — no error reaches the VM), while a *subscribed*
 * user over quota gets the storage dialog instead of a silently-dropped failure
 * (the original bug). Quota enforcement is the proxy's Tier-2 job; here we verify
 * only the VM's surfacing of the mapped [RepositoryError.InsufficientStorage].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddFileActionsViewModelUploadStorageTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun OwnedViewModels.viewModel(uploadResult: RepositoryResult<Unit>): Pair<AddFileActionsViewModel, FakeFilesRepository> {
        val repo = FakeFilesRepository(uploadResult = uploadResult)
        val controller = FolderUploadController(repo, ownScope(CoroutineScope(Dispatchers.Unconfined)))
        return own(AddFileActionsViewModel(repo, controller)) to repo
    }

    @Test
    fun successfulUpload_raisesNoStorageDialog() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, repo) = vms.viewModel(RepositoryResult.Success(Unit))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()

        assertEquals(1, repo.uploadCalls.size, "the upload is attempted")
        assertNull(vm.uiState.value.storageGateBlock, "a successful upload raises no dialog")
    }

    @Test
    fun overQuotaUpload_raisesFullStorageDialog() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = vms.viewModel(
            RepositoryResult.Error(
                RepositoryError.InsufficientStorage(requiredBytes = 200, availableBytes = 100)
            )
        )

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()

        assertEquals(
            StorageGateDecision.Full,
            vm.uiState.value.storageGateBlock,
            "a subscribed-over-quota upload surfaces the Full storage dialog"
        )

        vm.dismissStorageGate()
        assertNull(vm.uiState.value.storageGateBlock, "dismiss clears it")
    }

    @Test
    fun otherUploadError_raisesNoStorageDialog() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = vms.viewModel(RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED)))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()

        assertNull(
            vm.uiState.value.storageGateBlock,
            "non-storage errors don't raise the storage dialog"
        )
        // ⚠ This test used to end here — asserting only what does NOT happen, which is exactly
        // how the swallow survived: the failure raised no dialog of any kind and the picked file
        // silently never appeared.
        assertEquals(
            AddFileActionsViewModel.ERROR_UPLOAD_FAILED,
            vm.uiState.value.uploadError,
            "a non-storage failure is reported on its own channel"
        )

        vm.dismissUploadError()
        assertNull(vm.uiState.value.uploadError, "dismiss clears it")
    }

    @Test
    fun keepBoth_reportsAFailureItHitsPartway() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The original name collides (opening the conflict); the first candidate fails outright.
        val repo = FakeFilesRepository(
            uploadResultFor = { fileName ->
                if (fileName == "a.txt") RepositoryResult.Error(RepositoryError.AlreadyExists)
                else RepositoryResult.Error(RepositoryError.Unknown(ErrorCopyKey.UNEXPECTED))
            }
        )
        val vm = vms.own(AddFileActionsViewModel(
            repo,
            FolderUploadController(repo, vms.ownScope(CoroutineScope(Dispatchers.Unconfined))),
        ))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()
        assertEquals("a.txt", vm.uiState.value.uploadConflict?.fileName)

        vm.keepBothShown()
        advanceUntilIdle()

        // The dialog closes either way; before, that was ALL that happened, so Keep both looked
        // like it had worked.
        assertNull(vm.uiState.value.uploadConflict)
        assertEquals(AddFileActionsViewModel.ERROR_UPLOAD_FAILED, vm.uiState.value.uploadError)
    }

    @Test
    fun keepBoth_saysSoWhenItRunsOutOfNames() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Every candidate is taken, so the counter exhausts.
        val repo = FakeFilesRepository(
            uploadResult = RepositoryResult.Error(RepositoryError.AlreadyExists)
        )
        val vm = vms.own(AddFileActionsViewModel(
            repo,
            FolderUploadController(repo, vms.ownScope(CoroutineScope(Dispatchers.Unconfined))),
        ))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()
        vm.keepBothShown()
        advanceUntilIdle()

        // Its own message: "couldn't upload" after 99 silent attempts would not tell the user
        // that the *names* are the problem.
        assertEquals(AddFileActionsViewModel.ERROR_TOO_MANY_COPIES, vm.uiState.value.uploadError)
        assertNull(vm.uiState.value.uploadConflict)
    }

    @Test
    fun keepBoth_landsTheFirstFreeName() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeFilesRepository(
            uploadResultFor = { fileName ->
                if (fileName == "a.txt" || fileName == "a (1).txt") {
                    RepositoryResult.Error(RepositoryError.AlreadyExists)
                } else {
                    RepositoryResult.Success(Unit)
                }
            }
        )
        val vm = vms.own(AddFileActionsViewModel(
            repo,
            FolderUploadController(repo, vms.ownScope(CoroutineScope(Dispatchers.Unconfined))),
        ))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        advanceUntilIdle()
        vm.keepBothShown()
        advanceUntilIdle()

        assertEquals("a (2).txt", repo.uploadCalls.last().third)
        assertNull(vm.uiState.value.uploadError, "a Keep both that landed reports nothing")
        assertNull(vm.uiState.value.uploadConflict)
    }

    private fun OwnedViewModels.conflictViewModel(taken: Set<String>): Pair<AddFileActionsViewModel, FakeFilesRepository> {
        val repo = FakeFilesRepository(
            uploadResultFor = { fileName ->
                if (fileName in taken) RepositoryResult.Error(RepositoryError.AlreadyExists)
                else RepositoryResult.Success(Unit)
            }
        )
        val controller = FolderUploadController(repo, ownScope(CoroutineScope(Dispatchers.Unconfined)))
        return own(AddFileActionsViewModel(repo, controller)) to repo
    }

    @Test
    fun multiPick_everyConflictIsOfferedInTurn() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = vms.conflictViewModel(taken = setOf("a.txt", "b.txt"))

        // One multi-select pick: two collisions launched together.
        vm.uploadFile("file:///tmp/a.txt", "/dest")
        vm.uploadFile("file:///tmp/b.txt", "/dest")
        advanceUntilIdle()

        // Before the queue the second overwrote the first, so a.txt was skipped without a choice.
        assertEquals("a.txt", vm.uiState.value.uploadConflict?.fileName)
        vm.cancelShown()
        assertEquals("b.txt", vm.uiState.value.uploadConflict?.fileName, "the next one waits its turn")
        vm.cancelShown()
        assertNull(vm.uiState.value.uploadConflict)
    }

    @Test
    fun multiPick_aLaterSuccessDoesNotDismissAnOpenConflict() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = vms.conflictViewModel(taken = setOf("a.txt"))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        vm.uploadFile("file:///tmp/c.txt", "/dest")
        advanceUntilIdle()

        assertEquals(
            "a.txt",
            vm.uiState.value.uploadConflict?.fileName,
            "c.txt landing is about another file; a.txt still needs an answer"
        )
    }

    @Test
    fun multiPick_keepBothMovesOnToTheNextConflict() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, repo) = vms.conflictViewModel(taken = setOf("a.txt", "b.txt"))

        vm.uploadFile("file:///tmp/a.txt", "/dest")
        vm.uploadFile("file:///tmp/b.txt", "/dest")
        advanceUntilIdle()

        vm.keepBothShown()
        advanceUntilIdle()
        assertEquals("a (1).txt", repo.uploadCalls.last().third)
        assertEquals("b.txt", vm.uiState.value.uploadConflict?.fileName)

        vm.keepBothShown()
        advanceUntilIdle()
        assertEquals("b (1).txt", repo.uploadCalls.last().third)
        assertNull(vm.uiState.value.uploadConflict)
    }

    @Test
    fun aRepeatedAnswerDoesNotConsumeTheNextConflict() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, _) = vms.conflictViewModel(taken = setOf("a.txt", "b.txt"))
        vm.uploadFile("file:///tmp/a.txt", "/dest")
        vm.uploadFile("file:///tmp/b.txt", "/dest")
        advanceUntilIdle()

        // iOS answers twice: the Cancel button, then the alert's dismissal.
        val a = vm.uiState.value.uploadConflict!!.id
        vm.cancelConflict(a)
        vm.cancelConflict(a)

        assertEquals("b.txt", vm.uiState.value.uploadConflict?.fileName, "b.txt still gets its choice")
    }

    @Test
    fun aLateAnswerAfterKeepBothDoesNotConsumeTheNextConflict() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, repo) = vms.conflictViewModel(taken = setOf("a.txt", "b.txt"))
        vm.uploadFile("file:///tmp/a.txt", "/dest")
        vm.uploadFile("file:///tmp/b.txt", "/dest")
        advanceUntilIdle()

        // Keep both, then the dismissal (or a second tap) for the same alert before the retry ran.
        val a = vm.uiState.value.uploadConflict!!.id
        vm.resolveConflictKeepBoth(a)
        vm.cancelConflict(a)
        vm.resolveConflictKeepBoth(a)
        advanceUntilIdle()

        assertEquals("b.txt", vm.uiState.value.uploadConflict?.fileName)
        assertEquals(
            1,
            repo.uploadCalls.count { it.third == "a (1).txt" },
            "one Keep both, one retry — a repeat must not upload a second copy"
        )
    }

    @Test
    fun aPickThatNeverBecameBytesIsReported() = vmTest { vms ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (vm, repo) = vms.viewModel(RepositoryResult.Success(Unit))

        // The host could not stage the pick at all (unreadable document, photo file
        // representation that would not load) — the repository is never reached, so without this
        // entry point the host's only option was to drop the pick silently.
        vm.reportUploadFailed()

        assertEquals(AddFileActionsViewModel.ERROR_UPLOAD_FAILED, vm.uiState.value.uploadError)
        assertEquals(0, repo.uploadCalls.size, "nothing was attempted")
    }

    private fun AddFileActionsViewModel.keepBothShown() =
        resolveConflictKeepBoth(uiState.value.uploadConflict!!.id)

    private fun AddFileActionsViewModel.cancelShown() =
        cancelConflict(uiState.value.uploadConflict!!.id)
}
