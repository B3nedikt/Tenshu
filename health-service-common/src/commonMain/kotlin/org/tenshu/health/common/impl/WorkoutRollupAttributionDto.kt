package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.WorkoutRollupAttribution
import org.tenshu.core.schema.CompositeSchema

internal class WorkoutRollupAttributionDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), WorkoutRollupAttribution {

    override val schema: CompositeSchema get() = WorkoutRollupAttribution.Schema
}
