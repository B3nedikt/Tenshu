package org.tenshu.notifications.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.notifications.Notification
import org.tenshu.core.schema.CompositeSchema

internal open class NotificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Notification {

    override val schema: CompositeSchema
        get() = Notification.Schema
}
