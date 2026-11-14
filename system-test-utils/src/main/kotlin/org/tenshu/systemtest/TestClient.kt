package org.tenshu.systemtest

import org.koin.core.KoinApplication
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.proxy.ClientRegistry
import org.tenshu.core.common.proxy.Database
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.ProxyGateway
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.sync.common.SyncBlockEmitter
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.proxy.SyncScheduler
import org.tenshu.sync.proxy.SyncSchedulerHolder

/**
 * One device's full Tenshu stack inside a [TestSystem]. The proxy side
 * (Database, ProxyGateway, ClientRegistry, ops, mergers, scheduler), the app
 * side (TenshuClient, request chains), the identity (DeviceId, keypair,
 * SyncManager) and the wire seam ([LocalClientConnection] for app↔proxy +
 * `RestSyncRelay` over the shared `testApplication.client` for proxy↔relay)
 * all resolve through [koinApp].
 *
 * Construct via [TestSystem.client]; never directly.
 */
class TestClient internal constructor(
    val deviceId: String,
    val role: TestClientRole,
    val koinApp: KoinApplication,
    /**
     * Built in the harness's stepped mode ([TestSystemBuilder.stepped]): this device's polling loop
     * never started, and it ticks only when the test calls [TestSystem.sync] / [TestSystem.syncUntil]
     * / [TestSystem.syncUntilQuiet].
     */
    val stepped: Boolean = false,
) {
    val tenshuClient: TenshuClient get() = koinApp.koin.get()
    val syncManager: SyncManager get() = koinApp.koin.get()
    val database: Database get() = koinApp.koin.get()
    val fileDatabase: FileDatabase get() = koinApp.koin.get()
    val proxyGateway: ProxyGateway get() = koinApp.koin.get()
    val clientRegistry: ClientRegistry get() = koinApp.koin.get()

    val executionContext: ExecutionContext
        get() = clientRegistry.getExecutionContext(deviceId)

    /**
     * This device's live [SyncScheduler] — the same instance the polling loop ticks, reached
     * through the `SyncSchedulerHolder` production's `DrainSync` op reads.
     *
     * ⚠ The handle a test needs is [SyncScheduler.drainNow], and what makes it a handle is the
     * **serialization**, not the drain: `drainNow` takes the scheduler's own `tickMutex`, which the
     * loop holds for the whole of a tick, so it cannot return while a tick is in flight. That is
     * the only way a test can stand on a tick boundary — a `delay` guesses at one, and a changed
     * gate or preference is only *observed* by the tick that samples it, never by the one already
     * past that line. See `ConcurrentMoveVsDeleteSystemTest`, which closes two push gates and must
     * know both are in force before it writes anything.
     *
     * Touching [executionContext] first is load-bearing: the holder is populated by
     * `platformProxyInitialization`, which runs when this device's context is built.
     */
    val syncScheduler: SyncScheduler
        get() {
            executionContext
            return requireNotNull(koinApp.koin.get<SyncSchedulerHolder>().scheduler) {
                "no SyncScheduler for '$deviceId' — platformProxyInitialization has not run"
            }
        }

    /**
     * Models an object-store wipe of a device whose app is **not running** — an iOS container
     * wipe, a reinstall — and runs [afterWipe] against the emptied store before the device comes
     * back. The sync loop is restarted on the way out, which stands for the next launch.
     *
     * ⚠⚠ Two things are held, and a bare `InMemoryObjectStore.clear()` from the test thread holds
     * neither. Measured 2026-10-10 on `SyncCursorStoreDesyncSystemTest`, which did exactly that:
     *
     * - **The `DatabaseLock`, across the wipe and [afterWipe].** This device's scheduler ticks on
     *   `Dispatchers.Default` and writes under that lock (first-pull marker, catch-up row, health
     *   row, then the pulled pages), so a tick landed between the clear and the very next line —
     *   `store should be empty after the modelled wipe`, once in a full gate. Holding the test
     *   thread 150 ms at that point reproduced it 6 runs of 6.
     * - **The scheduler, stopped and awaited.** A tick already past its cursor read when the
     *   store empties writes that cursor straight back (`max(since, served)`), which is the
     *   "cursor above zero over an empty store" state a container wipe cannot produce: there, no
     *   process is alive to do it. `stopAndAwait`, not `stop` — see its KDoc.
     *
     * ⚠ [afterWipe] runs on the calling thread with the lock held, so it must not suspend and
     * must not wait for another coroutine of this device. `ScheduleDrainLoop` and the status-row
     * collectors stay alive; they queue behind the lock and cannot be seen from inside it.
     */
    suspend fun <T> wipeStoreWhileNotRunning(afterWipe: () -> T): T {
        val scheduler = syncScheduler
        scheduler.stopAndAwait()
        try {
            return database.transaction {
                (koinApp.koin.get<ObjectStore>() as InMemoryObjectStore).clear()
                afterWipe()
            }
        } finally {
            // In stepped mode the loop was never running, and the next launch is the test's next
            // [TestSystem.sync]: restarting it here would put a live poll back under a test that
            // chose to have none.
            if (!stepped) scheduler.start()
        }
    }

    /**
     * One stepped tick of this device, on the scheduler's own `tickMutex` path, answering whether
     * that tick left more to pull.
     *
     * ⚠⚠ The emit barrier comes first, and it is what makes "this device pushes its write" true.
     * A save hands its block to `SyncObserver`'s emit channel and returns (`onSaved`'s
     * `trySend`); the block is serialised, sealed and queued on the session scope a moment later.
     * A tick that ran in that moment would push nothing, and the next device would pull nothing.
     * [SyncBlockEmitter.awaitEmitIdle] returns only once every save before it is queued, so the tick
     * that follows sees the whole of what the test wrote. Tombstones need no barrier: they are
     * queued synchronously inside the op that emits them.
     */
    internal suspend fun stepOnce(): Boolean {
        val scheduler = syncScheduler
        koinApp.koin.get<SyncBlockEmitterHolder>().emitter?.awaitEmitIdle()
        return scheduler.drainNowReportingBacklog()
    }
}
