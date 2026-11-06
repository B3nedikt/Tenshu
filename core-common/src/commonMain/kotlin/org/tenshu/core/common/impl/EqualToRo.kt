package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.TBoolean
import org.tenshu.core.request.roProvider
import org.tenshu.core.common.util.toInteger
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.toType

class EqualToRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), EqualTo {

    override val params: Map<String, Data> get() = emptyMap()

    override fun equalTo(other: Any): TBoolean {
        val otherAsData: Data = when (other) {
            is String -> other.toTString()
            is Long -> other.toInteger()
            is Boolean -> TBooleanDto(other)
            is Data -> other
            else -> error("Unsupported type for equalTo: ${other::class.simpleName}")
        }

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    EqualTo.Params.OTHER to otherAsData
                ),
                schema = schema
            )
        )

        return context.roProvider()
            .createRequestObject(TBoolean.Schema.toType(), nextChain, context)
    }
}
