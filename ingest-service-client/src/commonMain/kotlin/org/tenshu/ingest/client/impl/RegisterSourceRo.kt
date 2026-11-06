package org.tenshu.ingest.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.ingest.RegisterSource
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

internal class RegisterSourceRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), RegisterSource {

    override fun registerSource(
        sourceId: String,
        appId: String,
        deviceId: String,
        displayName: String,
        sourceKind: String,
        watchedPaths: String,
    ): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RegisterSource.Params.SOURCE_ID to sourceId.toTString(context),
                    RegisterSource.Params.APP_ID to appId.toTString(context),
                    RegisterSource.Params.DEVICE_ID to deviceId.toTString(context),
                    RegisterSource.Params.DISPLAY_NAME to displayName.toTString(context),
                    RegisterSource.Params.SOURCE_KIND to sourceKind.toTString(context),
                    RegisterSource.Params.WATCHED_PATHS to watchedPaths.toTString(context),
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
