package org.tenshu.core.paging

import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.testing.TestPager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.Interactor
import org.tenshu.core.InteractorProvider
import org.tenshu.core.Operation
import org.tenshu.core.StreamCallback
import org.tenshu.core.StreamElement
import org.tenshu.core.StreamInteractor
import org.tenshu.core.TaskInteractor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.PureOperationRunnerImplementation
import org.tenshu.core.common.assertNotReached
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.impl.TStringDto
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.util.toTString
import org.tenshu.core.core.OperationResolver
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TenshuPagingSourceTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setUp() {
        val registry = createCoreTestRegistry()
        val resolverContext = createTestContext(schemaRegistry = registry)
        val resolver = TraitResolverImpl { resolverContext }

        val pureExecutor = PureOperationRunnerImplementation({ resolverContext }) {
            OperationImplementationFactory { _ -> null }
        }

        @Suppress("UNCHECKED_CAST")
        val interactorProvider = object : InteractorProvider {
            override suspend fun <T : Data?> getInteractor(ro: RequestObject): Interactor<T> {
                return ro as Interactor<T>
            }
        }

        tContext = createTestContext(
            schemaRegistry = registry,
            traitResolver = resolver,
            pureOperationRunner = pureExecutor,
            additionalComponents = mapOf(
                InteractorProvider.COMPONENT_ID to interactorProvider
            )
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadReturnsCorrectPage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("item1", "item2").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { it },
        ) { (it as TStringDto).get() }

        try {
            val pager = TestPager(PagingConfig(pageSize = 2), pagingSource)
            val result = pager.refresh() as PagingSource.LoadResult.Page

            assertEquals(listOf("item1", "item2"), result.data)
            assertEquals(0, mockList.lastDrop)
            assertEquals(2, mockList.lastTake)
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    /**
     * The window is `drop(pos).take(n)` — the shape the window-fold peephole pushes down for a
     * natural-order listing (O(n) per page), and one `foldWindowPrefix` folds identically under a
     * `sortBy` (`core-keyset-paging.md` §6.3). ⚠ A flag that cut it as `take(pos + n).drop(pos)`
     * for "ordered" sources used to be pinned here; it never changed anything and is retired.
     */
    @Test
    fun window_isDropThenTake() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = (1..100).map { "item$it".toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { it },
        ) { (it as TStringDto).get() }

        try {
            val pager = TestPager(androidx.paging.PagingConfig(pageSize = 10, initialLoadSize = 10, enablePlaceholders = false), pagingSource)
            pager.refresh(initialKey = 20)

            assertEquals(10, mockList.lastTake, "unordered window takes only the page size")
            assertEquals(20, mockList.lastDrop)
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun ordering_isAppliedToTheReceiver_aheadOfTheWindow() = runTest {
        // `drive-sorted-folder-paging.md` §6: the sort rides `ordering`, applied inside `load`
        // BEFORE `drop().take()` — so the chain reads `sortBy(...).drop(pos).take(n)`, the shape
        // the ordered-window fusion folds into one pass — and never on the receiver the change
        // observer watches.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = (1..30).map { "item$it".toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        var dropWhenOrdered = Int.MIN_VALUE
        var orderingCalls = 0
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { it },
            ordering = { list ->
                orderingCalls++
                dropWhenOrdered = mockList.lastDrop
                list
            },
        ) { (it as TStringDto).get() }

        try {
            pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 10, loadSize = 5, placeholdersEnabled = false)
            )
            assertEquals(1, orderingCalls, "the ordering is applied once per load")
            assertEquals(-1, dropWhenOrdered, "the ordering must run before the window is cut")
            assertEquals(10, mockList.lastDrop)
            assertEquals(5, mockList.lastTake)
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun rowKey_dropsARowAnEarlierPageOfThisGenerationAlreadyServed() = runTest {
        // `drive-sorted-folder-paging.md` §3 B4: keys are OFFSETS. Under a newest-first sort a
        // backup inserts at the head, so the row that was last on page 1 is first at page 2's
        // offset — and Drive keys its list by path, where a duplicate is a crash. The source
        // remembers what it served and drops the re-read row; the key math is untouched.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = (1..6).map { "item$it".toTString(tContext) }
        val mockList = MockTList(tContext, testData).apply { slicing = true }
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { it },
            rowKey = { it },
        ) { (it as TStringDto).get() }

        try {
            val first = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 0, loadSize = 3, placeholdersEnabled = false)
            )
            assertIs<PagingSource.LoadResult.Page<Int, String>>(first)
            assertEquals(listOf("item1", "item2", "item3"), first.data)
            assertEquals(3, first.nextKey)

            // A head insert lands between the two loads: every offset now points one row up.
            mockList.insertAtHead("item0".toTString(tContext))

            val second = pagingSource.load(
                PagingSource.LoadParams.Append(key = first.nextKey!!, loadSize = 3, placeholdersEnabled = false)
            )
            assertIs<PagingSource.LoadResult.Page<Int, String>>(second)
            assertEquals(
                listOf("item4", "item5"),
                second.data,
                "the row page 1 already served (item3, re-read at offset 3) must be dropped",
            )
            assertEquals(6, second.nextKey, "keys still advance by the RAW window, so nothing is skipped")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    /**
     * `drive-sorted-folder-paging.md` D3 / §6: Paging can run a prepend and an append together, and
     * after a re-anchor both may read snapshots shifted by an intervening write. Here the anchor
     * page holds rows 3..5; a head insert lands; then the prepend (offset 0) and the append
     * (offset 6) are in flight at the same time, and the three pages together must name every row
     * at most once.
     *
     * ⚠⚠ `load` hops to `Dispatchers.Default`, so the two loads are on **real threads** and
     * `runTest`'s scheduler orders nothing between them. Two things follow, and both are in the
     * double rather than in this body:
     * - the overlap is **arranged** ([MockTList.readsToOverlap]), not left to the scheduler — every
     *   run is the interleaving D3 describes, both windows cut before either page is deduped;
     * - a window belongs to the **chain** `drop`/`take` return ([MockTList.Window]), as it does on
     *   the real immutable `RequestChain`. Kept on the shared receiver, a load whose `drop` landed
     *   between the other's `drop` and `getAll` read the other's window — an empty or wrong page
     *   out of a source that had done nothing wrong.
     *
     * ⚠ If this ever hangs to `runTest`'s timeout, the two reads were **serialised**: D3's lock is
     * taken after the rows are in memory, never around the read.
     *
     * ⚠ It does not pin that lock. The two windows share no *unseen* row (the only repeat, row 6,
     * was recorded by the anchor before either started), so the dedupe going missing turns this
     * red and the `Mutex` going missing does not.
     */
    @Test
    fun rowKey_concurrentPrependAndAppend_carryNoRowTwiceBetweenThem() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = (1..9).map { "item$it".toTString(tContext) }
        val mockList = MockTList(tContext, testData).apply { slicing = true }
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { it },
            rowKey = { it },
        ) { (it as TStringDto).get() }

        try {
            val anchor = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 3, loadSize = 3, placeholdersEnabled = false)
            )
            assertIs<PagingSource.LoadResult.Page<Int, String>>(anchor)
            assertEquals(listOf("item4", "item5", "item6"), anchor.data)

            mockList.insertAtHead("item0".toTString(tContext))
            mockList.readsToOverlap = 2

            val prepend = async {
                pagingSource.load(PagingSource.LoadParams.Prepend(key = anchor.prevKey!!, loadSize = 3, placeholdersEnabled = false))
            }
            val append = async {
                pagingSource.load(PagingSource.LoadParams.Append(key = anchor.nextKey!!, loadSize = 3, placeholdersEnabled = false))
            }
            val before = prepend.await()
            val after = append.await()
            assertIs<PagingSource.LoadResult.Page<Int, String>>(before)
            assertIs<PagingSource.LoadResult.Page<Int, String>>(after)

            val served = anchor.data + before.data + after.data
            assertEquals(served.size, served.toSet().size, "the three pages together must carry no row twice: $served")
            // The shifted append window (offset 6 now holds item6) drops the row the anchor page
            // already served and keeps the rest; the prepend reads the new head. item3 — pushed
            // from offset 2 to offset 3, between the two windows — is in neither page: that is the
            // "missing, not duplicated" half of B4, which only the bounded reload serves.
            assertEquals(listOf("item7", "item8"), after.data)
            assertEquals(listOf("item0", "item1", "item2"), before.data)
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun invalidation_observesTheGivenChain_notTheReceiver() = runTest {
        // `drive-sorted-folder-paging.md` §6 B5: the receiver is the wrong thing to observe for a
        // sorted listing of composite rows (a bare diff sees `path` alone), so the factory
        // observes the `invalidation` chain — here a second list standing in for Drive's
        // projected one. A change there rebuilds; a change on the receiver alone does not.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val receiver = MockTList(tContext, listOf("item1").map { it.toTString(tContext) })
        val observed = MockTList(tContext, listOf("row1").map { it.toTString(tContext) })

        val generations = mutableListOf<PagingData<String>>()
        val job = backgroundScope.launch(testDispatcher) {
            receiver.pagingSource(
                mapper = { (it as TStringDto).get() },
                invalidation = { observed },
            ).collect { generations.add(it) }
        }
        advanceUntilIdle()
        assertEquals(1, generations.size, "initial generation")

        receiver.emit(TResult.Success.create(tContext, listOf("item1", "item2").map { it.toTString(tContext) }.toTList(tContext)))
        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertEquals(1, generations.size, "a change on the unobserved receiver must not rebuild")

        observed.emit(TResult.Success.create(tContext, listOf("row1", "row2").map { it.toTString(tContext) }.toTList(tContext)))
        advanceTimeBy(5_000)
        advanceUntilIdle()
        assertEquals(2, generations.size, "a change on the invalidation chain must rebuild")
        job.cancel()
    }

    @Test
    fun continuousSignals_stillRebuildWithinTheMaxWait() = runTest {
        // `drive-sorted-folder-paging.md` §6 B4: a trailing debounce restarts on every signal, so
        // a burst that never pauses for the cooldown would never rebuild — the drift window would
        // be the whole backup. A ceiling bounds it: while signals keep arriving the listing
        // still rebuilds every INVALIDATION_MAX_WAIT_MS, and once they stop the trailing edge
        // lands as before.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val mockList = MockTList(tContext, listOf("item1").map { it.toTString(tContext) })
        val generations = mutableListOf<PagingData<String>>()
        val job = backgroundScope.launch(testDispatcher) {
            mockList.pagingSource(mapper = { (it as TStringDto).get() })
                .collect { generations.add(it) }
        }
        advanceUntilIdle()
        assertEquals(1, generations.size, "initial generation")

        // Ten seconds of a signal every 100 ms — never a 400 ms pause.
        repeat(100) {
            mockList.emit(TResult.Success.create(tContext, listOf("item1").map { it.toTString(tContext) }.toTList(tContext)))
            advanceTimeBy(100)
        }
        advanceUntilIdle()
        val duringBurst = generations.size - 1
        assertTrue(
            duringBurst in 3..4,
            "ten seconds of continuous signals must rebuild about every 3 s (ceiling), got $duringBurst",
        )

        // Silence: the trailing edge of the last signal lands once, and nothing more.
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val afterSettle = generations.size - 1
        assertEquals(duringBurst + 1, afterSettle, "the burst's trailing edge must land exactly once")
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(afterSettle, generations.size - 1, "a quiet list must not keep rebuilding")
        job.cancel()
    }

    @Test
    fun load_readsWindowFromSyncPath_notStreamInitialEmission() = runTest {
        // Regression for the Android-only "folder detail empty over Binder" bug.
        // The page must be read via the synchronous GetAll (call/response) path,
        // NOT taken from a stream's initial emission — that emission rides
        // Android's async proxy->client callback binding, which can drop/reorder
        // the first push and leave the page empty while the parent's count (also
        // synchronous) stays correct. Model the loss: the Stream op registers but
        // never delivers its initial element. `load` must STILL return the page.
        // Under the old `flow().first()` load this call would hang forever.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("item1", "item2").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        mockList.dropInitialStreamEmission = true

        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { (it as TStringDto).get() }
        try {
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 0, loadSize = 2, placeholdersEnabled = false)
            )
            assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(
                listOf("item1", "item2"),
                result.data,
                "page must load via the synchronous GetAll path even when the stream's initial emission is lost",
            )
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun projectionRunsOnTheWindow_afterDropAndTake() = runTest {
        // The projection (Drive's `select { ... }`, with per-row relation
        // resolution incl. thumbnails) must see only the current page's
        // `drop().take()` window — never the whole list. Projecting the full
        // list per page load is the 2k-file-folder ANR (see TenshuPagingSource
        // docs). Assert the window ops have already been applied by the time
        // the projection runs.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("item1", "item2").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        var dropWhenProjected = Int.MIN_VALUE
        var takeWhenProjected = Int.MIN_VALUE
        val pagingSource = TenshuPagingSource(
            context = tContext,
            tenshuList = mockList,
            projection = { window ->
                dropWhenProjected = mockList.lastDrop
                takeWhenProjected = mockList.lastTake
                window
            }
        ) { (it as TStringDto).get() }

        try {
            val pager = TestPager(PagingConfig(pageSize = 2), pagingSource)
            val result = pager.refresh() as PagingSource.LoadResult.Page

            assertEquals(listOf("item1", "item2"), result.data)
            assertEquals(0, dropWhenProjected, "drop must run before the projection")
            assertEquals(2, takeWhenProjected, "take must run before the projection")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun pagingSourceFactoryReloadsWhenListEmitsChangeWhileCollecting() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val initial = listOf("item1").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, initial)

        // Hold ONE collection open — the real-screen case
        // (`collectAsLazyPagingItems` stays subscribed). Now that
        // `TenshuPagingSource.load` is one-shot, the factory-level observer in
        // `pagingSource()` is the SOLE live-refresh mechanism: a list change
        // must yield a NEW PagingData generation without re-subscribing. This
        // restores the deleted `flowEmissionInvalidatesPagingSource` coverage at
        // the factory level, where the behaviour actually lives.
        val generations = mutableListOf<PagingData<String>>()
        val job = backgroundScope.launch(testDispatcher) {
            mockList.pagingSource(mapper = { (it as TStringDto).get() })
                .collect { generations.add(it) }
        }
        advanceUntilIdle()
        assertEquals(1, generations.size, "initial generation")

        // Simulate a reactive list change (a folder created while collecting).
        mockList.emit(
            TResult.Success.create(
                tContext,
                listOf("item1", "item2").map { it.toTString(tContext) }.toTList(tContext)
            )
        )
        // The change signal rides `changes().debounce(INVALIDATION_COOLDOWN_MS)`
        // (see `pagingSource`). `advanceUntilIdle()` drains the ready change
        // signal but does NOT fire the debounce timer — virtual time must be
        // advanced past the cooldown for the trailing-debounced reload to land.
        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertEquals(
            2,
            generations.size,
            "a list change must invalidate the source -> emit a new paging generation"
        )
        job.cancel()
    }

    // NOTE: the former `flowEmissionInvalidatesPagingSource` test was removed.
    // `TenshuPagingSource.load` is now one-shot and no longer self-invalidates
    // on flow emissions — change-observation + invalidation moved to the
    // factory-level observer in `pagingSource()` (scoped to the collector, so it
    // doesn't leak). The reactive reload-on-change behavior is covered
    // end-to-end on desktop by `:drive-shared` FilesPagingIntegrationTest.

    @Test
    fun burstOfEmissions_coalescesToBoundedInvalidations() = runTest {
        // notification-burst-aggregation.md §2.5: `.flow()` re-emits per save,
        // so a bulk write (Synced-Folders backup = one save per file) used to
        // trigger one invalidation -> one paging generation PER ROW. The factory
        // observer now trailing-debounces the change signal
        // (`changes().debounce(INVALIDATION_COOLDOWN_MS)` in `pagingSource`), so a
        // burst of N settling within the cooldown collapses to a SINGLE reload —
        // one extra generation, not N.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val initial = listOf("item1").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, initial)

        val generations = mutableListOf<PagingData<String>>()
        val job = backgroundScope.launch(testDispatcher) {
            mockList.pagingSource(mapper = { (it as TStringDto).get() })
                .collect { generations.add(it) }
        }
        advanceUntilIdle()
        assertEquals(1, generations.size, "initial generation")

        // A burst: five list changes in quick succession (same virtual instant).
        repeat(5) { n ->
            mockList.emit(
                TResult.Success.create(
                    tContext,
                    List(n + 2) { "item$it" }.map { it.toTString(tContext) }.toTList(tContext)
                )
            )
        }
        // Advance past the debounce cooldown so the trailing-debounced reload
        // lands (see `pagingSourceFactoryReloadsWhenListEmitsChangeWhileCollecting`
        // for why `advanceUntilIdle()` alone does not fire the debounce timer).
        advanceTimeBy(1_000)
        advanceUntilIdle()

        // The whole burst settles within one cooldown, so it debounces to ONE
        // reload -> exactly one extra generation (gen 2). The `2..3` band leaves
        // room for the harness to split the burst across the cooldown boundary
        // without making the test brittle; the invariant under test is that the
        // count stays BOUNDED (not one-per-emission).
        assertTrue(
            generations.size in 2..3,
            "a 5-emission burst must cost a bounded number of generations " +
                "(one trailing-debounced reload), got ${generations.size}"
        )
        job.cancel()
    }

    @Test
    fun partialMapper_filteredRowsAreDroppedButKeysAdvanceByRawWindow() = runTest {
        // A partial mapper (`(T) -> R?`, e.g. Control's forward-compat
        // `mapToEventOrNull`) drops rows from the PAGE but must not disturb the
        // key math: keys are offsets into the SOURCE list, so nextKey advances
        // by the raw window size — advancing by the filtered size would re-read
        // the dropped rows as duplicates on the next page.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("item1", "skip-me", "item3").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { item ->
            (item as TStringDto).get().takeUnless { it.startsWith("skip") }
        }

        try {
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 0, loadSize = 3, placeholdersEnabled = false)
            )

            assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(listOf("item1", "item3"), result.data, "filtered row must be dropped")
            assertEquals(3, result.nextKey, "nextKey must advance by the RAW window size (3), not data.size (2)")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun allFilteredWindow_returnsEmptyPageButKeepsWalking() = runTest {
        // A window whose every row is filtered is NOT the end of the list: the
        // page is empty but nextKey stays non-null so Paging loads the next
        // window. Only an empty RAW window (source exhausted) ends paging.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("skip-1", "skip-2").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        val pagingSource = TenshuPagingSource<Data, Data, String>(tContext, mockList, { it }) { null }

        try {
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 0, loadSize = 2, placeholdersEnabled = false)
            )

            assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(emptyList(), result.data)
            assertEquals(2, result.nextKey, "an all-filtered window must still advance")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun emptyRawWindow_endsPaging() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val mockList = MockTList(tContext, emptyList<TStringDto>())
        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { (it as TStringDto).get() }

        try {
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(key = 0, loadSize = 2, placeholdersEnabled = false)
            )

            assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(null, result.nextKey, "an empty raw window is the end of the list")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun getRefreshKeyIsNeverNegativeForSmallFirstPage() {
        // Regression: the Files-tab reactivity bug. Keys are item OFFSETS
        // (`nextKey = position + data.size`), not page numbers. When the first
        // page holds fewer items than pageSize, `prevKey` is null (top of list)
        // and the fallback `nextKey - pageSize` underflows negative — on device
        // this was `5 - 20 = -15`. A negative refresh key made `load` read
        // `drop(-15)`, which fails, so a freshly-created folder never rendered
        // until the flow was rebuilt (tab switch). The key must stay >= 0.
        val mockList = MockTList(tContext, emptyList<TStringDto>())
        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { (it as TStringDto).get() }

        val firstPage = PagingSource.LoadResult.Page(
            data = List(5) { "item$it" },
            prevKey = null,           // first page -> no previous
            nextKey = 5,              // item offset, smaller than pageSize (20)
        )
        val state = PagingState(
            pages = listOf(firstPage),
            anchorPosition = 0,
            config = androidx.paging.PagingConfig(pageSize = 20, initialLoadSize = 60),
            leadingPlaceholderCount = 0,
        )

        val key = pagingSource.getRefreshKey(state)
        assertNotNull(key, "refresh key should be derivable from a non-empty state")
        assertTrue(key >= 0, "refresh key must never be negative (was $key)")
    }

    @Test
    fun loadClampsNegativeKeyToZero() = runTest {
        // Defence in depth: even if a negative key reaches `load`, it must not
        // become a `drop(-n)` that fails the whole reload. The window starts at 0.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val testData = listOf("item1", "item2").map { it.toTString(tContext) }
        val mockList = MockTList(tContext, testData)
        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { (it as TStringDto).get() }

        try {
            val result = pagingSource.load(
                PagingSource.LoadParams.Refresh(
                    key = -15,
                    loadSize = 2,
                    placeholdersEnabled = false,
                )
            )

            assertIs<PagingSource.LoadResult.Page<Int, String>>(result)
            assertEquals(0, mockList.lastDrop, "a negative key must clamp to 0 before reading")
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Test
    fun errorResultPropagatesToPagingSource() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val mockList = MockTList(tContext, emptyList())
        mockList.shouldFail = true
        val pagingSource = TenshuPagingSource(tContext, mockList, { it }) { it.toString() }

        try {
            val pager = TestPager(PagingConfig(pageSize = 2), pagingSource)
            val result = pager.refresh()

            assertIs<PagingSource.LoadResult.Error<Int, String>>(result)
        } finally {
            pagingSource.invalidate()
            advanceUntilIdle()
        }
    }

    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
    private class MockTList<T : Data>(
        override val context: TContext,
        initialItems: List<T>
    ) : TList<T>, List<T> by initialItems, RequestObject {
        private val items: MutableList<T> = initialItems.toMutableList()

        /** The last window **any** load cut — a shape assertion for one load at a time. */
        var lastDrop: Int = -1
        var lastTake: Int = -1
        var shouldFail = false

        /**
         * When true, `getAll` honours the chain's `drop`/`take` window instead of returning
         * every item — for the tests that need real page contents rather than window shapes.
         */
        var slicing = false

        /**
         * When positive, every `getAll` parks until this many are in flight and then all proceed
         * together: each of those loads has cut its window and none has its rows yet.
         */
        var readsToOverlap = 0
        private val readsInFlight = AtomicInteger()
        private val readsOverlap = CompletableDeferred<Unit>()

        fun insertAtHead(item: T) {
            items.add(0, item)
        }
        private val callbacks = mutableListOf<StreamCallback<T>>()

        override val requestChain: RequestChain = object : RequestChain {
            override fun addReference(id: String): RequestChain = this
            override fun addArray(array: org.tenshu.core.TArray): RequestChain = this
            override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain = this
            override fun getCallstack(): List<Data> = emptyList()
            override suspend fun getResult(): TResult<Data> = error("")
        }

        // When true, the Stream op registers its callback but NEVER delivers the
        // initial emission — modelling Android's Binder reverse-binding dropping
        // the stream's first push. `load()` must still return the page (it now
        // reads via GetAll, the synchronous path), which is the whole point of
        // the fix. With the old `flow().first()` load this flag would hang load.
        var dropInitialStreamEmission = false

        private val mockOperations: Map<String, Operation> by lazy {
            windowOperations { it } + (Stream.ID to streamOperation())
        }

        private fun windowOperations(slice: (List<T>) -> List<T>): Map<String, Operation> = mapOf(
            Drop.ID to dropOperation(slice),
            Take.ID to takeOperation(slice),
            GetAll.ID to getAllOperation(slice),
        )

        private fun resolving(operations: () -> Map<String, Operation>) = object : OperationResolver {
            @Suppress("UNCHECKED_CAST")
            override fun <O : Operation> findOperation(
                operationId: String,
                requestChain: RequestChain,
                context: TContext
            ): O? = operations()[operationId] as O?
        }

        private val mockResolver = resolving { mockOperations }

        override val components: Map<String, TComponent>
            get() = context.components + mapOf(OperationResolver.COMPONENT_ID to mockResolver)

        /**
         * What `drop`/`take` return: the receiver, narrowed by [slice]. ⚠ The window is carried by
         * the chain and never written back to the receiver, so overlapping loads cannot read each
         * other's.
         */
        @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
        private inner class Window(slice: (List<T>) -> List<T>) :
            TList<T>, List<T> by this@MockTList, RequestObject {
            private val resolver = windowOperations(slice).let { operations -> resolving { operations } }

            override val context: TContext get() = this@MockTList.context
            override val requestChain: RequestChain get() = this@MockTList.requestChain
            override val components: Map<String, TComponent>
                get() = context.components + mapOf(OperationResolver.COMPONENT_ID to resolver)

            override fun getElement(index: Int) = this@MockTList.getElement(index)
            override fun <V : Data> getField(reference: String): V = error("")
            override fun <V : Data> getOptionalField(reference: String): V? = null
        }

        fun emit(result: TResult<TList<T>>) {
            val targets = callbacks.toList()
            if (result is TResult.Success<*>) {
                @Suppress("UNCHECKED_CAST")
                val payload = result.value as TList<T>
                targets.forEach { it.element(createStreamValue(payload)) }
            } else if (result is TResult.Failure<*>) {
                targets.forEach { it.end(result.error) }
            }
        }

        private fun createStreamValue(listPayload: TList<T>): StreamElement.Value<T> {
            return object : StreamElement.Value<T> {
                override val schema get() = StreamElement.Value.Schema

                @Suppress("UNCHECKED_CAST")
                override val payload: T get() = listPayload as T
                override val streamId get() = 0L.toTInteger(context)
                override fun <R : Data> getField(reference: String): R {
                    @Suppress("UNCHECKED_CAST")
                    if (reference == StreamElement.Fields.STREAM_ID) return streamId as R
                    @Suppress("UNCHECKED_CAST")
                    if (reference == StreamElement.Value.Fields.PAYLOAD) return listPayload as R
                    error("Field $reference not mocked")
                }

                override fun <R : Data> getOptionalField(reference: String): R? = null
            }
        }

        private fun dropOperation(slice: (List<T>) -> List<T>): Drop<T> = object : Drop<T> {
            override val schema: OperationSchema get() = Drop.Schema
            override fun <V : Data> getField(reference: String): V {
                @Suppress("UNCHECKED_CAST")
                if (reference == Drop.Params.N) return lastDrop.toTInteger(context) as V
                assertNotReached()
            }

            override fun <V : Data> getOptionalField(reference: String): V? = null

            override fun drop(n: Int): TList<T> {
                lastDrop = n
                return Window { slice(it).drop(n) }
            }
        }

        private fun takeOperation(slice: (List<T>) -> List<T>): Take<T> = object : Take<T> {
            override val schema: OperationSchema get() = Take.Schema
            override fun <V : Data> getField(reference: String): V {
                @Suppress("UNCHECKED_CAST")
                if (reference == Take.Params.N) return lastTake.toTInteger(context) as V
                assertNotReached()
            }

            override fun <V : Data> getOptionalField(reference: String): V? = null

            override fun take(n: Int): TList<T> {
                lastTake = n
                return Window { slice(it).take(n) }
            }
        }

        private fun streamOperation(): Stream<T> =
            object : Stream<T>, StreamInteractor<T>, RequestObject {
                override val initialResult: TResult<Data?>? = null
                override val schema: OperationSchema get() = Stream.Schema
                override fun <V : Data> getField(reference: String): V = assertNotReached()
                override fun <V : Data> getOptionalField(reference: String): V? = null

                private var signalOnlyRequested = false

                override fun stream(signalOnly: Boolean): RequestObject {
                    signalOnlyRequested = signalOnly
                    return this
                }

                override suspend fun registerCallback(callback: StreamCallback<T>) {
                    callbacks.add(callback)
                    if (shouldFail) {
                        callback.start(GenericError.create(context, "Fail"))
                    } else {
                        callback.start(null)
                        // Mirrors the proxy contract: a signal-only
                        // registration sends NO initial emission (the real
                        // StreamRegistry skips it); a plain stream ships the
                        // current list — UNLESS dropInitialStreamEmission models
                        // the Binder reverse-binding losing that first push.
                        if (!signalOnlyRequested && !dropInitialStreamEmission) {
                            callback.element(createStreamValue(items.toTList(context)))
                        }
                    }
                }

                override suspend fun endStream(callback: StreamCallback<T>) {
                    callbacks.remove(callback)
                }

                override val context: TContext get() = this@MockTList.context
                override val requestChain: RequestChain get() = this@MockTList.requestChain
            }

        // One-shot window read over the synchronous call/response path — the
        // path `TenshuPagingSource.load` now uses instead of the stream's
        // initial emission. Returns the same (identity-windowed) items the
        // Stream mock used to ship, so all load assertions are unchanged; drop/
        // take are still recorded on the receiver for the window assertions.
        private fun getAllOperation(slice: (List<T>) -> List<T>): GetAll<T> = object : GetAll<T> {
            override val schema: OperationSchema get() = GetAll.Schema
            override fun <V : Data> getField(reference: String): V = assertNotReached()
            override fun <V : Data> getOptionalField(reference: String): V? = null
            override fun getAll(): RequestObject = object : TaskInteractor<TList<T>>, RequestObject {
                override suspend fun execute(): TResult<TList<T>> {
                    if (readsToOverlap > 0) {
                        if (readsInFlight.incrementAndGet() >= readsToOverlap) readsOverlap.complete(Unit)
                        readsOverlap.await()
                    }
                    return if (shouldFail) {
                        TResult.Failure.create(context, GenericError.create(context, "Fail"))
                    } else {
                        val window = if (slicing) slice(items) else items
                        TResult.Success.create(context, window.toTList(context))
                    }
                }

                override val context: TContext get() = this@MockTList.context
                override val requestChain: RequestChain get() = this@MockTList.requestChain
            }
        }

        override fun getElement(index: Int) = items[index]

        override fun <V : Data> getField(reference: String): V = error("")
        override fun <V : Data> getOptionalField(reference: String): V? = null
    }
}

@Suppress("SameParameterValue")
private fun PagingConfig(pageSize: Int) = androidx.paging.PagingConfig(
    pageSize = pageSize,
    initialLoadSize = pageSize // Ensure loadSize matches pageSize for simple assertions
)
