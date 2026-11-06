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
 * Removes **one occurrence** of a recurring [Event] — "skip this Tuesday" —
 * by adding [recurrenceId]'s date to the series' `exceptionDates`.
 *
 * ⚠⚠ Not a deletion of anything: no row is destroyed, the *rule's output* is
 * narrowed. That is what makes it reversible and what lets the series keep its
 * identity, its history and its remaining occurrences. Deleting the series is
 * [DeleteEvent], and the two must stay distinct — see that op's KDoc.
 *
 * ⚠ [recurrenceId] is the occurrence's **original** start
 * (`formatRecurrenceId`), the same key an [EventOverride] uses. Passing a
 * *moved* occurrence's new start would except a date the rule never produced,
 * silently doing nothing.
 *
 * ⚠ Also drops any [EventOverride] for that occurrence: an override describes
 * an occurrence that no longer exists, and leaving it behind would resurrect
 * the occurrence if the exception were ever lifted.
 */
suspend fun CalendarService.deleteOccurrence(
    eventId: String,
    recurrenceId: String,
): TResult<Data?> =
    findOperation<DeleteOccurrence>(DeleteOccurrence.ID)
        .deleteOccurrence(eventId, recurrenceId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteOccurrence : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteOccurrence(eventId: String, recurrenceId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteOccurrence"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.EVENT_ID)
                string(id = Params.RECURRENCE_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
    }
}
