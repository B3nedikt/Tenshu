package org.tenshu.core.common

import kotlinx.coroutines.Deferred
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TaskHandle

/**
 * Default [TaskHandle] — wraps the in-flight client-side [Deferred] that drives
 * the IPC round-trip plus the wire-cancel hook. Built by
 * [DefaultInteractorProvider] for [org.tenshu.core.trait.BackgroundOperation]
 * ops (Phase L2, `docs/proposals/core-background-processing.md` §8).
 */
class TaskHandleImpl<T : Data?>(
    override val taskId: String,
    private val deferred: Deferred<TResult<T>>,
    private val onWireCancel: () -> Unit,
) : TaskHandle<T> {

    override suspend fun await(): TResult<T> = deferred.await()

    override fun cancel() {
        // Tell the proxy to stop first so it unwinds promptly (a no-op for an
        // in-process runtime), then unwind the client-side wait. For the
        // in-process case cancelling the Deferred *is* the cancel — it
        // cooperatively unwinds LocalTaskExecutor's suspending IO loop.
        onWireCancel()
        deferred.cancel()
    }
}
