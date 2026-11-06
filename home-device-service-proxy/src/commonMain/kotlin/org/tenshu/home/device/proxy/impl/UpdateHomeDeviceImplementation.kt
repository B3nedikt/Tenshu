package org.tenshu.home.device.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.GenericError
import org.tenshu.core.entity.DataId
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.UpdateHomeDevice

/**
 * Executes on the CALLER's proxy: read-modify-write of the [HomeDevice] row —
 * updates the user-editable fields (`name`/`room`) while preserving every
 * hub-authored field byte-for-byte, then saves; the row syncs and row-level
 * LWW resolves concurrent writers (the hub only re-publishes inventory when
 * a hub-authored field changed, so the two authors rarely collide — HD-D1).
 */
internal class UpdateHomeDeviceImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val deviceId: TString = params.read(UpdateHomeDevice.Params.DEVICE_ID)
        val name: TString = params.read(UpdateHomeDevice.Params.NAME)
        val setName: TBoolean = params.read(UpdateHomeDevice.Params.SET_NAME)
        val room: TString = params.read(UpdateHomeDevice.Params.ROOM)
        val setRoom: TBoolean = params.read(UpdateHomeDevice.Params.SET_ROOM)

        val context = executionContext.context
        val existing = executionContext.database.getData(
            DataId.StringDataId.create(
                context = context,
                type = HomeDevice.ID,
                keyValue = deviceId.get(),
            )
        ) as? HomeDevice
            ?: throw TenshuException(
                GenericError.create(context, "Unknown device: ${deviceId.get()}")
            )

        if (setName.get() && name.get().isBlank()) {
            throw TenshuException(
                GenericError.create(context, "A device name must not be blank")
            )
        }
        if (!setName.get() && !setRoom.get()) {
            // Nothing to change — a no-op write would only burn a sync block.
            return null as T
        }

        val updated = HomeDevice.create(
            context = context,
            deviceId = existing.deviceId.get(),
            bridgeId = existing.bridgeId.get(),
            kind = existing.kind.get(),
            manufacturer = existing.manufacturer.get(),
            model = existing.model.get(),
            capabilityIds = existing.capabilityIds.get(),
            name = if (setName.get()) name.get().trim() else existing.name.get(),
            room = if (setRoom.get()) room.get().trim() else existing.room.get(),
            joinedAtEpochMs = existing.joinedAtEpochMs.get(),
            extras = existing.extras,
        )

        executionContext.saveData(updated)

        return null as T
    }
}
