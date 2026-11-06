package org.tenshu.file.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.file.system.BackfillThumbnailClasses
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec

/** Stable schedule id for the thumbnail backfill; coalesces re-registration on each startup. */
const val THUMBNAIL_BACKFILL_DEDUP_KEY = "tenshu/file/thumbnailBackfill"

/**
 * A day. The pass latches a marker once a wanted set is fully served, so the period only governs
 * how often a library with work left (budget-stopped, quota-stopped, a failed mint) resumes.
 */
const val THUMBNAIL_BACKFILL_PERIOD_MS = 24L * 60 * 60 * 1000

/** Long enough that a cold start has finished its first sync — and its content backfill's turn — first. */
const val THUMBNAIL_BACKFILL_INITIAL_DELAY_MS = 20L * 60 * 1000

/**
 * Register [BackfillThumbnailClasses] as a periodic **idle + charging + unmetered** `HeadlessSafe`
 * `ScheduleItem` (`drive-synced-thumbnails.md` §15.3), registered beside
 * [registerOfflineKeeperSchedule] and shaped like `registerContentBackfillSchedule`.
 *
 * ⚠⚠ **Unmetered because it uploads**: ~139 KB per photo for `XLARGE`, ~90 KB per video for
 * `MOTION`, fixed-size by §12.5 of the video doc — and a library's worth of them is a library's
 * worth of cellular data. Idle + charging because a render per file is CPU the user did not ask
 * for right now. Both are constraints on cost, not correctness: nothing waits on this pass.
 *
 * ⚠ The same in-process caveat as its neighbours: `ScheduleDrainLoop` does not enforce
 * `ConstraintSpec` and its cadence is a per-process tick accumulator, so on desktop this runs
 * roughly once per long-lived session with no idle or power gate. For a pass whose per-run budget
 * is bounded that is acceptable rather than a compromise.
 */
suspend fun ExecutionContext.registerThumbnailBackfillSchedule(
    periodMs: Long = THUMBNAIL_BACKFILL_PERIOD_MS,
    initialDelayMs: Long = THUMBNAIL_BACKFILL_INITIAL_DELAY_MS,
) {
    try {
        scheduleThumbnailBackfill(periodMs, initialDelayMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort BY DECISION, for the reason `registerOfflineKeeperSchedule` spells out: the
        // call site is a bare `sessionScope.launch { … }` at proxy init with no
        // CoroutineExceptionHandler, so a throw here reaches the thread's uncaught handler — which
        // on Android kills Control. Named line rather than silence: losing it means a library
        // uploaded before a class existed never grows that class, which reads as "the feature does
        // not reach old files" rather than as a bug.
        println("ThumbnailBackfill -> schedule registration failed, the backfill will not run: $e")
    }
}

private suspend fun ExecutionContext.scheduleThumbnailBackfill(periodMs: Long, initialDelayMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to BackfillThumbnailClasses.ID.toTString(context),
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
            Schedule.Params.DEDUP_KEY to THUMBNAIL_BACKFILL_DEDUP_KEY.toTString(context),
        ),
    )
}
