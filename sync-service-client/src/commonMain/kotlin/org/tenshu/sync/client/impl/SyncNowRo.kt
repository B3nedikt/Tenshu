package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.sync.SyncNow

/**
 * Client RO for [SyncNow] — the "Sync now over cellular" override. Param-less, like
 * [CreateSharedRealmRo]: adds a single op to the request chain and returns the terminal
 * [RequestObject] the caller executes.
 */
internal class SyncNowRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SyncNow {

    override val schema: OperationSchema
        get() = SyncNow.Schema

    override fun syncNow(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema,
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
