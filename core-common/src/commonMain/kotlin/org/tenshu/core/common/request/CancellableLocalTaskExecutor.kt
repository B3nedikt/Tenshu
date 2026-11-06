package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList

/**
 * An in-process [AsyncTaskExecutor] that can run a
 * [org.tenshu.core.trait.BackgroundOperation] *awaited and cancellable*
 * without a wire — the in-process analogue of [CancellableRemoteTaskExecutor]
 * (Phase L2, `docs/proposals/core-background-processing.md` §8).
 *
 * The cross-process path makes a background op cancellable by running it
 * awaited in `ProxyGateway`'s per-request scope (`runBackgroundInRequestScope`)
 * so a wire `cancel` → `requestJob.cancel()` unwinds it. In-process there is
 * no `ProxyGateway`, so without this the framework's legacy detach
 * (`ExecutionContext`: `sessionScope.launch` + immediate `Success(null)`)
 * makes a background op fire-and-forget and uncancellable. [executeAsyncCancellable]
 * closes that gap: it runs the task with a per-call evaluation scope bound to
 * the *caller's* coroutine and `runBackgroundInRequestScope = true`, so
 * cancelling the calling coroutine (what `TaskHandle.cancel()` does
 * in-process) cooperatively unwinds the op — no wire needed.
 */
interface CancellableLocalTaskExecutor : AsyncTaskExecutor {

    suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data>

    /**
     * Task-composite variant (TS-4, `core-task-composite.md` §5): in-process
     * mirror of `TaskTransport.callCancellable(request, taskId)` so a
     * `withTask`-tagged constituent call joins its task's sync lane on
     * in-process runtimes too (the JS SDK, iOS route-1, the `TestSystem`
     * harness). Default ignores the id — implementations that don't thread
     * lanes behave exactly as before.
     */
    suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String?): TResult<Data> =
        executeAsyncCancellable(task)
}
