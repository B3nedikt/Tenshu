package org.tenshu.core.common.impl.maybe

import org.tenshu.core.Data
import org.tenshu.core.IfEmptyError
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

/** See [org.tenshu.core.common.impl.array.FilterRo] for the Phase 5.2 rationale. */
internal class IfEmptyErrorRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), IfEmptyError<T> {

    override val schema: OperationSchema
        get() = IfEmptyError.Schema

    override fun ifEmptyError(): T {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = receiverElementType.toType(),
            context = context
        )
    }
}
