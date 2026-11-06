package org.tenshu.ingest.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.ingest.UpdateSourceStatus
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class UpdateSourceStatusRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), UpdateSourceStatus {

    override fun updateSourceStatus(
        sourceId: String,
        deviceId: String,
        status: String,
        pendingCount: Long,
        lastRunAtMillis: Long,
        lastError: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    UpdateSourceStatus.Params.SOURCE_ID to sourceId.toTString(context),
                    UpdateSourceStatus.Params.DEVICE_ID to deviceId.toTString(context),
                    UpdateSourceStatus.Params.STATUS to status.toTString(context),
                    UpdateSourceStatus.Params.PENDING_COUNT to pendingCount.toTInteger(context),
                    UpdateSourceStatus.Params.LAST_RUN_AT to lastRunAtMillis.toTInteger(context),
                    UpdateSourceStatus.Params.LAST_ERROR to lastError.toTString(context),
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
