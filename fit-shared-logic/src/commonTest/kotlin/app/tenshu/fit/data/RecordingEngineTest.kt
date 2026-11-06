package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingEngineTest {

    @Test
    fun sensorsBecomeReadyAfterArming() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        assertTrue(engine.sensors().first().any { it.state == SensorState.Searching })
        engine.armSensors()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(engine.sensors().first().all { it.state == SensorState.Ready })
    }

    @Test
    fun runningTicksElapsedDistancePaceAndHr() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        engine.start(ActivityType.Running)
        advanceTimeBy(60_000)
        runCurrent()
        val state = assertIs<RecordingState.Active>(engine.state().first())
        assertEquals(60, state.elapsedSec)
        assertTrue(state.distanceM in 150..220, "distance was ${state.distanceM}")
        assertNotNull(state.paceSecPerKm)
        assertNotNull(state.heartRateBpm)
        assertTrue(state.heartRateBpm!! > 96)
        assertTrue(state.steps in 130..200, "steps counted up over the run, was ${state.steps}")
    }

    @Test
    fun pauseFreezesTheClock() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        engine.start(ActivityType.Walking)
        advanceTimeBy(10_000)
        runCurrent()
        engine.pause()
        val paused = assertIs<RecordingState.Active>(engine.state().first())
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(paused.elapsedSec, (engine.state().first() as RecordingState.Active).elapsedSec)
        engine.resume()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(paused.elapsedSec + 5, (engine.state().first() as RecordingState.Active).elapsedSec)
    }

    @Test
    fun finishPersistsARecordedWorkoutOnMockToday() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        engine.start(ActivityType.Running)
        advanceTimeBy(600_000) // 10 minutes
        runCurrent()
        val finished = engine.finish()
        assertNotNull(finished)
        assertEquals(WorkoutSaveOutcome.Synced, finished.outcome)
        val workout = finished.workout
        assertEquals(Source.Recorded, workout.source)
        assertEquals(600, workout.durationSec)
        assertEquals(repository.today, workout.start.date)
        assertTrue(workout.hasRoute)
        assertNotNull(workout.steps)
        assertTrue(workout.steps!! > 0, "a recorded run persists its step count")
        assertTrue(workout.hrSeries.isNotEmpty())
        assertTrue(workout.laps.isNotEmpty(), "a 10-minute run covers >1 km, expect laps")
        // Persisted newest-first.
        assertEquals(workout.workoutId, repository.workouts().first().first().workoutId)
        assertIs<RecordingState.Idle>(engine.state().first())
    }

    @Test
    fun strengthHasNoDistanceOrRoute() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        engine.start(ActivityType.Strength)
        advanceTimeBy(120_000)
        runCurrent()
        val state = assertIs<RecordingState.Active>(engine.state().first())
        assertEquals(0, state.distanceM)
        assertNull(state.paceSecPerKm)
        val workout = engine.finish()!!.workout
        assertNull(workout.distanceM)
        assertTrue(workout.route.isEmpty())
        assertNotNull(workout.avgHr)
    }

    @Test
    fun stopParksAReviewingDraftWithoutSaving() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        val before = repository.workouts().first().size
        engine.start(ActivityType.Running)
        advanceTimeBy(120_000)
        runCurrent()
        val draft = engine.stop()
        assertNotNull(draft)
        assertEquals(120, draft.workout.durationSec)
        assertTrue(draft.routeSketch.isNotEmpty(), "the mock synthesises an abstract route")
        // Nothing persisted, state parked for review, capture not re-startable over the draft.
        assertEquals(before, repository.workouts().first().size)
        assertIs<RecordingState.Reviewing>(engine.state().first())
        engine.start(ActivityType.Walking)
        assertIs<RecordingState.Reviewing>(engine.state().first(), "start over a draft must not clobber it")
        // stop is not re-entrant from Reviewing.
        assertNull(engine.stop())
        engine.discard()
    }

    @Test
    fun commitAppliesReviewEditsAndPersists() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        engine.start(ActivityType.Running)
        advanceTimeBy(300_000)
        runCurrent()
        engine.stop()
        val finished = engine.commit(DraftEdits(title = "  Evening loop  ", activityType = ActivityType.Walking))
        assertNotNull(finished)
        assertEquals("Evening loop", finished.workout.title, "the title edit is trimmed and applied")
        assertEquals(ActivityType.Walking, finished.workout.activityType)
        assertEquals(finished.workout.workoutId, repository.workouts().first().first().workoutId)
        assertIs<RecordingState.Idle>(engine.state().first())
        // No draft left — a second commit is a no-op.
        assertNull(engine.commit())
    }

    @Test
    fun discardSavesNothing() = runTest {
        val repository = MockFitRepository(scope = backgroundScope)
        val engine = MockRecordingEngine(repository, scope = backgroundScope)
        val before = repository.workouts().first().size
        engine.start(ActivityType.Other)
        advanceTimeBy(30_000)
        runCurrent()
        engine.discard()
        assertIs<RecordingState.Idle>(engine.state().first())
        assertEquals(before, repository.workouts().first().size)
    }
}
