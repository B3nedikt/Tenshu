package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.Get
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

internal class GetRo<T : Data>(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), Get<T> {

    override val schema: OperationSchema
        get() = Get.Schema

    override fun get(index: Int): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap<String, Data>(), // TODO: Pass index as param if Get.Schema supports it
                schema = schema
            )
        )
        return context.roProvider().createRequestObject<RequestObject>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
