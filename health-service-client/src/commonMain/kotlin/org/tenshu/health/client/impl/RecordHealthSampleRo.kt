package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.RecordHealthSample
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordHealthSampleRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordHealthSample {

    override fun recordHealthSample(healthSample: HealthSample): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordHealthSample.Params.HEALTH_SAMPLE to healthSample
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
