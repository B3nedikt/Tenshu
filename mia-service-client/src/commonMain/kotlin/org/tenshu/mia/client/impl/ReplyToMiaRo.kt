package org.tenshu.mia.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.mia.ReplyToMia

internal class ReplyToMiaRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ReplyToMia {

    override fun replyToMia(token: String, utterance: String, nowEpochMs: Long, timeZoneId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReplyToMia.Params.TOKEN to token.toTString(context),
                    ReplyToMia.Params.UTTERANCE to utterance.toTString(context),
                    ReplyToMia.Params.NOW_EPOCH_MS to nowEpochMs.toTInteger(context),
                    ReplyToMia.Params.TIME_ZONE_ID to timeZoneId.toTString(context),
                ),
                schema = schema,
            )
        )
        // ⚠ A generic `Data` request object, as for `askMia` (see `AskMiaRo`).
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
