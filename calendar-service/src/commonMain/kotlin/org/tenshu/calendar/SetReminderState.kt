package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records that the user dismissed or snoozed one occurrence's reminder (CA-5).
 *
 * ⭐⭐ **One op for both, deliberately.** Dismiss and snooze are the same write
 * to the same row, and splitting them would let a client set one without
 * knowing the other's current value — which is exactly how a dismissal that
 * forgot to clear a snooze comes back to life. One call states the whole state.
 *
 * ⚠ Writing a `Syncable` row rather than posting anything: dismissing on the
 * phone clears the alert on the laptop through the ordinary sync machinery, at
 * no Calendar-side cost (§4.5). ⚠⚠ Note this is **not** the
 * `:notification-service` `DismissedRequest` the proposal's §4.1 gestured at —
 * that type is keyed to inbound *actionable requests* (membership grants,
 * contact invites) and carries no per-occurrence identity, so reusing it would
 * have meant inventing a fake request id per reminder.
 */
suspend fun CalendarService.setReminderState(
    reminderId: String,
    recurrenceId: String,
    dismissed: Boolean = false,
    snoozedUntilEpochMs: Long = 0L,
): TResult<Data?> =
    findOperation<SetReminderState>(SetReminderState.ID)
        .setReminderState(reminderId, recurrenceId, dismissed, snoozedUntilEpochMs)
        .asTaskInteractor<Data?>()
        .execute()

interface SetReminderState : Operation {

    override val schema: OperationSchema get() = Schema

    fun setReminderState(
        reminderId: String,
        recurrenceId: String,
        dismissed: Boolean,
        snoozedUntilEpochMs: Long,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/setReminderState"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.REMINDER_ID)
                string(id = Params.RECURRENCE_ID)
                boolean(id = Params.DISMISSED)
                int(id = Params.SNOOZED_UNTIL_EPOCH_MS)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val REMINDER_ID = "$ID/reminderId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
        const val DISMISSED = "$ID/dismissed"
        const val SNOOZED_UNTIL_EPOCH_MS = "$ID/snoozedUntilEpochMs"
    }
}
