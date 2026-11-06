package org.tenshu.storage.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.storage.CapacityResult
import org.tenshu.storage.CheckCapacity

internal class CheckCapacityRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), CheckCapacity {

    override fun checkCapacity(requiredBytes: Long): RequestObject {
        val params = buildMap<String, Data> {
            put(CheckCapacity.Params.REQUIRED_BYTES, requiredBytes.toTInteger(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
                schema = schema,
            ),
        )
        return context.roProvider().createRequestObject<CapacityResult>(
            type = CapacityResult.Schema.toType(),
            requestChain = nextChain,
            context = context,
        ) as RequestObject
    }
}
