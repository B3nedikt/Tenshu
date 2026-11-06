package org.tenshu.sync.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.ReadContentRange

internal class ReadContentRangeRo(
    override val requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), ReadContentRange {

    override val schema: OperationSchema
        get() = ReadContentRange.Schema

    override fun readContentRange(handle: String, offset: Long, length: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    ReadContentRange.Params.HANDLE to handle.toTString(context),
                    ReadContentRange.Params.OFFSET to offset.toTInteger(context),
                    ReadContentRange.Params.LENGTH to length.toTInteger(context),
                ),
                schema = schema
            )
        )
        @Suppress("UNCHECKED_CAST")
        return context.roProvider().createRequestObject<TByteArray>(
            type = TByteArray.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
