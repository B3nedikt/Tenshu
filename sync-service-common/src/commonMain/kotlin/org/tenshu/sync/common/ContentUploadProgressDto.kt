package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.ContentUploadProgress

internal class ContentUploadProgressDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ContentUploadProgress {

    override val schema: CompositeSchema
        get() = ContentUploadProgress.Schema
}
