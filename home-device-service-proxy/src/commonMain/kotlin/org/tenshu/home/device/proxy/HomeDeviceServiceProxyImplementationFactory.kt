package org.tenshu.home.device.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.home.device.UpdateHomeDevice
import org.tenshu.home.device.proxy.impl.IssueDeviceCommandImplementation
import org.tenshu.home.device.proxy.impl.UpdateHomeDeviceImplementation

/**
 * @param nowEpochMs proxy wall clock — stamps `DeviceCommand.issuedAtEpochMs`
 *        (the hub's 2-minute staleness-window anchor, HD-D7).
 * @param issuerDeviceId this device's realm device id, `null` while unpaired
 *        (recorded as `""` — audit-only).
 */
fun homeDeviceServiceProxyImplementationFactory(
    id: String,
    nowEpochMs: () -> Long,
    issuerDeviceId: () -> String?,
): OperationImplementation? {
    return when (id) {
        IssueDeviceCommand.ID -> IssueDeviceCommandImplementation(nowEpochMs, issuerDeviceId)
        UpdateHomeDevice.ID -> UpdateHomeDeviceImplementation()
        else -> null
    }
}
