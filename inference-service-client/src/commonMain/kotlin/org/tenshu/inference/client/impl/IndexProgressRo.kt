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
import org.tenshu.inference.IndexProgress

/**
 * Client RO for the [IndexProgress] **stream** op (IN-D6, 1e-3). Stamps a fresh `streamId` onto the
 * chain and carries it as the only param — there is at most one index pass per device, so unlike
 * `DownloadProgress` there is nothing else to address.
 *
 * ⚠ The `StreamOperation` trait on [IndexProgress.Schema] is what routes `.asStreamInteractor()` to
 * a stream interactor — a terminal-only op would resolve and then never deliver an element.
 */
internal class IndexProgressRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), IndexProgress {

    override val schema: OperationSchema
        get() = IndexProgress.Schema

    override fun indexProgress(): RequestObject {
        val streamIdChain = requestChain.createStreamId()
        val streamId = streamIdChain.streamId

        val nextChain = streamIdChain.addArray(
            OperationDto(
                params = mapOf(
                    IndexProgress.Params.STREAM_ID to streamId.toInteger(),
                ),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject<RequestObject>(
            type = IndexProgress.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
