package org.tenshu.file.common.impl.notifications

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.toTString
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.schema.CompositeSchema

internal class FolderAddedNotificationDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), FolderAddedNotification {

    override val schema: CompositeSchema
        get() = FolderAddedNotification.Schema

    constructor(
        uuid: String,
        timestamp: Long,
        path: String,
    ) : this(
        fieldValues = listOf(
            uuid.toTString(),
            UnixInstantDto(timestamp),
            path.toTString()
        )
    )
}
