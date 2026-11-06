package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.sync.BackfillContentDeclarations
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/** Dedup key, so registering on every startup keeps exactly one item. */
private const val CONTENT_BACKFILL_DEDUP_KEY = "sync/contentDeclarationBackfill"

/**
 * A day, and it barely matters: the pass latches a completion marker on its first clean run, so the
 * period only governs how often a *degraded* realm retries.
 */
const val CONTENT_BACKFILL_PERIOD_MS = 24L * 60 * 60 * 1000

/** Long enough that a cold start has finished its first sync before this competes with anything. */
const val CONTENT_BACKFILL_INITIAL_DELAY_MS = 15L * 60 * 1000

/**
 * Register [BackfillContentDeclarations] as a periodic **idle + charging + unmetered**
 * `HeadlessSafe` `ScheduleItem` (`content-plane-refcount.md` §9.7, CR-4d).
 *
 * ⚠⚠ **The constraints are about cost.** This pass fetches and decrypts one manifest per live
 * content object — one `GET` each. Unlike the slice-snapshot pass beside it, the work is *for this
 * device's own realm*, so it is not selfless; it is simply expensive, and expensive-and-once
 * belongs behind constraints that may go unmet for a while.
 *
 * ⚠ The same in-process caveat applies as for `registerSliceSnapshotSchedule`: `ScheduleDrainLoop`
 * does not enforce `ConstraintSpec` and its cadence is a per-process tick accumulator, so on
 * desktop this effectively runs once per long-lived session. For a pass that is *meant* to run once
 * that is the right failure mode rather than a compromise.
 */
suspend fun ExecutionContext.registerContentBackfillSchedule(
    periodMs: Long = CONTENT_BACKFILL_PERIOD_MS,
    initialDelayMs: Long = CONTENT_BACKFILL_INITIAL_DELAY_MS,
) {
    try {
        scheduleContentBackfill(periodMs, initialDelayMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort by decision, for the reason `registerSliceSnapshotSchedule` spells out:
        // the call site is a bare `launch { … }` at proxy init with no CoroutineExceptionHandler,
        // so a throw here reaches the thread's uncaught handler — which on Android kills Control.
        // Named line rather than silence: losing it means the existing corpus stays undescribed,
        // which is invisible until someone wonders why their quota never drops.
        println("ContentBackfill -> schedule registration failed, the backfill will not run: $e")
    }
}

private suspend fun ExecutionContext.scheduleContentBackfill(periodMs: Long, initialDelayMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to BackfillContentDeclarations.ID.toTString(context),
            Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                context = context,
                kind = ScheduleSpec.KIND_PERIODIC,
                minPeriodMs = periodMs,
                initialDelayMs = initialDelayMs,
            ),
            Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(
                context = context,
                network = ConstraintSpec.NETWORK_UNMETERED,
                power = ConstraintSpec.POWER_CHARGING,
                idle = ConstraintSpec.IDLE_DEVICE_IDLE,
                battery = ConstraintSpec.BATTERY_NOT_LOW,
            ),
            Schedule.Params.RETRY_POLICY to RetryPolicy.create(context),
            Schedule.Params.DEDUP_KEY to CONTENT_BACKFILL_DEDUP_KEY.toTString(context),
        ),
    )
}
