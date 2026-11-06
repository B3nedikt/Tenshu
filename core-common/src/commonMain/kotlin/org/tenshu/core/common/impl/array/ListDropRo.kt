package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class ListDropRo<T : Data>(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Drop<T> {

    override val schema: OperationSchema
        get() = Drop.Schema

    override fun drop(n: Int): TList<T> {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    Drop.Params.N to n.toTInteger()
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<T>>(
            requestChain = nextChain,
            type = TList.Schema.toType(),
            context = context
        )
    }
}
