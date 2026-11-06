package org.tenshu.notifications.test

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.notifications.common.notificationServiceCommonModule

fun createNotificationServiceCommonTestRegistry(): SchemaRegistry = createCoreTestRegistry().apply {
    registerConfig(notificationServiceCommonModule)
}
