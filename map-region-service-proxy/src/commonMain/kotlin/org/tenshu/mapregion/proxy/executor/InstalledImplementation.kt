package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.mapregion.InstalledRegion
import org.tenshu.mapregion.proxy.RegionStore

/**
 * Returns the proxy's canonical store contents (D5) as a `TList<InstalledRegion>`. Reads local
 * state only — no network. Mirrors the `TList`-returning op shape (cf. GetPendingPairingRequests).
 */
internal class InstalledImplementation(
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
        val context = executionContext.context

        val installed = store.installed().map { region ->
            InstalledRegion.create(
                context = context,
                regionId = region.regionId,
                name = region.name,
                localPath = region.localPath,
                byteSize = region.byteSize,
                sha256 = region.sha256,
                buildDate = region.buildDate,
            )
        }

        return installed.toTList(context) as T
    }
}
