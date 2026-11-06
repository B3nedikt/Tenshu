package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.Reminder
import org.tenshu.calendar.SaveReminder
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger

/**
 * Upserts a [Reminder].
 *
 * ⚠ No validation that the event exists. A reminder for a deleted event simply
 * never resolves an occurrence and so never fires — and requiring the event to
 * be present would make ordering matter between two writes that sync
 * independently, which is a race this data model has no reason to invent.
 */
internal class SaveReminderImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val eventId: TString = params.read(SaveReminder.Params.EVENT_ID)
        val minutesBefore: TInteger = params.read(SaveReminder.Params.MINUTES_BEFORE)

        executionContext.saveData(
            Reminder.create(
                context = executionContext.context,
                eventId = eventId.get(),
                minutesBefore = minutesBefore.get().toInt(),
            )
        )
        return null as T
    }
}
