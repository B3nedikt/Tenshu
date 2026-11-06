package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.DeleteOccurrence
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId

/**
 * Skips one occurrence by adding its date to the series' `exceptionDates`.
 *
 * ⚠ The stored entry is the occurrence's **date**, not its full recurrence id:
 * the expander excepts by date (a rule produces at most one occurrence per day
 * for every frequency it supports), and storing the time as well would make a
 * moved-then-skipped occurrence fail to match.
 *
 * ⚠⚠ Idempotent by construction — the date is added to a set, so skipping an
 * already-skipped occurrence is a no-op rather than a duplicate entry that
 * would consume a second `COUNT` slot.
 */
internal class DeleteOccurrenceImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val eventId: TString = params.read(DeleteOccurrence.Params.EVENT_ID)
        val recurrenceId: TString = params.read(DeleteOccurrence.Params.RECURRENCE_ID)

        val event = executionContext.eventById(eventId.get())
            ?: throw TenshuException(
                GenericError.create(executionContext.context, "No such event: ${eventId.get()}")
            )

        val date = recurrenceId.get().substringBefore('T')
        val existing = event.exceptionDates.get()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val merged = (existing + date).distinct().sorted().joinToString(",")

        executionContext.saveData(
            Event.create(
                context = executionContext.context,
                eventId = event.eventId.get(),
                calendarId = event.calendarId.get(),
                title = event.title.get(),
                startLocal = event.startLocal.get(),
                endLocal = event.endLocal.get(),
                timeZoneId = event.timeZoneId.get(),
                allDay = event.allDay.get(),
                location = event.location.get(),
                notes = event.notes.get(),
                recurrenceRule = event.recurrenceRule.get(),
                exceptionDates = merged,
            )
        )

        // ⚠ An override for a now-excepted occurrence describes something that
        // no longer exists, and would resurrect it if the exception were lifted.
        // ⚠ Tombstoned even when this device holds none: a peer may have moved the occurrence before
        // this device pulled the move, and its override must not outlive the skip.
        executionContext.deleteRow(
            EventOverride.ID,
            EventOverride.keyOf(eventId.get(), recurrenceId.get()),
            tombstoneEmitter,
        )
        return null as T
    }
}
