package org.tenshu.file.client.impl

import org.tenshu.core.TByteArray
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.file.File
import org.tenshu.core.file.GetData
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.toType

internal class GetDataRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetData {

    override fun getData(file: File): TByteArray {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(GetData.Params.FILE to file),
                schema = schema
            )
        )
        return context.roProvider()
            .createRequestObject(TByteArray.Schema.toType(), nextChain, context)
    }
}
