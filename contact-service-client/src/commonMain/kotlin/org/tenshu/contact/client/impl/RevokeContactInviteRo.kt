package org.tenshu.contact.client.impl

import org.tenshu.contact.RevokeContactInvite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RevokeContactInviteRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RevokeContactInvite {

    override fun revokeContactInvite(inviteId: String): RequestObject {
        val params = buildMap<String, Data> {
            put(RevokeContactInvite.Params.INVITE_ID, inviteId.toTString(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(params = params, schema = schema)
        )
        return context.roProvider().createRequestObject(
            type = TBoolean.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
