package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.mapregion.DeleteRegion
import org.tenshu.mapregion.proxy.RegionStore

/**
 * Evicts a region from the canonical store ([RegionStore.remove]) — index entry + bundle bytes.
 * Returns null (the effect is the eviction; callers re-read [Installed]).
 */
internal class DeleteRegionImplementation(
    private val store: RegionStore,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val regionId: TString = params.read(DeleteRegion.Params.REGION_ID)
        store.remove(regionId.get())
        return null as T
    }
}
