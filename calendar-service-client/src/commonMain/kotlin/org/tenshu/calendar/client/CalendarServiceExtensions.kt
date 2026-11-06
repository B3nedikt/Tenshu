package org.tenshu.calendar.client

import org.tenshu.calendar.CalendarService
import org.tenshu.core.common.TenshuClient

val TenshuClient.calendarService: CalendarService
    get() = getService(CalendarService.ID)
