package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Runs one slice-snapshot pass by delegating to the live
 * [org.tenshu.sync.proxy.SyncScheduler]'s writer (`sync-device-catch-up.md` C8).
 *
 * Supplied as a thunk for the same reason as [DrainSyncImplementation]: the scheduler does not
 * exist when the DI graph is built, and a not-yet-wired one is a harmless no-op — the pass is
 * periodic and nothing depends on any single run.
 */
internal class WriteSliceSnapshotsImplementation(
    private val write: suspend () -> Unit,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        write()
        return null as T
    }
}
