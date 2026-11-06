package org.tenshu.file.common.impl.notifications

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.notifications.ChangeLostNotification
import org.tenshu.core.schema.CompositeSchema

internal class ChangeLostNotificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ChangeLostNotification {

    override val schema: CompositeSchema
        get() = ChangeLostNotification.Schema
}
