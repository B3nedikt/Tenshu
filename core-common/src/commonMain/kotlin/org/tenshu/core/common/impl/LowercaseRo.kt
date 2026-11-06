package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Lowercase
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class LowercaseRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Lowercase {

    override val params: Map<String, Data> get() = emptyMap()

    override fun lowercase(): TString {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        return context.roProvider()
            .createRequestObject(TString.Schema.toType(), nextChain, context)
    }
}
