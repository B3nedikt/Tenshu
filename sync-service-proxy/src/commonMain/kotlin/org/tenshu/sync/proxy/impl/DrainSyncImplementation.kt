package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Runs one drain/pull cycle by delegating to the live [org.tenshu.sync.proxy.SyncScheduler]'s
 * single-flight `drainNow()` (bgproc Phase 1, `bgproc-scheduling-roles.md` §6). The op
 * (`DrainSync`) is the registry-addressable face of the realtime drain — dispatched by
 * `ScheduleDrainLoop` in-process and, on Android, by `TenshuOperationWorker` when
 * WorkManager wakes a closed Control.
 *
 * The drain is supplied as a [drain] thunk (the factory passes
 * `{ syncSchedulerHolder?.scheduler?.drainNow() }`) rather than a `SyncScheduler` reference:
 * the scheduler doesn't exist when the DI graph is built (the same reason the retired
 * `SyncWorker` read it lazily from `SyncSchedulerHolder`, and why `TenshuOperationWorker`
 * does now), and the thunk keeps this impl
 * unit-testable without a live scheduler. A not-yet-wired scheduler (a cold-boot race) is a
 * no-op inside the thunk — the in-process loop covers the tick and WorkManager retries.
 * Param-less and receiver-less, like `OfflineKeeperImplementation`.
 */
internal class DrainSyncImplementation(
    private val drain: suspend () -> Unit,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        drain()
        return null as T
    }
}
