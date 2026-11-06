package app.tenshu.photos

import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.groupIntoDays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The feed's day sectioning (`photos-app.md` S1) — arithmetic, so it is tested without a renderer. */
class PhotoDayGroupingTest {

    private val day = 86_400_000L

    /** A fixed-offset "local midnight", so the test says nothing about the device's timezone. */
    private fun dayStart(ms: Long): Long = ms - (ms % day)

    private fun photoAt(ms: Long) = Photo("/p$ms.jpg", ms, isVideo = false)

    @Test
    fun photosFromOneDayShareOneHeader() {
        val days = listOf(photoAt(3 * day + 5), photoAt(3 * day + 1)).groupIntoDays(::dayStart)

        assertEquals(1, days.size)
        assertEquals(2, days.single().photos.size)
    }

    @Test
    fun daysComeBackNewestFirstAndKeepTheirOrderWithin() {
        val photos = listOf(
            photoAt(5 * day + 20),
            photoAt(5 * day + 10),
            photoAt(2 * day + 30),
        )

        val days = photos.groupIntoDays(::dayStart)

        assertEquals(listOf(5 * day, 2 * day), days.map { it.dayStartMs })
        assertEquals(listOf(5 * day + 20, 5 * day + 10), days.first().photos.map { it.capturedAtMs })
    }

    @Test
    fun aDayThatStraddlesTwoPagesStillGetsOneHeader() {
        // ⚠ The reason grouping runs over the accumulated list rather than per page: page 1 ends
        // mid-afternoon and page 2 continues the same day.
        val page1 = listOf(photoAt(9 * day + 900), photoAt(9 * day + 800))
        val page2 = listOf(photoAt(9 * day + 700), photoAt(8 * day + 10))

        val days = (page1 + page2).groupIntoDays(::dayStart)

        assertEquals(2, days.size)
        assertEquals(3, days.first().photos.size)
    }

    @Test
    fun anEmptyFeedHasNoDays() {
        assertTrue(emptyList<Photo>().groupIntoDays(::dayStart).isEmpty())
    }
}
