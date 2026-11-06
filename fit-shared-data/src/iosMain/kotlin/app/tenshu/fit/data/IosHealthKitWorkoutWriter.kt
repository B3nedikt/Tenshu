@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Workout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKSampleType
import platform.HealthKit.HKWorkoutActivityTypeCycling
import platform.HealthKit.HKWorkoutActivityTypeOther
import platform.HealthKit.HKWorkoutActivityTypeRunning
import platform.HealthKit.HKWorkoutActivityTypeTraditionalStrengthTraining
import platform.HealthKit.HKWorkoutActivityTypeWalking
import platform.HealthKit.HKWorkoutBuilder
import platform.HealthKit.HKWorkoutConfiguration
import kotlin.coroutines.resume

/**
 * The **HealthKit write-back** (R-iOS-6, `docs/proposals/fit-app.md` §11.5) — a *courtesy export*
 * so a workout **recorded in Fit** also appears in Apple Health, the way any fitness app that
 * records writes an `HKWorkout`. Our store of record stays the Tenshu realm; this is additive, not
 * a dependency.
 *
 * Built over the non-deprecated [HKWorkoutBuilder] (K/N does not expose the deprecated `HKWorkout`
 * convenience initializer) — activity type + start/end → duration; energy / distance totals are a
 * later refinement. Best-effort and **consent-gated**: the write needs HealthKit *share*
 * authorization (its own system sheet, distinct from the read grant the importer requests) and the
 * host's `NSHealthUpdateUsageDescription` — absent (a bare test host, or a non-Fit host assembling
 * this) the export is a clean no-op, never a process abort (the read-side `requestAuthorization`-
 * aborts lesson).
 *
 * ⚠ Loop avoidance: [HealthKitImporter] skips workouts whose HealthKit source is Fit's own bundle,
 * so a Fit-recorded workout exported here is **not** re-imported into the realm as a duplicate
 * `AppleHealth`-sourced row.
 */
class IosHealthKitWorkoutWriter {

    private val store: HKHealthStore? by lazy {
        if (HKHealthStore.isHealthDataAvailable()) HKHealthStore() else null
    }

    /** Export [workout] (spanning [startEpochMs]..[endEpochMs]) to Apple Health. Returns success. */
    suspend fun export(workout: Workout, startEpochMs: Long, endEpochMs: Long): Boolean {
        val hk = store ?: return false
        // A write needs the UPDATE usage string; requestAuthorization aborts the process without it
        // (the HealthKitImporter read-side lesson). Absent → skip cleanly.
        if (NSBundle.mainBundle.objectForInfoDictionaryKey("NSHealthUpdateUsageDescription") == null) {
            fitLog("hk-export: skipped (no NSHealthUpdateUsageDescription)")
            return false
        }
        if (!requestShareAuthorization(hk, HKObjectType.workoutType())) return false

        val config = HKWorkoutConfiguration()
        // K/N exposes HKWorkoutConfiguration's activity type as `activityType` (setter
        // `setActivityType:`), not the Obj-C `workoutActivityType`.
        config.activityType = hkActivityType(workout.activityType)
        val builder = HKWorkoutBuilder(hk, config, null)
        val start = NSDate.dateWithTimeIntervalSince1970(startEpochMs / 1000.0)
        val end = NSDate.dateWithTimeIntervalSince1970(endEpochMs / 1000.0)

        if (!beginCollection(builder, start)) return false
        if (!endCollection(builder, end)) return false
        val saved = finishWorkout(builder)
        fitLog(if (saved) "hk-export: saved ${workout.activityType} workout to Apple Health" else "hk-export: save failed")
        return saved
    }

    private suspend fun requestShareAuthorization(hk: HKHealthStore, workoutType: HKSampleType): Boolean =
        suspendCancellableCoroutine { cont ->
            hk.requestAuthorizationToShareTypes(setOf(workoutType), readTypes = null) { ok, error ->
                if (error != null) fitLog("hk-export authorization error: ${error.localizedDescription}")
                cont.resume(ok)
            }
        }

    private suspend fun beginCollection(builder: HKWorkoutBuilder, start: NSDate): Boolean =
        suspendCancellableCoroutine { cont ->
            builder.beginCollectionWithStartDate(start) { ok, error ->
                if (error != null) fitLog("hk-export beginCollection error: ${error.localizedDescription}")
                cont.resume(ok)
            }
        }

    private suspend fun endCollection(builder: HKWorkoutBuilder, end: NSDate): Boolean =
        suspendCancellableCoroutine { cont ->
            builder.endCollectionWithEndDate(end) { ok, error ->
                if (error != null) fitLog("hk-export endCollection error: ${error.localizedDescription}")
                cont.resume(ok)
            }
        }

    private suspend fun finishWorkout(builder: HKWorkoutBuilder): Boolean =
        suspendCancellableCoroutine { cont ->
            builder.finishWorkoutWithCompletion { workout, error ->
                if (error != null) fitLog("hk-export finishWorkout error: ${error.localizedDescription}")
                cont.resume(workout != null)
            }
        }

    private fun hkActivityType(type: ActivityType): ULong = when (type) {
        ActivityType.Running -> HKWorkoutActivityTypeRunning
        ActivityType.Walking -> HKWorkoutActivityTypeWalking
        ActivityType.Cycling -> HKWorkoutActivityTypeCycling
        ActivityType.Strength -> HKWorkoutActivityTypeTraditionalStrengthTraining
        ActivityType.Other -> HKWorkoutActivityTypeOther
    }
}
