package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList

/**
 * An executor that can run a task as a **cancellable read** — one whose evaluation stops when its
 * caller is cancelled, instead of running to the end for nobody (`cancellableReads { }`,
 * `docs/proposals/core-background-processing.md` §6.2.1).
 *
 * Deliberately separate from the Layer-2 task path ([CancellableRemoteTaskExecutor] /
 * [CancellableLocalTaskExecutor] with a task id): a read is not detached from its session, not
 * counted towards foreground-service liveness and joins no sync lane. It only stops when abandoned.
 *
 * An executor that does not implement this runs the read as a plain [AsyncTaskExecutor.executeAsync]
 * — the caller still unwinds, the proxy just finishes the walk. That is also what an implementation
 * must do against a proxy too old to stop one.
 */
interface CancellableReadTaskExecutor : AsyncTaskExecutor {
    suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data>
}
