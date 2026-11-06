package org.tenshu.notifications.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.notifications.DismissPendingRequest
import org.tenshu.core.notifications.SendNotification
import org.tenshu.notifications.proxy.executor.DismissPendingRequestImplementation
import org.tenshu.notifications.proxy.executor.SendNotificationImplementation

fun notificationServiceProxyImplementationFactory(
    id: String
): OperationImplementation? {
    return when (id) {
        SendNotification.ID -> SendNotificationImplementation()
        DismissPendingRequest.ID -> DismissPendingRequestImplementation()
        else -> null
    }
}
