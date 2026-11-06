package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.SignedHlcTip

internal class GetSyncLogBeforeRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetSyncLogBefore {

    override val schema: OperationSchema
        get() = GetSyncLogBefore.Schema

    override fun getSyncLogBefore(beforeSeq: Long, tip: SignedHlcTip): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetSyncLogBefore.Params.BEFORE_SEQ to beforeSeq.toInteger(),
                    GetSyncLogBefore.Params.TIP to tip
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
