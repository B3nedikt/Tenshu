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
import org.tenshu.core.toTString
import org.tenshu.identity.GetRealmInvitations

internal class GetRealmInvitationsRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), GetRealmInvitations {

    override val schema: OperationSchema
        get() = GetRealmInvitations.Schema

    override fun getRealmInvitations(realmId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetRealmInvitations.Params.REALM_ID to realmId.toTString(context)),
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
