package org.tenshu.timeline.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.timeline.MomentSource

internal class MomentSourceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MomentSource {

    override val schema: CompositeSchema get() = MomentSource.Schema
}
