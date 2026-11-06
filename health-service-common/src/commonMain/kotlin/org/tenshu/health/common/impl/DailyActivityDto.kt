package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.schema.CompositeSchema

internal class DailyActivityDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), DailyActivity {

    override val schema: CompositeSchema get() = DailyActivity.Schema
}
