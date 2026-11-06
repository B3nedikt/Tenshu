package org.tenshu.sync.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.getPlatformHardwareName
import org.tenshu.core.entity.DataId
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware

/**
 * Publishes **this device's** hardware model as its [DeviceHardware] row — the immutable half of
 * the roster label, beside the user-chosen `Device.name` (`tech-debt.md` *"Device Naming
 * Conflict"*).
 *
 * The device is the row's only writer by construction (see [DeviceHardware]'s KDoc for why that is
 * the reason it is not a `Device` field).
 *
 * ⚠ **Write-if-changed**, as [DeviceSchemaVersionPublisher]: the row is `Syncable(Eager)`, so an
 * unconditional save would emit a block per launch per device for a value that never changes.
 *
 * ⚠⚠ **Gated on this device's own `Device` row being present locally**, which is stricter than
 * realm membership and deliberately so: the row exists only once the device was admitted (or
 * self-healed through `reconcileOwnDeviceRow`'s tombstone guard), so a revoked or never-paired
 * device publishes nothing. Called after that self-heal, at init and on the unlock edge.
 */
internal class DeviceHardwarePublisher(
    private val executionContext: ExecutionContext,
    private val hardwareName: () -> String = ::getPlatformHardwareName,
) {

    /** @return true when a row was written (new or changed). */
    fun publishIfChanged(deviceId: String): Boolean {
        if (deviceId.isEmpty()) return false
        val context = executionContext.context
        val database = executionContext.database

        val ownDeviceRow = DataId.StringDataId.create(context, Device.ID, deviceId)
        if (database.getData(ownDeviceRow) == null) return false

        val mine = hardwareName().trim()
        if (mine.isEmpty()) return false

        val rowId = DataId.StringDataId.create(context, DeviceHardware.ID, deviceId)
        val recorded = (database.getData(rowId) as? DeviceHardware)?.hardwareName?.get()
        if (recorded == mine) return false

        executionContext.saveData(
            DeviceHardware.create(context = context, deviceId = deviceId, hardwareName = mine)
        )
        return true
    }
}
