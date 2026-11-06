package app.tenshu.fit.data

import app.tenshu.fit.model.DetectedMovement
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Writes the workouts Timeline detected (`fit-automatic-activity-detection.md` D2/D5).
 *
 * **Retrospective, never live** (D5). It runs when Fit is opened or refreshed and looks
 * *backwards* at traces that already exist. That is what removes the race with Timeline's
 * capture — by the time this looks, the trace is closed and written — and it is why Fit needs no
 * detector, no sensor and no second location subscription of its own.
 *
 * ⚠ Lives in `:fit-shared-logic`, not `-data`: every type it touches (`FitRepository`,
 * `DetectedMovement`, `Workout`) is declared here, and a view model needs to call it — the
 * first-detection prompt promotes one movement on the user's say-so.
 *
 * ⚠ Idempotent by construction, not by bookkeeping. [WorkoutPromotion.workoutIdFor] is
 * content-addressed on the trace id, so re-running this writes the same row with the same
 * content. Two devices doing it concurrently converge; a hundred runs cost a hundred no-op
 * upserts, never a duplicate.
 */
class DetectedWorkoutPromoter(
    private val repository: FitRepository,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val thresholds: WorkoutPromotion.Thresholds = WorkoutPromotion.Thresholds(),
) {

    /**
     * Promotes every eligible movement not already present as a workout.
     *
     * [existing] is passed in rather than read here so the caller can reuse the listing it
     * already has. ⚠ Skipping ids that exist is a **cost** optimisation — the write is idempotent
     * either way. Skipping movements a *real* workout already covers is **correctness**: see the
     * churn note below.
     *
     * Returns the workouts written, newest first.
     *
     * @param beforeEachWrite ADR 0025 §8.6 write boundary: runs before EACH `addWorkout` and throws
     *   to refuse it, so a realm gate that starts holding mid-pass stops the promotions there and
     *   the refusal reaches the caller (the bootstrap holds the pass for a re-run on release).
     *   ⚠⚠ Required, never defaulted (Codex P1 on b25d524d2): the unattended pass sampled the gate
     *   only before it started, so promotions made after Control lost its realm went into an
     *   ownerless store and — with nothing to supersede — the pass completed and never re-ran. A
     *   user act (the first-detection prompt) passes `OPEN_REALM_GATE` explicitly.
     */
    suspend fun promote(
        movements: List<DetectedMovement>,
        existing: List<Workout>,
        beforeEachWrite: suspend () -> Unit,
    ): List<Workout> {
        val existingIds = existing.mapTo(mutableSetOf()) { it.workoutId }
        // ⚠⚠ Windows of workouts that are NOT inferences. A movement already covered by one must
        // never be promoted — not "promoted then superseded". Writing a row we would delete on
        // the next launch is not merely wasteful: the rows are gone by the following launch, so
        // the promoter re-creates them, and the pair churns forever — eight realm writes and four
        // tombstones per launch, replicated to every paired device. Observed on a real device
        // before this guard existed.
        val realWindows = existing
            .filter { it.source != Source.Detected }
            .map { it.start.toInstant(zone).toEpochMilliseconds() to it.end.toInstant(zone).toEpochMilliseconds() }

        val promoted = mutableListOf<Workout>()
        for (movement in movements) {
            val decision = WorkoutPromotion.decide(movement, thresholds)
            if (decision !is WorkoutPromotion.Decision.Promote) continue
            if (decision.workoutId in existingIds) continue
            if (realWindows.any { (s, e) -> movement.startAtEpochMs < e && s < movement.endAtEpochMs }) continue

            val workout = Workout(
                workoutId = decision.workoutId,
                activityType = decision.activityType,
                source = Source.Detected,
                title = "",
                device = "",
                start = movement.startAtEpochMs.toLocalDateTime(zone),
                end = movement.endAtEpochMs.toLocalDateTime(zone),
                durationSec = movement.durationSec,
                distanceM = movement.distanceM,
                // FA-4: the trace's own pedometer count, or null when nobody counted.
                steps = movement.steps?.takeIf { it >= 0 },
                // ⚠ No HR, no energy, and no estimate standing in for steps either. A trace
                // carries neither (§1.2), and a fabricated number in a health record is worse
                // than an absent one. Energy is §10 Q2.
            )
            // ⚠ No `track`: a promoted workout deliberately does not copy the geometry. The
            // trace id is recoverable from the workout id, so the route is fetched on demand
            // (§4.1) rather than duplicated into a second blob.
            beforeEachWrite()
            repository.addWorkout(workout)
            promoted += workout
        }
        return promoted.sortedByDescending { it.start }
    }

    private fun Long.toLocalDateTime(zone: TimeZone) =
        Instant.fromEpochMilliseconds(this).toLocalDateTime(zone)
}
