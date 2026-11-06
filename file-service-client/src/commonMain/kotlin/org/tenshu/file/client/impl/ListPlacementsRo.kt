package org.tenshu.file.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.file.system.ListPlacements
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType

internal class ListPlacementsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ListPlacements {

    override fun listPlacements(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<Placement>>(
            type = listType(Placement.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
