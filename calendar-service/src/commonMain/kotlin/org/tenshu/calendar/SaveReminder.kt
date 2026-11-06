package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Adds "remind me [minutesBefore] minutes before" to an [Event] (CA-5).
 *
 * ⭐ Idempotent by construction: the row's key is derived from
 * `(eventId, minutesBefore)`, so adding the same reminder twice — from two
 * devices, or from Mia and the editor — converges on one row rather than
 * alerting twice.
 *
 * ⚠ [minutesBefore] is signed; negative means *after* the start, which is how
 * an all-day reminder ("on the day at 9 a.m.") is expressed. See [Reminder].
 */
suspend fun CalendarService.saveReminder(
    eventId: String,
    minutesBefore: Int,
): TResult<Data?> =
    findOperation<SaveReminder>(SaveReminder.ID)
        .saveReminder(eventId, minutesBefore)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveReminder : Operation {

    override val schema: OperationSchema get() = Schema

    fun saveReminder(eventId: String, minutesBefore: Int): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveReminder"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.EVENT_ID)
                int(id = Params.MINUTES_BEFORE)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
        const val MINUTES_BEFORE = "$ID/minutesBefore"
    }
}
