package org.tenshu.notifications.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.AppRemovedNotification
import org.tenshu.core.notifications.DismissPendingRequest
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.notifications.SendNotification
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.notifications.client.impl.DismissPendingRequestRo
import org.tenshu.notifications.client.impl.NotificationServiceRo
import org.tenshu.notifications.client.impl.SendNotificationRo

val notificationServiceClientModule = tModule {

    // The BASE Notification type needs a request-object factory of its own:
    // chain operations whose selector runs against an element of the
    // `notifications` relation (element schema = Notification) materialise an
    // RO of that base type — `notifications.sortByDescending { timestamp }`
    // (TenshuEventRepository.getEvents) records the selector against it. The
    // anonymous object implements the interface so the selector's accessors
    // resolve; the subtype registrations below don't cover the base.
    type(
        interfaceClass = Notification::class,
        dataSchema = Notification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(
                chain,
                ctx,
                Notification.Schema
            ), Notification {
                override val schema: CompositeSchema get() = Notification.Schema
            }
        }
    )

    type(
        interfaceClass = AppRegisteredNotification::class,
        dataSchema = AppRegisteredNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, AppRegisteredNotification.Schema), AppRegisteredNotification {
                override val schema: CompositeSchema get() = AppRegisteredNotification.Schema
            }
        }
    )

    type(
        interfaceClass = AppRemovedNotification::class,
        dataSchema = AppRemovedNotification.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, AppRemovedNotification.Schema), AppRemovedNotification {
                override val schema: CompositeSchema get() = AppRemovedNotification.Schema
            }
        }
    )

    operation(
        interfaceClass = SendNotification::class,
        protocolSchema = SendNotification.Schema,
        roFactory = ::SendNotificationRo
    )

    type(
        interfaceClass = DismissedRequest::class,
        dataSchema = DismissedRequest.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DismissedRequest.Schema), DismissedRequest {
                override val schema: CompositeSchema get() = DismissedRequest.Schema
            }
        }
    )

    operation(
        interfaceClass = DismissPendingRequest::class,
        protocolSchema = DismissPendingRequest.Schema,
        roFactory = ::DismissPendingRequestRo
    )

    type(
        interfaceClass = NotificationService::class,
        dataSchema = NotificationService.Schema,
        roFactory = ::NotificationServiceRo
    )
}
