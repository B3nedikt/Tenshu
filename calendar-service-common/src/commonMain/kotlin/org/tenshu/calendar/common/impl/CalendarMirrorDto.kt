package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.CalendarMirror

internal class CalendarMirrorDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CalendarMirror {

    override val schema: CompositeSchema
        get() = CalendarMirror.Schema
}
