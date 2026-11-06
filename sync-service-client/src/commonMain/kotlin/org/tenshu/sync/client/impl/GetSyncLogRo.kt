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
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.SignedHlcTip

internal class GetSyncLogRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetSyncLog {

    override val schema: OperationSchema
        get() = GetSyncLog.Schema

    override fun getSyncLog(sinceTimestamp: Long, tip: SignedHlcTip): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    GetSyncLog.Params.SINCE_TIMESTAMP to sinceTimestamp.toInteger(),
                    GetSyncLog.Params.TIP to tip
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
