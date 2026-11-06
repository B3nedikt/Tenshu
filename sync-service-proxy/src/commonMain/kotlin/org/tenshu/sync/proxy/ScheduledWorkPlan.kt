package org.tenshu.sync.proxy

import org.tenshu.sync.ConstraintSpec
import org.tenshu.sync.RetryPolicy
import org.tenshu.sync.ScheduleItem
import org.tenshu.sync.ScheduleSpec

/** Network requirement a platform scheduler must honour (`core-background-processing.md` §5.6). */
enum class NetworkRequirement { NONE, CONNECTED, UNMETERED }

/**
 * The platform-agnostic translation of a [ScheduleItem]'s schedule + constraints —
 * the error-prone vocabulary mapping, pulled out of the Android `WorkManagerScheduler`
 * so it is **unit-testable without Android** (`ScheduledWorkPlanTest`). The Android
 * layer only turns this into `WorkRequest` / `Constraints` (§5.6 table); nothing
 * platform-specific is decided here.
 *
 * `maxDataMb` is carried but not part of `WorkManager`'s constraint set — the
 * metered-data ceiling is enforced by the Path-A `ConstraintSyncGate` at drain
 * time, not the OS scheduler (§3.3 / §6.4).
 */
data class ScheduledWorkPlan(
    val dedupKey: String,
    val opId: String,
    val periodic: Boolean,
    val minPeriodMs: Long?,
    val initialDelayMs: Long?,
    val network: NetworkRequirement,
    val requireCharging: Boolean,
    val requireDeviceIdle: Boolean,
    val requireStorageNotLow: Boolean,
    val requireBatteryNotLow: Boolean,
    /**
     * The op must run *at* [initialDelayMs] from now, not merely eventually
     * (`Punctual`, RE-D5). ⚠ Only meaningful for a one-shot: a periodic
     * "every 15 minutes, exactly" is an alarm-clock app, not a routine.
     */
    val punctual: Boolean = false,
    /**
     * The backoff the OS scheduler applies to work that asked to be retried — what a
     * [org.tenshu.sync.ScheduledRunDeferred] becomes on Android. `null` ⇒ the OS default.
     * ⚠ Not a fixed interval: see [WorkBackoff].
     */
    val backoff: WorkBackoff? = null,
) {
    /**
     * The OS scheduler, not the in-process `ScheduleDrainLoop`, is this item's only driver
     * (`control-background-work-off-fgs.md` §5.6) — on a platform whose scheduler enforces
     * constraints ([PlatformScheduler.ownsDispatch]).
     *
     * ⚠⚠ True for every constraint the in-process loop **cannot check**: charging, device idle,
     * battery or storage not low, and an unmetered network. The loop dispatches by cadence alone, so
     * an idle+charging pass it ran was a pass with the screen on and on battery, and on Android one
     * with no job behind it — so no network once Control left the foreground (§3.3).
     * ⚠ `CONNECTED` and no network are not on the list: the loop's process is alive and has whatever
     * network it has, which is all those ask for. A punctual one-shot is the alarm's (RE-D5).
     */
    val platformOwned: Boolean
        get() = !punctual && (
            network == NetworkRequirement.UNMETERED || requireCharging || requireDeviceIdle ||
                requireStorageNotLow || requireBatteryNotLow
            )
}

/**
 * A retry backoff the item asked for, from its `RetryPolicy`.
 *
 * ⚠⚠ **[initialMs] is the first wait, not every wait.** WorkManager has no fixed-interval retry.
 * It computes the wait before retry *n* of a chain from the attempt count
 * (`WorkSpec.calculateNextRunTime`, work-runtime 2.11.2):
 *
 *  - linear: `initialMs × n` — for 15 min: 15, 30, 45, 60 … min;
 *  - exponential: `initialMs × 2^(n-1)` — for 15 min: 15, 30, 60, 120 … min;
 *
 * both capped at 5 h (`WorkRequest.MAX_BACKOFF_MILLIS`), counted from the moment the previous
 * attempt returned, and still subject to the request's constraints. `n` counts every start of the
 * chain — deferrals, failures and runs the system interrupted alike — and goes back to zero only
 * when periodic work completes a run (or fails one for good). A fixed interval would need the
 * worker to re-enqueue itself, which it deliberately does not do.
 */
data class WorkBackoff(val exponential: Boolean, val initialMs: Long)

/**
 * WorkManager's floor for a backoff (`WorkRequest.MIN_BACKOFF_MILLIS`), restated here because this
 * module's common half cannot see Android.
 *
 * ⚠⚠ Also the line between "asked for a backoff" and "left the default": `RetryPolicy.create`'s
 * default `initialBackoffMs` is 1 s, which most registrants pass without meaning anything by it. Honouring it would silently move every item's failure retries from WorkManager's 30 s default
 * to its 10 s floor, so a policy below this floor is read as "unset".
 */
const val MIN_PLATFORM_BACKOFF_MS: Long = 10_000L

/**
 * Derive the platform-agnostic [ScheduledWorkPlan] for this item.
 *
 * @param isPunctual resolves the `Punctual` trait for an op id. Passed in rather
 *   than read here because this module has no schema provider — the wiring that
 *   builds the scheduler does. Defaults to "not punctual", which is the correct
 *   answer for every op that predates RE-D5.
 */
fun ScheduleItem.toWorkPlan(
    isPunctual: (opId: String) -> Boolean = { false },
): ScheduledWorkPlan {
    val spec = scheduleSpec
    val constraints = constraintSpec
    return ScheduledWorkPlan(
        dedupKey = dedupKey.get(),
        opId = opId.get(),
        periodic = spec.kind.get() == ScheduleSpec.KIND_PERIODIC,
        minPeriodMs = spec.minPeriodMs?.get(),
        initialDelayMs = spec.initialDelayMs?.get(),
        network = constraints.networkRequirement(),
        requireCharging = constraints.power.get() == ConstraintSpec.POWER_CHARGING,
        requireDeviceIdle = constraints.idle.get() == ConstraintSpec.IDLE_DEVICE_IDLE,
        requireStorageNotLow = constraints.storage.get() == ConstraintSpec.STORAGE_NOT_LOW,
        requireBatteryNotLow = constraints.battery.get() == ConstraintSpec.BATTERY_NOT_LOW,
        // ⚠ A periodic item is never punctual, whatever the op declares: the
        // concept only makes sense for "run once, at this instant".
        punctual = spec.kind.get() != ScheduleSpec.KIND_PERIODIC && isPunctual(opId.get()),
        // ⚠⚠ Never on an idle-mode job: WorkManager's builder throws "Cannot set backoff criteria on
        // an idle mode job" (JobScheduler reschedules an idle job for the next idle window instead),
        // and the throw leaves the item with no job at all (`control-background-work-off-fgs.md`
        // §3.6). Dropped here, for every item, not per caller.
        backoff = retryPolicy.initialBackoffMs.get()
            .takeIf { it >= MIN_PLATFORM_BACKOFF_MS }
            ?.takeUnless { constraints.idle.get() == ConstraintSpec.IDLE_DEVICE_IDLE }
            ?.let { WorkBackoff(exponential = retryPolicy.backoff.get() != RetryPolicy.BACKOFF_LINEAR, initialMs = it) },
    )
}

/**
 * The network half of the §5.6 constraint mapping — the **one** place it is decided, shared by
 * [toWorkPlan] and the Android `WorkManagerScheduler`.
 *
 * ⚠⚠ [ConstraintSpec.NETWORK_ANY] — which is also `ConstraintSpec.create`'s **default** — means
 * *"any live connection"*, **not** *"no network needed"*: it folds to [NetworkRequirement.CONNECTED],
 * and WorkManager will not start the job offline. A schedule whose op needs no network must say
 * [ConstraintSpec.NETWORK_NONE] explicitly; leaving `network` out asks for a connection, and the op
 * waits for a network it never uses (`media-processing-pipeline-sweep.md` F3.2 / D7). An unknown
 * value also folds to `CONNECTED` — the conservative reading for a vocabulary this build does not know.
 */
fun ConstraintSpec.networkRequirement(): NetworkRequirement = when (network.get()) {
    ConstraintSpec.NETWORK_UNMETERED -> NetworkRequirement.UNMETERED
    ConstraintSpec.NETWORK_NONE -> NetworkRequirement.NONE
    else -> NetworkRequirement.CONNECTED // NETWORK_ANY (the default) / unknown → any live connection
}
