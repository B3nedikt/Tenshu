package org.tenshu.sync.proxy

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.toTString
import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.Schedule
import org.tenshu.sync.ScheduleSpec
import org.tenshu.sync.WriteSliceSnapshots

/** Stable schedule id; coalesces re-registration on each startup. */
const val SLICE_SNAPSHOT_DEDUP_KEY = "tenshu/sync/sliceSnapshot"

/**
 * Default cadence. Deliberately slower than the offline keeper's six hours: a snapshot only has
 * to be *newer than a joiner's absence*, and every hour of freshness it buys costs a re-seal of
 * every folder that changed.
 */
const val SLICE_SNAPSHOT_PERIOD_MS = 24L * 60 * 60 * 1000

/**
 * Delay before the **first** pass of a process.
 *
 * ⚠⚠ **Without this the only pass a device runs lands ~1 s after proxy init, and the writer's gate
 * is guaranteed closed at that moment.** `ScheduleDrainLoop` accumulates its 1 s tick against
 * `initialDelayMs` for a never-fired item, so an absent delay means a threshold of zero — and at
 * one second in, the realm is typically still locked and any catch-up still walking, both of which
 * are exactly what [SliceSnapshotWriter] refuses to publish through. The pass then skips and the
 * item waits a **full period**, so a device that looks scheduled effectively never publishes.
 *
 * Found on a real device (2026-08-06): the item registered, the pass ran within a second, and it
 * sealed nothing — the failure this delay exists to prevent, observed exactly once.
 *
 * ⚠ Ten minutes is chosen to clear startup and the initial catch-up burst, not because the work is
 * urgent afterwards. A device still mid-backfill at that point skips and waits out the period,
 * which is acceptable for the same reason everything else here is: a joiner without a snapshot
 * replays the log.
 */
const val SLICE_SNAPSHOT_INITIAL_DELAY_MS = 10L * 60 * 1000

/**
 * Register [WriteSliceSnapshots] as a periodic **idle + charging + unmetered** `HeadlessSafe`
 * `ScheduleItem` — the maintenance path §5.7.2 asks for, and the one compaction will use.
 *
 * ⚠ The constraints are the whole point rather than politeness. This pass walks the folder tree
 * and re-seals every slice, entirely for the benefit of *other* devices; the device doing the
 * work gains nothing. Anything that expensive and that selfless belongs behind constraints that
 * may go unmet for days — a snapshot that is never written costs a joiner time, never data.
 *
 * ⚠⚠ **The constraints are advisory in-process, and the in-process loop can barely run this
 * anyway — both pre-existing, neither introduced here.** `ScheduleDrainLoop` never reads
 * `ConstraintSpec` (only Android's `WorkManager` path enforces it — the outstanding bgproc Phase 6
 * item the offline keeper's `NETWORK_UNMETERED` waits on too), *and* its cadence is a **per-process
 * tick accumulator with no wall clock**: both `accumulatedMs` and `firstFired` die with the
 * process. So a threshold only elapses across **continuous uptime**.
 *
 * Concretely, and this is the part worth internalising: a [periodMs] of a day means the in-process
 * loop fires this **only if the proxy lives a day without restarting**. On Android that is fine —
 * `WorkManager` is the real driver and it *does* honour idle+charging. On desktop, where there is
 * no such driver, this effectively runs once per long-lived session at [initialDelayMs] and rarely
 * again. Acceptable for the usual reason: a joiner without a snapshot replays the log.
 *
 * ⚠ An earlier draft of this note claimed the opposite — "on desktop it fires every [periodMs]
 * regardless of idle or charging". That was wrong: it assumed wall-clock scheduling.
 *
 * Idempotent: [SLICE_SNAPSHOT_DEDUP_KEY] coalesces, so calling this on every startup keeps
 * exactly one item.
 */
suspend fun ExecutionContext.registerSliceSnapshotSchedule(
    periodMs: Long = SLICE_SNAPSHOT_PERIOD_MS,
    initialDelayMs: Long = SLICE_SNAPSHOT_INITIAL_DELAY_MS,
) {
    try {
        scheduleSliceSnapshots(periodMs, initialDelayMs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // ⚠⚠ Best-effort BY DECISION, exactly as `registerOfflineKeeperSchedule` documents: the
        // call site is a bare `launch { … }` at proxy init carrying no CoroutineExceptionHandler,
        // so anything thrown here reaches the thread's uncaught handler — which on Android kills
        // Control. Registering an optional maintenance tick must not be able to take the proxy
        // down with it, least of all one whose entire purpose is making *other* devices faster.
        //
        // Named line rather than silence: losing it means joiners keep bootstrapping the slow
        // way, which is invisible until someone sets up a new device.
        println("SliceSnapshot -> schedule registration failed, snapshots will not be written: $e")
    }
}

private suspend fun ExecutionContext.scheduleSliceSnapshots(periodMs: Long, initialDelayMs: Long) {
    executeAsync<Data>(
        operationId = Schedule.ID,
        caller = null,
        params = mapOf(
            Schedule.Params.OP_ID to WriteSliceSnapshots.ID.toTString(context),
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
            Schedule.Params.DEDUP_KEY to SLICE_SNAPSHOT_DEDUP_KEY.toTString(context),
        ),
    )
}
