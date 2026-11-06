package org.tenshu.notifications.client

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.notifications.NotificationService

val TenshuClient.notificationService: NotificationService
    get() = getService(NotificationService.ID)
