package org.tenshu.core.common.impl

import org.tenshu.core.Contains
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.TContext
import org.tenshu.core.toTBoolean
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class ContainsRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Contains {

    override val params: Map<String, Data> get() = emptyMap()

    override fun contains(other: Any, ignoreCase: Boolean): TBoolean {
        // `contains` is text-only, so the operand is a string. A raw String is
        // coerced to a TString for the wire; a caller that already holds a
        // TString (e.g. another field value) passes straight through.
        val otherAsData: Data = when (other) {
            is String -> other.toTString()
            is Data -> other
            else -> error("Unsupported type for contains: ${other::class.simpleName}")
        }

        val nextChain = requestChain.addArray(
            OperationDto(
                // IGNORE_CASE rides only when set — the common (case-sensitive)
                // case doesn't carry the param on the wire.
                params = buildMap {
                    put(Contains.Params.OTHER, otherAsData)
                    if (ignoreCase) put(Contains.Params.IGNORE_CASE, true.toTBoolean(context))
                },
                schema = schema
            )
        )

        return context.roProvider()
            .createRequestObject(TBoolean.Schema.toType(), nextChain, context)
    }
}
