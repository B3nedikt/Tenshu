package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.OneOf
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class OneOfRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), OneOf {

    override val params: Map<String, Data> get() = emptyMap()

    override fun oneOf(candidates: List<Any>): TBoolean {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    OneOf.Params.CANDIDATES to candidates.map(::candidate).toTList(context)
                ),
                schema = schema,
            )
        )

        return context.roProvider()
            .createRequestObject(TBoolean.Schema.toType(), nextChain, context)
    }

    /**
     * ⚠ Coerced per candidate, and anything unsupported fails loudly *here* rather than riding to
     * the proxy to evaluate as `false` — a mistyped candidate is a caller bug, not an empty result.
     * The same call [EqualToRo] makes for its single `other`, for the same reason.
     */
    private fun candidate(value: Any): Data = when (value) {
        is String -> value.toTString()
        is Long -> value.toInteger()
        is Int -> value.toLong().toInteger()
        is Boolean -> TBooleanDto(value)
        is Data -> value
        else -> error("Unsupported candidate type for oneOf: ${value::class.simpleName}")
    }
}
