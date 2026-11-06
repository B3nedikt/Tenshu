package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Fetches the signed region catalog (`maps-region-delivery.md` D3): the proxy pulls
 * `catalog.json` + its detached signature, verifies the signature against the pinned release
 * key, filters the regions to renderable tile-schema versions, and returns the [RegionCatalog].
 * A verification/transport failure surfaces as a typed [TResult.Failure] (error-channel Shape A)
 * — never a silently-empty catalog.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap (the network fetch happens proxy-side) is crossed only here via `asTaskInteractor`.
 */
suspend fun MapRegionService.catalog(): TResult<RegionCatalog> =
    findOperation<Catalog>(Catalog.ID)
        .catalog()
        .asTaskInteractor<RegionCatalog>()
        .execute()

interface Catalog : Operation {

    override val schema: OperationSchema get() = Schema

    fun catalog(): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/catalog"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {},
            returnType = RegionCatalog.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
