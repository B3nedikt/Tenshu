package org.tenshu.core.common.impl.maybe

import org.tenshu.core.Data
import org.tenshu.core.Get
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

/** See [org.tenshu.core.common.impl.array.FilterRo] for the Phase 5.2 rationale. */
internal class GetRo<T : Data>(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Get<T> {

    override val schema: OperationSchema
        get() = Get.Schema

    override fun get(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap<String, Data>(),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = receiverElementType.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
