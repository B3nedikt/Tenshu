package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * RC-3 (fit-app.md §13.3): the idle-trim detector over synthetic 1 Hz tracks, and the
 * trimmed-draft recompute both the review preview and the commit share.
 */
class IdleTrimTest {

    // ── detectIdleTrim ──────────────────────────────────────────────────────────────────

    @Test
    fun leadingDwellThenWalkSuggestsAStartTrim() {
        val route = dwell(0..59, atXM = 0.0) + walk(60..179, fromXM = 0.0)
        val suggestion = detectIdleTrim(route, durationSec = 180)
        assertNotNull(suggestion)
        val start = assertNotNull(suggestion.trimStartSec, "60 s of standing at the start is trimmable")
        assertTrue(start in 55..75, "boundary anchors near the real movement start, was $start")
        // Moving right up to the end — nothing to trim there.
        assertNull(suggestion.trimEndSec)
    }

    @Test
    fun walkThenTrailingDwellSuggestsAnEndTrim() {
        val route = walk(0..119, fromXM = 0.0) + dwell(120..269, atXM = 180.0)
        val suggestion = detectIdleTrim(route, durationSec = 270)
        assertNotNull(suggestion)
        assertNull(suggestion.trimStartSec)
        val end = assertNotNull(suggestion.trimEndSec, "150 s of standing at the end is trimmable")
        assertTrue(end in 105..130, "boundary anchors near where movement stopped, was $end")
    }

    @Test
    fun dwellAtBothEndsSuggestsBoth() {
        val route = dwell(0..59, atXM = 0.0) + walk(60..179, fromXM = 0.0) + dwell(180..269, atXM = 180.0)
        val suggestion = detectIdleTrim(route, durationSec = 270)
        assertNotNull(suggestion)
        assertNotNull(suggestion.trimStartSec)
        assertNotNull(suggestion.trimEndSec)
        assertTrue(suggestion.trimStartSec!! < suggestion.trimEndSec!!)
    }

    @Test
    fun shortIdleIsNeverNagged() {
        // 20 s of fumbling < the 45 s floor.
        val route = dwell(0..19, atXM = 0.0) + walk(20..179, fromXM = 0.0)
        assertNull(detectIdleTrim(route, durationSec = 180))
    }

    @Test
    fun aLoneGpsSpikeInsideTheDwellIsNotMovement() {
        val spiked = dwell(0..59, atXM = 0.0).map { fix ->
            if (fix.tSec == 30) fix.copy(lng = 50.0 * DEGREES_PER_METRE) else fix
        } + walk(60..179, fromXM = 0.0)
        val suggestion = detectIdleTrim(spiked, durationSec = 180)
        val start = assertNotNull(assertNotNull(suggestion).trimStartSec)
        assertTrue(start in 55..75, "the spike must not end the dwell at t=30, was $start")
    }

    @Test
    fun anAllIdleRecordingSuggestsNothing() {
        // Trimming the entire recording is nonsense — no suggestion.
        assertNull(detectIdleTrim(dwell(0..119, atXM = 0.0), durationSec = 120))
    }

    @Test
    fun tooFewFixesSuggestNothing() {
        assertNull(detectIdleTrim(walk(0..1, fromXM = 0.0), durationSec = 300))
    }

    // ── RecordingDraft.trimmed ──────────────────────────────────────────────────────────

    @Test
    fun trimmedRewindowsAndRecomputes() {
        val draft = draft(route = dwell(0..59, atXM = 0.0) + walk(60..179, fromXM = 0.0))
        val trimmed = draft.trimmed(trimStartSec = 60, trimEndSec = null)

        assertEquals(120, trimmed.workout.durationSec)
        // Start shifts by the trimmed lead; the end stamp is unchanged in wall time.
        assertEquals(LocalDateTime(2026, 7, 25, 10, 1, 0), trimmed.workout.start)
        assertEquals(LocalDateTime(2026, 7, 25, 10, 3, 0), trimmed.workout.end)
        // Fixes are windowed and re-based to the new zero.
        val track = assertNotNull(trimmed.track)
        assertEquals(0, track.route.first().tSec)
        assertEquals(119, track.route.last().tSec)
        // The dwell jitter no longer contributes distance; the walk's ~178 m remains.
        val distance = assertNotNull(trimmed.workout.distanceM)
        assertTrue(distance in 165..190, "was $distance")
        // HR windowed + re-based; energy scales linearly with duration (120/180 of 180 kJ).
        assertEquals(0, track.hr.first().t)
        assertTrue(track.hr.all { it.t <= 120 })
        assertEquals(120, trimmed.workout.activeEnergyKj)
        // The cumulative-only step total is deliberately kept.
        assertEquals(draft.workout.steps, trimmed.workout.steps)
    }

    @Test
    fun nothingToTrimIsIdentity() {
        val draft = draft(route = walk(0..179, fromXM = 0.0))
        assertSame(draft, draft.trimmed(null, null))
        assertSame(draft, draft.trimmed(0, 180))
        // No dense track (the mock engine) → nothing to re-window.
        val trackless = draft.copy(track = null)
        assertSame(trackless, trackless.trimmed(60, null))
    }

    @Test
    fun applyingComposesTrimWithTitleAndActivityEdits() {
        val draft = draft(route = dwell(0..59, atXM = 0.0) + walk(60..179, fromXM = 0.0))
        val applied = draft.applying(
            DraftEdits(title = "Lunch walk", activityType = ActivityType.Walking, trimStartSec = 60),
        )
        assertEquals("Lunch walk", applied.workout.title)
        assertEquals(ActivityType.Walking, applied.workout.activityType)
        assertEquals(120, applied.workout.durationSec)
    }

    // ── synthetic-track helpers (1 Hz, metres around the equatorial origin) ─────────────

    private fun dwell(tRange: IntRange, atXM: Double): List<GpsFix> =
        tRange.map { t -> fix(t, atXM + if (t % 2 == 0) 3.0 else -3.0) }

    private fun walk(tRange: IntRange, fromXM: Double, speedMps: Double = 1.5): List<GpsFix> =
        tRange.map { t -> fix(t, fromXM + (t - tRange.first) * speedMps) }

    private fun fix(t: Int, xM: Double) = GpsFix(tSec = t, lat = 0.0, lng = xM * DEGREES_PER_METRE)

    private fun draft(route: List<GpsFix>) = RecordingDraft(
        workout = Workout(
            workoutId = "rec-test",
            activityType = ActivityType.Running,
            source = Source.Recorded,
            title = "",
            device = "",
            start = LocalDateTime(2026, 7, 25, 10, 0, 0),
            end = LocalDateTime(2026, 7, 25, 10, 3, 0),
            durationSec = 180,
            distanceM = 200,
            activeEnergyKj = 180,
            avgHr = 120,
            maxHr = 150,
            steps = 2400,
            hasRoute = true,
            hrSeries = (0..180 step 5).map { HrPoint(it, 110 + it % 30) },
        ),
        track = RecordedTrack(
            route = route,
            hr = (0..180 step 5).map { HrPoint(it, 110 + it % 30) },
        ),
    )

    private companion object {
        const val DEGREES_PER_METRE = 1.0 / 111_320.0
    }
}
