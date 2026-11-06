package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.isActive

/**
 * Process-wide handle to the **one** sync runtime, so a second client session reuses it instead of
 * standing up a rival (`drive-upload-progress.md` P3; `docs/tech-debt.md` *"Every client session
 * runs its own SyncScheduler"*).
 *
 * ## What was wrong
 *
 * `ClientRegistry` mints one `ExecutionContext` per **client app** and runs the platform
 * initialisation on each, so `initSyncService` ran once per connected app: seven first-party apps
 * meant seven `SyncScheduler`s polling, draining and pulling in one Control process, seven
 * `SyncHealthHolder`s publishing into the *same* single-row status relations, and — since the
 * plane split — seven drains competing over the same `PENDING_CONTENT_EMITS` journal, so a
 * deferred file could upload several times.
 *
 * The rows are last-writer-wins, and a freshly built holder publishes `SyncHealthFigures()`
 * **defaults** before its first tick corrects them, which is why the status banner flickered and
 * dropped correct verdicts with no bug in the derivation itself.
 *
 * ## Why the observers stay per-session
 *
 * `ExecutionContext.observerRegistry` is a per-context default, not a shared instance, so a
 * `DataObserver` only ever sees saves made through **its own** context. A single process-wide
 * `SyncObserver` would therefore stop observing six of the seven apps' writes — silently. The
 * observers are the one genuinely per-session part of the sync service; everything else (scheduler,
 * processor, registries, health/status writers, the one-shot startup repairs) is process work that
 * was only per-session because it was bundled into the same function.
 *
 * So the split is: **N observers, one runtime.** This holder carries the runtime plus the shared
 * pieces a later session needs in order to build its own observer against the same queues
 * ([wakeSignal], and the registries on [components]).
 */
class SyncRuntimeHolder {

    /**
     * The one runtime, or `null` until the first session builds it. Set exactly once per process
     * by `initSyncService`; read by every later session.
     */
    var runtime: SyncRuntime? = null

    /**
     * Held for a session's whole check → build → publish (`initSyncService`, via [electing]).
     *
     * ⚠⚠ The check and the publish are ~500 lines apart, and sessions do not arrive one at a time:
     * when Control restarts, every running app rebinds at once, each on its own binder thread. Before
     * this lock each one checked [runtime] before the first had published it and built its own.
     * Measured 2026-10-30: five schedulers in one Control process, overwriting the one status row each
     * tick (a forced "Upload over cellular" read *Waiting for Wi-Fi* for the whole upload), each with
     * its own emit-key table. `SyncRuntimeElectionConcurrencyTest` pins it.
     *
     * Safe to block on: the build takes no lock of its own before the database's, and nothing that
     * holds the database lock mints a session. ⚠ Reentrant so that a nested election on the same
     * thread duplicates rather than deadlocks Control — nothing nests one today; if something ever
     * does, the inner call finds no runtime yet and builds its own.
     */
    internal val electionLock: ReentrantLock = reentrantLock()
}

/**
 * Run [block] — `initSyncService`'s whole body — as one election: no other session can check
 * [SyncRuntimeHolder.runtime] between this one's check and its publish. No holder, no election.
 */
internal inline fun <T> SyncRuntimeHolder?.electing(block: () -> T): T =
    if (this == null) block() else electionLock.withLock(block)

/**
 * The process-scoped half of the sync service — everything a second client session must **reuse**
 * rather than rebuild. See [SyncRuntimeHolder] for why this half exists.
 *
 * @property components the built runtime, as returned to the first caller.
 * @property wakeSignal shared with every session's `SyncObserver` so a user-originated enqueue on
 *   any client still shortcuts the one scheduler's poll interval.
 */
class SyncRuntime(
    val components: SyncProxyComponents,
    val wakeSignal: SyncWakeSignal,
    /**
     * The scope the runtime's loops run on. Read only through [isAlive] — a cancelled scope means
     * the session that elected this runtime has gone away and its scheduler is no longer ticking.
     */
    private val scope: CoroutineScope,
) {
    /**
     * Whether this runtime is still running. A dead one must be replaced rather than handed to the
     * next session, or sync stops process-wide the moment the electing client disconnects.
     */
    fun isAlive(): Boolean = scope.isActive
}
