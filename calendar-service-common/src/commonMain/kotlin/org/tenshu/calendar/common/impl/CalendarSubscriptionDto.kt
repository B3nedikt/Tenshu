package org.tenshu.calendar.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.calendar.CalendarSubscription

internal class CalendarSubscriptionDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CalendarSubscription {

    override val schema: CompositeSchema
        get() = CalendarSubscription.Schema
}
