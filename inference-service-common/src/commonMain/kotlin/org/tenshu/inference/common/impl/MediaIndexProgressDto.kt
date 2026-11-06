package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.MediaIndexProgress

internal class MediaIndexProgressDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MediaIndexProgress {

    override val schema: CompositeSchema get() = MediaIndexProgress.Schema
}
