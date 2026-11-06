package org.tenshu.mapregion.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.request.createStreamId
import org.tenshu.core.common.request.streamId
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.mapregion.DownloadProgress
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString

/**
 * Client RO for the [DownloadProgress] **stream** op. Mirrors the generic list
 * [org.tenshu.core.common.impl.array.StreamRo]: stamp a fresh `streamId` onto the chain
 * (`createStreamId()` over the pre-op callstack), then carry both it and the region id as params so the
 * proxy executor can push [org.tenshu.core.mapregion.RegionDownloadProgress] elements back on this
 * streamId. The `StreamOperation` trait on [DownloadProgress.Schema] is what routes
 * `.asStreamInteractor()` to a stream interactor.
 */
internal class DownloadProgressRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DownloadProgress {

    override val schema: OperationSchema
        get() = DownloadProgress.Schema

    override fun downloadProgress(regionId: String): RequestObject {
        val streamIdChain = requestChain.createStreamId()
        val streamId = streamIdChain.streamId

        val nextChain = streamIdChain.addArray(
            OperationDto(
                params = mapOf(
                    DownloadProgress.Params.REGION_ID to regionId.toTString(context),
                    DownloadProgress.Params.STREAM_ID to streamId.toInteger(),
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<RequestObject>(
            type = DownloadProgress.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
