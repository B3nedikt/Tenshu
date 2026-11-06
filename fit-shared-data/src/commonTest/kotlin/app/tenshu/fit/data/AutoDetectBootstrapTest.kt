package app.tenshu.fit.data

import app.tenshu.fit.model.DetectedMovement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠ These assert the **reason**, not merely that nothing was written. A pass that no-ops for the
 * wrong reason looks identical from the outside to one that no-ops correctly — which is exactly
 * what made the first on-device pass undiagnosable.
 */
class AutoDetectBootstrapTest {

    /**
     * ⚠⚠ `enabled()` deliberately mimics the real implementation: seeded with the **default**,
     * then the stored value. Anything taking the head of that stream reads the default forever —
     * the shipped bug the on-device logging caught. `enabledOnce()` is the one-shot truth.
     */
    private class Prefs(private val stored: Boolean) : AutoDetectPreferences {
        val state = MutableStateFlow(stored)
        override fun enabled(): Flow<Boolean> =
            state.onStart { emit(FitPreferences.AutoDetectActivities.default) }
        override suspend fun enabledOnce(): Boolean = state.value
        override suspend fun setEnabled(value: Boolean) { state.value = value }
        override suspend fun promptAnswered(): Boolean = true
        override suspend fun markPromptAnswered() = Unit
    }

    private class Repo(
        private val movements: List<DetectedMovement>,
        delegate: FitRepository,
    ) : FitRepository by delegate {
        override fun detectedMovements(): Flow<List<DetectedMovement>> = flowOf(movements)
    }

    private fun movement(
        traceId: String = "cap-trace:1",
        mode: String = "walk",
        distanceM: Int = 2_400,
        durationSec: Int = 1_800,
    ) = DetectedMovement(traceId, mode, 80, distanceM, 1_700_000_000_000, 1_700_000_000_000 + durationSec * 1_000L, 4)

    /** Built without starting the init pass, so each test drives `runOnce` deliberately. */
    private fun bootstrap(movements: List<DetectedMovement>, prefs: Prefs, scope: CoroutineScope) =
        AutoDetectBootstrap(Repo(movements, MockFitRepository(scope)), prefs, scope)

    /**
     * A repository with a controllable realm gate, a real workout that supersedes a stored
     * detection, and a recorded delete log — for the launch-time pass under the gate.
     */
    private class GatedRepo(
        delegate: FitRepository,
        /** Whether a stored detection exists for supersession to remove. */
        withSupersedable: Boolean = true,
    ) : FitRepository by delegate {
        private val zone = kotlinx.datetime.TimeZone.UTC
        private fun workout(id: String, source: app.tenshu.fit.model.Source, startMs: Long, sec: Int) =
            app.tenshu.fit.model.Workout(
                workoutId = id,
                activityType = app.tenshu.fit.model.ActivityType.Walking,
                source = source,
                title = "",
                device = "",
                start = kotlin.time.Instant.fromEpochMilliseconds(startMs).toLocalDateTime(zone),
                end = kotlin.time.Instant.fromEpochMilliseconds(startMs + sec * 1_000L).toLocalDateTime(zone),
                durationSec = sec,
            )
        // A real import at noon, and a detection inside it that supersession must remove.
        val stored = MutableStateFlow(
            listOfNotNull(
                workout("hc-noon", app.tenshu.fit.model.Source.HealthConnect, 1_700_050_000_000, 3_600),
                if (withSupersedable) workout("det-noon", app.tenshu.fit.model.Source.Detected, 1_700_050_600_000, 600) else null,
            ),
        )
        val deleted = mutableListOf<String>()
        val added = mutableListOf<String>()
        override suspend fun addWorkout(
            workout: app.tenshu.fit.model.Workout,
            track: app.tenshu.fit.model.RecordedTrack?,
        ): WorkoutSaveOutcome {
            added += workout.workoutId
            stored.value = stored.value + workout
            return WorkoutSaveOutcome.Synced
        }
        val writable = MutableStateFlow(false)
        var refusing = false
        val releases = MutableStateFlow(0L)

        override fun workouts(): Flow<List<app.tenshu.fit.model.Workout>> = stored
        override suspend fun deleteWorkout(id: String): Boolean {
            deleted += id
            stored.value = stored.value.filterNot { it.workoutId == id }
            return true
        }
        override suspend fun awaitRealmWritable() { writable.first { it } }
        override fun refuseIfRealmWritesHeld() {
            if (refusing || !writable.value) throw app.tenshu.reachability.RealmWritesHeldException()
        }
        override fun realmReleaseMarker() = releases.value
        override suspend fun onEachRealmRelease(since: Long, action: suspend () -> Unit) {
            var seen = since
            releases.collect { if (it != seen) { seen = it; action() } }
        }
        fun release() { refusing = false; writable.value = true; releases.value += 1 }
    }

    /** A movement the promoter accepts, so the pass reaches supersession. */
    private fun promotable() = movement(traceId = "cap-trace:walk", distanceM = 2_400, durationSec = 1_800)

    /**
     * ⚠⚠ Codex P1 on d2a13fe22: the launch-time pass used to run straight away and supersede with
     * NO gate (a defaulted no-op `beforeEachDelete`). Under PENDING it must delete nothing, and
     * complete once the gate releases.
     */
    @Test
    fun launchSupersessionUnderAHeldGateDeletesNothingThenCompletesOnRelease() = runTest {
        val repo = GatedRepo(Repo(listOf(promotable()), MockFitRepository(backgroundScope)))
        AutoDetectBootstrap(repo, Prefs(stored = true), backgroundScope, zone = kotlinx.datetime.TimeZone.UTC)
        runCurrent()
        assertTrue(repo.deleted.isEmpty(), "nothing deleted while the realm is unknown")

        repo.release()
        runCurrent()
        assertEquals(listOf("det-noon"), repo.deleted, "the held pass completed on release")
    }

    /** A refusal in the middle of the pass (the gate closed after the entry wait) re-runs on release. */
    @Test
    fun aSupersessionRefusedMidPassReRunsOnRelease() = runTest {
        val repo = GatedRepo(Repo(listOf(promotable()), MockFitRepository(backgroundScope)))
        repo.writable.value = true
        repo.refusing = true // the gate starts holding after the entry wait
        AutoDetectBootstrap(repo, Prefs(stored = true), backgroundScope, zone = kotlinx.datetime.TimeZone.UTC)
        runCurrent()
        assertTrue(repo.deleted.isEmpty(), "the refused delete was not made")

        repo.release()
        runCurrent()
        assertEquals(listOf("det-noon"), repo.deleted, "re-run on release")
    }

    /**
     * ⚠⚠ Codex P1 on b25d524d2: the promotions themselves go through the gate, one check each. The
     * gate closes after the entry wait and there is nothing to supersede — so before this, the pass
     * wrote its promotion into the ownerless store and completed normally, never to re-run. Now
     * the promotion is refused, the pass is held, and the release re-runs it.
     */
    @Test
    fun aPromotionRefusedMidPassWritesNothingAndReRunsOnRelease() = runTest {
        val repo = GatedRepo(Repo(listOf(promotable()), MockFitRepository(backgroundScope)), withSupersedable = false)
        repo.writable.value = true
        repo.refusing = true // the gate starts holding after the entry wait
        AutoDetectBootstrap(repo, Prefs(stored = true), backgroundScope, zone = kotlinx.datetime.TimeZone.UTC)
        runCurrent()
        assertTrue(repo.added.isEmpty(), "no promotion written while the gate holds")

        repo.release()
        runCurrent()
        assertEquals(listOf("detected:cap-trace:walk"), repo.added, "re-run on release")
    }

    @Test
    fun aDisabledToggleSaysSoRatherThanGoingQuiet() = runTest {
        val outcome = bootstrap(listOf(movement()), Prefs(stored = false), this).runOnce()
        assertEquals(AutoDetectBootstrap.Outcome.Disabled, outcome)
    }

    /** ⚠ The case the device pass could not distinguish from a bug. */
    @Test
    fun noTracesIsReportedDistinctlyFromNothingPromotable() = runTest {
        assertEquals(
            AutoDetectBootstrap.Outcome.NoTraces,
            bootstrap(emptyList(), Prefs(stored = true), this).runOnce(),
        )
        assertEquals(
            AutoDetectBootstrap.Outcome.NonePromotable(
                traceCount = 2,
                byReason = mapOf(
                    WorkoutPromotion.Rejection.NotAnExercise to 1,
                    WorkoutPromotion.Rejection.TooShort to 1,
                ),
            ),
            bootstrap(
                listOf(movement(mode = "drive"), movement(traceId = "t2", durationSec = 30)),
                Prefs(stored = true),
                this,
            ).runOnce(),
        )
    }

    /**
     * ⚠⚠ The regression test for the shipped bug: with the toggle **on**, a reader that takes the
     * head of the seeded reactive flow sees the `false` default and reports `Disabled`. Auto
     * detection was dead on every device where the user had switched it on, and nothing failed.
     */
    @Test
    fun anEnabledToggleIsNotMisreadAsDisabledByTheSeededFlow() = runTest {
        val prefs = Prefs(stored = true)
        assertEquals(false, prefs.enabled().first(), "the seed really is the head of the stream")
        val outcome = bootstrap(listOf(movement()), prefs, this).runOnce()
        assertTrue(
            outcome is AutoDetectBootstrap.Outcome.Promoted,
            "expected a promotion, got $outcome — the one-shot read took the seed",
        )
    }

    /**
     * ⚠ The histogram is the point: "0 of 15" says the feature is quiet, while a breakdown says
     * whether the floor is wrong or the traces simply are not workouts (§10 Q1).
     */
    @Test
    fun theRejectionBreakdownCountsEachReason() = runTest {
        val outcome = bootstrap(
            listOf(
                movement(mode = "drive"),
                movement(traceId = "t2", mode = "transit"),
                movement(traceId = "t3", durationSec = 60),
                movement(traceId = "t4", distanceM = 10),
            ),
            Prefs(stored = true),
            this,
        ).runOnce() as AutoDetectBootstrap.Outcome.NonePromotable

        assertEquals(4, outcome.traceCount)
        assertEquals(2, outcome.byReason[WorkoutPromotion.Rejection.NotAnExercise])
        assertEquals(1, outcome.byReason[WorkoutPromotion.Rejection.TooShort])
        assertEquals(1, outcome.byReason[WorkoutPromotion.Rejection.TooFar])
    }

    @Test
    fun aPromotionReportsBothCounts() = runTest {
        val outcome = bootstrap(
            listOf(movement(), movement(traceId = "cap-trace:2", mode = "drive")),
            Prefs(stored = true),
            this,
        ).runOnce()
        assertEquals(AutoDetectBootstrap.Outcome.Promoted(count = 1, traceCount = 2), outcome)
    }

    /** A second launch over the same traces is a no-op — and says which kind of no-op. */
    @Test
    fun aSecondPassReportsNothingNew() = runTest {
        val prefs = Prefs(stored = true)
        val repo = Repo(listOf(movement()), MockFitRepository(this))
        val boot = AutoDetectBootstrap(repo, prefs, this)

        assertTrue(boot.runOnce() is AutoDetectBootstrap.Outcome.Promoted)
        assertEquals(AutoDetectBootstrap.Outcome.NothingNew(traceCount = 1), boot.runOnce())
    }
}
