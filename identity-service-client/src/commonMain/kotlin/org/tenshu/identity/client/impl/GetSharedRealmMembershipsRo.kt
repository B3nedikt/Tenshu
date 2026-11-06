package org.tenshu.identity.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.identity.GetSharedRealmMemberships
import org.tenshu.identity.SharedRealmMembership

internal class GetSharedRealmMembershipsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetSharedRealmMemberships {

    override fun getSharedRealmMemberships(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<SharedRealmMembership>>(
            type = listType(SharedRealmMembership.Schema),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
