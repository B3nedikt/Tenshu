package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Deletes a [Calendar] **and every [Event] filed under it**.
 *
 * ⚠⚠ The cascade is deliberate and is the whole reason this is not a one-line
 * `deleteData`. An `Event` names its calendar by id, not by reference, so
 * nothing else would collect the orphans — they would stay in the store, sync
 * to every device, and surface in any query that does not filter by a known
 * calendar. Deleting the container has to delete the contents.
 *
 * ⚠ Overrides are cascaded through their events for the same reason.
 */
suspend fun CalendarService.deleteCalendar(calendarId: String): TResult<Data?> =
    findOperation<DeleteCalendar>(DeleteCalendar.ID)
        .deleteCalendar(calendarId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteCalendar : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteCalendar(calendarId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteCalendar"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = { string(id = Params.CALENDAR_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CALENDAR_ID = "$ID/calendarId"
    }
}
