package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.IsA
import org.tenshu.core.TBoolean
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class IsARo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), IsA {

    override val params: Map<String, Data> get() = emptyMap()

    override fun isA(typeId: String): TBoolean {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    IsA.Params.TYPE_ID to typeId.toTString()
                ),
                schema = schema
            )
        )

        return context.roProvider()
            .createRequestObject(TBoolean.Schema.toType(), nextChain, context)
    }
}
