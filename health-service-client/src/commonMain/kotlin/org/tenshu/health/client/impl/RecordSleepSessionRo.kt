package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.RecordSleepSession
import org.tenshu.core.health.SleepSession
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordSleepSessionRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordSleepSession {

    override fun recordSleepSession(sleepSession: SleepSession): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordSleepSession.Params.SLEEP_SESSION to sleepSession
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
