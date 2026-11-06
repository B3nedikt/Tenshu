package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.Event

internal class EventDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Event {

    override val schema: CompositeSchema
        get() = Event.Schema
}
