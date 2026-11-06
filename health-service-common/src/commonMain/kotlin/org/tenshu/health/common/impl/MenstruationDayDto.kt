package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.schema.CompositeSchema

internal class MenstruationDayDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MenstruationDay {

    override val schema: CompositeSchema get() = MenstruationDay.Schema
}
