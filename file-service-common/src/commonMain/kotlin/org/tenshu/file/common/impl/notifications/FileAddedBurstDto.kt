package org.tenshu.file.common.impl.notifications

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.file.notifications.FileAddedBurst
import org.tenshu.core.schema.CompositeSchema

internal class FileAddedBurstDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), FileAddedBurst {

    override val schema: CompositeSchema
        get() = FileAddedBurst.Schema
}
