package org.tenshu.core.common.proxy

import kotlinx.coroutines.isActive
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.request.CancellableLocalTaskExecutor
import org.tenshu.core.common.request.CancellableReadTaskExecutor
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * In-process task executor that survives a proxy teardown.
 *
 * The in-process client (Control's own UI over `LocalClientConnection`) used to
 * capture its session's [ExecutionContext] once, lazily, for the process
 * lifetime. That pins a *quiesced* session when the proxy is torn down while
 * the process lives on — on Android, `ProxyService.onDestroy` quiesces every
 * session whenever the last Binder client unbinds (routine under OEM lifecycle
 * policies), cancelling every `sessionScope` including the local one. From
 * then on each op died with a CancellationException that upstream coroutine
 * machinery swallowed silently: UI spinners hung forever and background loops
 * never came back (the "Registering your realm" incident, 2026-07-25). Binder
 * clients recover on re-bind because they mint fresh sessions; this class
 * gives the local client the same recovery.
 *
 * [executor] re-resolves the session through [sessionProvider] (normally
 * `ClientRegistry.getExecutionContext(clientId)` — a quiesced-and-drained
 * registry mints a fresh, `init()`-ed session) whenever the cached context's
 * `sessionScope` is no longer active. [onSessionAdopted] fires once per
 * *distinct* adopted context so the caller can re-wire per-session plumbing
 * (the stream-element listener on the session's `LocalClientConnection`).
 *
 * After a *final* shutdown (`ClientRegistry.shutdownAndJoin` — process/test
 * exit) the registry refuses new sessions; the rebuild then falls back to the
 * dead cached executor, whose ops fail as contained typed failures via
 * `ExecutionContext.execute`'s dead-scope guard. No resurrection of background
 * loops against a dropped DI world.
 *
 * Lock-free: the cached session rides an [AtomicReference] CAS loop. Two
 * concurrent rebuilds resolve the same registry session; only the CAS winner
 * fires [onSessionAdopted], so the per-session listener is registered exactly
 * once.
 */
@OptIn(ExperimentalAtomicApi::class)
class SessionAwareLocalTaskExecutor(
    private val sessionProvider: () -> ExecutionContext,
    private val onSessionAdopted: (ExecutionContext) -> Unit = {},
) : CancellableLocalTaskExecutor, CancellableReadTaskExecutor {

    private class Session(val context: ExecutionContext, val executor: LocalTaskExecutor)

    private val current = AtomicReference<Session?>(null)

    private fun executor(): LocalTaskExecutor {
        while (true) {
            val cached = current.load()
            if (cached != null && cached.context.sessionScope.isActive) return cached.executor
            val fresh = try {
                sessionProvider()
            } catch (e: Throwable) {
                // Final shutdown: the registry is closed against new sessions
                // (ClientRegistry.closed) — resurrecting background loops after
                // the DI world is dropped is exactly what must not happen. Fall
                // back to the dead cached executor: its dead-scope guard turns
                // each op into a contained typed TResult.Failure rather than a
                // hang or an uncaught exception.
                if (cached != null) return cached.executor
                throw e
            }
            val candidate = Session(fresh, LocalTaskExecutor(fresh))
            if (current.compareAndSet(cached, candidate)) {
                // Adoption hook only for a genuinely new context — re-adopting
                // the same (dead) context must not re-register its listeners.
                if (cached?.context !== fresh) onSessionAdopted(fresh)
                return candidate.executor
            }
            // Lost the CAS to a concurrent rebuild; retry — the next load sees
            // the winner's session for the same provider result.
        }
    }

    override fun execute(task: TList<Data>): TResult<Data> =
        executor().execute(task)

    override suspend fun executeAsync(task: TList<Data>): TResult<Data> =
        executor().executeAsync(task)

    override suspend fun executeAsyncCancellable(task: TList<Data>): TResult<Data> =
        executor().executeAsyncCancellable(task)

    override suspend fun executeAsyncCancellable(task: TList<Data>, taskId: String?): TResult<Data> =
        executor().executeAsyncCancellable(task, taskId)

    // `cancellableReads { }` in-process (`core-background-processing.md` §6.2.1). Without the
    // override the interactor falls back to `executeAsync` and the read is simply not stopped.
    override suspend fun executeAsyncCancellableRead(task: TList<Data>): TResult<Data> =
        executor().executeAsyncCancellableRead(task)
}
