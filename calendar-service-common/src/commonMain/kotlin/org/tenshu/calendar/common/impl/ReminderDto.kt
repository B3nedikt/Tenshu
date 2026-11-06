package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.Reminder

internal class ReminderDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Reminder {

    override val schema: CompositeSchema
        get() = Reminder.Schema
}
