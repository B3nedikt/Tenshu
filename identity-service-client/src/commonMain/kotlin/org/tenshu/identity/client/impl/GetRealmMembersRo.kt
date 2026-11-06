package org.tenshu.identity.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.listType
import org.tenshu.core.toTString
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.Member

internal class GetRealmMembersRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetRealmMembers {

    override fun getRealmMembers(realmId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetRealmMembers.Params.REALM_ID to realmId.toTString(context)),
                schema = schema,
            ),
        )
        return context.roProvider().createRequestObject<TList<Member>>(
            type = listType(Member.Schema),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
