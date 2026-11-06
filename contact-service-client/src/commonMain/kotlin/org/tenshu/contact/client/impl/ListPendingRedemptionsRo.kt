package org.tenshu.contact.client.impl

import org.tenshu.contact.ListPendingRedemptions
import org.tenshu.contact.PendingRedemptionInfo
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType

internal class ListPendingRedemptionsRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ListPendingRedemptions {

    override fun listPendingRedemptions(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(params = emptyMap(), schema = schema)
        )
        return context.roProvider().createRequestObject<TList<PendingRedemptionInfo>>(
            type = listType(PendingRedemptionInfo.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
