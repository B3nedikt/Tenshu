package org.tenshu.contact.client.impl

import org.tenshu.contact.RedeemContactInvite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RedeemContactInviteRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RedeemContactInvite {

    override fun redeemContactInvite(
        linkPayload: String,
        selfAssertedName: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(RedeemContactInvite.Params.LINK_PAYLOAD, linkPayload.toTString(context))
            put(RedeemContactInvite.Params.SELF_ASSERTED_NAME, selfAssertedName.toTString(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(params = params, schema = schema)
        )
        return context.roProvider().createRequestObject(
            type = TString.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
