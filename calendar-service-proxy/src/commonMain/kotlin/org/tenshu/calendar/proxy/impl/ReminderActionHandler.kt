package org.tenshu.calendar.proxy.impl

import app.tenshu.notify.AlertActionHandler
import org.tenshu.calendar.SetReminderState
import org.tenshu.calendar.common.reminder.CalendarAlerts
import org.tenshu.calendar.eventId
import org.tenshu.calendar.reminderId
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.get
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Turns a *Snooze* or *Dismiss* button press into a `ReminderState` row (CA-5f).
 *
 * ⚠⚠ **This closes a loop that has been open since CA-5a**: the planner has
 * honoured dismissal and snooze from the first slice, and nothing anywhere could
 * write either. A reminder you could not dismiss from the notification came back
 * on the next re-arm — swiping it away wrote nothing.
 *
 * ⚠ Runs in the **proxy** process, because that is what posted the notification
 * (Control owns the alarm). It therefore writes through the op implementation
 * directly rather than through a client: there is no IPC to make, and going out
 * and back would mean waking the app the action exists to avoid.
 *
 * ⚠⚠ **No re-arm call.** Writing a `ReminderState` is exactly what
 * [ReminderRearmObserver] watches, so the next wake is recomputed by the
 * mechanism that already exists — including the snooze's own re-alert. Arming
 * here as well would double-plan on every button press.
 */
internal class ReminderActionHandler(
    private val executionContext: ExecutionContext,
    private val implementationFactory: OperationImplementationFactory,
    private val nowEpochMs: () -> Long,
    private val snoozeMinutes: Int = SNOOZE_MINUTES,
) : AlertActionHandler {

    override suspend fun handle(tapPayload: String, actionId: String): Boolean {
        // ⚠ The payload identifies the *occurrence*. Not ours ⇒ `false`, so
        // another app's handler gets a look (`CompositeAlertActionHandler`).
        val (eventId, recurrenceId) = CalendarAlerts.parseTapPayload(tapPayload) ?: return false

        val dismissed = when (actionId) {
            CalendarAlerts.ACTION_DISMISS -> true
            CalendarAlerts.ACTION_SNOOZE -> false
            // ⚠ An unrecognised action on a payload we *do* own is still ours —
            // returning `false` would hand it to the next handler, which would
            // understand it even less. Swallowed, and the alert stays put.
            else -> return true
        }
        val snoozedUntil = if (dismissed) 0L else nowEpochMs() + snoozeMinutes * 60_000L

        // ⚠⚠ **Every reminder on the occurrence, not just the one that fired.**
        // An event can carry "10 minutes before" *and* "1 hour before", and the
        // notification names the occurrence, not which of them rang. Dismissing
        // means "I have seen this"; writing only one would let the other arrive
        // minutes after the user said so.
        //
        // ⚠ No reminders left (deleted between the arm and the tap) ⇒ nothing to
        // write, and still `true`: the payload was ours and the alert is spent.
        reminderIdsFor(eventId).forEach { reminderId ->
            write(reminderId, recurrenceId, dismissed, snoozedUntil)
        }
        return true
    }

    private fun reminderIdsFor(eventId: String): List<String> =
        executionContext.allReminders()
            .filter { it.eventId.get() == eventId }
            .map { it.reminderId.get() }

    private fun write(
        reminderId: String,
        recurrenceId: String,
        dismissed: Boolean,
        snoozedUntilEpochMs: Long,
    ) {
        val implementation =
            implementationFactory(SetReminderState.ID) as? SyncOperationImplementation ?: return
        val context = executionContext.context
        implementation.execute<Data?>(
            executionContext = executionContext,
            operationContext = null,
            implementationFactory = implementationFactory,
            caller = null,
            params = mapOf(
                SetReminderState.Params.REMINDER_ID to reminderId.toTString(context),
                SetReminderState.Params.RECURRENCE_ID to recurrenceId.toTString(context),
                SetReminderState.Params.DISMISSED to dismissed.toTBoolean(context),
                SetReminderState.Params.SNOOZED_UNTIL_EPOCH_MS to snoozedUntilEpochMs.toTInteger(context),
            ),
        )
    }

    companion object {
        /**
         * ⚠ Ten minutes, fixed. Android shows at most three actions and routinely
         * collapses the third, so a duration picker would cost the *Dismiss*
         * button — and a snooze you cannot dismiss is worse than a snooze you
         * cannot tune. The stock default everywhere.
         */
        const val SNOOZE_MINUTES = 10
    }
}
