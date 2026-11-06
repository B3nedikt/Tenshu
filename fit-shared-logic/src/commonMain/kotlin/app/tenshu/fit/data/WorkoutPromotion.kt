package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DetectedMovement

/**
 * Turns a Timeline-captured [DetectedMovement] into a `Workout` — deterministically, from the
 * trace alone (`fit-automatic-activity-detection.md` D2).
 *
 * ⚠⚠ **Every input must come from the trace.** No clock, no device id, no local state, no
 * ordering. That is the whole convergence argument: two devices promoting the same synced trace
 * mint the *same* content-addressed row, so multi-device promotion needs no election, no receipt
 * and no dedup pass. Adding one device-local input silently creates a cross-device broadcast loop.
 *
 * ⚠ Pure and UI-free on purpose — it is the half `:activity-recognition` will take when D6
 * extracts the shared classifier, and keeping it free of repository/preference types is what
 * makes that a move rather than a rewrite.
 */
object WorkoutPromotion {

    /**
     * Thresholds for "is this trace workout-worthy".
     *
     * ⭐ **Settled against a real corpus, not guessed** (§10 Q1): 10 min / 500 m / confidence 50
     * promoted **0 of 15** traces on a real device, and the per-trace verdicts said why.
     */
    data class Thresholds(
        /**
         * ⚠ Ten minutes refuses a **2.7 km run over 8 minutes** — a workout by any
         * ordinary reading. Five minutes still drops the corridor-wander class (the corpus's
         * sub-300 s walks were 184–296 s over 450–924 m) without discarding short real runs.
         */
        val minDurationSec: Int = 5 * 60,
        /**
         * Below this a "walk" is more likely GPS jitter than movement.
         * ⚠ Applies only where distance is known — see [decide].
         */
        val minDistanceM: Int = 500,
        /**
         * ⚠⚠ **A floor of 50 measures the wrong thing.** `modeConfidence` is
         * `margin × sample-count × accuracy`, so a short walk scores low *because it is short*,
         * not because the verdict is doubtful. On the corpus, pedestrian traces scored **0–46**
         * while transit scored **54–85** — a 50 floor excludes essentially every walk and run
         * while admitting every train ride, the exact inverse of its purpose.
         *
         * ⚠ Kept as a token floor, though, and the reason is narrow: within the pedestrian set a
         * mode-boundary error only mislabels (run vs walk) and cannot invent a workout — the
         * thing worth refusing is a *drive misread as a walk*, and that is what near-zero
         * confidence looks like. A token floor keeps that guard and costs nothing real; the
         * corpus's only sub-10 traces were 184 s and 201 s walks, already refused on duration.
         */
        val minConfidence: Int = 10,
    )

    /** Why a trace was not promoted — surfaced for logs and tests, never for the user. */
    enum class Rejection { NotAnExercise, TooShort, TooFar, LowConfidence }

    sealed interface Decision {
        data class Promote(val workoutId: String, val activityType: ActivityType) : Decision
        data class Reject(val reason: Rejection) : Decision
    }

    /**
     * The id a promoted workout takes. ⚠ Derived from `traceId` **alone** — deliberately not from
     * `classifierVersion`, or a re-classification would mint a second workout instead of
     * correcting the first. And the embedded traceId is what lets the route be fetched on demand,
     * so a promoted workout never copies the geometry (§4.1).
     */
    fun workoutIdFor(traceId: String): String = "detected:$traceId"

    /** The inverse of [workoutIdFor] — `null` for a workout that was not promoted. */
    fun traceIdOf(workoutId: String): String? = workoutId.removePrefix("detected:")
        .takeIf { workoutId.startsWith("detected:") && it.isNotEmpty() }

    /**
     * Timeline's mode vocabulary → Fit's. ⚠ `drive`, `transit` and `flight` are **not** exercise
     * and must never promote: sitting in a car is the single most common thing the classifier
     * sees, and logging it as a workout would discredit every real detection beside it.
     * `unknown` is likewise refused — an unnamed activity is not a workout.
     */
    fun activityTypeFor(mode: String): ActivityType? = when (mode.lowercase()) {
        "walk" -> ActivityType.Walking
        "run" -> ActivityType.Running
        "cycle" -> ActivityType.Cycling
        else -> null
    }

    fun decide(
        movement: DetectedMovement,
        thresholds: Thresholds = Thresholds(),
    ): Decision {
        val activityType = activityTypeFor(movement.mode)
            ?: return Decision.Reject(Rejection.NotAnExercise)
        // Order matters only for which reason is reported; the verdict is the same either way.
        if (movement.durationSec < thresholds.minDurationSec) return Decision.Reject(Rejection.TooShort)
        // ⚠⚠ Skipped when distance is unknown, not failed. The floor exists to reject GPS
        // jitter — a few hundred metres of wander that never went anywhere — and a step-derived
        // bout has no GPS to jitter, so the noise it guards against cannot occur. Failing it
        // instead would refuse every Tier-1 detection by a test it structurally cannot pass.
        val distanceM = movement.distanceM
        if (distanceM != null && distanceM < thresholds.minDistanceM) {
            return Decision.Reject(Rejection.TooFar)
        }
        if (movement.modeConfidence < thresholds.minConfidence) {
            return Decision.Reject(Rejection.LowConfidence)
        }
        return Decision.Promote(workoutIdFor(movement.traceId), activityType)
    }
}
