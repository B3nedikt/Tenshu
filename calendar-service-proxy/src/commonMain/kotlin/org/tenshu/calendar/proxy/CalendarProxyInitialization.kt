package org.tenshu.calendar.proxy

import app.tenshu.notify.AlertActionHandler
import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.calendar.proxy.impl.ReminderActionHandler
import org.tenshu.calendar.proxy.impl.ReminderRearmObserver
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.relation.installRelationMaintainer

/**
 * Installs the calendar service's proxy-side observers.
 *
 * ⚠ All four relations are declared `maintained = true` on `CalendarService`,
 * so the single generic `RelationMaintainer` serves them all and Calendar
 * registers no observer of its own. Without it a row arriving **via sync**
 * would land in the local store but stay invisible to `CalendarService.events`
 * — present but unfindable, which is the confusing half of the failure.
 */
fun ExecutionContext.initCalendarService(
    implementationFactory: OperationImplementationFactory? = null,
    /**
     * Receives the handler for *Snooze* / *Dismiss* buttons, once there is an
     * `ExecutionContext` to write through (CA-5f).
     *
     * ⚠⚠ A holder rather than a return value or a Koin binding. The handler needs
     * this context, which does not exist when the DI graph is built, and the
     * consumer is a framework-instantiated `BroadcastReceiver` that reads from
     * Koin lazily — exactly the shape `ScheduledOpDispatcherHolder` already has,
     * for the same reason.
     */
    actionHandler: CalendarReminderActionHolder? = null,
    /** ⚠ Only used to date a snooze; unused when [actionHandler] is null. */
    nowEpochMs: () -> Long = { 0L },
    /**
     * Plan reminders once at session start, not only on the first calendar change. ⚠ Only for a host whose
     * presenter hands the plan to the OS rather than posting (desktop); see `ReminderRearmObserver`.
     */
    planRemindersOnStart: Boolean = false,
) {
    installRelationMaintainer()

    // ⚠ Only where a factory is supplied. A host without one (a read-only
    // fixture, a platform with no scheduler) still gets the maintainer, which is
    // what makes rows *visible*; arming is the part that needs to reach the
    // `Schedule` op.
    implementationFactory?.let { factory ->
        addDataObserver(
            ReminderRearmObserver(scope = sessionScope, planOnStart = planRemindersOnStart) {
                (factory(DeliverDueReminders.ID) as? SyncOperationImplementation)?.execute<Data?>(
                    executionContext = this,
                    operationContext = null,
                    implementationFactory = factory,
                    caller = null,
                    params = null,
                )
            }
        )

        actionHandler?.handler = ReminderActionHandler(
            executionContext = this,
            implementationFactory = factory,
            nowEpochMs = nowEpochMs,
        )
    }
}

/**
 * Bridges an OS notification button back to the resident proxy (CA-5f).
 *
 * ⚠ Mirrors `ScheduledOpDispatcherHolder`: the value cannot exist when Koin is
 * assembled, so the platform init hook fills it and the receiver reads it lazily.
 * ⚠ `null` until then — a button pressed during a cold boot before the hook runs
 * simply does nothing, which leaves the alert on screen rather than dismissing an
 * action that was never applied.
 */
class CalendarReminderActionHolder : AlertActionHandler {
    var handler: AlertActionHandler? = null

    override suspend fun handle(tapPayload: String, actionId: String): Boolean =
        handler?.handle(tapPayload, actionId) ?: false
}
