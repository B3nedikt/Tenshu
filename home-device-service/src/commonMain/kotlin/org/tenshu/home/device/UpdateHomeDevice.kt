package org.tenshu.home.device

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Updates the user-editable fields of a [HomeDevice] row — [HomeDevice.name]
 * and/or [HomeDevice.room] — on the caller's proxy (read-modify-write
 * preserving every hub-authored field); sync propagates the row and row-level
 * LWW resolves concurrent writers.
 *
 * [setName]/[setRoom] make the two edits independently addressable (an empty
 * string is a legitimate *room* value — "unassigned" — so "" cannot double as
 * "leave unchanged"). An empty [name] with `setName = true` is rejected
 * proxy-side: a device must keep a renderable name.
 */
suspend fun HomeDeviceService.updateHomeDevice(
    deviceId: String,
    name: String = "",
    setName: Boolean = false,
    room: String = "",
    setRoom: Boolean = false,
): TResult<Data?> =
    findOperation<UpdateHomeDevice>(UpdateHomeDevice.ID)
        .updateHomeDevice(deviceId, name, setName, room, setRoom)
        .asTaskInteractor<Data?>()
        .execute()

interface UpdateHomeDevice : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun updateHomeDevice(
        deviceId: String,
        name: String,
        setName: Boolean,
        room: String,
        setRoom: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/home/device/service/updateHomeDevice"

        val Schema = operation(
            id = ID,
            receiver = HomeDeviceService.Schema.toType(),
            params = {
                string(id = Params.DEVICE_ID)
                string(id = Params.NAME)
                boolean(id = Params.SET_NAME)
                string(id = Params.ROOM)
                boolean(id = Params.SET_ROOM)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val DEVICE_ID = "$ID/deviceId"
        const val NAME = "$ID/name"
        const val SET_NAME = "$ID/setName"
        const val ROOM = "$ID/room"
        const val SET_ROOM = "$ID/setRoom"
    }
}
