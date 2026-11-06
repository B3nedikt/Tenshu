package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.Calendar
import org.tenshu.calendar.SaveCalendar
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger

/** Upserts a [Calendar]; the relation maintainer files it under the service. */
internal class SaveCalendarImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val calendarId: TString = params.read(SaveCalendar.Params.CALENDAR_ID)
        val name: TString = params.read(SaveCalendar.Params.NAME)
        val colorArgb: TInteger = params.read(SaveCalendar.Params.COLOR_ARGB)
        val sensitive: TBoolean = params.read(SaveCalendar.Params.SENSITIVE)

        executionContext.saveData(
            Calendar.create(
                context = executionContext.context,
                calendarId = calendarId.get(),
                name = name.get(),
                colorArgb = colorArgb.get().toInt(),
                sensitive = sensitive.get(),
            )
        )
        return null as T
    }
}
