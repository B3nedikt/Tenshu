package app.tenshu.timeline.data.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What "during this moment" means (`timeline-photos.md` §7 Q1).
 *
 * The selection itself moved into the proxy with T-P2; this is the half Timeline keeps, and it is
 * the half that decides whether a photo lands on the right row.
 */
class PhotoWindowTest {

    private val start = 1_700_000_000_000L
    private val minWindow = 15 * 60 * 1000L

    private fun windowOf(durationSec: Long) =
        PhotoSelection.window(startMs = start, durationSec = durationSec, minWindowMs = minWindow)

    @Test
    fun aMomentsWindowRunsForItsDuration() {
        val window = windowOf(durationSec = 3600)
        assertEquals(start, window.first)
        assertEquals(start + 3_600_000L - 1, window.last)
    }

    @Test
    fun aDurationlessMomentStillGetsAWindow() {
        // An Event is stamped at an instant. A zero-width window would match nothing at all — no
        // photo is taken in the same millisecond a moment is recorded.
        assertEquals(minWindow, windowOf(durationSec = 0).let { it.last - it.first + 1 })
    }

    @Test
    fun aShortMomentIsWidenedToTheFloorRatherThanShrunk() {
        assertEquals(minWindow, windowOf(durationSec = 60).let { it.last - it.first + 1 })
    }

    @Test
    fun theWindowIsHalfOpenSoAdjacentMomentsCannotBothClaimTheSamePhoto() {
        val first = windowOf(durationSec = 3600)
        val second = PhotoSelection.window(start + 3_600_000L, durationSec = 3600, minWindowMs = minWindow)
        val boundary = start + 3_600_000L
        assertTrue(boundary !in first, "the boundary millisecond belongs to the next moment only")
        assertTrue(boundary in second)
    }
}
