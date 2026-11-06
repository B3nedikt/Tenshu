package org.tenshu.mapregion.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.mapregion.RegionCatalog
import org.tenshu.core.mapregion.RegionEntry
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.renderableRegions

/**
 * Fetches + verifies the signed catalog via [RegionCatalogSource], filters it to renderable tile-
 * schema versions ([renderableRegions], D3), and returns the wire [RegionCatalog]. Async because
 * the source performs a network fetch; a verification/transport failure throws through the
 * error-channel catch-all rather than returning an empty catalog.
 */
internal class CatalogImplementation(
    private val source: RegionCatalogSource,
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
        val data = source.load()

        val regions = renderableRegions(data.regions).map { entry ->
            RegionEntry.create(
                context = context,
                regionId = entry.regionId,
                name = entry.name,
                coverage = entry.coverage,
                byteSize = entry.byteSize,
                sha256 = entry.sha256,
                tileSchemaVersion = entry.tileSchemaVersion,
                path = entry.path,
            )
        }

        return RegionCatalog.create(
            context = context,
            catalogSchemaVersion = data.schemaVersion,
            buildDate = data.buildDate,
            regions = regions,
        ) as T
    }
}
