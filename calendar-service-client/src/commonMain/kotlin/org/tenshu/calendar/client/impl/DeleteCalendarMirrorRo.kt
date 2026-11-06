package org.tenshu.calendar.client.impl

import org.tenshu.calendar.DeleteCalendarMirror
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class DeleteCalendarMirrorRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DeleteCalendarMirror {

    override fun deleteCalendarMirror(mirrorId: String): RequestObject {
        val params = mapOf<String, Data>(
            DeleteCalendarMirror.Params.MIRROR_ID to mirrorId.toTString(context),
        )
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
