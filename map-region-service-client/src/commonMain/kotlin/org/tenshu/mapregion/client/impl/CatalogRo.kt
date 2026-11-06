package org.tenshu.mapregion.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.Catalog
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class CatalogRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Catalog {

    override val schema: OperationSchema
        get() = Catalog.Schema

    override fun catalog(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(params = emptyMap(), schema = schema)
        )
        // Erase to `Data` — the concrete RegionCatalog is materialised from the wire response
        // by its DTO factory (mapRegionServiceCommonModule) at execute() time. See OpenContentStreamRo.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
