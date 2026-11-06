package app.tenshu.fit.ui.screens.history

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.tenshu.fit.data.FitRepository
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import app.tenshu.ui.layout.AdaptiveNavItem
import app.tenshu.ui.layout.AdaptiveNavScaffold
import app.tenshu.ui.navigation.KmpNavHost
import app.tenshu.ui.navigation.kmpEntryProvider
import app.tenshu.ui.theme.TenshuTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.tenshu.fit.desktopComposeVmTest

/**
 * The **round trip** History could not survive: scroll deep into the feed, open a workout, come
 * back (`fit-history-paging-handover.md`; reported 2026-08-12).
 *
 * Two separable losses hide behind the one symptom, and this test pins the first:
 *
 * 1. ⚠⚠ **The view model was reconstructed** — the week roll-up visibly counted up from 0 on the
 *    way back. Nothing in Fit asked for that. `AdaptiveNavScaffold` used to call its `content`
 *    lambda from a different source position when the chrome was hidden, and Fit hides the chrome
 *    for a non-top-level destination — so pushing the detail screen discarded the whole nav host,
 *    its `ViewModelStore`s and its saveable state, and popping built a second one. Drive never
 *    reproduced it for the single reason that it never passes `showNav`.
 * 2. ⚠⚠ **The paged feed re-collected from page 0**, because it was collected without `cachedIn` —
 *    a rule inherited from Drive that SR-3 of `core-paging-scroll-restoration.md` reversed once
 *    SR-1 measured it no longer holds. Fixed too; [aDeepScrolledFeedKeepsItsPlaceAfterARoundTrip]
 *    is the pin.
 *
 * ⚠ The harness is the real one — [AdaptiveNavScaffold] over [KmpNavHost] with `showNav` wired the
 * way `fit/ui/App.kt` wires it — because that pairing IS the bug. A `HistoryScreen` rendered on its
 * own, as every other test in this package renders it, cannot see any of it.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryNavRoundTripTest {

    private sealed interface Dest {
        data object History : Dest
        data object Detail : Dest
    }

    /** Counts *constructions*, which is the thing a surviving `ViewModelStore` prevents. */
    private class Built {
        var count = 0
    }

    @Test
    fun openingAWorkoutAndComingBackKeepsTheSameViewModel() {
        val built = Built()
        val repository = LongPagedRepository(days = 60, pageSize = 10)
        desktopComposeVmTest(width = 420, height = 900) { vms ->
            val backStack = mutableStateListOf<Dest>(Dest.History)
            setContent {
                TenshuTheme {
                    val current = backStack.last()
                    AdaptiveNavScaffold(
                        navItems = listOf(
                            AdaptiveNavItem(
                                key = "history",
                                icon = Icons.Outlined.Home,
                                label = "History",
                                selected = true,
                                onClick = {},
                            ),
                        ),
                        // Exactly what `fit/ui/App.kt` passes: the chrome goes away on a detail
                        // screen. This one line is what used to destroy the nav host.
                        showNav = current is Dest.History,
                    ) {
                        KmpNavHost(
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            entryProvider = kmpEntryProvider {
                                entry<Dest.History> {
                                    val vm = viewModel<HistoryViewModel> {
                                        built.count++
                                        vms.own(HistoryViewModel(repository))
                                    }
                                    HistoryScreen(
                                        viewModel = vm,
                                        onOpenWorkout = { backStack.add(Dest.Detail) },
                                    )
                                }
                                entry<Dest.Detail> {
                                    Box(Modifier.fillMaxSize()) { Text("Workout detail") }
                                }
                            },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Session 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, built.count, "the view model must be built once on first entry")

            backStack.add(Dest.Detail)
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Workout detail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            backStack.removeLastOrNull()
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Session 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(
                1,
                built.count,
                "coming back from a workout rebuilt the view model — the week roll-up restarts at 0",
            )
        }
    }

    /**
     * The second loss, and the one the reporter actually described: *"scroll down to 4 days ago …
     * the scrolled view is at the top again"*.
     *
     * ⚠ Until 2026-08-12 this test asserted the **opposite** — that the feed re-anchors near the top
     * — because that was the honest behaviour under the no-`cachedIn` rule, and pinning it was how a
     * silent change to the paging contract would have been caught. SR-3 flipped it. Reverting
     * `HistoryViewModel.workouts`' `cachedIn` fails it, which is the whole point.
     */
    @Test
    fun aDeepScrolledFeedKeepsItsPlaceAfterARoundTrip() {
        val repository = LongPagedRepository(days = 60, pageSize = 10)
        desktopComposeVmTest(width = 420, height = 900) { vms ->
            val backStack = mutableStateListOf<Dest>(Dest.History)
            setContent {
                TenshuTheme {
                    val current = backStack.last()
                    AdaptiveNavScaffold(
                        navItems = listOf(
                            AdaptiveNavItem(
                                key = "history",
                                icon = Icons.Outlined.Home,
                                label = "History",
                                selected = true,
                                onClick = {},
                            ),
                        ),
                        showNav = current is Dest.History,
                    ) {
                        KmpNavHost(
                            backStack = backStack,
                            onBack = { backStack.removeLastOrNull() },
                            entryProvider = kmpEntryProvider {
                                entry<Dest.History> {
                                    HistoryScreen(
                                        viewModel = viewModel { vms.own(HistoryViewModel(repository)) },
                                        onOpenWorkout = { backStack.add(Dest.Detail) },
                                    )
                                }
                                entry<Dest.Detail> {
                                    Box(Modifier.fillMaxSize()) { Text("Workout detail") }
                                }
                            },
                        )
                    }
                }
            }

            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Session 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            // Scroll far enough that several appends must have run — "four days ago" in the report
            // was well past the first page.
            //
            // ⚠ Walked, not jumped: only the *loaded* pages are in bounds, so asking for index 40 up
            // front throws `out of bounds [0, 21)`. Scrolling to within the prefetch distance of the
            // current end is what makes the next page load, so the walk has to advance one page at a
            // time and wait — which is also what the user's thumb does.
            var target = 5
            var reached = false
            repeat(120) {
                if (reached) return@repeat
                if (onAllNodesWithText("Session 39", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                    reached = true
                    return@repeat
                }
                try {
                    onNode(hasScrollToIndexAction()).performScrollToIndex(target)
                    target += 5
                } catch (_: IllegalArgumentException) {
                    // Past the loaded end — the append triggered by the previous scroll has yet to
                    // land. Let it, and retry the same index.
                }
                waitForIdle()
            }
            assertTrue(reached, "the feed never paged as far as Session 39 — fixture or paging is wrong")

            backStack.add(Dest.Detail)
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Workout detail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            backStack.removeLastOrNull()
            // ⚠ Wait on the screen *title*, not on "Session 0". Whether the top row is composed is
            // precisely what is under test, so waiting for it would build the expected answer into
            // the harness — and once the scroll IS restored, "Session 0" never appears and the test
            // times out instead of failing with a readable message.
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("History", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            assertEquals(
                1,
                onAllNodesWithText("Session 39", useUnmergedTree = true).fetchSemanticsNodes().size,
                "the feed lost its place across the round trip — `HistoryViewModel.workouts` needs " +
                    "its `cachedIn`, or the loaded pages die with the composition",
            )
        }
    }
}

/**
 * 60 days, one workout each, behind a **real** `Pager` — so re-collecting the flow really does
 * start at page 0 the way the live repository's does. `PagingData.from` would deliver all 60 in one
 * shot and quietly hide exactly the behaviour under test.
 *
 * ⚠ `workoutsPaged()` is overridden explicitly: Kotlin class delegation forwards interface defaults
 * to the delegate too, so without it the feed would show the mock's handful of workouts.
 */
private class LongPagedRepository(
    days: Int,
    private val pageSize: Int,
    private val delegate: MockFitRepository = MockFitRepository(),
) : FitRepository by delegate {

    private val rows: List<Workout> = (0 until days).map { daysAgo ->
        val date = delegate.today.minus(DatePeriod(days = daysAgo))
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

    override fun workouts(): Flow<List<Workout>> = flowOf(rows)

    override fun workoutsPaged(): Flow<PagingData<Workout>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            initialLoadSize = pageSize,
            enablePlaceholders = false,
        ),
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
