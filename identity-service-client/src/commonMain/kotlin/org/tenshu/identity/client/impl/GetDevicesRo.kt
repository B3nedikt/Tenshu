package org.tenshu.identity.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.request.roProvider
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.identity.Device
import org.tenshu.identity.GetDevices

internal class GetDevicesRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), GetDevices {

    override val schema: OperationSchema
        get() = GetDevices.Schema

    override fun getDevices(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap(),
                schema = schema
            )
        )

        // Phase 5.3 of `docs/adr/0009-operation-schema-typing.md`: pass parameterised
        // `listType(Device.Schema)` so the ListRo factory binds the element type at
        // construction — replaces the post-create marker mutation.
        val listRo = context.roProvider().createRequestObject<TList<Device>>(
            requestChain = nextChain,
            type = listType(Device.Schema),
            context = context
        )
        return listRo as RequestObject
    }
}
