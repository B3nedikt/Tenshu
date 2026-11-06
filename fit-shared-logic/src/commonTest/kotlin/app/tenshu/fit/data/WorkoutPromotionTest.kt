package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DetectedMovement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutPromotionTest {

    private fun movement(
        traceId: String = "cap-trace:1000",
        mode: String = "walk",
        confidence: Int = 80,
        distanceM: Int = 2_400,
        durationSec: Int = 1_800,
    ) = DetectedMovement(
        traceId = traceId,
        mode = mode,
        modeConfidence = confidence,
        distanceM = distanceM,
        startAtEpochMs = 1_000L,
        endAtEpochMs = 1_000L + durationSec * 1_000L,
        classifierVersion = 4,
    )

    @Test
    fun aRealWalkPromotes() {
        val d = WorkoutPromotion.decide(movement())
        assertEquals(
            WorkoutPromotion.Decision.Promote("detected:cap-trace:1000", ActivityType.Walking),
            d,
        )
    }

    @Test
    fun modeMapsToFitsVocabulary() {
        assertEquals(ActivityType.Walking, WorkoutPromotion.activityTypeFor("walk"))
        assertEquals(ActivityType.Running, WorkoutPromotion.activityTypeFor("run"))
        assertEquals(ActivityType.Cycling, WorkoutPromotion.activityTypeFor("cycle"))
    }

    /**
     * ⚠ The most important case in this file. Sitting in a car is the single most common thing
     * the classifier sees; logging it as a workout would discredit every real detection beside it.
     */
    @Test
    fun nonExerciseModesNeverPromote() {
        listOf("drive", "transit", "flight", "unknown", "", "WALKING_SOMETHING").forEach { mode ->
            assertNull(WorkoutPromotion.activityTypeFor(mode), "mode '$mode' must not map")
            assertEquals(
                WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.NotAnExercise),
                WorkoutPromotion.decide(movement(mode = mode)),
                "mode '$mode' must not promote",
            )
        }
    }

    @Test
    fun modeMatchingIsCaseInsensitive() {
        assertEquals(ActivityType.Running, WorkoutPromotion.activityTypeFor("RUN"))
    }

    @Test
    fun eachThresholdRejectsOnItsOwn() {
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.TooShort),
            WorkoutPromotion.decide(movement(durationSec = 4 * 60)),
        )
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.TooFar),
            WorkoutPromotion.decide(movement(distanceM = 499)),
        )
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.LowConfidence),
            WorkoutPromotion.decide(movement(confidence = 9)),
        )
    }

    @Test
    fun thresholdsAreBoundariesNotRanges() {
        val t = WorkoutPromotion.Thresholds()
        assertTrue(
            WorkoutPromotion.decide(
                movement(durationSec = t.minDurationSec, distanceM = t.minDistanceM, confidence = t.minConfidence)
            ) is WorkoutPromotion.Decision.Promote,
            "exactly-at-threshold must promote",
        )
    }

    /**
     * ⭐ The corpus that settled the thresholds (§10 Q1, 2026-08-10). Real traces from a real
     * device, reduced to the four fields the decision reads. The old 10 min / confidence-50 bar
     * promoted **none** of these; three of the four are workouts by any ordinary reading.
     */
    /**
     * ⚠⚠ Option 2 for the Tier-1 collision: a bout with **no distance** must promote. The floor
     * exists to reject GPS jitter, and a step-derived bout has no GPS to jitter — failing it
     * would refuse every Tier-1 detection by a test it structurally cannot pass.
     */
    @Test
    fun aDistancelessBoutSkipsTheDistanceGateRatherThanFailingIt() {
        val bout = movement(distanceM = 0).copy(distanceM = null)
        assertTrue(WorkoutPromotion.decide(bout) is WorkoutPromotion.Decision.Promote)
    }

    /** ⚠ And the gate still bites where distance IS known — skipping is not disabling. */
    @Test
    fun aKnownShortDistanceStillFails() {
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.TooFar),
            WorkoutPromotion.decide(movement(distanceM = 100)),
        )
    }

    @Test
    fun theRealCorpusThatSettledTheThresholds() {
        // run, 2.7 km in 8 min — refused as TooShort by the old floor.
        assertTrue(WorkoutPromotion.decide(movement(mode = "run", durationSec = 491, distanceM = 2_728, confidence = 35)) is WorkoutPromotion.Decision.Promote)
        // run, 2.5 km in 12.75 min — refused at confidence 14 by the old floor.
        assertTrue(WorkoutPromotion.decide(movement(mode = "run", durationSec = 765, distanceM = 2_507, confidence = 14)) is WorkoutPromotion.Decision.Promote)
        // walk, 1 km in 6.3 min.
        assertTrue(WorkoutPromotion.decide(movement(mode = "walk", durationSec = 380, distanceM = 1_020, confidence = 36)) is WorkoutPromotion.Decision.Promote)

        // ⚠ And what must still be refused, from the same corpus:
        //   a train leg that scored HIGH confidence — the mode filter, not the score, stops it.
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.NotAnExercise),
            WorkoutPromotion.decide(movement(mode = "transit", durationSec = 3_084, distanceM = 54_760, confidence = 70)),
        )
        //   a 3-minute corridor wander.
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.TooShort),
            WorkoutPromotion.decide(movement(mode = "walk", durationSec = 184, distanceM = 924, confidence = 0)),
        )
        //   a 6-minute shuffle that never went anywhere.
        assertEquals(
            WorkoutPromotion.Decision.Reject(WorkoutPromotion.Rejection.TooFar),
            WorkoutPromotion.decide(movement(mode = "walk", durationSec = 353, distanceM = 410, confidence = 41)),
        )
    }

    /**
     * D2's convergence argument in one assertion: the id is a function of the trace id alone, so
     * two devices promoting the same synced trace mint the same row.
     */
    @Test
    fun theWorkoutIdDependsOnNothingButTheTraceId() {
        val a = WorkoutPromotion.decide(movement(confidence = 55))
        val b = WorkoutPromotion.decide(movement(confidence = 99, distanceM = 9_999))
        assertEquals(
            (a as WorkoutPromotion.Decision.Promote).workoutId,
            (b as WorkoutPromotion.Decision.Promote).workoutId,
        )
    }

    /**
     * ⚠ Pins the deliberate omission: `classifierVersion` is NOT in the id. A re-classification
     * must correct the existing workout, never mint a second one.
     */
    @Test
    fun reclassificationDoesNotMintASecondWorkout() {
        val v4 = movement().copy(classifierVersion = 4)
        val v11 = movement().copy(classifierVersion = 11)
        assertEquals(
            WorkoutPromotion.workoutIdFor(v4.traceId),
            WorkoutPromotion.workoutIdFor(v11.traceId),
        )
    }

    @Test
    fun theTraceIdRoundTripsOutOfTheWorkoutId() {
        assertEquals("cap-trace:1000", WorkoutPromotion.traceIdOf("detected:cap-trace:1000"))
        // A hand-recorded or imported workout is not a promotion and has no trace behind it.
        assertNull(WorkoutPromotion.traceIdOf("rec-123"))
        assertNull(WorkoutPromotion.traceIdOf("detected:"))
    }
}
