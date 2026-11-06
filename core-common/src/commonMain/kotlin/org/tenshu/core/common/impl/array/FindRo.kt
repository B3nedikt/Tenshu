package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.Find
import org.tenshu.core.collection.list.Search
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
internal class FindRo<T : Data>(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Find<T> {

    override val schema: OperationSchema
        get() = Find.Schema

    override val params: Map<String, Data> get() = emptyMap()

    override fun find(predicate: (T) -> TBoolean): Maybe<T> {
        val elementSchema = receiverElementType
        val findChain = requestChain.addNestedArray { arrayChain ->
            val searchRo = context.roProvider().createRequestObject<T>(
                type = elementSchema.toType(),
                requestChain = arrayChain,
                context = context
            )

            val resultRo = predicate(searchRo)

            (resultRo as RequestObject).requestChain
        }

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    Search.Params.PREDICATE to findChain.getCallstack().last()
                ),
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
