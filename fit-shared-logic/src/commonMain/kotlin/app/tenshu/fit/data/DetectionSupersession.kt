package app.tenshu.fit.data

import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.coroutines.flow.first
import org.tenshu.core.common.util.runCatchingCancellable

/**
 * Which auto-detected workouts an import makes redundant
 * (`fit-automatic-activity-detection.md` D3).
 *
 * ⚠⚠ **Supersession, not pre-checking, and that is forced by ordering.** The phone detects a run
 * at 08:40; the watch syncs the same run to Health Connect at 09:15. A check at *write* time
 * cannot see a row that does not exist yet, and delaying promotion until "nothing else has
 * claimed it" picks an arbitrary window that is wrong whenever a watch syncs late. So the
 * imported row arrives and *removes* the inference it replaces.
 *
 * ⭐ **The M-track rule's "unless" clause dissolves here.** `timeline-movement-capture.md` says a
 * health `Workout` supersedes a captured movement *"unless the workout has no GPS and the capture
 * has a route"* — a real caveat for Moments, where dropping the capture would drop the only
 * geometry. It does not apply at the `Workout` level, because a detected workout **stores no
 * route**: the geometry lives on Timeline's `MovementTrace`, which this never touches (D2 §4.1).
 * Removing a detected workout is therefore lossless — the trace, and Timeline's rendering of it,
 * survive untouched.
 */
object DetectionSupersession {

    /**
     * The `workoutId`s to delete: every [Source.Detected] workout whose window intersects a
     * workout from any other source.
     *
     * ⚠⚠ Stated over the **stored set**, not "imported vs detected", and that generalisation is
     * the fix for a real ordering gap. The first version ran only inside an import, keyed on the
     * rows arriving — but promotion happens at *launch*, which can be after the last import, so a
     * detection created post-import survived until the next one. It was visible on a device as two
     * "Walking" rows at 20:24, one Health Connect and one Detected. Phrased over what is stored,
     * the rule holds whenever it is run, and both callers use the same one.
     *
     * [zone] converts a [Workout]'s `LocalDateTime` to comparable instants. ⚠ It must be the zone
     * the repository projected with, or a comparison near midnight silently shifts by hours.
     *
     * ⚠ Only `Detected` rows are ever removed. A recorded or imported workout is the user's data,
     * whatever it overlaps.
     *
     * ⚠ Overlap alone, deliberately — no activity-type match. The two sides speak different
     * vocabularies (Health Connect's `Hiking` lands as `Other` where ours says `Walking`), so a
     * match test would mostly fail and leave the duplicate it exists to remove.
     *
     * ⚠ Half-open: an import ending exactly when a detection begins is a different activity.
     * Back-to-back sessions are ordinary — walk to the gym, then lift.
     */
    fun supersededIds(existing: List<Workout>, zone: TimeZone): Set<String> {
        val real = existing.filter { it.source != Source.Detected }
        if (real.isEmpty()) return emptySet()
        val realWindows = real.map { it.start.toInstant(zone).toEpochMilliseconds() to it.end.toInstant(zone).toEpochMilliseconds() }
        return existing
            .asSequence()
            .filter { it.source == Source.Detected }
            .filter { detected ->
                val startMs = detected.start.toInstant(zone).toEpochMilliseconds()
                val endMs = detected.end.toInstant(zone).toEpochMilliseconds()
                realWindows.any { (realStart, realEnd) -> startMs < realEnd && realStart < endMs }
            }
            .map { it.workoutId }
            .toSet()
    }
}

/**
 * Removes the detections any real workout has made redundant, and reports how many.
 *
 * ⚠ Runs at **both** moments a duplicate can appear — after an import lands, and after a
 * promotion — because either side can arrive second. Idempotent and cheap when there is nothing
 * to do; a failure is the caller's to swallow, since neither an import nor a launch should fail
 * over a tidy-up.
 */
suspend fun FitRepository.supersedeDetectedWorkouts(
    zone: TimeZone,
    /**
     * ADR 0025 §8.6 write boundary: runs before EACH delete and throws to refuse it, so a realm gate
     * that starts holding half-way stops the deletes there. ⚠⚠ Required, never defaulted: a no-op
     * default is how `AutoDetectBootstrap`'s launch-time supersession skipped the gate (Codex P1 on
     * d2a13fe22).
     */
    beforeEachDelete: suspend () -> Unit,
): Int {
    val existing = runCatchingCancellable { workouts().first() }.getOrNull() ?: return 0
    val ids = DetectionSupersession.supersededIds(existing, zone)
    ids.forEach {
        beforeEachDelete()
        deleteWorkout(it)
    }
    return ids.size
}
