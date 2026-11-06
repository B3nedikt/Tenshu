package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.DeliverDueReminders
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/**
 * Arms the single next-wake `ScheduleItem` for reminders.
 *
 * ⭐ **One item, not N** — the `RoutineScheduleArmer` shape. The earliest
 * upcoming reminder gets a `ONE_SHOT`; when it lands, the op re-derives
 * everything due and arms the next. One OS alarm beats one per reminder, and it
 * is the only shape a parameter-less `ScheduleItem` allows anyway.
 *
 * ⚠⚠ **The `ConstraintSpec` is empty, deliberately.** Idle + charging is the
 * *compaction* scheduling class and is exactly wrong here: a reminder that only
 * fires while the phone is on the charger is not a reminder. Nothing about an
 * alert the user asked for is deferrable maintenance.
 *
 * ⚠ Goes through the `Schedule` op rather than writing the item, so it inherits
 * that op's fail-closed `HeadlessSafe` check — if `DeliverDueReminders` ever
 * lost the trait this refuses loudly at arming instead of failing silently at
 * wake.
 *
 * ⚠ A `null` next wake leaves the previous one-shot to expire rather than
 * cancelling it. An expired one-shot is inert, and re-arming replaces it by
 * `dedupKey` — the same reasoning the routines armer records.
 */
internal class ReminderArmer(
    private val executionContext: ExecutionContext,
    private val implementationFactory: OperationImplementationFactory,
    private val nowEpochMs: () -> Long,
) {

    operator fun invoke(nextWakeEpochMs: Long?) {
        if (nextWakeEpochMs == null) return
        val impl = implementationFactory(Schedule.ID) as? SyncOperationImplementation ?: return
        val context = executionContext.context
        val delay = (nextWakeEpochMs - nowEpochMs()).coerceAtLeast(0)

        // ⚠ Swallowed: a failure to arm must not fail the write that triggered
        // it. Losing a reminder is bad; losing the user's *event* because the
        // scheduler was unavailable is worse, and the next write re-arms.
        runCatching {
            impl.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = implementationFactory,
                caller = null,
                params = mapOf(
                    Schedule.Params.OP_ID to DeliverDueReminders.ID.toTString(context),
                    Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                        context = context,
                        kind = ScheduleSpec.KIND_ONE_SHOT,
                        initialDelayMs = delay,
                    ),
                    Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(context),
                    Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
                    Schedule.Params.DEDUP_KEY to DEDUP_KEY.toTString(context),
                ),
            )
        }
    }

    internal companion object {
        /** One key ⇒ re-arming replaces the pending wake rather than stacking. */
        const val DEDUP_KEY = "tenshu/calendar/nextReminder"
    }
}
