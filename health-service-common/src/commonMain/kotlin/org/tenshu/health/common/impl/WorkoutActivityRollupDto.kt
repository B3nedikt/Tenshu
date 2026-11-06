package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.WorkoutActivityRollup
import org.tenshu.core.schema.CompositeSchema

internal class WorkoutActivityRollupDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), WorkoutActivityRollup {

    override val schema: CompositeSchema get() = WorkoutActivityRollup.Schema
}
