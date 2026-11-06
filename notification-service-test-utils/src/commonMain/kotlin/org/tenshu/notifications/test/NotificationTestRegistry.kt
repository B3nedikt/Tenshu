package org.tenshu.notifications.test

import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.notifications.client.notificationServiceClientModule

fun createNotificationTestRegistry(): SchemaRegistry =
    createNotificationServiceCommonTestRegistry().apply {
        registerConfig(notificationServiceClientModule)
    }
