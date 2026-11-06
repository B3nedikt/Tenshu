package org.tenshu.sync.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.toTString
import org.tenshu.sync.ReadmitDevice

internal class ReadmitDeviceRo(
    override val requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), ReadmitDevice {

    override val schema: OperationSchema
        get() = ReadmitDevice.Schema

    override fun readmitDevice(deviceId: String): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(ReadmitDevice.Params.DEVICE_ID to deviceId.toTString(context)),
                schema = schema
            )
        )
        // ⚠⚠ `Data.Schema.toType()`, never the concrete return type: naming a concrete type here
        // demands a composite RO factory nothing else registers, and the op then dies over IPC
        // with "No request object factory registered" while in-process tests stay green. See
        // `docs/tech-debt.md` → the `openTarget` case.
        return context.roProvider().createRequestObject<Data>(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        ) as RequestObject
    }
}
