package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.Workout
import org.tenshu.core.schema.CompositeSchema

internal class WorkoutDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Workout {

    override val schema: CompositeSchema get() = Workout.Schema
}
