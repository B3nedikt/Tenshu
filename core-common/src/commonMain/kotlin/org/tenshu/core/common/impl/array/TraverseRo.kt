package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType

/** Builds the `Traverse` chain step — no predicate, just the recursive walk. */
internal class TraverseRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Traverse<T> {

    override val schema: OperationSchema
        get() = Traverse.Schema

    override fun traverse(): TList<T> {
        val elementSchema = receiverElementType
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = listType(elementSchema),
            context = context
        )
    }
}
