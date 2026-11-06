package app.tenshu.fit.data
import app.tenshu.fit.data.WorkoutRead

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.DetectedMovement
import app.tenshu.fit.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectedWorkoutPromoterTest {

    private fun movement(
        traceId: String,
        mode: String = "walk",
        distanceM: Int = 2_400,
        durationSec: Int = 1_800,
        startMs: Long = 1_700_000_000_000,
        steps: Int? = null,
    ) = DetectedMovement(
        traceId = traceId,
        mode = mode,
        modeConfidence = 80,
        distanceM = distanceM,
        startAtEpochMs = startMs,
        endAtEpochMs = startMs + durationSec * 1_000L,
        classifierVersion = 4,
        steps = steps,
    )

    @Test
    fun anEligibleMovementBecomesADetectedWorkout() = runTest {
        val repo = MockFitRepository(this)
        val promoted = DetectedWorkoutPromoter(repo).promote(
            movements = listOf(movement("cap-trace:1")),
            existing = emptyList(),
            beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE,
        )

        assertEquals(1, promoted.size)
        val w = promoted.single()
        assertEquals("detected:cap-trace:1", w.workoutId)
        assertEquals(Source.Detected, w.source)
        assertEquals(ActivityType.Walking, w.activityType)
        assertEquals(2_400, w.distanceM)
        assertEquals(1_800, w.durationSec)
    }

    /**
     * ⚠ No fabricated effort data. An uncounted trace carries no steps, and no trace carries HR or
     * energy; inventing them would put numbers into a health record that no sensor ever produced.
     */
    @Test
    fun aPromotedWorkoutInventsNoEffortData() = runTest {
        val repo = MockFitRepository(this)
        val w = DetectedWorkoutPromoter(repo)
            .promote(listOf(movement("cap-trace:1")), emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).single()

        assertEquals(null, w.steps)
        assertEquals(null, w.avgHr)
        assertEquals(null, w.activeEnergyKj)
        assertTrue(w.route.isEmpty() && w.geoRoute.isEmpty(), "the route is fetched on demand, not copied")
    }

    /** FA-4: the trace's counted steps become the workout's — a real count, not an estimate. */
    @Test
    fun aCountedTraceGivesTheWorkoutItsSteps() = runTest {
        val repo = MockFitRepository(this)
        val w = DetectedWorkoutPromoter(repo)
            .promote(listOf(movement("cap-trace:1", steps = 3_120)), emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE).single()

        assertEquals(3_120, w.steps)
    }

    /** ⚠ A counted zero is still a count; only an uncounted trace leaves `steps` null. */
    @Test
    fun aCountedZeroIsKeptAndAnUncountedTraceStaysNull() = runTest {
        val repo = MockFitRepository(this)
        val promoted = DetectedWorkoutPromoter(repo).promote(
            listOf(
                movement("cap-trace:1", steps = 0, startMs = 1_700_000_000_000),
                movement("cap-trace:2", steps = null, startMs = 1_700_010_000_000),
            ),
            emptyList(),
            beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE,
        ).associateBy { it.workoutId }

        assertEquals(0, promoted.getValue("detected:cap-trace:1").steps)
        assertEquals(null, promoted.getValue("detected:cap-trace:2").steps)
    }

    @Test
    fun theWriteReachesTheRepository() = runTest {
        val repo = MockFitRepository(this)
        DetectedWorkoutPromoter(repo).promote(listOf(movement("cap-trace:1")), emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertEquals("detected:cap-trace:1", (repo.workout("detected:cap-trace:1") as? WorkoutRead.Found)?.workout?.workoutId)
    }

    @Test
    fun anAlreadyPromotedTraceIsNotWrittenAgain() = runTest {
        val repo = MockFitRepository(this)
        val promoter = DetectedWorkoutPromoter(repo)
        val movements = listOf(movement("cap-trace:1"))

        promoter.promote(movements, emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        val alreadyThere = repo.workouts().first()
        val second = promoter.promote(movements, alreadyThere, beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        assertTrue(second.isEmpty())
    }

    /**
     * ⚠⚠ The idempotence that matters is by *construction*, not by the skip-list. Re-running with
     * an empty `existingWorkoutIds` — a caller that forgot, or a second device that has not synced
     * the workout yet — must still converge to one row, because the id is content-addressed.
     */
    @Test
    fun rePromotingWithoutTheSkipListStillConvergesToOneRow() = runTest {
        val repo = MockFitRepository(this)
        val promoter = DetectedWorkoutPromoter(repo)
        val movements = listOf(movement("cap-trace:1"))

        promoter.promote(movements, emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)
        promoter.promote(movements, emptyList(), beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE)

        val all = repo.workouts().first()
        assertEquals(1, all.count { it.workoutId == "detected:cap-trace:1" })
    }

    /**
     * ⚠⚠ The churn guard. Before it, a movement a real workout already covered was promoted and
     * then superseded — and since the row was gone by the next launch, the promoter re-created
     * it, forever. Eight realm writes and four tombstones per launch, replicated to every paired
     * device. Observed on a real phone.
     */
    @Test
    fun aMovementAlreadyCoveredByARealWorkoutIsNeverPromoted() = runTest {
        val repo = MockFitRepository(this)
        val start = 1_700_000_000_000
        // Health Connect already logged this window.
        repo.addWorkout(
            app.tenshu.fit.model.Workout(
                workoutId = "hc-1",
                activityType = ActivityType.Walking,
                source = app.tenshu.fit.model.Source.HealthConnect,
                title = "", device = "",
                start = kotlinx.datetime.Instant.fromEpochMilliseconds(start)
                    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()),
                end = kotlinx.datetime.Instant.fromEpochMilliseconds(start + 1_200_000)
                    .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()),
                durationSec = 1_200,
            )
        )
        val promoted = DetectedWorkoutPromoter(repo).promote(
            listOf(movement("cap-trace:1", startMs = start)),
            repo.workouts().first(),
            beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE,
        )
        assertTrue(promoted.isEmpty(), "a covered movement must not be written at all")
    }

    @Test
    fun ineligibleMovementsAreSkippedEntirely() = runTest {
        val repo = MockFitRepository(this)
        val promoted = DetectedWorkoutPromoter(repo).promote(
            movements = listOf(
                movement("cap-trace:drive", mode = "drive"),
                movement("cap-trace:short", durationSec = 60),
                movement("cap-trace:jitter", distanceM = 100),
            ),
            existing = emptyList(),
            beforeEachWrite = app.tenshu.reachability.OPEN_REALM_GATE,
        )
        assertTrue(promoted.isEmpty())
    }
}
