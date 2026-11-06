package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.sync.GetChanges
import org.tenshu.sync.SignedHlcTip

internal class GetChangesRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetChanges {

    override val schema: OperationSchema
        get() = GetChanges.Schema

    override fun getChanges(sinceTimestamp: Long, tip: SignedHlcTip): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetChanges.Params.SINCE_TIMESTAMP to sinceTimestamp.toInteger(),
                    GetChanges.Params.TIP to tip
                ),
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
