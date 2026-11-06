package org.tenshu.notifications.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.toTString
import org.tenshu.core.notifications.AppRegisteredNotification
import org.tenshu.core.schema.CompositeSchema

internal class AppRegisteredNotificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), AppRegisteredNotification {

    override val schema: CompositeSchema
        get() = AppRegisteredNotification.Schema

    constructor(
        uuid: String,
        timestamp: Long,
        appName: String,
    ) : this(
        fieldValues = listOf(
            uuid.toTString(),
            UnixInstantDto(timestamp),
            appName.toTString()
        )
    )
}
