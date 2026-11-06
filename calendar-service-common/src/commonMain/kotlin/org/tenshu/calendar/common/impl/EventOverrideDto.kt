package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.EventOverride

internal class EventOverrideDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), EventOverride {

    override val schema: CompositeSchema
        get() = EventOverride.Schema
}
