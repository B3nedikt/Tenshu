package org.tenshu.place.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.RecordPlaceRole
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordPlaceRoleRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordPlaceRole {

    override fun recordPlaceRole(role: PlaceRole): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordPlaceRole.Params.ROLE to role
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
