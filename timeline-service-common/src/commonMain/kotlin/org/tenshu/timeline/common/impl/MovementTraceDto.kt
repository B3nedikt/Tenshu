package org.tenshu.timeline.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.timeline.MovementTrace

internal class MovementTraceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MovementTrace {

    override val schema: CompositeSchema get() = MovementTrace.Schema
}
