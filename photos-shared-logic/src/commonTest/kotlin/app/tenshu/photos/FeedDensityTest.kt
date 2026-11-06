package app.tenshu.photos

import androidx.paging.testing.asSnapshot
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.model.FeedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.vmTest

/**
 * The feed's zoom control (`photos-app.md` S1, §28).
 *
 * ⚠⚠ These exist because S1 calls the density *"the standard idiom [that] costs only layout"*, and
 * it does not: the density decides what a section IS, so it changes the separator rule upstream of
 * `cachedIn`. A column-count-only implementation would pass a layout test and show a year of
 * photographs under headers still reading "17 December".
 */
class FeedDensityTest {

    @BeforeTest fun setUpMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    private companion object {
        const val DAY = 86_400_000L
    }

    /**
     * ⚠ A crude but monotonic grouper, so the test owns the arithmetic rather than a calendar:
     * 30-day "months" and 360-day "years" are wrong as dates and exactly right as a grouping rule.
     */
    private fun sectionStart(density: FeedDensity, ms: Long): Long = when (density) {
        FeedDensity.Mosaic, FeedDensity.Day -> ms / DAY * DAY
        FeedDensity.Month -> ms / (30 * DAY) * (30 * DAY)
        FeedDensity.Year -> ms / (360 * DAY) * (360 * DAY)
    }

    /** One photograph a day for a year, newest first. */
    private fun photos(days: Int) = List(days) { i ->
        Photo("/p$i.jpg", (days - i) * DAY, isVideo = false, contentId = "c$i")
    }

    private fun feed(vms: OwnedViewModels, days: Int) = vms.own(PhotosFeedViewModel(
        MockPhotosRepository(photos = photos(days)),
        sectionStartOf = ::sectionStart,
    ))

    private suspend fun headers(vm: PhotosFeedViewModel): List<Long> =
        vm.items.asSnapshot().filterIsInstance<FeedItem.DayHeader>().map { it.dayStartMs }

    @Test
    fun theFeedOpensAsTheMosaic() = vmTest { vms ->
        // ⚠ The maintainer's call (§32): the day view with some photographs drawn larger is what
        // the feed opens on, and the uniform grid is one tap away.
        assertEquals(FeedDensity.Mosaic, feed(vms, 3).density.value)
    }

    /**
     * ⚠⚠ The property the whole feature rests on. 90 photographs on 90 different days give 90 day
     * headers and 3 month headers — a density that only changed the column count would give 90
     * either way.
     */
    @Test
    fun acoarserDensityProducesFewerHeadersOverTheSamePhotographs() = vmTest { vms ->
        val vm = feed(vms, 90)
        val byDay = headers(vm).size

        vm.setDensity(FeedDensity.Month)
        val byMonth = headers(vm).size

        vm.setDensity(FeedDensity.Year)
        val byYear = headers(vm).size

        assertEquals(90, byDay, "one header per day")
        assertTrue(byMonth in 3..4, "roughly one per 30-day month, got $byMonth")
        assertEquals(1, byYear, "and one for the whole year")
    }

    @Test
    fun theHeadersMoveToTheSectionStart_notJustFewerOfThem() = vmTest { vms ->
        // ⚠ Count alone would pass for an implementation that dropped headers at random; the
        // remaining ones must sit on the section boundary.
        val vm = feed(vms, 90)
        vm.setDensity(FeedDensity.Month)

        assertTrue(
            headers(vm).all { it % (30 * DAY) == 0L },
            "every header is a month start: " + headers(vm),
        )
    }

    @Test
    fun goingBackToDayRestoresTheDayHeaders() = vmTest { vms ->
        // ⚠ The control: a one-way coarsening would pass every assertion above.
        val vm = feed(vms, 30)
        vm.setDensity(FeedDensity.Year)
        assertEquals(1, headers(vm).size)

        vm.setDensity(FeedDensity.Day)
        assertEquals(30, headers(vm).size, "the day grouping came back")
    }

    @Test
    fun theControlCyclesThroughEveryDensityAndReturns() = vmTest { vms ->
        assertEquals(FeedDensity.Day, FeedDensity.Mosaic.next())
        assertEquals(FeedDensity.Month, FeedDensity.Day.next())
        assertEquals(FeedDensity.Year, FeedDensity.Month.next())
        assertEquals(FeedDensity.Mosaic, FeedDensity.Year.next(), "one button, four states, wrapping")
    }

    @Test
    fun aCoarserDensityPacksMoreColumns() = vmTest { vms ->
        // ⚠ Held here rather than in the screen so the grouping and the layout cannot drift apart.
        assertTrue(
            FeedDensity.Mosaic.columns == FeedDensity.Day.columns &&
                FeedDensity.Day.columns < FeedDensity.Month.columns &&
                FeedDensity.Month.columns < FeedDensity.Year.columns,
            "columns grow as the grouping coarsens",
        )
    }

    @Test
    fun everyPhotographSurvivesADensityChange() = vmTest { vms ->
        // ⚠⚠ The rebuild goes through `flatMapLatest`, which cancels the previous stream. A
        // regrouping that dropped rows on the way would look like a zoom that eats photographs.
        val vm = feed(vms, 40)
        val before = vm.items.asSnapshot().filterIsInstance<FeedItem.Tile>().map { it.photo.path }

        vm.setDensity(FeedDensity.Year)
        val after = vm.items.asSnapshot().filterIsInstance<FeedItem.Tile>().map { it.photo.path }

        assertEquals(before, after, "the same photographs, in the same order")
    }
}
