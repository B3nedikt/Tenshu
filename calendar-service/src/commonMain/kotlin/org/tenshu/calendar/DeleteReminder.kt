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
 * Removes a [Reminder] and every [ReminderState] filed under it.
 *
 * ⚠ The cascade matters for the same reason [DeleteCalendar]'s does: a state
 * row keys on `reminderId`, and nothing else collects the orphans. Left behind
 * they would sync to every device and silently suppress a *future* reminder if
 * the same offset were ever added again — the key is content-derived, so a
 * re-added reminder inherits the old id and, with it, an old dismissal.
 */
suspend fun CalendarService.deleteReminder(reminderId: String): TResult<Data?> =
    findOperation<DeleteReminder>(DeleteReminder.ID)
        .deleteReminder(reminderId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteReminder : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteReminder(reminderId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteReminder"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = { string(id = Params.REMINDER_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REMINDER_ID = "$ID/reminderId"
    }
}
