package org.tenshu.calendar.proxy.impl

import app.tenshu.notify.NotificationPresenter
import app.tenshu.notify.SchedulingNotificationPresenter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import org.tenshu.calendar.common.reminder.CalendarAlerts
import org.tenshu.calendar.common.reminder.ReminderDrainCalculator
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

/**
 * The wake-up: show what is due, arm the next one (CA-5c).
 *
 * ⚠⚠ **Both halves, every time, and they are not independent.** Delivering
 * without re-arming means exactly one reminder ever fires; re-arming without
 * delivering means none do. That is why `ReminderDrainCalculator` answers both
 * questions from one pass rather than being called twice with two windows.
 *
 * ⚠ The plan window opens **before now** by the catch-up allowance, so a wake
 * that the OS delivered late still finds the reminder it was armed for. It
 * closes a week out — far enough to always find a next wake if one exists, near
 * enough that a re-plan on every calendar edit stays cheap.
 */
internal class DeliverDueRemindersImplementation(
    private val presenter: NotificationPresenter,
    private val nowEpochMs: () -> Long,
    private val zoneId: () -> String,
    private val catchUpWindow: Duration = ReminderDrainCalculator.DEFAULT_CATCH_UP,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val now = Instant.fromEpochMilliseconds(nowEpochMs())

        val planned = executionContext.planReminders(
            from = now - catchUpWindow,
            until = now + PLAN_HORIZON,
            zoneId = zoneId(),
        )

        val drain = ReminderDrainCalculator.drain(
            planned = planned,
            now = now,
            catchUpWindow = catchUpWindow,
        )

        // ⚠⚠ **On a platform that pre-arms, hand over the whole forward set and
        // stop there** (`calendar-app.md` §4.6). Once the OS holds a trigger the
        // reminder fires whether or not this process is alive, so an alarm of our
        // own would be a second, worse copy of a schedule the OS already has —
        // and the two would drift the moment one of them was throttled.
        if (presenter is SchedulingNotificationPresenter) {
            presenter.replaceScheduled(
                planned.filter { it.fireAt > now }.map(CalendarAlerts::scheduledAlert)
            )
            return null as T
        }

        // ⚠ Posting first, arming second. A crash between them loses one alert;
        // the other order would lose the whole future schedule, and the next
        // calendar edit re-arms anyway.
        if (presenter.canPost()) {
            drain.deliver.forEach { due ->
                presenter.post(CalendarAlerts.scheduledAlert(due).alert)
            }
        }

        ReminderArmer(executionContext, implementationFactory, nowEpochMs)(
            drain.nextWakeAt?.toEpochMilliseconds()
        )

        return null as T
    }

    private companion object {
        /**
         * ⚠ A week. Long enough that "there is nothing to wake for" is almost
         * always true rather than an artefact of a short horizon, short enough
         * that re-planning on every calendar edit does not walk a year of
         * recurrences.
         */
        val PLAN_HORIZON = 7.days
    }
}
