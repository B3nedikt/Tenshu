package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList

/**
 * A [RemoteTaskExecutor] whose transport can carry a client-minted task id and
 * accept an out-of-band cancel for it — the wire half of Phase L2
 * (`docs/proposals/core-background-processing.md` §8).
 *
 * Only remote executors over a cancel-aware transport implement this. An
 * in-process executor (`LocalTaskExecutor`, the JS SDK) does not: there is no
 * wire, and cancelling the client-side [kotlinx.coroutines.Deferred] that drives
 * `executeAsync` already unwinds the real work cooperatively. A remote
 * transport that does not (yet) speak the cancel frame degrades to a no-op
 * [cancel] — matching the proxy's "unknown id is a no-op" contract.
 */
interface CancellableRemoteTaskExecutor : RemoteTaskExecutor {

    /**
     * Like [AsyncTaskExecutor.executeAsync] but tags the request with [taskId]
     * in the wire envelope so the proxy registers `taskId -> requestJob` in its
     * connection-scoped in-flight registry.
     */
    suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String): TResult<Data>

    /**
     * Sends a `cancel(taskId)` RPC over the same transport. Fire-and-forget;
     * an unknown or already-completed id is a no-op on the proxy.
     */
    fun cancel(taskId: String)
}
