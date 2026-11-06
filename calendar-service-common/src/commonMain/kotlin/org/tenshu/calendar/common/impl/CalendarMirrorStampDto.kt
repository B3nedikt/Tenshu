package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.CalendarMirrorStamp

internal class CalendarMirrorStampDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CalendarMirrorStamp {

    override val schema: CompositeSchema
        get() = CalendarMirrorStamp.Schema
}
