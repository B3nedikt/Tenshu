package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * §13.5e: which journals describe a session to pick back up, and which describe one that is over.
 * Getting this wrong in either direction is user-visible — a missed resume loses the rest of a run,
 * a wrong resume attaches a second leg to a workout that ended.
 */
class ResumableSessionTest {

    private val startWallMs = 1_786_793_600_000L
    private val killedAt = startWallMs + 600_000L

    private fun journal(stopped: Boolean = false, withWallClock: Boolean = true) = buildList {
        add(RecordingJournalCodec.header(ActivityType.Running, startWallMs))
        add(RecordingJournalCodec.fix(GpsFix(tSec = 600, lat = 48.1, lng = 11.5)))
        if (stopped) add(RecordingJournalCodec.stopped())
        add(if (withWallClock) RecordingJournalCodec.tick(600, killedAt) else RecordingJournalCodec.tick(600))
    }

    @Test
    fun aSessionKilledMomentsAgoResumes() {
        val recovered = assertNotNull(RecordingJournalCodec.decode(journal()))
        assertFalse(recovered.stoppedByUser)
        assertTrue(recovered.isResumable(killedAt + 90_000))
    }

    @Test
    fun aStoppedSessionNeverResumes() {
        // ⚠ The journal deliberately outlives stop() until the draft is saved or discarded, so
        // without the `P` marker every parked draft would resume capture on the next launch.
        val recovered = assertNotNull(RecordingJournalCodec.decode(journal(stopped = true)))
        assertTrue(recovered.stoppedByUser)
        assertFalse(recovered.isResumable(killedAt + 90_000))
    }

    @Test
    fun aStaleSessionIsHistoryNotAnInterruption() {
        val recovered = assertNotNull(RecordingJournalCodec.decode(journal()))
        assertTrue(recovered.isResumable(killedAt + RESUME_WINDOW_MS - 1))
        assertFalse(recovered.isResumable(killedAt + RESUME_WINDOW_MS + 1))
        assertFalse(recovered.isResumable(killedAt + 12 * 60 * 60 * 1000L))
    }

    @Test
    fun anUndatableSessionIsNeverResumed() {
        // A journal from before the wall-clock tick (§13.5b) cannot be aged, and an unaged session
        // could be from last week — review is the safe answer.
        val recovered = assertNotNull(RecordingJournalCodec.decode(journal(withWallClock = false)))
        assertFalse(recovered.isResumable(killedAt + 90_000))
    }

    @Test
    fun aClockSkewedFutureFlushDoesNotResume() {
        val recovered = assertNotNull(RecordingJournalCodec.decode(journal()))
        assertFalse(recovered.isResumable(killedAt - 60_000), "a flush 'in the future' is not evidence")
    }

    @Test
    fun theClockPicksUpWhereTheKilledSessionLeftOff() {
        // The dead stretch counts as neither active nor paused time: 600 s in, killed, resumed on a
        // fresh process clock four minutes later → still 600 s, then ticking on.
        val clock = PauseClock()
        clock.resumeFrom(nowMs = 5_000_000, alreadyActiveSec = 600)
        assertEquals(600, clock.activeSec(5_000_000))
        assertEquals(630, clock.activeSec(5_030_000))
        assertFalse(clock.paused)
    }
}
