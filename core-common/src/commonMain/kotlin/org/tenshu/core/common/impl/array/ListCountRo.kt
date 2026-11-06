package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Count
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class ListCountRo<T : Data>(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Count<T> {

    override val schema: OperationSchema
        get() = Count.Schema

    override fun count(): TInteger {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = TInteger.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
