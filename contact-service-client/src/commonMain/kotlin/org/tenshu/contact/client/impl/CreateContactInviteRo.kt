package org.tenshu.contact.client.impl

import org.tenshu.contact.CreateContactInvite
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

internal class CreateContactInviteRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), CreateContactInvite {

    override fun createContactInvite(
        intentKind: String,
        intentLabel: String,
    ): RequestObject {
        val params = buildMap<String, Data> {
            if (intentKind.isNotEmpty()) {
                put(CreateContactInvite.Params.INTENT_KIND, intentKind.toTString(context))
            }
            if (intentLabel.isNotEmpty()) {
                put(CreateContactInvite.Params.INTENT_LABEL, intentLabel.toTString(context))
            }
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
