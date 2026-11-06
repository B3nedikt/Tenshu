package org.tenshu.core.notifications

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

interface NotificationService : Service {

    override val schema: ServiceSchema
        get() = Schema

    val notifications: TList<Notification>

    /**
     * Synced "seen" markers for inbound actionable requests
     * (`control-central-notifications.md` Phase 4). One [DismissedRequest] per
     * dismissed `PendingRequest.id`; replicated eagerly so a dismissal on one
     * device clears the badge on all of them.
     */
    val dismissedRequests: TList<DismissedRequest>

    companion object {
        const val ID = "tenshu/notification/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:notification-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.NOTIFICATIONS,
                    type = TList.typeOf(Notification.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DISMISSED_REQUESTS,
                    type = TList.typeOf(DismissedRequest.Schema),
                    maintained = true,
                )
            )
        )
    }

    object Relations {
        const val NOTIFICATIONS = "$ID/notifications"
        const val DISMISSED_REQUESTS = "$ID/dismissedRequests"
    }
}
