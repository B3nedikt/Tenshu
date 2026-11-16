package org.tenshu.identity.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.identity.GetPendingAcceptances

internal class GetPendingAcceptancesRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetPendingAcceptances {

    override val schema: OperationSchema
        get() = GetPendingAcceptances.Schema

    override fun getPendingAcceptances(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema,
            ),
        )
        // ⚠⚠ `Data.Schema.toType()`, never the concrete return type — see `ReadmitDeviceRo`.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
