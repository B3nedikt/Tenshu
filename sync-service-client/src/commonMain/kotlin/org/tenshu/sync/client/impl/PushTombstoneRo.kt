package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushTombstone

internal class PushTombstoneRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), PushTombstone {

    override val schema: OperationSchema
        get() = PushTombstone.Schema

    override fun pushTombstone(block: DeleteBlock): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(PushTombstone.Params.BLOCK to block),
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
