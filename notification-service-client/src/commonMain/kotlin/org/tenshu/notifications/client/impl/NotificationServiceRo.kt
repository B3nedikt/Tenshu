package org.tenshu.notifications.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.request.RequestChain

internal class NotificationServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : NotificationService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val notifications: TList<Notification>
        get() = serviceRelation(NotificationService.Relations.NOTIFICATIONS, Notification.Schema)

    override val dismissedRequests: TList<DismissedRequest>
        get() = serviceRelation(NotificationService.Relations.DISMISSED_REQUESTS, DismissedRequest.Schema)
}
