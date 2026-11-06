package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/**
 * Registration counterpart to [ScheduleDrainLoop]: keeps a single periodic,
 * head-less [org.tenshu.sync.ScheduleItem] (one [dedupKey]) reconciled with a
 * [Flow] of [ConstraintSpec]. Each emission re-runs the `Schedule` op, which
 * coalesces on [dedupKey] (§3.3) so there is always exactly one pending item
 * carrying the latest constraint — i.e. a preference toggle reschedules under
 * the new constraint.
 *
 * Proxy-side (drives the same `ScheduleItem` registry as [ScheduleDrainLoop] via
 * [ExecutionContext]); `ScheduleService` has no client RO yet, so registration
 * goes through `executeAsync(Schedule.ID, ...)` like the rest of the
 * schedule machinery.
 *
 * The concrete consumer is Control's background sync — the constraint flow is
 * `PreferenceService.backgroundSyncConstraints()` (`WifiOnly` /
 * `MaxMonthlyDataMb` → `ConstraintSpec`, `core-preference-service.md` §9 step 6)
 * and [opId] is the head-less sync-drain operation.
 *
 * Wired in production on Android (`PlatformProxyModule`) with `opId = DrainSync.ID`. Running
 * alongside the `SyncScheduler` poll is safe rather than a double-drain: `drainNow()` is
 * single-flight behind `SyncScheduler.tickMutex`.
 *
 * ⚠ The constraint is still dispatched **best-effort** by [ScheduleDrainLoop] — no network or
 * `maxDataMb` enforcement in-process (bgproc Phase 1c).
 */
class BackgroundSyncScheduler(
    private val executionContext: ExecutionContext,
    private val constraints: Flow<ConstraintSpec>,
    private val scope: CoroutineScope,
    private val opId: String,
    private val periodMs: Long,
    private val dedupKey: String = DEFAULT_DEDUP_KEY,
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch { constraints.collect { reschedule(it) } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Registers (or, on [dedupKey], re-registers in place) the single periodic
     * sync item under [constraint]. The per-emission action behind [start];
     * `internal` so it can be awaited deterministically in tests.
     */
    internal suspend fun reschedule(constraint: ConstraintSpec) {
        val context = executionContext.context
        executionContext.executeAsync<Data>(
            operationId = Schedule.ID,
            caller = null,
            params = mapOf(
                Schedule.Params.OP_ID to opId.toTString(context),
                Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                    context = context,
                    kind = ScheduleSpec.KIND_PERIODIC,
                    minPeriodMs = periodMs,
                ),
                Schedule.Params.CONSTRAINT_SPEC to constraint,
                Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
                Schedule.Params.DEDUP_KEY to dedupKey.toTString(context),
            )
        )
    }

    companion object {
        const val DEFAULT_DEDUP_KEY = "sync-drain"
    }
}
