package org.tenshu.routine.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.routine.RoutineRun

internal class RoutineRunDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RoutineRun {

    override val schema: CompositeSchema
        get() = RoutineRun.Schema
}
