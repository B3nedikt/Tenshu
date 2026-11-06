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
import org.tenshu.mia.AskMia

internal class AskMiaRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), AskMia {

    override fun askMia(utterance: String, nowEpochMs: Long, timeZoneId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    AskMia.Params.UTTERANCE to utterance.toTString(context),
                    AskMia.Params.NOW_EPOCH_MS to nowEpochMs.toTInteger(context),
                    AskMia.Params.TIME_ZONE_ID to timeZoneId.toTString(context),
                ),
                schema = schema,
            )
        )

        // ⚠ A GENERIC `Data` request object, not a `MiaAnswer`-typed one (the TS-4
        // lesson): the answer is parsed back through the type id its own bytes carry,
        // via the `dtoFactory` registered in `miaServiceCommonModule`. Naming the
        // concrete type here would additionally demand a composite RO factory that
        // nothing else needs.
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
