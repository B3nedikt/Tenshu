package org.tenshu.routine.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.routine.RoutineFiringMarker

internal class RoutineFiringMarkerDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RoutineFiringMarker {

    override val schema: CompositeSchema
        get() = RoutineFiringMarker.Schema
}
