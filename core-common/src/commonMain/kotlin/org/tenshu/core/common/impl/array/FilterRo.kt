package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Filter
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

/**
 * Phase 5.2 of `docs/adr/0009-operation-schema-typing.md`: derives the predicate-RO and result-list
 * elementType from the receiver via [receiverElementType]. Narrowing for callers like
 * `root.files /* TList<File> */ .search<Folder> { ... }` is published via
 * [OperationRo.narrowedElementType] (set by the DSL extension, read by
 * [receiverElementType]).
 */
internal class FilterRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Filter<T> {

    override val schema: OperationSchema
        get() = Filter.Schema

    override fun filter(predicate: (T) -> TBoolean): TList<T> {
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
                params = mapOf(Filter.Params.PREDICATE to predicateList),
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
