package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.Calendar

internal class CalendarDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Calendar {

    override val schema: CompositeSchema
        get() = Calendar.Schema
}
