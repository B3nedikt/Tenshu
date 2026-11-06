package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.ModelDownloadProgress

internal class ModelDownloadProgressDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ModelDownloadProgress {

    override val schema: CompositeSchema get() = ModelDownloadProgress.Schema
}
