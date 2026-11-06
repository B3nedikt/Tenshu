package org.tenshu.notifications.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.notifications.proxy.initNotificationService
import org.tenshu.notifications.proxy.notificationServiceProxyImplementationFactory

fun createNotificationProxy(coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) =
    TestProxy(
        schemaRegistry = createNotificationTestRegistry(),
        coroutineScope = coroutineScope,
        implementationFactoryOverride = { id, _ ->
            notificationServiceProxyImplementationFactory(id)
        }
    ).also {
        // Mirror production wiring: `initNotificationService` registers the
        // DataObserver that maintains `NotificationService.Relations.NOTIFICATIONS`
        // on every saved `Notification`. Without it, `SendNotification`'s
        // `saveData` lands the entity in the DB but never in the relation,
        // so notification streams return nothing.
        it.executionContext.initNotificationService()
    }
