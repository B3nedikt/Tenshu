package org.tenshu.contact.client.impl

import org.tenshu.contact.CompleteContactHandshake
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class CompleteContactHandshakeRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), CompleteContactHandshake {

    override fun completeContactHandshake(
        inviteId: String,
        decision: String,
        shownFingerprint: String,
        personId: String,
        newPersonName: String,
        selfAssertedName: String,
        sasConfirmed: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(CompleteContactHandshake.Params.INVITE_ID, inviteId.toTString(context))
            put(CompleteContactHandshake.Params.DECISION, decision.toTString(context))
            // Always sent, even empty: the parameter is required, and "" is the honest answer of a
            // prompt that displayed no claim.
            put(CompleteContactHandshake.Params.SHOWN_FINGERPRINT, shownFingerprint.toTString(context))
            if (personId.isNotEmpty()) {
                put(CompleteContactHandshake.Params.PERSON_ID, personId.toTString(context))
            }
            if (newPersonName.isNotEmpty()) {
                put(CompleteContactHandshake.Params.NEW_PERSON_NAME, newPersonName.toTString(context))
            }
            if (selfAssertedName.isNotEmpty()) {
                put(
                    CompleteContactHandshake.Params.SELF_ASSERTED_NAME,
                    selfAssertedName.toTString(context)
                )
            }
            if (sasConfirmed) {
                put(CompleteContactHandshake.Params.SAS_CONFIRMED, sasConfirmed.toTBoolean(context))
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
