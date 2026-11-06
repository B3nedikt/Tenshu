package org.tenshu.file.common.impl.notifications

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.TIntegerDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.toTString
import org.tenshu.core.file.notifications.FileAddedNotification
import org.tenshu.core.schema.CompositeSchema

internal class FileAddedNotificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), FileAddedNotification {

    override val schema: CompositeSchema
        get() = FileAddedNotification.Schema

    constructor(
        uuid: String,
        timestamp: Long,
        path: String,
        count: Long = 1L,
    ) : this(
        fieldValues = listOf(
            uuid.toTString(),
            UnixInstantDto(timestamp),
            path.toTString(),
            TIntegerDto(count)
        )
    )
}
