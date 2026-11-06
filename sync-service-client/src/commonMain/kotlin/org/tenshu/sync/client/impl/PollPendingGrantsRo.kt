package org.tenshu.sync.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.identity.PendingGrant
import org.tenshu.sync.PollPendingGrants

internal class PollPendingGrantsRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), PollPendingGrants {

    override val schema: OperationSchema
        get() = PollPendingGrants.Schema

    override fun pollPendingGrants(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TList<PendingGrant>>(
            type = listType(PendingGrant.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
