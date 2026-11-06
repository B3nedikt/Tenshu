package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.DeleteCalendar
import org.tenshu.calendar.Event
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId

/**
 * Deletes the calendar **and cascades to its events**.
 *
 * ⚠⚠ The cascade is the point. `Event.calendarId` is an id, not a reference, so
 * nothing collects orphans: without this they would linger in the store, sync
 * to every device, and appear in any query not filtered by a known calendar.
 * Each event is removed through [deleteEventCascade] so its overrides and
 * source sidecar go with it.
 */
internal class DeleteCalendarImplementation(
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
        val calendarId: TString = params.read(DeleteCalendar.Params.CALENDAR_ID)

        executionContext
            .allEvents()
            .filter { it.calendarId.get() == calendarId.get() }
            .forEach { deleteEventCascade(executionContext, it.eventId.get(), tombstoneEmitter) }

        // ⚠ And any mirror filling it (CX-1b): a mirror left pointing at a deleted calendar would
        // re-create it on the bound device's next refresh — the calendar the user just deleted,
        // back again, with no way to tell why.
        executionContext.allMirrors()
            .filter { it.calendarId.get() == calendarId.get() }
            .forEach { executionContext.deleteMirrorRow(it.mirrorId.get(), tombstoneEmitter) }

        executionContext.deleteRow(Calendar.ID, calendarId.get(), tombstoneEmitter)
        return null as T
    }
}

/**
 * Every locally-known [Event], read off the service relation.
 *
 * ⚠ A full scan. Correct at calendar scale (thousands of rows) and the same
 * shape the chain DSL's predicate filter would take anyway, since `Event`
 * carries its calendar as an id rather than a reference. If this ever matters
 * it wants an index, not a different traversal.
 */
internal fun ExecutionContext.allEvents(): List<Event> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.EVENTS),
    )
    return resolveList(arrayRef)?.filterIsInstance<Event>()?.toList().orEmpty()
}
