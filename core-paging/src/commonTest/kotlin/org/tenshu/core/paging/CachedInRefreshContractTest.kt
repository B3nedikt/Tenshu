package org.tenshu.core.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **G1 of `core-paging-scroll-restoration.md`** (§6) — the claim that lets a paged listing carry
 * `cachedIn`.
 *
 * `drive-folder-listing-consistency.md` banned `cachedIn` from every paged listing because it
 * *"drops the refresh `load()` on new `PagingData` generations"*, a device finding with no test
 * behind it. `cachedIn` preserves the `uiReceiver` and so should forward a UI-initiated refresh;
 * whether it also forwards a new generation decides the shape of the fix (§2).
 *
 * ⚠⚠ Both halves are assertions here:
 *
 * - [refreshThroughCachedInReachesTheSource] — the load-bearing claim.
 * - [aNewUpstreamGenerationUnderCachedInStillLands] — ⚠⚠ the ban's own premise, measured on
 *   paging 3.5.0, and it **does not reproduce**. See that test.
 * - [aNewUpstreamGenerationWithoutCachedInLands] — the control, the uncached shape.
 *
 * ⚠⚠ Every collection here uses **`collectLatest`**, because that is what both real consumers do
 * (`TenshuPagingPresenter.collect`, and Compose's `LazyPagingItems`). It is not a detail:
 * `collectFrom` never returns for a given `PagingData`, so a plain `collect` never reaches a second
 * generation at all — and "measures" a swallow that is its own harness.
 *
 * ⚠ `commonTest`, so the `iosSimulatorArm64` gate runs it too: the presenter path is the one iOS
 * uses.
 *
 * ⚠⚠ **Everything here runs on the test scheduler.** `Probe` takes its context as a parameter, the
 * scope is built here, and `Pager.flow` loads on its **collector's** context — so handing all three
 * a `StandardTestDispatcher` puts the whole path under the scheduler. `docs/testing.md` §10 carries
 * the rule: the discriminator is whether every dispatcher on the path is injectable, not which
 * builder the test uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedInRefreshContractTest {

    /**
     * Reads whatever [rows] holds **at load time**, so a reload observes a mutation and a swallowed
     * reload cannot. One page, no appends — this is about generations, not windowing.
     */
    private class SnapshotSource(private val rows: () -> List<Int>) : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> =
            LoadResult.Page(data = rows(), prevKey = null, nextKey = null)
    }

    /** Minimal `PagingDataPresenter` — the base `LazyPagingItems` is built on. */
    private class Probe(context: CoroutineContext) : PagingDataPresenter<Int>(context) {
        val items = MutableStateFlow<List<Int>>(emptyList())
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Int>) {
            items.value = snapshot().items
        }
    }

    private val config = PagingConfig(pageSize = 50, initialLoadSize = 50, enablePlaceholders = false)

    private fun pagerFlow(rows: () -> List<Int>): Flow<PagingData<Int>> =
        Pager(config = config, pagingSourceFactory = { SnapshotSource(rows) }).flow

    /**
     * Runs the scheduler to quiescence and returns what the probe holds. ⚠ No timeout and no
     * predicate: after [advanceUntilIdle] there is nothing left to run, so this is a *fact* rather
     * than a sample — a wrong value fails the caller's `assertEquals` instead of timing out at 5 s
     * with no diff to read.
     */
    private fun TestScope.settled(probe: Probe): List<Int> {
        advanceUntilIdle()
        return probe.items.value
    }

    /**
     * ⭐ **The load-bearing claim.** A UI-initiated `refresh()` must reach the real `PageFetcher`
     * through the `uiReceiver` `cachedIn` preserves, invalidate, and re-run `load()` — so the
     * mutation lands even though the flow is cached.
     */
    @Test
    fun refreshThroughCachedInReachesTheSource() = runTest {
        run {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher)
            val rows = MutableStateFlow(listOf(1, 2, 3))
            val probe = Probe(dispatcher)
            try {
                val cached = pagerFlow { rows.value }.cachedIn(scope)
                scope.launch { cached.collectLatest { probe.collectFrom(it) } }
                assertEquals(listOf(1, 2, 3), settled(probe), "the first generation lands before the trigger")

                rows.value = listOf(1, 2, 3, 4)
                probe.refresh()

                assertEquals(
                    listOf(1, 2, 3, 4),
                    settled(probe),
                    "refresh() did not reach the source through cachedIn — the whole of " +
                        "core-paging-scroll-restoration.md is built on it doing so",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /**
     * ⚠⚠ **The ban's premise does not reproduce.** A fresh `Pager` per change — the
     * `TList.pagingSource` shape — reaches the presenter as a new `PagingData`, and on paging 3.5.0
     * `cachedIn` **forwards it**: the mutation lands without anyone calling `refresh()`. That is why
     * the per-change `Pager` stays under `cachedIn` (`core-paging-scroll-restoration.md` §2).
     *
     * ⚠ A fixture this small proves little alone: one page, `nextKey = null`, an instant trigger.
     * `FilesPagingReactivityUiTest` asks the same question of the real `getFilesPaged` pipeline
     * through the real `LazyPagingItems` differ.
     *
     * ⚠⚠ Both are desktop; the on-device evidence is §6 G5. If this goes red the ban is back, and
     * §7 holds the design that routes around it.
     */
    @Test
    fun aNewUpstreamGenerationUnderCachedInStillLands() = runTest {
        run {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher)
            val rows = MutableStateFlow(listOf(1, 2, 3))
            val reloads = MutableStateFlow(0)
            val probe = Probe(dispatcher)
            try {
                val cached = reloads.flatMapLatest { pagerFlow { rows.value } }.cachedIn(scope)
                scope.launch { cached.collectLatest { probe.collectFrom(it) } }
                assertEquals(listOf(1, 2, 3), settled(probe), "the first generation lands before the trigger")

                rows.value = listOf(1, 2, 3, 4)
                reloads.value = 1

                assertEquals(
                    listOf(1, 2, 3, 4),
                    settled(probe),
                    "cachedIn swallowed a new upstream generation — the ban on cachedIn is live " +
                        "again, and core-paging-scroll-restoration.md §7 holds the design around it",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    /** The control: the same upstream without `cachedIn` does land. */
    @Test
    fun aNewUpstreamGenerationWithoutCachedInLands() = runTest {
        run {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(dispatcher)
            val rows = MutableStateFlow(listOf(1, 2, 3))
            val reloads = MutableStateFlow(0)
            val probe = Probe(dispatcher)
            try {
                val uncached = reloads.flatMapLatest { pagerFlow { rows.value } }
                scope.launch { uncached.collectLatest { probe.collectFrom(it) } }
                assertEquals(listOf(1, 2, 3), settled(probe), "the first generation lands before the trigger")

                rows.value = listOf(1, 2, 3, 4)
                reloads.value = 1

                assertEquals(
                    listOf(1, 2, 3, 4),
                    settled(probe),
                    "the uncached reactive shape stopped working",
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
