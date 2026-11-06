package app.tenshu.drive.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3a: cancelling the folder-upload loop must propagate the cancel
 * **cross-process** so the in-flight per-file encrypt in the Control proxy
 * stops too — not merely the client-side wait. This unit-tests the
 * propagation contract of [awaitOrWireCancel] in isolation (the actual
 * cross-process plumbing it triggers — `TaskHandle.cancel()` → wire
 * `cancel(taskId)` → proxy `requestJob.cancel()` — is Phase L2 and is covered
 * by `PhaseL2CancellationTest` / `InProcessBackgroundCancelTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitOrWireCancelTest {

    private class RecordingTaskHandle : TaskHandle<Data?> {
        override val taskId: String = "test-task"
        var cancelCalled = false
            private set
        private val never = CompletableDeferred<TResult<Data?>>()

        override suspend fun await(): TResult<Data?> = never.await()

        override fun cancel() {
            cancelCalled = true
            never.cancel()
        }
    }

    @Test
    fun loopCancellation_wireCancelsTheInFlightFile() = runTest {
        val handle = RecordingTaskHandle()
        val started = CompletableDeferred<Unit>()

        val job = launch {
            started.complete(Unit)
            handle.awaitOrWireCancel()
        }

        started.await()
        assertFalse(handle.cancelCalled, "not cancelled before the loop is cancelled")

        job.cancel()
        job.join()

        // The suspended await() threw on coroutine cancellation; awaitOrWireCancel
        // must have called handle.cancel() to push the cancel across the wire to
        // the proxy before rethrowing.
        assertTrue(handle.cancelCalled, "in-flight per-file op must be wire-cancelled")
    }
}
