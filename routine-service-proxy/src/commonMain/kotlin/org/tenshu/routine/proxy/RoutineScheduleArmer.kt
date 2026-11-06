package org.tenshu.routine.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.toTString
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/**
 * Arms the single next-wake `ScheduleItem` (`routines-engine.md` RE-D5).
 *
 * ⭐ **One item, not N.** The engine keeps a single `ONE_SHOT` armed to the
 * *earliest* upcoming firing across everything it is eligible for; the op is
 * parameter-less and finds its own work when it lands. One OS alarm beats N.
 *
 * ⚠⚠ **The `ConstraintSpec` is empty, deliberately.** Idle + charging +
 * unmetered is the *compaction* scheduling class and is exactly wrong here: a
 * routine that only fires while the phone is on the charger is not a routine.
 * Nothing about a routine is deferrable maintenance.
 *
 * ⚠ Goes through the `Schedule` op rather than writing the item directly, so it
 * inherits that op's fail-closed `HeadlessSafe` check — `EvaluateDueRoutines`
 * carries the trait, and if that ever regressed this would refuse loudly at the
 * arming point instead of failing silently at wake time.
 *
 * ⚠ A `null` next wake means "nothing to arm" — the previous item is left to
 * expire rather than actively cancelled. Cancel-by-key is a `Schedule` concern
 * and an expired one-shot is inert; re-arming replaces it by `dedupKey`.
 */
internal class RoutineScheduleArmer(
    private val executionContext: ExecutionContext,
    private val implementationFactory: OperationImplementationFactory,
    private val nowEpochMs: () -> Long,
) {

    operator fun invoke(nextWakeEpochMs: Long?) {
        if (nextWakeEpochMs == null) return
        val impl = implementationFactory(Schedule.ID) as? SyncOperationImplementation ?: return
        val context = executionContext.context
        val delay = (nextWakeEpochMs - nowEpochMs()).coerceAtLeast(0)

        runCatching {
            impl.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = implementationFactory,
                caller = null,
                params = mapOf(
                    Schedule.Params.OP_ID to EvaluateDueRoutines.ID.toTString(context),
                    Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                        context = context,
                        kind = ScheduleSpec.KIND_ONE_SHOT,
                        initialDelayMs = delay,
                    ),
                    // ⚠⚠ Empty on purpose — see the class KDoc.
                    Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(context),
                    Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
                    Schedule.Params.DEDUP_KEY to DEDUP_KEY.toTString(context),
                ),
            )
        }
    }

    private companion object {
        /** One key ⇒ re-arming replaces the pending item rather than stacking wakes. */
        const val DEDUP_KEY = "tenshu/routine/nextWake"
    }
}
