package org.tenshu.contact.client.impl

import org.tenshu.contact.AwaitInviteAck
import org.tenshu.contact.InviteAckResult
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class AwaitInviteAckRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), AwaitInviteAck {

    override fun awaitInviteAck(inviteId: String, sasConfirmed: Boolean): RequestObject {
        val params = buildMap<String, Data> {
            put(AwaitInviteAck.Params.INVITE_ID, inviteId.toTString(context))
            if (sasConfirmed) {
                put(AwaitInviteAck.Params.SAS_CONFIRMED, sasConfirmed.toTBoolean(context))
            }
        }
        val nextChain = requestChain.addArray(
            OperationDto(params = params, schema = schema)
        )
        return context.roProvider().createRequestObject(
            type = InviteAckResult.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
