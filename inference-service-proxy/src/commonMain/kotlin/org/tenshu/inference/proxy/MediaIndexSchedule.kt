package org.tenshu.inference.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.inference.MediaIndexPass
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.ScheduledRunDeferred

/** Stable schedule id for the media index pass; coalesces re-registration on each startup. */
const val MEDIA_INDEX_DEDUP_KEY = "tenshu/inference/mediaIndexPass"

/**
 * Default cadence.
 *
 * ⚠ Long on purpose. The pass is idempotent and cheap when there is nothing new — an already-indexed
 * photo costs a stamp read, not a file read — so the tick only has to be frequent enough that a
 * library catches up over a few nights, not frequent enough to feel responsive. Responsiveness is
 * what an explicit "index now" would be for, and IN-D6 does not ask for one.
 */
const val MEDIA_INDEX_PERIOD_MS = 12L * 60 * 60 * 1000 // 12 hours

/**
 * How soon a **deferred** pass looks again (`media-processing-pipeline-sweep.md` F3.1) — the device
 * was busy, not the library done. ⚠ Without it, the pass the in-process loop fires at Control's
 * startup (screen on, so declined) would be the only look for 12 h of process uptime.
 *
 * Used twice, and ⚠⚠ **the two do not mean the same thing**:
 *
 *  - as the `ScheduledRunDeferred` the pass throws — the in-process `ScheduleDrainLoop` waits
 *    exactly this long, every time;
 *  - as the `RetryPolicy` backoff — which ⚠⚠ **Android does not apply**. The item is idle-mode,
 *    and WorkManager refuses backoff criteria on an idle-mode job; asking for one throws at
 *    registration, leaving the pass with no job on Android at all
 *    (`control-background-work-off-fgs.md` §3.6). `toWorkPlan` drops it, and a deferred pass's `Result.retry()` waits for the
 *    next idle window instead. The policy is kept for a platform whose scheduler can use it.
 *
 * ⚠ On Android the in-process loop does not dispatch this item (it is WorkManager's: §5.6), so
 * the fixed interval above applies only where the loop is the driver.
 */
const val MEDIA_INDEX_RETRY_MS = ScheduledRunDeferred.DEFAULT_RETRY_AFTER_MS

/**
 * Register [MediaIndexPass] as a **periodic, idle + charging** `HeadlessSafe` `ScheduleItem`
 * (IN-D6). On Android it runs only as Control's own idle+charging WorkManager job (the in-process
 * `ScheduleDrainLoop` leaves it to WorkManager, `control-background-work-off-fgs.md` §5.6); where
 * no OS scheduler owns it, the loop dispatches it in-process. Idempotent — `dedupKey` coalesces, so calling this on every
 * startup keeps exactly one item.
 *
 * ⚠⚠ **Idle AND charging, not merely unmetered.** A first index of a large library is a multi-hour,
 * battery-visible run of a neural network over every photograph the user owns. IN-D6: *"silently
 * doing it is the kind of thing this product exists to not do."* The constraints are what make the
 * silence acceptable until the visible progress surface exists — it happens when the device is
 * plugged in and unused, never while someone is holding it.
 *
 * ⚠ `BATTERY_NOT_LOW` as well as charging: a device plugged into a weak source can still be
 * discharging, and this is the one workload that would win that race.
 *
 * ⚠⚠ No network constraint — **declared**, as `NETWORK_NONE`. The pass reads local files and runs a
 * local model, and asking for a connection would idle a device that is ready to work. Leaving
 * `network` out is *not* the same thing: `ConstraintSpec.create` defaults to `NETWORK_ANY`, which
 * the platform scheduler maps to "any live connection" (WorkManager `CONNECTED`)
 * (`media-processing-pipeline-sweep.md` D7). The one part of the pass that does touch the network —
 * the relay XLARGE fallback in `IndexPreview` — is metered-gated on its own and degrades to
 * `notOnDevice` offline.
 */
suspend fun ExecutionContext.registerMediaIndexSchedule(
    periodMs: Long = MEDIA_INDEX_PERIOD_MS,
) {
    try {
        scheduleMediaIndexPass(periodMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort BY DECISION, the `registerOfflineKeeperSchedule` shape and for its reason:
        // every call site is a bare `sessionScope.launch { … }` at proxy init, and that scope
        // carries no CoroutineExceptionHandler — so anything thrown here reaches the thread's
        // uncaught handler, which on Android kills Control. Registering a maintenance tick must not
        // be able to take the proxy down with it.
        //
        // Reachable without exotic conditions: the `Schedule` op resolves through the platform
        // implementation chain, so a DI that does not wire the sync factory throws
        // `No implementation found for operation: …/schedule` right here.
        //
        // ⚠ Named line rather than silence: the cost of losing it is that nothing is ever indexed,
        // which is invisible until someone searches their photos and is told `NOT_INDEXED`.
        println("MediaIndexPass -> schedule registration failed, indexing will not run: $e")
    }
}

private suspend fun ExecutionContext.scheduleMediaIndexPass(periodMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to MediaIndexPass.ID.toTString(context),
            Schedule.Params.SCHEDULE_SPEC to ScheduleSpec.create(
                context = context,
                kind = ScheduleSpec.KIND_PERIODIC,
                minPeriodMs = periodMs,
            ),
            Schedule.Params.CONSTRAINT_SPEC to ConstraintSpec.create(
                context = context,
                // ⚠⚠ Explicit: the default (`NETWORK_ANY`) means "needs a connection" (D7).
                network = ConstraintSpec.NETWORK_NONE,
                power = ConstraintSpec.POWER_CHARGING,
                idle = ConstraintSpec.IDLE_DEVICE_IDLE,
                battery = ConstraintSpec.BATTERY_NOT_LOW,
            ),
            // ⚠ LINEAR from the in-process retry interval. ⚠⚠ Dropped on Android, where the job is
            // idle-mode and may carry no backoff — see `MEDIA_INDEX_RETRY_MS`.
            Schedule.Params.RETRY_POLICY to RetryPolicy.create(
                context = context,
                backoff = RetryPolicy.BACKOFF_LINEAR,
                initialBackoffMs = MEDIA_INDEX_RETRY_MS,
            ),
            Schedule.Params.DEDUP_KEY to MEDIA_INDEX_DEDUP_KEY.toTString(context),
        ),
    )
}
