package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Runs the client-initiated "Sync now over cellular" override ([org.tenshu.sync.SyncNow])
 * by signalling the live [org.tenshu.sync.proxy.SyncScheduler]'s `forceDrainOnce()`
 * through the [forceDrain] thunk (the factory passes
 * `{ syncSchedulerHolder?.scheduler?.forceDrainOnce() }`). Mirrors [DrainSyncImplementation]:
 * a thunk (not a scheduler reference) because the scheduler is created after the DI graph is
 * built, and a not-yet-wired scheduler (cold-boot race) is a harmless no-op.
 *
 * Fire-and-forget: `forceDrainOnce()` sets a one-shot flag and wakes the loop, so this
 * returns as soon as the override is *signalled* — the drain then runs on the next tick and
 * the deferred banner clears as the backlog actually drains. Param-less and receiver-less.
 */
internal class SyncNowImplementation(
    private val forceDrain: suspend () -> Unit,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        forceDrain()
        return null as T
    }
}
