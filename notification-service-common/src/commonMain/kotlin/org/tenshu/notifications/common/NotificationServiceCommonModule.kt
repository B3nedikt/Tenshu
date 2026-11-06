package org.tenshu.notifications.common

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.serviceDataRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.notifications.AppRemovedNotification
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.DismissedRequestDataIdProvider
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationDataIdProvider
import org.tenshu.core.notifications.NotificationService
import org.tenshu.notifications.common.impl.AppRegisteredNotificationDto
import org.tenshu.notifications.common.impl.AppRemovedNotificationDto
import org.tenshu.notifications.common.impl.DismissedRequestDataIdProviderImpl
import org.tenshu.notifications.common.impl.DismissedRequestDto
import org.tenshu.notifications.common.impl.NotificationDataIdProviderImpl
import org.tenshu.notifications.common.impl.NotificationDto

val notificationServiceCommonModule = tModule {

    composite(
        interfaceClass = AppRegisteredNotification::class,
        dataSchema = AppRegisteredNotification.Schema,
        dtoFactory = ::AppRegisteredNotificationDto,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, AppRegisteredNotification.Schema), AppRegisteredNotification {
                override val schema: CompositeSchema get() = AppRegisteredNotification.Schema
            }
        }
    )

    composite(
        interfaceClass = AppRemovedNotification::class,
        dataSchema = AppRemovedNotification.Schema,
        dtoFactory = ::AppRemovedNotificationDto,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, AppRemovedNotification.Schema), AppRemovedNotification {
                override val schema: CompositeSchema get() = AppRemovedNotification.Schema
            }
        }
    )

    service(NotificationService.Schema)
    composite(
        interfaceClass = Notification::class,
        dataSchema = Notification.Schema,
        dtoFactory = ::NotificationDto
    )

    trait(
        dataSchema = Notification.Schema,
        traitSchema = NotificationDataIdProvider.Schema,
        traitFactory = { context, schema ->
            NotificationDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = DismissedRequest::class,
        dataSchema = DismissedRequest.Schema,
        dtoFactory = ::DismissedRequestDto,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DismissedRequest.Schema), DismissedRequest {
                override val schema: CompositeSchema get() = DismissedRequest.Schema
            }
        }
    )

    trait(
        dataSchema = DismissedRequest.Schema,
        traitSchema = DismissedRequestDataIdProvider.Schema,
        traitFactory = { context, schema ->
            DismissedRequestDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
