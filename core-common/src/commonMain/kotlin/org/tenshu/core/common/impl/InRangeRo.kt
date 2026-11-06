package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.InRange
import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class InRangeRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), InRange {

    override val params: Map<String, Data> get() = emptyMap()

    override fun inRange(startInclusive: Any, endExclusive: Any): TBoolean {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    InRange.Params.START_INCLUSIVE to bound(startInclusive),
                    InRange.Params.END_EXCLUSIVE to bound(endExclusive),
                ),
                schema = schema,
            )
        )

        return context.roProvider()
            .createRequestObject(TBoolean.Schema.toType(), nextChain, context)
    }

    /**
     * ⚠ A raw `Long`/`Int` is coerced to a `TInteger` for the wire; a caller already
     * holding one (a timestamp read off another field) passes straight through. Anything
     * else fails loudly *here* rather than silently evaluating to `false` on the proxy —
     * a mistyped bound is a caller bug, not an empty result.
     */
    private fun bound(value: Any): Data = when (value) {
        is Long -> value.toTInteger(context)
        is Int -> value.toLong().toTInteger(context)
        is Data -> value
        else -> error("Unsupported bound type for inRange: ${value::class.simpleName}")
    }
}
