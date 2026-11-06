package org.tenshu.core.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.tenshu.core.CancellableReadsElement
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.Interactor
import org.tenshu.core.InteractorProvider
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TaskInteractor
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.GetAll
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
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [TenshuKeysetPagingSource].
 *
 * ⚠⚠ The fixture here **really slices** — unlike [TenshuPagingSourceTest]'s `MockTList`, whose
 * `drop`/`take` only record the requested window and return the receiver unchanged. That is
 * deliberate and load-bearing: every defect this class exists to catch (a tie run that never
 * terminates, a cursor that re-serves rows, an insert that shifts an offset page) is invisible to a
 * fixture that hands back the same rows whatever window you ask for. A shape-only assertion would
 * pass against all of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeysetPagingSourceTest {

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
            override suspend fun <T : Data?> getInteractor(ro: RequestObject): Interactor<T> =
                ro as Interactor<T>
        }

        tContext = createTestContext(
            schemaRegistry = registry,
            traitResolver = resolver,
            pureOperationRunner = pureExecutor,
            additionalComponents = mapOf(InteractorProvider.COMPONENT_ID to interactorProvider),
        )
    }

    // ── walking the list ─────────────────────────────────────────────────────────────────────

    @Test
    fun pagesWalkTheListOnceEach_noOverlapAndNoGap() = runTest {
        val all = rows(30) { 100L - it }
        val pager = TestPager(config(pageSize = 10), sourceOver(SliceableTList(tContext, all)))

        assertEquals(all.subList(0, 10), pager.refresh().page().data)
        assertEquals(all.subList(10, 20), pager.append().page().data)
        assertEquals(all.subList(20, 30), pager.append().page().data)
        assertNull(pager.append().page().nextKey, "an exhausted list ends the pager")
    }

    /**
     * ⚠⚠ The one that matters: a run of 25 rows sharing ONE cursor value, paged 10 at a time,
     * reached **through a cursor**.
     *
     * The window is inclusive of the cursor, so a page whose rows all share that value leaves the
     * cursor exactly where it started. Without [KeysetKey.skip] the next window is byte-identical
     * to the last and the pager serves the same ten rows forever — which in Photos is not a
     * duplicate-row bug but a `while (true)` that cannot terminate (`core-keyset-paging.md`
     * §6.3).
     *
     * ⚠⚠ The five distinct rows at the front are not decoration. Without them the very first page
     * is entirely one tie run, which sends the source down the *positional* fallback and this test
     * stops exercising the cursor at all — it passes with the skip deleted. Assertions that survive
     * the mutation they were written for are worse than
     * no assertion, because they read as coverage.
     *
     * ⚠ Asserted as distinct ids, not as a count: ten rows served three times is also 30 rows.
     */
    @Test
    fun aTieRunLongerThanAPage_terminatesAndServesEachRowExactlyOnce() = runTest {
        val all = rows(35) { i ->
            when {
                i <= 5 -> 100L - i
                i <= 30 -> 50L
                else -> 40L - i
            }
        }
        val pager = TestPager(config(pageSize = 10), sourceOver(SliceableTList(tContext, all)))

        val served = pager.drain()

        assertEquals(all, served, "every row exactly once, in order, through a 25-row tie run")
    }

    /**
     * The degenerate shape the fallback exists for: a list that is *nothing but* one tie run, so no
     * page can ever produce a boundary its own first row does not share. It stays positional the
     * whole way down — and still terminates, which is the part that is not free.
     */
    @Test
    fun aListThatIsEntirelyOneTieRun_terminatesPositionally() = runTest {
        val pager = TestPager(
            config(pageSize = 10),
            sourceOver(SliceableTList(tContext, rows(25) { 500L })),
        )

        val served = pager.drain()

        assertEquals(25, served.size, "every tied row is served")
        assertEquals(served.size, served.distinct().size, "and none of them twice")
    }

    /**
     * The tie run spans a page boundary rather than filling whole pages, which is the case where
     * the skip has to *carry* — counting only the rows in the current page would re-serve the part
     * of the run the previous page already took.
     */
    @Test
    fun aTieRunSpanningAPageBoundary_carriesTheSkip() = runTest {
        // ⚠ Descending throughout — the window owns the ordering, so a fixture whose keys are not
        // sorted is not a harder test, it is an invalid one: the cursor would filter away rows that
        // sit *after* the tie run. (It was, before this comment existed.)
        // ids 1..4 distinct, ids 5..16 all share cursor 40, then distinct again below it.
        val keys = List(20) { i ->
            when {
                i < 4 -> 100L - i
                i < 16 -> 40L
                else -> 30L - (i - 16)
            }
        }
        val all = rows(20) { keys[it - 1] }
        val pager = TestPager(config(pageSize = 6), sourceOver(SliceableTList(tContext, all)))

        val served = pager.drain()

        assertEquals(all, served, "every row exactly once, in order, across the tie boundary")
    }

    // ── the reason keyset exists ─────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The whole point, and it is asserted against the offset source on the SAME fixture rather
     * than argued: a row inserted *ahead* of the cursor between two page reads shifts every offset
     * by one, so the offset pager re-serves a row it has already shown. The keyset pager cannot,
     * because its key names a row rather than a position.
     */
    @Test
    fun anInsertAheadOfTheCursor_shiftsTheOffsetPagerButNotTheKeysetOne() = runTest {
        val all = rows(30) { 100L - it }
        val live = all.toMutableList()
        val list = SliceableTList(tContext, live)

        val keyset = TestPager(config(pageSize = 10), sourceOver(list))
        val first = keyset.refresh().page()
        assertEquals(all.subList(0, 10), first.data)

        // A photograph is taken while the user is scrolling: it sorts to the head of the feed.
        live.add(0, "999:fresh")

        assertEquals(
            all.subList(10, 20),
            keyset.append().page().data,
            "the keyset cursor names the last row of page 1, so page 2 still starts after it",
        )

        // Same fixture, same insert, the offset source — which addresses row 11 by POSITION 10, and
        // position 10 is now row 10.
        val offsetList = SliceableTList(tContext, live)
        val offset = TestPager(
            config(pageSize = 10),
            TenshuPagingSource<TString, TString, String>(
                tContext, offsetList, projection = { it },
            ) { (it as TStringDto).get() },
        )
        offset.refresh()
        val offsetSecond = offset.append().page().data
        assertEquals(
            first.data.last(),
            offsetSecond.first(),
            "the offset pager's page 2 opens on the row page 1 ended with — the duplicate",
        )
    }

    // ── the window's preconditions ───────────────────────────────────────────────────────────

    /**
     * ⚠⚠ Why [keysetPagingSource]'s KDoc insists the window is INCLUSIVE of the cursor, shown as
     * the consequence rather than stated as a rule: an exclusive window drops the rows the skip is
     * about to drop again, so every row tied at a page boundary vanishes from the feed.
     *
     * ⚠ A test double is where this divergence hides: a mock that uses `<` where production uses
     * `inRange` (`<=`) passes a fidelity guard that asserts the mock's side of the difference.
     */
    @Test
    fun anExclusiveWindow_silentlyLosesTheRowsTiedAtAPageBoundary() = runTest {
        // rows 10 and 11 share cursor 91, straddling the page-1/page-2 boundary.
        val keys = List(20) { i -> if (i in 9..10) 91L else 100L - i }
        val list = SliceableTList(tContext, rows(20) { keys[it - 1] })

        val exclusive = TestPager(
            config(pageSize = 10),
            sourceOver(list, window = { root, cursor ->
                (root as SliceableTList).where { key -> cursor == null || key < cursor }
            }),
        )
        exclusive.refresh()
        assertTrue(
            "91:r11" !in exclusive.append().page().data,
            "an exclusive window loses the tied row — this is the failure the precondition prevents",
        )

        // The inclusive window, on the identical fixture, keeps it.
        val inclusive = TestPager(config(pageSize = 10), sourceOver(list))
        inclusive.refresh()
        assertTrue("91:r11" in inclusive.append().page().data)
    }

    /**
     * A partial [mapper] may empty a whole page. The cursor still has to advance, which is why it
     * is read off the unmapped window — reading it off the mapped rows would stall the pager on one
     * window with nothing to take a cursor from.
     */
    @Test
    fun aPageTheMapperEmpties_stillAdvancesTheCursor() = runTest {
        val all = rows(30) { 100L - it }
        val list = SliceableTList(tContext, all)
        // Drop rows 11..20 — the whole of page 2.
        val source = sourceOver(list, mapper = { row ->
            val id = (row as TStringDto).get()
            id.takeUnless { it.substringAfter(":r").toInt() in 11..20 }
        })
        val pager = TestPager(config(pageSize = 10), source)

        assertEquals(all.subList(0, 10), pager.refresh().page().data)
        val emptied = pager.append().page()
        assertEquals(emptyList(), emptied.data)
        assertEquals(
            KeysetKey(80L, skip = 1, position = 20),
            emptied.nextKey,
            "an all-filtered page must still carry the window's last cursor",
        )
        assertEquals(all.subList(20, 30), pager.append().page().data)
    }

    // ── refresh anchoring ────────────────────────────────────────────────────────────────────

    @Test
    fun getRefreshKey_returnsTheCursorThatProducedTheAnchorPage() = runTest {
        val list = SliceableTList(tContext, rows(30) { 100L - it })
        val source = sourceOver(list)
        val pager = TestPager(config(pageSize = 10), source)

        pager.refresh()
        pager.append()
        pager.append()

        // Anchor in the third page (rows 21..30).
        val state = pager.getPagingState(anchorPosition = 25)
        assertEquals(
            KeysetKey(80L, skip = 1, position = 20),
            source.getRefreshKey(state),
            "a reload must re-open the window that produced the anchor page, not the head",
        )
    }

    @Test
    fun getRefreshKey_isNullWhenTheAnchorIsInTheFirstPage() = runTest {
        val list = SliceableTList(tContext, rows(30) { 100L - it })
        val source = sourceOver(list)
        val pager = TestPager(config(pageSize = 10), source)

        pager.refresh()
        pager.append()

        assertNull(
            source.getRefreshKey(pager.getPagingState(anchorPosition = 3)),
            "the first page is already the head, so it reloads from the head",
        )
    }

    // ── the shape the fusion gate needs ──────────────────────────────────────────────────────

    /**
     * The window ops must be a `drop`/`take` **prefix** immediately after the caller's sort — that
     * prefix is what `core-ordered-window-fusion.md`'s F2 gate folds into one bounded top-N pass
     * (`foldWindowPrefix`, `OrderedWindowFusionTest.dropTakeWindow_slicesTheOrderedList`). Anything
     * between the sort and the window misses the gate and re-sorts the materialised list per page.
     */
    @Test
    fun theWindowIsCutAsDropThenTake_immediatelyAfterTheCallersSort() = runTest {
        val list = SliceableTList(tContext, rows(30) { 100L - it })
        val pager = TestPager(config(pageSize = 10), sourceOver(list))

        pager.refresh()
        assertEquals(0, list.recorder.lastDrop, "the first page opens the window at its head")
        assertEquals(10, list.recorder.lastTake)

        pager.append()
        assertEquals(1, list.recorder.lastDrop, "page 2 skips the one row tied at the boundary")
        assertEquals(10, list.recorder.lastTake)

        assertEquals(
            listOf("drop", "take", "drop", "take"),
            list.recorder.order,
            "drop must precede take, so the pair folds to one (offset, limit)",
        )
    }

    // ── append-only, for now ─────────────────────────────────────────────────────────────────

    // ── seeking: prepend and jump ────────────────────────────────────────────────────────

    /**
     * ⚠⚠ A prepend is **positional**, not a cursor window running backwards, and that is a decision
     * rather than a shortcut. With a `(key, skip)` cursor the reverse window enumerates ties in
     * encounter order, so the already-loaded rows at the boundary value are *interleaved* with the
     * unloaded ones and dropping `skip` from its head removes the wrong ones. A rank has no such
     * problem: the page above `[p]` is `[p - n, p)`.
     */
    @Test
    fun aPrependLoadsThePageEndingWhereTheAnchorPageBegins() = runTest {
        val all = rows(30) { 100L - it }
        val source = sourceOver(SliceableTList(tContext, all))

        val page = source.load(
            PagingSource.LoadParams.Prepend(
                key = KeysetKey<Long>(cursor = null, position = 20),
                loadSize = 10,
                placeholdersEnabled = false,
            )
        ).page()

        assertEquals(all.subList(10, 20), page.data)
        assertEquals(KeysetKey<Long>(cursor = null, position = 10), page.prevKey)
    }

    @Test
    fun aPrependAtTheHeadStops() = runTest {
        val source = sourceOver(SliceableTList(tContext, rows(30) { 100L - it }))

        val page = source.load(
            PagingSource.LoadParams.Prepend(
                key = KeysetKey<Long>(cursor = null, position = 0),
                loadSize = 10,
                placeholdersEnabled = false,
            )
        ).page()

        assertEquals(emptyList(), page.data)
        assertNull(page.prevKey, "a null prevKey is what terminates prepending")
    }

    /**
     * ⚠⚠ The scrubber's whole reason for existing: a jump reads **one** window at the target rank
     * rather than walking every page down to it.
     */
    @Test
    fun aJumpReadsOneWindowAtTheTargetRank_notEveryPageDownToIt() = runTest {
        val all = rows(300) { 1000L - it }
        val list = SliceableTList(tContext, all)
        val source = sourceOver(list, total = { all.size })

        val page = source.load(
            PagingSource.LoadParams.Refresh(
                key = KeysetKey<Long>(cursor = null, position = 200),
                loadSize = 10,
                placeholdersEnabled = true,
            )
        ).page()

        assertEquals(all.subList(200, 210), page.data)
        assertEquals(200, list.recorder.lastDrop, "the seek is a single drop to the rank")
        assertEquals(listOf("drop", "take"), list.recorder.order, "and it is ONE read, not 20")
        assertEquals(200, page.itemsBefore)
        assertEquals(90, page.itemsAfter)
    }

    /**
     * ⚠⚠ The subtle one. A positional page whose rows *all* share one cursor value cannot hand back
     * a cursor: rows tied with its last row may sit **above** where the window was dropped into,
     * invisible from the page, and a skip that missed them would re-serve them on the next page. It
     * stays positional instead of guessing.
     *
     * ⚠ The tell is the page's own first row. When that key differs from the last, the boundary's
     * tie run demonstrably began inside the page and the count is exact — which is why the
     * neighbouring jump test does get a cursor back.
     */
    @Test
    fun aJumpLandingInsideATieRun_staysPositionalRatherThanGuessingASkip() = runTest {
        // Rows 1..50 distinct, 51..150 all share cursor 500, then distinct below it.
        val all = rows(200) { i ->
            when {
                i <= 50 -> 1000L - i
                i <= 150 -> 500L
                else -> 400L - i
            }
        }
        val source = sourceOver(SliceableTList(tContext, all), total = { all.size })

        val landed = source.load(
            PagingSource.LoadParams.Refresh(
                key = KeysetKey<Long>(cursor = null, position = 80),
                loadSize = 10,
                placeholdersEnabled = true,
            )
        ).page()

        assertEquals(all.subList(80, 90), landed.data, "every row here shares one cursor value")
        assertEquals(
            KeysetKey<Long>(cursor = null, position = 90),
            landed.nextKey,
            "a cursor whose skip counted only this page would re-serve the run's earlier rows",
        )

        val next = source.load(
            PagingSource.LoadParams.Append(
                key = landed.nextKey!!,
                loadSize = 10,
                placeholdersEnabled = true,
            )
        ).page()
        assertEquals(all.subList(90, 100), next.data, "and continuing does not duplicate")
    }

    // ── placeholders ─────────────────────────────────────────────────────────────────────

    @Test
    fun withATotal_everyPageReportsItsRankAndTheRemainder() = runTest {
        val all = rows(100) { 1000L - it }
        val pager = TestPager(
            seekablePagingConfig(pageSize = 10),
            sourceOver(SliceableTList(tContext, all), total = { all.size }),
        )

        // ⚠ The fixture is longer than the initial load, or every assertion here reads 0 and proves
        // nothing. The refresh is ONE page (`seekablePagingConfig`, `photos-app.md` §38 fix 4), not
        // Paging's default of three, under which these read 70 / 30 / 60.
        val first = pager.refresh().page()
        assertEquals(0, first.itemsBefore)
        assertEquals(90, first.itemsAfter, "the track is the whole list, not the loaded window")

        val second = pager.append().page()
        assertEquals(10, second.itemsBefore)
        assertEquals(80, second.itemsAfter)
    }

    @Test
    fun withoutATotal_theCountsStayUndefinedAndJumpingIsOff() = runTest {
        val source = sourceOver(SliceableTList(tContext, rows(30) { 100L - it }))
        val page = TestPager(config(pageSize = 10), source).refresh().page()

        assertFalse(source.jumpingSupported, "there would be no placeholders to jump through")
        assertEquals(PagingSource.LoadResult.Page.COUNT_UNDEFINED, page.itemsBefore)
        assertEquals(PagingSource.LoadResult.Page.COUNT_UNDEFINED, page.itemsAfter)
    }

    /**
     * ⚠⚠ Once per source, not once per page. For the caller this exists for the total is a
     * `count()` over a *filtered* whole-realm traversal — which has no O(1) member-hash count to
     * fall back on and walks the tree — so counting per page would roughly double what every page
     * costs, to keep a placeholder region exact to the row.
     *
     * ⚠ A source is one generation (the factory rebuilds the `Pager` per settled change), so this
     * is not staleness the reader can accumulate: a row arriving means a new source and a fresh
     * count.
     */
    @Test
    fun theTotalIsCountedOncePerSource_notOncePerPage() = runTest {
        val all = rows(100) { 1000L - it }
        var counts = 0
        val pager = TestPager(
            seekablePagingConfig(pageSize = 10),
            sourceOver(SliceableTList(tContext, all), total = { counts++; all.size }),
        )

        pager.refresh()
        pager.append()
        pager.append()

        assertEquals(1, counts, "three loads, one count")
    }

    /**
     * ⚠⚠ The count is read BESIDE the page, not after it (`photos-app.md` §38, fix 3). Both are
     * whole-realm walks in Control for the Photos feed, and in series the count added 1.0–1.8 s to
     * every first page on the maintainer's phone (§37.1) before a single row could be drawn.
     */
    @Test
    fun theCountIsReadBesideThePage_notAfterIt() = runTest {
        val all = rows(100) { 1000L - it }
        val recorder = WindowRecorder().apply { readDelayMs = 1_000 }
        val pager = TestPager(
            seekablePagingConfig(pageSize = 10),
            sourceOver(SliceableTList(tContext, all, recorder), total = { delay(1_000); all.size }),
        )

        val first = pager.refresh().page()

        assertEquals(1_000L, currentTime, "a 1 s page and a 1 s count, side by side: 1 s, not 2")
        assertEquals(100, first.itemsBefore + first.data.size + first.itemsAfter, "and the count reaches the page")
    }

    /** ⚠ A page that fails is an error at once — it does not wait for a count nobody will use. */
    @Test
    fun aFailedPageDoesNotWaitForTheCount() = runTest {
        val recorder = WindowRecorder().apply { failReads = true }
        val pager = TestPager(
            seekablePagingConfig(pageSize = 10),
            sourceOver(SliceableTList(tContext, rows(30) { 100L - it }, recorder), total = { delay(5_000); 30 }),
        )

        assertIs<PagingSource.LoadResult.Error<*, *>>(pager.refresh())
        assertEquals(0L, currentTime)
    }

    /**
     * ⚠⚠ The count running beside the page is a **cancellable read** (`cancellableReads { }`) — so
     * when a failed page
     * cancels it, or a superseded generation does, the cancel reaches Control instead of leaving it
     * to walk the realm for a count nobody will use.
     */
    @Test
    fun theCountBesideThePageIsACancellableRead_andAFailedPageCancelsIt() = runTest {
        var countMarked: Boolean? = null
        var countCancelled = false
        val recorder = WindowRecorder().apply { readDelayMs = 100; failReads = true }
        val pager = TestPager(
            seekablePagingConfig(pageSize = 10),
            sourceOver(SliceableTList(tContext, rows(30) { 100L - it }, recorder), total = {
                countMarked = currentCoroutineContext()[CancellableReadsElement] != null
                try {
                    delay(5_000)
                    30
                } catch (e: CancellationException) {
                    countCancelled = true
                    throw e
                }
            }),
        )

        assertIs<PagingSource.LoadResult.Error<*, *>>(pager.refresh())
        assertEquals(true, countMarked, "the count runs inside `cancellableReads`")
        assertTrue(countCancelled, "and the failed page cancelled it")
        assertEquals(100L, currentTime, "without waiting for it")
    }

    @Test
    fun withATotal_jumpingIsDeclared_soAConfigMaySetAJumpThreshold() = runTest {
        val all = rows(30) { 100L - it }
        assertTrue(sourceOver(SliceableTList(tContext, all), total = { all.size }).jumpingSupported)
    }

    /**
     * ⚠ With placeholders on, `anchorPosition` is a rank into the whole list — so a jump re-anchors
     * where the thumb was dragged. Without them it counts loaded rows only and is not a rank at
     * all, which is why the two paths in `getRefreshKey` are not one.
     */
    @Test
    fun getRefreshKey_withATotal_seeksToTheAnchorRank() = runTest {
        val all = rows(300) { 1000L - it }
        val source = sourceOver(SliceableTList(tContext, all), total = { all.size })
        val pager = TestPager(seekablePagingConfig(pageSize = 10), source)
        pager.refresh()

        assertEquals(
            KeysetKey<Long>(cursor = null, position = 240),
            source.getRefreshKey(pager.getPagingState(anchorPosition = 240)),
        )
    }

    /**
     * ⚠⚠ Loud rather than inert: Paging ignores `itemsBefore`/`itemsAfter` when placeholders are
     * off, so this pairing would otherwise produce a listing that reports a full-length track and
     * silently is not one.
     */
    @Test
    fun aTotalWithoutPlaceholders_isRefusedRatherThanIgnored() = runTest {
        val list = SliceableTList(tContext, rows(30) { 100L - it })
        val failure = assertFailsWith<IllegalArgumentException> {
            list.toKeysetPagingData(
                mapper = { (it as TStringDto).get() },
                cursorOf = { (it as TStringDto).get().substringBefore(':').toLong() },
                pagingConfig = config(pageSize = 10),
                totalCount = { 30 },
            ) { root, cursor ->
                (root as SliceableTList).where { k -> cursor == null || k <= cursor }
            }
        }
        assertTrue("seekablePagingConfig" in failure.message.orEmpty())
    }



    // ── the per-generation count memo (§11) ───────────────────────────────────────────────────

    /**
     * ⚠⚠ **What a jump costs, and why the memo moved.** `TenshuKeysetPagingSource` memoises the
     * total on itself, so one source counts once however many pages it serves — but Paging builds a
     * NEW source every time it invalidates, and a jump invalidates. A seek therefore threw the memo
     * away exactly when the reader moved, and Photos paid a full-tree count (~700 ms on 922
     * photographs) on top of the refresh before any row appeared.
     *
     * ⚠ The end-to-end proof is not here: this file's fixture cannot serve `stream/start`
     * (`FirstLoadWaitsForTheObserverTest`'s can), so `reloadSignals()` — and therefore the whole
     * flow — will not run against it. It is pinned over a real stack instead, in
     * `RealRepositoryReadTest.aJumpDoesNotRecountTheLibrary`.
     */
    @Test
    fun theCountMemoAnswersOnceAndKeepsTheAnswer() = runTest {
        var counted = 0
        val memo = { counted++; 42 }.asSuspend().memoisedForOneGeneration()

        assertEquals(42, memo())
        assertEquals(42, memo())
        assertEquals(42, memo())
        assertEquals(1, counted, "a value is counted once and remembered")
    }

    /**
     * ⚠⚠ A failed count must NOT be remembered. `null` means the read failed — a dead proxy, a
     * version-skewed one — and caching it would disable placeholders for the whole generation on
     * the strength of one bad read, which is a collapsed scrubber track that never recovers.
     */
    @Test
    fun aFailedCountIsRetriedRatherThanRemembered() = runTest {
        var attempts = 0
        val memo = { if (++attempts < 3) null else 7 }.asSuspend().memoisedForOneGeneration()

        assertNull(memo())
        assertNull(memo())
        assertEquals(7, memo(), "the third attempt succeeds")
        assertEquals(7, memo())
        assertEquals(3, attempts, "and only then does it stop asking")
    }

    /** Lifts a plain lambda so the memo under test can be applied to it. */
    private fun (() -> Int?).asSuspend(): suspend () -> Int? = { this() }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Rows in list order, `"<cursor>:r<n>"`, so a duplicate is visible in an assertion. */
    private fun rows(count: Int, key: (Int) -> Long): List<String> =
        (1..count).map { "${key(it)}:r$it" }

    /**
     * Every row the pager will serve, appending until it stops.
     *
     * ⚠⚠ A bounded loop rather than a fixed page count, because "does it terminate" is half of what
     * the tie tests assert — and a broken skip does not fail an assertion, it never stops. The guard
     * turns that hang into a red test.
     */
    private suspend fun TestPager<KeysetKey<Long>, String>.drain(): List<String> {
        val served = mutableListOf<String>()
        served += refresh().page().data
        var loads = 0
        while (true) {
            val next = append() ?: break
            served += next.page().data
            check(++loads < 50) { "the pager did not terminate after $loads appends" }
        }
        return served
    }

    private fun config(pageSize: Int) = PagingConfig(
        pageSize = pageSize,
        initialLoadSize = pageSize,
        enablePlaceholders = false,
    )

    private fun sourceOver(
        list: SliceableTList,
        window: (TList<TString>, Long?) -> TList<TString> = { root, cursor ->
            (root as SliceableTList).where { key -> cursor == null || key <= cursor }
        },
        mapper: (TString) -> String? = { (it as TStringDto).get() },
        total: (suspend () -> Int?)? = null,
    ) = TenshuKeysetPagingSource<TString, TString, String, Long>(
        context = tContext,
        tenshuList = list,
        window = window,
        projection = { it },
        cursorOf = { (it as TStringDto).get().substringBefore(':').toLong() },
        mapper = mapper,
        totalCount = total,
    )

    private fun <K : Any, R : Any> PagingSource.LoadResult<K, R>?.page():
        PagingSource.LoadResult.Page<K, R> = assertIs(this)
}

/** Records the window ops a source asked for, across the whole derived chain. */
private class WindowRecorder {
    var lastDrop: Int = -1
    var lastTake: Int = -1
    val order = mutableListOf<String>()

    /** How long a page read takes, in virtual time — for the count-beside-the-page test. */
    var readDelayMs: Long = 0

    /** Every page read answers a failure. */
    var failReads: Boolean = false
}

/**
 * A [TList] whose `drop`/`take` **actually slice**, over a live backing list.
 *
 * ⚠ Live on purpose: `anInsertAheadOfTheCursor_...` mutates the backing list between two page reads
 * and the derived windows have to see it, which a snapshot taken at construction would not.
 *
 * ⚠ `TList` is not a `List` (it declares `getElement`), and the paging path only ever reads a page
 * through `getAll()`, so nothing here needs a `List` face.
 */
private class SliceableTList(
    override val context: TContext,
    private val provider: () -> List<String>,
    val recorder: WindowRecorder = WindowRecorder(),
) : TList<TString>, RequestObject {

    constructor(context: TContext, rows: List<String>, recorder: WindowRecorder = WindowRecorder())
        : this(context, { rows }, recorder)

    /** The caller-supplied window: filter by cursor, preserving list order. */
    fun where(predicate: (Long) -> Boolean): SliceableTList =
        SliceableTList(
            context,
            { provider().filter { predicate(it.substringBefore(':').toLong()) } },
            recorder,
        )

    override val requestChain: RequestChain = object : RequestChain {
        override fun addReference(id: String): RequestChain = this
        override fun addArray(array: org.tenshu.core.TArray): RequestChain = this
        override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain = this
        override fun getCallstack(): List<Data> = emptyList()
        override suspend fun getResult(): TResult<Data> = error("not used")
    }

    private val mockOperations: Map<String, Operation> by lazy {
        mapOf(Drop.ID to dropOperation(), Take.ID to takeOperation(), GetAll.ID to getAllOperation())
    }

    private val mockResolver = object : OperationResolver {
        @Suppress("UNCHECKED_CAST")
        override fun <O : Operation> findOperation(
            operationId: String,
            requestChain: RequestChain,
            context: TContext,
        ): O? = mockOperations[operationId] as O?
    }

    override val components: Map<String, TComponent>
        get() = context.components + mapOf(OperationResolver.COMPONENT_ID to mockResolver)

    private fun dropOperation(): Drop<TString> = object : Drop<TString> {
        override val schema: OperationSchema get() = Drop.Schema
        override fun <V : Data> getField(reference: String): V = assertNotReached()
        override fun <V : Data> getOptionalField(reference: String): V? = null
        override fun drop(n: Int): TList<TString> {
            recorder.lastDrop = n
            recorder.order += "drop"
            return SliceableTList(context, { provider().drop(n) }, recorder)
        }
    }

    private fun takeOperation(): Take<TString> = object : Take<TString> {
        override val schema: OperationSchema get() = Take.Schema
        override fun <V : Data> getField(reference: String): V = assertNotReached()
        override fun <V : Data> getOptionalField(reference: String): V? = null
        override fun take(n: Int): TList<TString> {
            recorder.lastTake = n
            recorder.order += "take"
            return SliceableTList(context, { provider().take(n) }, recorder)
        }
    }

    private fun getAllOperation(): GetAll<TString> = object : GetAll<TString> {
        override val schema: OperationSchema get() = GetAll.Schema
        override fun <V : Data> getField(reference: String): V = assertNotReached()
        override fun <V : Data> getOptionalField(reference: String): V? = null
        override fun getAll(): RequestObject = object : TaskInteractor<TList<TString>>, RequestObject {
            @Suppress("UNCHECKED_CAST")
            override suspend fun execute(): TResult<TList<TString>> {
                if (recorder.readDelayMs > 0) delay(recorder.readDelayMs)
                if (recorder.failReads) {
                    return TResult.Failure.create(context, GenericError.create(context, "read failed"))
                }
                return TResult.Success.create(
                    context,
                    provider().map { it.toTString(context) }.toTList(context) as TList<TString>,
                )
            }

            override val context: TContext get() = this@SliceableTList.context
            override val requestChain: RequestChain get() = this@SliceableTList.requestChain
        }
    }

    override fun getElement(index: Int): TString = provider()[index].toTString(context)
    override fun <V : Data> getField(reference: String): V = assertNotReached()
    override fun <V : Data> getOptionalField(reference: String): V? = null
}
