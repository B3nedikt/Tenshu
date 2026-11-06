package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Runs one content-declaration backfill pass by delegating to the live
 * [org.tenshu.sync.proxy.SyncScheduler] (`content-plane-refcount.md` §9.7).
 *
 * Thunk-shaped for the same reason as [WriteSliceSnapshotsImplementation]: the scheduler does not
 * exist when the DI graph is built. ⚠ A cold-boot no-op is harmless here too — the pass is
 * idempotent and its marker only latches on a clean run, so the next period picks it up.
 */
internal class BackfillContentDeclarationsImplementation(
    private val backfill: suspend () -> Unit,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        backfill()
        return null as T
    }
}
