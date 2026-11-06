package org.tenshu.mia.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.mia.ConfirmMiaAction

internal class ConfirmMiaActionRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ConfirmMiaAction {

    override fun confirmMiaAction(token: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(ConfirmMiaAction.Params.TOKEN to token.toTString(context)),
                schema = schema,
            )
        )
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
