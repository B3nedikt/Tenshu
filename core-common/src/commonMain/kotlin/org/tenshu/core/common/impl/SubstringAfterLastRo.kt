package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.SubstringAfterLast
import org.tenshu.core.TString
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class SubstringAfterLastRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SubstringAfterLast {

    override val params: Map<String, Data> get() = emptyMap()

    override fun substringAfterLast(separator: Any): TString {
        val separatorAsData: Data = when (separator) {
            is String -> separator.toTString()
            is Data -> separator
            else -> error("Unsupported type for substringAfterLast separator: ${separator::class.simpleName}")
        }

        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SubstringAfterLast.Params.SEPARATOR to separatorAsData
                ),
                schema = schema
            )
        )

        return context.roProvider()
            .createRequestObject(TString.Schema.toType(), nextChain, context)
    }
}
