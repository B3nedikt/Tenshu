package app.tenshu.drive.ui

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.folderupload.FolderUploadController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.tenshu.viewmodel.test.vmTest
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A multi-select pick calls [AddFileActionsViewModel.uploadFile] once per item from inside the
 * picker's result callback, on Main. Whatever the repository does there blocks the resumed
 * screen's first frame — on Android, two ContentResolver queries, a descriptor open and a
 * synchronous Binder push per item, which on a large pick was a black screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddFileActionsViewModelPickThreadingTest {

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Delegates everything to [FakeFilesRepository] except the two calls a pick makes. */
    private class PickRepository(
        private val onName: () -> Unit = {},
        private val onUpload: suspend () -> Unit = {},
        private val fake: FakeFilesRepository = FakeFilesRepository(),
    ) : FilesRepository by fake {
        override fun getFileName(uriString: String): String {
            onName()
            return uriString.substringAfterLast('/')
        }

        override suspend fun uploadFile(
            uriString: String,
            parentPath: String,
            fileName: String,
        ): RepositoryResult<Unit> {
            onUpload()
            return RepositoryResult.Success(Unit)
        }
    }

    @Test
    fun aPick_readsItsNameAndUploadsOffMain() = vmTest { vms ->
        // Unconfined Main stands in for `Main.immediate`: the launch body runs inside the call.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val executor = Executors.newSingleThreadExecutor { Thread(it, PICK_THREAD) }
        try {
            val nameThread = CompletableDeferred<String>()
            val uploadThread = CompletableDeferred<String>()
            val repo = PickRepository(
                onName = { nameThread.complete(Thread.currentThread().name.substringBefore(" @")) },
                onUpload = { uploadThread.complete(Thread.currentThread().name.substringBefore(" @")) },
            )
            val vm = vms.own(
                AddFileActionsViewModel(
                    repo,
                    FolderUploadController(repo, vms.ownScope(CoroutineScope(Dispatchers.Unconfined))),
                    executor.asCoroutineDispatcher(),
                )
            )

            vm.uploadFile("content://picker/a.jpg", "/dest")

            // Real time, not the test scheduler's: the work runs on a real thread, and under
            // runTest a virtual timeout fires as soon as the scheduler idles, before that thread
            // has reported back on a slow runner.
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    assertEquals(PICK_THREAD, nameThread.await(), "the name query must not run on the caller's (Main) thread")
                    assertEquals(PICK_THREAD, uploadThread.await(), "the upload must not run on the caller's (Main) thread")
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aLargePick_boundsHowManyUploadsAreInFlight() = vmTest { vms ->
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        var inFlight = 0
        var peak = 0
        var finished = 0
        val repo = PickRepository(
            onUpload = {
                inFlight++
                peak = maxOf(peak, inFlight)
                gate.await()
                inFlight--
                finished++
            },
        )
        val vm = vms.own(
            AddFileActionsViewModel(
                repo,
                FolderUploadController(repo, vms.ownScope(CoroutineScope(Dispatchers.Unconfined))),
                dispatcher,
            )
        )

        repeat(10) { vm.uploadFile("content://picker/$it.jpg", "/dest") }
        advanceUntilIdle()
        assertEquals(3, inFlight, "a 10-item pick starts only the bounded few")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(10, finished, "every picked file is still uploaded")
        assertTrue(peak <= 3, "never more than three in flight, saw $peak")
    }

    private companion object {
        const val PICK_THREAD = "pick-work"
    }
}
