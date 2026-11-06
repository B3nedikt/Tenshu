package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.Punctual
import org.tenshu.core.trait.TerminalOperation

/**
 * Shows whatever reminders are due, and arms the next wake (CA-5c).
 *
 * ⚠⚠ **Parameter-less, and it has to be.** A `ScheduleItem` carries an `opId`
 * and nothing else — no serialised arguments — so a reminder's identity cannot
 * ride in the scheduled item. The op finds its own work when it lands, which is
 * exactly the `EvaluateDueRoutines` shape and the reason `ReminderPlanner` takes
 * a *window* rather than one event.
 *
 * ⚠ [Punctual]: a reminder batched into the scheduler's next convenient wake has
 * not been slightly late, it has failed. ⚠⚠ And [HeadlessSafe] is **not
 * optional** — `ScheduleImplementation` rejects a punctual op that lacks it at
 * *registration* time, so omitting it would fail loudly at arming rather than
 * quietly at wake.
 *
 * ⚠ Also invoked directly (not only by the scheduler) whenever the calendar
 * changes, so the armed wake follows an edit — including an edit that arrived
 * from another device.
 */
suspend fun CalendarService.deliverDueReminders(): TResult<Data?> =
    findOperation<DeliverDueReminders>(DeliverDueReminders.ID)
        .deliverDueReminders()
        .asTaskInteractor<Data?>()
        .execute()

interface DeliverDueReminders : Operation {

    override val schema: OperationSchema get() = Schema

    fun deliverDueReminders(): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deliverDueReminders"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            traits = listOf(
                IoOperation.Schema,
                TerminalOperation.Schema,
                HeadlessSafe.Schema,
                Punctual.Schema,
            ),
        )
    }
}
