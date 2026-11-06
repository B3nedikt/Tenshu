package org.tenshu.home.device.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString
import org.tenshu.home.device.UpdateHomeDevice

internal class UpdateHomeDeviceRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), UpdateHomeDevice {

    override fun updateHomeDevice(
        deviceId: String,
        name: String,
        setName: Boolean,
        room: String,
        setRoom: Boolean,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(UpdateHomeDevice.Params.DEVICE_ID, deviceId.toTString(context))
            put(UpdateHomeDevice.Params.NAME, name.toTString(context))
            put(UpdateHomeDevice.Params.SET_NAME, setName.toTBoolean(context))
            put(UpdateHomeDevice.Params.ROOM, room.toTString(context))
            put(UpdateHomeDevice.Params.SET_ROOM, setRoom.toTBoolean(context))
        }
        val nextChain = requestChain.addArray(
            OperationDto(
                params = params,
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
