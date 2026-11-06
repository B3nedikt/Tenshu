package app.tenshu.fit.ui.screens.history

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for History (mirrors Drive/Control ScreenTest harness): the
 * seeded workouts flow through [HistoryViewModel] into the feed, the screen title renders,
 * and tapping a row routes through `onOpenWorkout`.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryScreenTest {

    @Test
    fun seededWorkoutsRenderInTheFeed() = composeVmTest { vms ->
        val vm = vms.own(HistoryViewModel(MockFitRepository()))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = {}) }
        }

        onNodeWithText("History", useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText("Morning run", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Coast loop", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun tappingAWorkoutRowRoutesThroughOnOpenWorkout() = composeVmTest { vms ->
        val opened = mutableListOf<String>()
        val vm = vms.own(HistoryViewModel(MockFitRepository()))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = { opened += it }) }
        }

        waitUntil {
            onAllNodesWithText("Morning run", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Morning run", useUnmergedTree = true).performClick()

        waitUntil { opened.isNotEmpty() }
        assertEquals(listOf("w-sun-run"), opened, "the Morning run row must open its workout")
    }

    /**
     * The feed is lazy, and has to be: an eager `Column(verticalScroll)` composed every row on
     * first paint **and again on every return to the tab** — a nav entry below the top of the back
     * stack leaves composition, so the pop re-runs the subtree. That rebuild is what read as "the
     * list reloads".
     *
     * ⚠ Asserting on the *visible* rows would pass either way — the two tests above do exactly that
     * and stayed green throughout the conversion. The discriminating assertion is that a far-off
     * day is **not composed at all**: with the eager Column every one of these 60 days is in the
     * semantics tree from the first frame (verified by reverting the screen and watching this
     * fail).
     */
    @Test
    fun distantDaysAreNotComposedUntilScrolledTo() = composeVmTest { vms ->
        val repository = ManyDaysRepository(days = 60)
        val vm = vms.own(HistoryViewModel(repository))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = {}) }
        }

        waitUntil {
            onAllNodesWithText("Session 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText("Session 59", useUnmergedTree = true).fetchSemanticsNodes().size,
            "the oldest day composed on first paint — the feed is rendering eagerly",
        )
    }

    /**
     * ⚠⚠ **A day straddles page boundaries, which is why `HistoryDayGroup` could not survive the
     * conversion.** Grouping happens before the rows are known; paging delivers them a page at a
     * time. The port that suggests itself — group each page and emit its day headers — draws the
     * SAME day's header once per page it appears in, and that is invisible in any fixture whose
     * days happen to align with the page size.
     *
     * This fixture is built so they cannot align: three workouts on each of two days, cut into
     * pages of two, so the middle page holds the tail of one day and the head of the next. The
     * assertion is on *absence* — exactly one header per day, not "the header renders".
     */
    @Test
    fun aDayStraddlingAPageBoundaryStillGetsExactlyOneHeader() = composeVmTest { vms ->
        val repository = PagedRepository(workoutsPerDay = 3, days = 2, pageSize = 2)
        val vm = vms.own(HistoryViewModel(repository))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = {}) }
        }

        // Wait for the last row of the SECOND day — i.e. for every page to have landed.
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Day 1 #2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            1,
            onAllNodesWithText("Today", useUnmergedTree = true).fetchSemanticsNodes().size,
            "today's header was drawn once per page its workouts landed in",
        )
        assertEquals(
            1,
            onAllNodesWithText("Yesterday", useUnmergedTree = true).fetchSemanticsNodes().size,
            "yesterday's header was drawn once per page its workouts landed in",
        )
    }

    /**
     * ⚠ The week card rides the **list** read, never the paged feed. Summing a `PagingData` is not
     * possible in any meaningful sense — only the loaded pages exist, so the roll-up would grow as
     * the user scrolled. This repository's paged feed is deliberately EMPTY while its list read is
     * not: wire the card to the feed and the numbers go to zero.
     */
    @Test
    fun theWeekRollUpDoesNotComeFromThePagedFeed() = composeVmTest { vms ->
        val repository = object : app.tenshu.fit.data.FitRepository by MockFitRepository() {
            override fun workouts(): Flow<List<Workout>> = flowOf(
                listOf(workoutOn(today, index = 0, durationSec = 3_600, distanceM = 12_000)),
            )

            override fun workoutsPaged(): Flow<PagingData<Workout>> = flowOf(PagingData.empty())
        }
        val vm = vms.own(HistoryViewModel(repository))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = {}) }
        }

        // The card's distance stat — 12 km, from a feed that has no rows at all. Distance is the
        // discriminating one: a count could be confused with something else on screen, and both
        // the empty feed and a summed-from-pages card would render 0.
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("12.0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("This week", useUnmergedTree = true).assertIsDisplayed()
    }
}

/** A workout at 09:00 + [index] hours on [date], titled `Day N #i` where N is [dayLabel]. */
private fun workoutOn(
    date: LocalDate,
    index: Int,
    dayLabel: Int = 0,
    durationSec: Int = 1_800,
    distanceM: Int? = null,
): Workout {
    val start = LocalDateTime(date, LocalTime(9 + index, 0))
    return Workout(
        workoutId = "w-$dayLabel-$index",
        activityType = ActivityType.Running,
        source = Source.Recorded,
        title = "Day $dayLabel #$index",
        device = "",
        start = start,
        end = start,
        durationSec = durationSec,
        distanceM = distanceM,
    )
}

/**
 * [MockFitRepository] with a long, one-workout-per-day history in place of the fixture's few.
 *
 * ⚠ `workoutsPaged()` is overridden explicitly even though the interface default would derive it
 * from `workouts()`. Kotlin class delegation forwards EVERY interface member to the delegate,
 * defaults included — so without this the feed would quietly show the mock's handful of workouts
 * while `workouts()` returned these, and the test would fail looking like a rendering bug.
 */
private class ManyDaysRepository(
    days: Int,
    private val delegate: MockFitRepository = MockFitRepository(),
) : app.tenshu.fit.data.FitRepository by delegate {

    private val rows = (0 until days).map { daysAgo ->
        val date = today.minus(DatePeriod(days = daysAgo))
        val start = LocalDateTime(date, LocalTime(9, 0))
        Workout(
            workoutId = "w-$daysAgo",
            activityType = ActivityType.Running,
            source = Source.Recorded,
            title = "Session $daysAgo",
            device = "",
            start = start,
            end = LocalDateTime(date, LocalTime(9, 30)),
            durationSec = 1_800,
        )
    }

    override fun workouts() = flowOf(rows)

    override fun workoutsPaged(): Flow<PagingData<Workout>> = flowOf(PagingData.from(rows))
}

/**
 * A repository whose feed is driven by a **real `Pager`** rather than a static `PagingData`, so
 * page boundaries actually exist. [pageSize] is deliberately co-prime with [workoutsPerDay] so a
 * day cannot line up with a page.
 */
private class PagedRepository(
    private val workoutsPerDay: Int,
    days: Int,
    private val pageSize: Int,
    private val delegate: MockFitRepository = MockFitRepository(),
) : app.tenshu.fit.data.FitRepository by delegate {

    private val rows: List<Workout> = (0 until days).flatMap { daysAgo ->
        val date = today.minus(DatePeriod(days = daysAgo))
        (0 until workoutsPerDay).map { i -> workoutOn(date, index = i, dayLabel = daysAgo) }
    }

    override fun workouts() = flowOf(rows)

    override fun workoutsPaged(): Flow<PagingData<Workout>> = Pager(
        // initialLoadSize == pageSize so the first load really is one page; the default 3×
        // would swallow the boundary this fixture exists to create.
        config = PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, enablePlaceholders = false),
        pagingSourceFactory = { ListSource(rows, pageSize) },
    ).flow

    private class ListSource(private val rows: List<Workout>, private val pageSize: Int) :
        PagingSource<Int, Workout>() {
        override fun getRefreshKey(state: PagingState<Int, Workout>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Workout> {
            val start = params.key ?: 0
            val end = minOf(start + params.loadSize, rows.size)
            return LoadResult.Page(
                data = rows.subList(start, end),
                prevKey = if (start == 0) null else (start - pageSize).coerceAtLeast(0),
                nextKey = if (end >= rows.size) null else end,
            )
        }
    }
}
