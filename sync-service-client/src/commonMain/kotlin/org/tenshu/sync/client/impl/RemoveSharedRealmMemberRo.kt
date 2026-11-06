package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.RemoveSharedRealmMember

internal class RemoveSharedRealmMemberRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RemoveSharedRealmMember {

    override val schema: OperationSchema
        get() = RemoveSharedRealmMember.Schema

    override fun removeSharedRealmMember(
        realmId: String,
        memberId: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RemoveSharedRealmMember.Params.REALM_ID to realmId.toTString(context),
                    RemoveSharedRealmMember.Params.MEMBER_ID to memberId.toTString(context),
                ),
                schema = schema,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
