package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.toType

/** See [FilterRo] for the Phase 5.2 rationale. */
internal class SearchAllRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SearchAll<T> {

    override val schema: OperationSchema
        get() = SearchAll.Schema

    override fun searchAll(predicate: (T) -> TBoolean): TList<T> {
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
                params = mapOf(SearchAll.Params.PREDICATE to predicateList),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject<TList<T>>(
            requestChain = nextChain,
            type = listType(elementSchema),
            context = context
        )
    }
}
