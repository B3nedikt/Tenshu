package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.RecordImportRun
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordImportRunRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordImportRun {

    override fun recordImportRun(run: HealthImportRun): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordImportRun.Params.RUN to run
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
