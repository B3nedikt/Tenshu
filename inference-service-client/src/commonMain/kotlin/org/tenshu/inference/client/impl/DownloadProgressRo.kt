package org.tenshu.inference.client.impl

import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.request.createStreamId
import org.tenshu.core.common.request.streamId
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.inference.DownloadProgress

/**
 * Client RO for the [DownloadProgress] **stream** op (IN-2c, 1c-3). Stamps a fresh `streamId` onto
 * the chain, then carries it and the model id as params so the proxy executor can push
 * [org.tenshu.inference.ModelDownloadProgress] elements back on that streamId.
 *
 * ⚠ The `StreamOperation` trait on [DownloadProgress.Schema] is what routes `.asStreamInteractor()`
 * to a stream interactor — a terminal-only op would resolve and then never deliver an element.
 */
internal class DownloadProgressRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), DownloadProgress {

    override val schema: OperationSchema
        get() = DownloadProgress.Schema

    override fun downloadProgress(modelId: String): RequestObject {
        val streamIdChain = requestChain.createStreamId()
        val streamId = streamIdChain.streamId

        val nextChain = streamIdChain.addArray(
            OperationDto(
                params = mapOf(
                    DownloadProgress.Params.MODEL_ID to modelId.toTString(context),
                    DownloadProgress.Params.STREAM_ID to streamId.toInteger(),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<RequestObject>(
            type = DownloadProgress.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
