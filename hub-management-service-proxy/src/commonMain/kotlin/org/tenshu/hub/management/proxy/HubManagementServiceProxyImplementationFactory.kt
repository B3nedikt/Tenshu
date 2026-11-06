package org.tenshu.hub.management.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.hub.management.IssueHubCommand
import org.tenshu.hub.management.proxy.impl.IssueHubCommandImplementation

/**
 * @param nowEpochMs proxy wall clock — stamps `HubCommand.issuedAtEpochMs`
 *        (the hub's staleness-window anchor).
 * @param issuerDeviceId this device's realm device id, `null` while unpaired
 *        (recorded as `""` — audit-only).
 */
fun hubManagementServiceProxyImplementationFactory(
    id: String,
    nowEpochMs: () -> Long,
    issuerDeviceId: () -> String?,
): OperationImplementation? {
    return when (id) {
        IssueHubCommand.ID -> IssueHubCommandImplementation(nowEpochMs, issuerDeviceId)
        else -> null
    }
}
