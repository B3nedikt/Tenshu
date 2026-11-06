package org.tenshu.routine.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.routine.Routine

internal class RoutineDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Routine {

    override val schema: CompositeSchema
        get() = Routine.Schema
}
