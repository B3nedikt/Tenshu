package app.tenshu.fit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RC-4 (fit-app.md §13.4): paused time must never come back on resume. */
class PauseClockTest {

    @Test
    fun activeTimeExcludesPausedSpans() {
        val clock = PauseClock()
        clock.start(1_000L)
        assertEquals(10, clock.activeSec(11_000L))
        clock.pause(11_000L)
        assertTrue(clock.paused)
        // Frozen while paused — 5 minutes of standing still count for nothing.
        assertEquals(10, clock.activeSec(311_000L))
        clock.resume(311_000L)
        assertFalse(clock.paused)
        assertEquals(10, clock.activeSec(311_000L))
        assertEquals(15, clock.activeSec(316_000L))
    }

    @Test
    fun repeatedPausesAccumulate() {
        val clock = PauseClock()
        clock.start(0L)
        clock.pause(10_000L)
        clock.resume(20_000L)   // 10 s paused
        clock.pause(30_000L)
        clock.resume(45_000L)   // +15 s paused
        assertEquals(35, clock.activeSec(60_000L)) // 60 s wall − 25 s paused
    }

    @Test
    fun pauseAndResumeAreIdempotent() {
        val clock = PauseClock()
        clock.start(0L)
        clock.resume(5_000L)    // resume while running: no-op
        clock.pause(10_000L)
        clock.pause(20_000L)    // second pause: no-op — the first anchor holds
        clock.resume(30_000L)
        assertEquals(10 + 10, clock.activeSec(40_000L))
    }

    @Test
    fun restartResetsEverything() {
        val clock = PauseClock()
        clock.start(0L)
        clock.pause(10_000L)
        clock.start(100_000L)
        assertFalse(clock.paused)
        assertEquals(5, clock.activeSec(105_000L))
    }
}
