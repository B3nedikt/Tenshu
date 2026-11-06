package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.Source
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** RC-5 (fit-app.md §13.5): the in-flight journal codec + the recovered-draft rebuild. */
class RecordingJournalTest {

    private val startWallMs = 1_753_440_000_000L

    @Test
    fun encodeDecodeRoundTrip() {
        val fixes = listOf(
            GpsFix(tSec = 1, lat = 48.1, lng = 11.5, altM = 512.5),
            GpsFix(tSec = 2, lat = 48.1001, lng = 11.5002, altM = null),
        )
        val hr = listOf(HrPoint(t = 1, bpm = 121), HrPoint(t = 6, bpm = 132))
        val lines = listOf(RecordingJournalCodec.header(ActivityType.Cycling, startWallMs)) +
            fixes.map { RecordingJournalCodec.fix(it) } +
            hr.map { RecordingJournalCodec.hr(it) } +
            listOf(RecordingJournalCodec.steps(42), RecordingJournalCodec.tick(15))

        val recovered = assertNotNull(RecordingJournalCodec.decode(lines))
        assertEquals(ActivityType.Cycling, recovered.activityType)
        assertEquals(startWallMs, recovered.startWallMs)
        assertEquals(fixes, recovered.route)
        assertEquals(hr, recovered.hr)
        assertEquals(42, recovered.steps)
        // The tick outruns the last sample → it owns the recovered duration.
        assertEquals(15, recovered.activeSec)
    }

    @Test
    fun lastSampleOwnsDurationWhenTicksLag() {
        val lines = listOf(
            RecordingJournalCodec.header(ActivityType.Running, startWallMs),
            RecordingJournalCodec.tick(10),
            RecordingJournalCodec.fix(GpsFix(tSec = 14, lat = 0.0, lng = 0.0)),
        )
        assertEquals(14, RecordingJournalCodec.decode(lines)!!.activeSec)
    }

    @Test
    fun tickCarriesTheWallClockOfTheLastFlush() {
        val killedAt = startWallMs + 1_278_000L
        val lines = listOf(
            RecordingJournalCodec.header(ActivityType.Running, startWallMs),
            RecordingJournalCodec.tick(1263, killedAt - 15_000L),
            RecordingJournalCodec.tick(1278, killedAt),
        )
        val recovered = assertNotNull(RecordingJournalCodec.decode(lines))
        assertEquals(killedAt, recovered.lastFlushWallMs, "the newest flush dates the interruption")
        assertEquals(killedAt, recovered.toDraft().interruptedAtWallMs)
    }

    @Test
    fun aWallClocklessTickStillDatesTheDraft() {
        // A journal written by the pre-`wallMs` engine (or a platform without a wall clock at
        // flush): the field is absent, the line still decodes, and the draft falls back to the
        // derived end rather than losing the date entirely.
        val lines = listOf(
            RecordingJournalCodec.header(ActivityType.Running, startWallMs),
            RecordingJournalCodec.tick(600),
        )
        val recovered = assertNotNull(RecordingJournalCodec.decode(lines))
        assertNull(recovered.lastFlushWallMs)
        assertEquals(600, recovered.activeSec)
        assertEquals(startWallMs + 600_000L, recovered.toDraft().interruptedAtWallMs)
    }

    @Test
    fun tornTailAndGarbageLinesAreSkipped() {
        val lines = listOf(
            RecordingJournalCodec.header(ActivityType.Walking, startWallMs),
            RecordingJournalCodec.fix(GpsFix(tSec = 3, lat = 48.0, lng = 11.0)),
            "F|4|48.0",          // torn mid-write
            "R|nonsense",        // garbage
            "",                  // blank
            RecordingJournalCodec.hr(HrPoint(t = 5, bpm = 99)),
            "F|6|48.001|11.0",   // valid tail after the tear
        )
        val recovered = assertNotNull(RecordingJournalCodec.decode(lines))
        assertEquals(2, recovered.route.size)
        assertEquals(listOf(HrPoint(5, 99)), recovered.hr)
    }

    @Test
    fun emptyOrHeaderlessJournalRecoversNothing() {
        assertNull(RecordingJournalCodec.decode(emptyList()))
        assertNull(RecordingJournalCodec.decode(listOf("F|1|48.0|11.0|")))
        assertNull(RecordingJournalCodec.decode(listOf("H|99|Running|123"))) // unknown version
        assertNull(RecordingJournalCodec.decode(listOf("H|1|Yoga|123"))) // unknown activity
    }

    @Test
    fun recoveredDraftIsReviewReady() {
        val zone = TimeZone.currentSystemDefault()
        val route = (0..119).map { t ->
            GpsFix(tSec = t, lat = 0.0, lng = t * 1.5 / 111_320.0)
        }
        val recovered = RecoveredRecording(
            activityType = ActivityType.Running,
            startWallMs = startWallMs,
            route = route,
            hr = listOf(HrPoint(10, 140), HrPoint(60, 160)),
            steps = 340,
            activeSec = 120,
        )
        val draft = recovered.toDraft(zone)
        assertTrue(draft.recovered, "the review screen notes the provenance")
        val workout = draft.workout
        assertEquals(Source.Recorded, workout.source)
        assertEquals("rec-$startWallMs", workout.workoutId)
        assertEquals(120, workout.durationSec)
        assertEquals(Instant.fromEpochMilliseconds(startWallMs).toLocalDateTime(zone), workout.start)
        // Distance re-summed from the journalled fixes (~178 m of 1.5 m/s walking).
        assertTrue(workout.distanceM!! in 165..190, "was ${workout.distanceM}")
        assertEquals(150, workout.avgHr)
        assertEquals(160, workout.maxHr)
        assertEquals(340, workout.steps)
        assertTrue(workout.hasRoute)
        // The dense track survives → the RC-3 trim suggestion works on recovered drafts too.
        assertNotNull(draft.track)
        assertEquals(120, draft.track!!.route.size)
    }
}
