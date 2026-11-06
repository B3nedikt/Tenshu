package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Search
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.toType

/** See [FilterRo] for the Phase 5.2 rationale. */
internal class ListSearchRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Search<T> {

    override val schema: OperationSchema
        get() = super<Search>.schema

    override fun search(predicate: (T) -> TBoolean): Maybe<T> {
        val elementSchema = receiverElementType
        val chainWithPredicate = requestChain.addNestedArray { arrayChain ->
            val searchRo = context.roProvider().createRequestObject<T>(
                type = elementSchema.toType(),
                requestChain = arrayChain,
                context = context
            )

            val resultRo = predicate(searchRo)

            (resultRo as RequestObject).requestChain
        }

        @Suppress("UNCHECKED_CAST")
        val predicateList = chainWithPredicate.getCallstack().last() as TList<Data>

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(Search.Params.PREDICATE to predicateList),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<Maybe<T>>(
            requestChain = nextChain,
            type = maybeType(elementSchema),
            context = context
        )
    }
}
