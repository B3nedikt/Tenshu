package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.request.createStreamId
import org.tenshu.core.request.roProvider
import org.tenshu.core.common.request.streamId
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.core.TContext
import org.tenshu.core.toTBoolean
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.toType

class StreamRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Stream<T> {

    override fun stream(signalOnly: Boolean): RequestObject {
        val streamIdChain = requestChain.createStreamId()
        val streamId = streamIdChain.streamId

        val nextChain = streamIdChain.addArray(
            OperationDto(
                params = buildMap {
                    put(Stream.Params.STREAM_ID, streamId.toInteger())
                    // Only ride the wire when true — plain streams stay
                    // byte-identical (old-proxy interop).
                    if (signalOnly) {
                        put(Stream.Params.SIGNAL_ONLY, true.toTBoolean(context))
                    }
                },
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<RequestObject>(
            type = Stream.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
