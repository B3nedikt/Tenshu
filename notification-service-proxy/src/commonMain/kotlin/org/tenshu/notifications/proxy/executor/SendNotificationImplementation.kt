package org.tenshu.notifications.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.SendNotification

internal class SendNotificationImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val notification: Notification = params.read(SendNotification.Params.NOTIFICATION)

        // `Notification` is now `Syncable`. Saving via `ExecutionContext.saveData`
        // fires `SyncObserver` → outbound SyncBlock, and the generic `RelationMaintainer`
        // → adds to `NotificationService.Relations.NOTIFICATIONS`. Replaces the prior
        // `addDataToArray` path, which bypassed `SyncObserver` (it goes through
        // `Database.saveData`, not `ExecutionContext.saveData`) so the notification
        // never propagated to other realm devices.
        executionContext.saveData(notification)

        return null as T
    }
}
