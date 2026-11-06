package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.WorkoutRollupState
import org.tenshu.core.schema.CompositeSchema

internal class WorkoutRollupStateDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), WorkoutRollupState {

    override val schema: CompositeSchema get() = WorkoutRollupState.Schema
}
