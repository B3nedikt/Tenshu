package org.tenshu.core.paging

import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import androidx.paging.PagingData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test for [TenshuPagingPresenter] — the non-Compose collection path
 * iOS uses in place of `collectAsLazyPagingItems()`. Lives in `commonTest` so it
 * also runs on the `iosSimulatorArm64` gate, proving the path links + works on
 * K/N. Uses only multiplatform paging-common + coroutines-test (no proxy/test
 * helpers), so it stays iOS-capable.
 *
 * ⚠⚠ **Everything here runs on the test scheduler, and [advanceUntilIdle] is what drives it.**
 * The presenter takes both its [CoroutineScope] and its `mainContext` as parameters, and
 * `Pager.flow` loads on its collector's context — so handing all three a [StandardTestDispatcher]
 * puts the whole path under the scheduler: one `advanceUntilIdle()` yields `size=10, snapshot=10,
 * loadState=NotLoading`.
 *
 * ⚠ That is not a style preference, it is what makes the assertions *sound*. On real dispatchers
 * the snapshot and the load state arrive on two independent channels with no happens-before, so
 * awaiting the size and then reading `loadState.value` is a race that loses under load. After
 * `advanceUntilIdle()` there is nothing left to run, so reading `.value` is a fact rather than a
 * sample, and no test here needs a timeout.
 */
class TenshuPagingPresenterTest {

    /** Trivial in-memory source emitting `[0, total)` in fixed-size pages. */
    private class IntSource(private val total: Int) : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            val start = params.key ?: 0
            val end = minOf(start + params.loadSize, total)
            return LoadResult.Page(
                data = (start until end).toList(),
                prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
                nextKey = if (end >= total) null else end,
            )
        }
    }

    // initialLoadSize == pageSize so the first load is exactly one page, and the
    // minimum prefetchDistance (1; 0 is rejected when placeholders are off) so
    // nothing pages forward until an item near the end is accessed — makes the
    // windowing assertions deterministic.
    private fun pagerFlow(total: Int, pageSize: Int = 10): Flow<PagingData<Int>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            initialLoadSize = pageSize,
            prefetchDistance = 1,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { IntSource(total) },
    ).flow

    /**
     * A presenter whose scope *and* `mainContext` are the test scheduler, so
     * [advanceUntilIdle] runs its loads to completion. Cancels the scope afterwards.
     */
    private fun TestScope.withPresenter(block: TestScope.(TenshuPagingPresenter<Int>) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            block(TenshuPagingPresenter(scope, dispatcher))
        } finally {
            scope.cancel()
        }
    }

    /** [IntSource], but its FIRST append fails — the edge load a caller must be able to retry. */
    private class FailingOnceSource(private val total: Int, private val failures: IntArray) : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            val start = params.key ?: 0
            if (params is LoadParams.Append && failures[0]++ == 0) return LoadResult.Error(IllegalStateException("append failed once"))
            val end = minOf(start + params.loadSize, total)
            return LoadResult.Page(
                data = (start until end).toList(),
                prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
                nextKey = if (end >= total) null else end,
            )
        }
    }

    @Test
    fun retry_restarts_a_failed_append_of_the_current_generation() = runTest {
        // A caller retrying a failed EDGE load must reach Paging's own retry —
        // re-collecting a `cachedIn` flow only replays the failed generation.
        withPresenter { presenter ->
            val failures = intArrayOf(0)
            presenter.collect(
                Pager(
                    config = PagingConfig(pageSize = 10, initialLoadSize = 10, prefetchDistance = 1, enablePlaceholders = false),
                    pagingSourceFactory = { FailingOnceSource(30, failures) },
                ).flow,
            )
            advanceUntilIdle()
            presenter.get(9) // the end of the first page: asks for the next one, which fails
            advanceUntilIdle()
            assertTrue(presenter.loadState.value?.append is LoadState.Error, "precondition: the append failed")
            assertEquals(10, presenter.size)

            presenter.retry()
            advanceUntilIdle()
            assertTrue(presenter.loadState.value?.append !is LoadState.Error, "the retried append succeeded")
            assertEquals(20, presenter.size, "the failed page arrived")
        }
    }

    /** A source over a list the test can change — what a trash does to the feed's source. */
    private class ListSource(private val backing: List<Int>) : PagingSource<Int, Int>() {
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? = 0
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> =
            LoadResult.Page(data = backing.toList(), prevKey = null, nextKey = null)
    }

    @Test
    fun refresh_reloads_what_the_source_now_returns() = runTest {
        withPresenter { presenter ->
            val backing = mutableListOf(0, 1, 2, 3)
            presenter.collect(
                Pager(
                    config = PagingConfig(pageSize = 10, initialLoadSize = 10, enablePlaceholders = false),
                    pagingSourceFactory = { ListSource(backing) },
                ).flow,
            )
            advanceUntilIdle()
            assertEquals(listOf(0, 1, 2, 3), presenter.snapshot.value, "precondition")

            backing.remove(2)
            presenter.refresh()
            advanceUntilIdle()
            assertEquals(listOf(0, 1, 3), presenter.snapshot.value, "the refresh read the source again")
        }
    }

    @Test
    fun first_page_surfaces_in_snapshot_and_via_get() = runTest {
        withPresenter { presenter ->
            presenter.collect(pagerFlow(total = 30))
            advanceUntilIdle()

            assertEquals((0 until 10).toList(), presenter.snapshot.value)
            // Assert the window size *before* touching the end of the loaded
            // range: `get` records the access, and at `prefetchDistance = 1`
            // reading the last loaded item is a prefetch signal that appends
            // the next page (that is what `accessing_last_item_pages_forward`
            // asserts).
            assertEquals(10, presenter.size)
            assertEquals(0, presenter.get(0))
            assertEquals(9, presenter.get(9))
            // Refresh completed; the list is not yet exhausted. Sound to read directly:
            // `advanceUntilIdle` above left nothing to run, so the load state's separate
            // channel has already delivered (see the class KDoc).
            assertTrue(presenter.loadState.value?.refresh is LoadState.NotLoading)
        }
    }

    @Test
    fun a_static_source_settles_even_though_it_emits_no_load_states() = runTest {
        withPresenter { presenter ->
            // `PagingData.from(list)` is what Drive's SORTED listing returns (the whole
            // folder as one page). It drives no Pager, so `loadStateFlow` never reports a
            // completed refresh — settling has to come from the presentation event instead.
            // Gating on load states alone leaves an empty sorted folder spinning forever.
            presenter.collect(flowOf(PagingData.from(listOf(1, 2, 3))))
            advanceUntilIdle()

            assertEquals(listOf(1, 2, 3), presenter.snapshot.value)
            assertTrue(presenter.refreshSettled.value, "a static source must settle")
        }
    }

    @Test
    fun an_empty_static_source_settles_so_the_empty_state_can_replace_the_spinner() = runTest {
        withPresenter { presenter ->
            // The hard case: zero rows AND no load states, so neither
            // signal could distinguish "empty" from "loading".
            presenter.collect(flowOf(PagingData.from(emptyList<Int>())))
            advanceUntilIdle()

            assertTrue(presenter.refreshSettled.value, "an empty static source must settle")
            assertEquals(emptyList(), presenter.snapshot.value)
        }
    }

    @Test
    fun get_out_of_range_is_null_not_a_crash() = runTest {
        withPresenter { presenter ->
            presenter.collect(pagerFlow(total = 5))
            advanceUntilIdle()

            assertEquals((0 until 5).toList(), presenter.snapshot.value)
            assertNull(presenter.get(99))
            assertNull(presenter.get(-1))
        }
    }

    @Test
    fun accessing_last_item_pages_forward() = runTest {
        withPresenter { presenter ->
            presenter.collect(pagerFlow(total = 30, pageSize = 10))
            advanceUntilIdle()
            assertEquals(10, presenter.size)

            // Touch the last loaded item — Paging treats this as a prefetch
            // signal and loads the next page.
            presenter.get(presenter.size - 1)
            advanceUntilIdle()

            val grown = presenter.snapshot.value
            assertTrue(grown.size >= 20, "expected next page after end access")
            assertEquals((0 until grown.size).toList(), grown)
        }
    }

    /** [IntSource] with counted placeholders — what a seekable listing (the Photos feed) pages with. */
    private class CountedIntSource(private val total: Int) : PagingSource<Int, Int>() {
        override val jumpingSupported: Boolean get() = true
        override fun getRefreshKey(state: PagingState<Int, Int>): Int? =
            state.anchorPosition?.let { (it - state.config.initialLoadSize / 2).coerceAtLeast(0) }
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Int> {
            val start = (params.key ?: 0).coerceIn(0, total)
            val end = minOf(start + params.loadSize, total)
            return LoadResult.Page(
                data = (start until end).toList(),
                prevKey = if (start == 0) null else (start - params.loadSize).coerceAtLeast(0),
                nextKey = if (end >= total) null else end,
                itemsBefore = start,
                itemsAfter = total - end,
            )
        }
    }

    private fun countedFlow(total: Int, initialKey: Int, pageSize: Int = 10): Flow<PagingData<Int>> = Pager(
        config = PagingConfig(
            pageSize = pageSize,
            initialLoadSize = pageSize,
            prefetchDistance = 1,
            enablePlaceholders = true,
            jumpThreshold = pageSize * 3,
        ),
        initialKey = initialKey,
        pagingSourceFactory = { CountedIntSource(total) },
    ).flow

    @Test
    fun the_window_places_a_mid_list_load_at_its_true_position() = runTest {
        withPresenter { presenter ->
            // ⚠⚠ The case `snapshot` gets wrong (`photos-ios.md` §1.3): a window loaded mid-list,
            // as after a scrubber jump, has placeholders on both sides. `snapshot` says "10 items
            // starting at index 0"; the list is 100 long and they start at 50.
            presenter.collect(countedFlow(total = 100, initialKey = 50))
            advanceUntilIdle()

            val window = presenter.window.value
            assertEquals(50, window.placeholdersBefore)
            assertEquals((50 until 60).toList(), window.items)
            assertEquals(40, window.placeholdersAfter)
            assertEquals(100, window.size, "a scrubber spans the whole list, not what has loaded")
            assertEquals(presenter.size, window.size, "the window and the presenter agree on the length")
            assertNull(window.itemAt(0), "an unloaded slot is a placeholder, not the first loaded item")
            assertEquals(50, window.itemAt(50))
            assertEquals(59, window.itemAt(59))
            assertNull(window.itemAt(60))
            assertNull(window.itemAt(-1))

            // The snapshot is unchanged: same items, no positions. Drive depends on that.
            assertEquals((50 until 60).toList(), presenter.snapshot.value)
        }
    }

    @Test
    fun accessing_a_window_position_through_get_pages_from_there() = runTest {
        withPresenter { presenter ->
            presenter.collect(countedFlow(total = 100, initialKey = 50))
            advanceUntilIdle()

            // A cell at the window's last loaded position appears — the load trigger Swift fires
            // from `onAppear`, at the window's index, not the snapshot's.
            presenter.get(59)
            advanceUntilIdle()

            val window = presenter.window.value
            assertEquals(50, window.placeholdersBefore)
            assertTrue(window.items.size >= 20, "expected the next page after an access at the window's end")
            assertEquals(100, window.size)
            assertEquals(60, window.itemAt(60))
        }
    }

    @Test
    fun with_placeholders_off_the_window_is_the_snapshot_with_a_size() = runTest {
        withPresenter { presenter ->
            presenter.collect(pagerFlow(total = 30))
            advanceUntilIdle()

            val window = presenter.window.value
            assertEquals(0, window.placeholdersBefore)
            assertEquals(0, window.placeholdersAfter)
            assertEquals(presenter.snapshot.value, window.items)
            assertEquals(10, window.size)
        }
    }
}
