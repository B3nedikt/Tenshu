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
import org.tenshu.sync.GetUnlockStatus

internal class GetUnlockStatusRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetUnlockStatus {

    override val schema: OperationSchema
        get() = GetUnlockStatus.Schema

    override fun getUnlockStatus(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(params = emptyMap(), schema = schema)
        )
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
