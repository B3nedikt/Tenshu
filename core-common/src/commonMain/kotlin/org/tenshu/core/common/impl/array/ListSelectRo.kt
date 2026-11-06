package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class ListSelectRo<R : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Select<R> {

    override val schema: OperationSchema
        get() = Select.Schema

    override fun select(selectors: List<Selector<R>>): Data {

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    Select.Params.SELECTORS to TListDto(selectors)
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = TList.Schema.toType(),
            context = context
        )
    }
}
