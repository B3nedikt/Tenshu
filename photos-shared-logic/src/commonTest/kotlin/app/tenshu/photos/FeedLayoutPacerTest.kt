package app.tenshu.photos

import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.FeedLayout
import app.tenshu.photos.ui.screens.feed.FeedLayoutPacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** How often a shell draws the feed while a sync delivers photographs (`photos-ios.md` §8.20). */
class FeedLayoutPacerTest {

    private fun layout(photos: Int, density: FeedDensity = FeedDensity.Day, columns: Int = density.columns) =
        FeedLayout.of(0, List(photos) { FeedItem.Tile(Photo("/$it.jpg", 1_000L - it, isVideo = false)) }, 0, density, columns)

    @Test
    fun theFirstLayoutIsDrawnAtOnce_andSoIsOneAfterAQuietSpell() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        val first = layout(1)
        assertSame(first, pacer.offer(first, nowMs = 5_000, urgent = false))
        assertNull(pacer.heldDueInMs(5_000))

        val later = layout(2)
        assertSame(later, pacer.offer(later, nowMs = 5_300, urgent = false), "the interval is up")
    }

    @Test
    fun aBurstIsHeld_theLatestWins_andTheLastIsAlwaysDrawn() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        pacer.offer(layout(1), nowMs = 0, urgent = false)

        val burst = List(5) { layout(2 + it) }
        burst.forEachIndexed { i, it -> assertNull(pacer.offer(it, nowMs = 10L + i * 20, urgent = false)) }
        assertEquals(210L, pacer.heldDueInMs(90))
        assertEquals(0L, pacer.heldDueInMs(900), "overdue is due now, never negative")

        assertSame(burst.last(), pacer.release(nowMs = 300), "the latest, and none of the four before it")
        assertNull(pacer.release(nowMs = 300), "drawn once")
        assertNull(pacer.heldDueInMs(300))

        // The release restarts the interval: a burst still running is held again, not drawn.
        val next = layout(9)
        assertNull(pacer.offer(next, nowMs = 400, urgent = false))
        assertEquals(200L, pacer.heldDueInMs(400))
        assertSame(next, pacer.release(nowMs = 600))
    }

    @Test
    fun aHeldLayoutSurvivesUntilItIsReleased_howeverLateTheShellWakes() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        pacer.offer(layout(1), nowMs = 0, urgent = false)
        val last = layout(2)
        assertNull(pacer.offer(last, nowMs = 50, urgent = false))
        assertSame(last, pacer.release(nowMs = 60_000))
    }

    @Test
    fun whatTheUserDidIsNeverHeld() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        pacer.offer(layout(3), nowMs = 0, urgent = false)

        val regrouped = layout(3, FeedDensity.Month)
        assertSame(regrouped, pacer.offer(regrouped, nowMs = 10, urgent = false), "a density change")

        val wider = layout(3, FeedDensity.Month, columns = FeedDensity.Month.columns * 2)
        assertSame(wider, pacer.offer(wider, nowMs = 20, urgent = false), "a column change")

        val underAFinger = layout(4, FeedDensity.Month, columns = FeedDensity.Month.columns * 2)
        assertSame(underAFinger, pacer.offer(underAFinger, nowMs = 30, urgent = true), "a drag, a re-anchor")
    }

    @Test
    fun anUrgentLayoutSupersedesTheHeldOne_soNothingOlderIsDrawnAfterIt() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        pacer.offer(layout(1), nowMs = 0, urgent = false)
        assertNull(pacer.offer(layout(2), nowMs = 10, urgent = false))

        val urgent = layout(3)
        assertSame(urgent, pacer.offer(urgent, nowMs = 20, urgent = true))
        assertNull(pacer.release(nowMs = 300), "the held layout was older than what is drawn")
    }

    @Test
    fun aWidthChangeExpeditesTheNextLayoutOnly() {
        val pacer = FeedLayoutPacer(minIntervalMs = 300)
        pacer.offer(layout(1), nowMs = 0, urgent = false)
        pacer.expediteNext()

        val recut = layout(1)
        assertSame(recut, pacer.offer(recut, nowMs = 10, urgent = false))
        assertNull(pacer.offer(layout(2), nowMs = 20, urgent = false), "and the one after it is paced again")
    }
}
