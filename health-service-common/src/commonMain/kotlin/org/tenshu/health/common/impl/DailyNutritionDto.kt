package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.schema.CompositeSchema

internal class DailyNutritionDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DailyNutrition {

    override val schema: CompositeSchema get() = DailyNutrition.Schema
}
