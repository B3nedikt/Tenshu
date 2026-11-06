package app.tenshu.fit.ui.screens.history

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
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
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlin.test.Test
import app.tenshu.fit.composeVmTest

/**
 * ⚠⚠ **The live guard on the half `cachedIn` used to break.** A workout saved while History is on
 * screen must appear **without leaving the tab**.
 *
 * `HistoryViewModel.workouts` gained `.cachedIn(viewModelScope)` in SR-3 of
 * `core-paging-scroll-restoration.md`, reversing a rule Fit inherited from Drive:
 * `drive-folder-listing-consistency.md` measured on-device in 2026-07-10 that `cachedIn` **drops the
 * refresh `load()` on every generation after the first**, so a newly created row never appeared
 * until re-entry. SR-1 could not reproduce that on paging 3.5.0 — neither in an isolated fixture
 * (`CachedInRefreshContractTest`) nor against Drive's real pipeline (`FilesPagingReactivityUiTest`).
 *
 * ⚠ **If this test goes red, the ban is back.** Put `cachedIn` away and build
 * `core-paging-scroll-restoration.md` §7 instead; do not weaken the assertion. It is Fit's
 * counterpart to `FilesPagingReactivityUiTest`, which guards the same thing for Drive — Fit needs
 * its own because Fit's feed is a different chain with a different page size, and because Fit is the
 * surface the scroll fix was actually reported against.
 *
 * ⚠ The fixture is a **reactive** feed — a fresh `Pager` per change, exactly the shape
 * `TList.pagingSource` emits. That is load-bearing and was verified, not assumed: swap
 * `workoutsPaged()` for a static `PagingData.from(rows.value)` and this test **fails**, because
 * there is then no second generation for `cachedIn` to forward. A fixture that cannot produce the
 * failure cannot guard against it.
 */
@OptIn(ExperimentalTestApi::class)
class HistoryFeedReactivityTest {

    @Test
    fun aWorkoutSavedWhileHistoryIsOpenAppearsWithoutLeavingTheTab() = composeVmTest { vms ->
        val repository = ReactiveRepository(initialDays = 3)
        val vm = vms.own(HistoryViewModel(repository))
        setContent {
            TenshuTheme { HistoryScreen(viewModel = vm, onOpenWorkout = {}) }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Session 0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Save one while the same collection stays open — no navigation, no re-entry.
        repository.record("Just recorded")

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Just recorded", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // And a second one, spaced — the original bug let the first through and dropped the rest.
        repository.record("And another")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("And another", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

/**
 * A feed that emits a **new `PagingData` generation per change**, the way `TList.pagingSource`'s
 * `flatMapLatest` does. [record] prepends a workout and trips that generation.
 *
 * ⚠ `workoutsPaged()` is overridden explicitly: Kotlin class delegation forwards interface defaults
 * to the delegate too, so without it the feed would quietly show the mock's own workouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ReactiveRepository(
    initialDays: Int,
    private val delegate: MockFitRepository = MockFitRepository(),
) : FitRepository by delegate {

    private val rows = MutableStateFlow(
        (0 until initialDays).map { daysAgo -> workout("Session $daysAgo", daysAgo, index = 0) }
    )
    private var recorded = 0

    fun record(title: String) {
        recorded++
        rows.value = listOf(workout(title, daysAgo = 0, index = recorded)) + rows.value
    }

    private fun workout(title: String, daysAgo: Int, index: Int): Workout {
        val date = delegate.today.minus(DatePeriod(days = daysAgo))
        val start = LocalDateTime(date, LocalTime(9 + index, 0))
        return Workout(
            workoutId = "w-$daysAgo-$index-$title",
            activityType = ActivityType.Running,
            source = Source.Recorded,
            title = title,
            device = "",
            start = start,
            end = start,
            durationSec = 1_800,
        )
    }

    override fun workouts(): Flow<List<Workout>> = rows

    override fun workoutsPaged(): Flow<PagingData<Workout>> = rows.flatMapLatest { snapshot ->
        Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, enablePlaceholders = false),
            pagingSourceFactory = { ListSource(snapshot) },
        ).flow
    }

    private class ListSource(private val rows: List<Workout>) : PagingSource<Int, Workout>() {
        override fun getRefreshKey(state: PagingState<Int, Workout>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Workout> {
            val start = params.key ?: 0
            val end = minOf(start + params.loadSize, rows.size)
            return LoadResult.Page(
                data = rows.subList(start, end),
                prevKey = null,
                nextKey = if (end >= rows.size) null else end,
            )
        }
    }
}
