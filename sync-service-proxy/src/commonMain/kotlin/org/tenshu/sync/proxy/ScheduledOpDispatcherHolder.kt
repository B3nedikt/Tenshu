package org.tenshu.sync.proxy

import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * Bridges an OS-woken worker (Android `TenshuOperationWorker`) back to the resident proxy so it can run a scheduled op by id. Mirrors
 * `SyncSchedulerHolder`: the dispatcher doesn't exist when the Koin graph is
 * built, so the platform init hook assigns [dispatch] after `initSyncService`,
 * and the framework-instantiated worker reads it lazily via Koin.
 *
 * `dispatch` runs the op through the same `ExecutionContext.executeAsync`
 * (op id, no params) that [ScheduleDrainLoop] uses — so the OS-woken path and
 * the in-process loop dispatch identically. `null` (cold-boot race before the
 * hook runs) ⇒ the worker no-ops and WorkManager retries.
 *
 * ⚠ `dispatch` must let a `ScheduledRunDeferred` escape unwrapped: the worker
 * maps it to `Result.retry()`, the way the in-process loop maps it to an early re-fire.
 */
class ScheduledOpDispatcherHolder {
    var dispatch: (suspend (opId: String) -> Unit)? = null
}

/**
 * Runs the scheduled op [opId] **as a child of the caller**, so stopping the caller stops the op —
 * what an OS-woken worker's `dispatch` must be (`control-background-work-off-fgs.md` §5.6).
 *
 * ⚠⚠ Not plain [ExecutionContext.executeAsync]: that starts an async op on the *session's* scope
 * and only awaits it, so cancelling the awaiter abandons the op rather than stopping it. Under a
 * WorkManager job that is the wrong half to lose. When the OS stops the job (its 10-minute limit,
 * a constraint lost, quota), the job ends and takes Control's network with it, while the pass it
 * started carries on detached. It then fails every request, keeps its single-flight lock, and the
 * job's own continuation is answered "a pass is already running". Through the caller's scope the
 * op unwinds with the job, and the continuation starts a fresh, idempotent pass.
 *
 * ⚠⚠ **And it still ends with its session.** Running in the caller's scope takes
 * the op out of the session's own teardown: the session that installed the dispatcher can be torn
 * down (a client unregisters, `ProxyService.onDestroy` quiesces), and an op left running there
 * writes through a destroyed context — saves that emit no `SyncBlock` and notify no live listing.
 * So the op is cancelled when either ends, and a session that ended under it is answered as
 * [ScheduledRunDeferred]: the worker retries, and the next attempt runs in a live session.
 */
suspend fun org.tenshu.core.common.proxy.ExecutionContext.dispatchScheduledOp(opId: String) {
    val session = sessionScope.coroutineContext[kotlinx.coroutines.Job]
    try {
        kotlinx.coroutines.coroutineScope {
            val child = this
            val unlink = session?.invokeOnCompletion {
                child.cancel(kotlinx.coroutines.CancellationException("the proxy session ended under the op"))
            }
            try {
                withEvaluationScope(this).executeAsync<org.tenshu.core.Data?>(operationId = opId, caller = null, params = null)
            } finally {
                unlink?.dispose()
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        // The caller is alive and only the session went: not a stop, a retry in a live session.
        if (session?.isActive == false && kotlinx.coroutines.currentCoroutineContext().isActive) {
            throw org.tenshu.sync.ScheduledRunDeferred(SESSION_ENDED_RETRY_MS, "scheduled op $opId: its proxy session ended")
        }
        throw e
    }
}

/** How soon an op whose session ended under it asks to run again. */
const val SESSION_ENDED_RETRY_MS: Long = 30_000L

/**
 * Installs [ctx]'s scheduled-op dispatcher — the ONE place the OS-woken worker's dispatch is defined,
 * so `PlatformScheduleDispatchTest` pins what production installs.
 * ⚠⚠ Through [dispatchScheduledOp], never plain `executeAsync`: see its KDoc.
 */
fun ScheduledOpDispatcherHolder.install(ctx: org.tenshu.core.common.proxy.ExecutionContext) {
    dispatch = { opId -> ctx.dispatchScheduledOp(opId) }
}

/**
 * The dispatcher, waiting up to [timeoutMs] for a cold start to install it. On API 37 JobScheduler
 * starts the worker ~30 ms before the proxy session that installs it, and answering retry there
 * costs a second job start and ~38 s. `null` only when no session came.
 */
suspend fun ScheduledOpDispatcherHolder.awaitDispatch(timeoutMs: Long = 10_000, pollMs: Long = 100): (suspend (String) -> Unit)? {
    var waited = 0L
    while (true) {
        dispatch?.let { return it }
        if (waited >= timeoutMs) return null
        kotlinx.coroutines.delay(pollMs)
        waited += pollMs
    }
}
