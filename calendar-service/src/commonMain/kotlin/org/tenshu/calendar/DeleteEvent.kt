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
 * Deletes an [Event] and everything keyed to it — its [EventOverride] rows and
 * its [EventSource] sidecar.
 *
 * ⚠ Deleting the **whole series**. Removing a single occurrence of a recurring
 * event is [DeleteOccurrence], which is a different operation on a different
 * noun; conflating them is how a user loses a year of standups by trying to
 * skip one.
 */
suspend fun CalendarService.deleteEvent(eventId: String): TResult<Data?> =
    findOperation<DeleteEvent>(DeleteEvent.ID)
        .deleteEvent(eventId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteEvent : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteEvent(eventId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteEvent"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = { string(id = Params.EVENT_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
    }
}
