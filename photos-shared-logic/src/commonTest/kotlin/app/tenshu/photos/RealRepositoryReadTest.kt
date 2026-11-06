package app.tenshu.photos

import org.tenshu.photo.saveAlbum
import org.tenshu.photo.client.photoService
import app.tenshu.photos.data.AlbumsResult
import app.tenshu.photos.data.TrashResult
import org.tenshu.core.TResult
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.file.system.replaceFile
import org.tenshu.core.file.system.moveFile
import androidx.paging.testing.asSnapshot
import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.TenshuPhotosRepository
import app.tenshu.photos.data.DuplicatesResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory
import org.tenshu.core.paging.keysetPagingSource
import org.tenshu.core.paging.seekablePagingConfig
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.entity.get
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.category
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.equalTo
import org.tenshu.core.inRange
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `TenshuPhotosRepository` — the **real** one — read through an in-process proxy.
 *
 * ⚠⚠ **Before this file, none of it had ever been executed by a test.** Every photos test injects
 * `MockPhotosRepository`, which replaces the whole class, so the repository's unchecked casts, its
 * field reads and its result folds were covered by nothing at all — including the `catch` arms that
 * decide whether a user is told "Control is unreachable".
 *
 * The stack is `DataSourceReadTest`'s (`fit-shared-data`): one `TestProxy` holding the real
 * `:photo-service` implementations, and a real `TenshuClient` over it. That is what makes a read
 * here go through the same chain, serializer and entity materialisation a device would.
 */
class RealRepositoryReadTest {

    /** One store, so a second repository over it is the closest a unit test gets to a relaunch. */
    private class Stack(scope: CoroutineScope, placesAndLabels: Boolean = false) {
        val proxy: TestProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(photoServiceCommonModule)
                registerConfig(photoServiceClientModule)
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
                // ⚠ Schemas only, for the library watch: it observes the places and the labels, and
                // a chain cannot even be built for a service the registry has never heard of.
                if (placesAndLabels) {
                    registerConfig(org.tenshu.place.common.placeServiceCommonModule)
                    registerConfig(org.tenshu.place.client.placeServiceClientModule)
                    registerConfig(org.tenshu.inference.common.inferenceServiceCommonModule)
                    registerConfig(org.tenshu.inference.client.inferenceServiceClientModule)
                }
            },
            coroutineScope = scope,
            // ⚠⚠ BOTH factories, composed. Photos reads across two services — albums from
            // `:photo-service`, the feed and its metadata from `:file-service` — and a stack with
            // only one of them fails in a way that looks like a product bug: `feedPage` came back
            // `Failed(Entity not found … keyValue=/)` simply because nothing had created the root
            // folder.
            implementationFactoryOverride = { id, proxy ->
                photoServiceProxyImplementationFactory(id, nowEpochMs = { 1_000L })
                    ?: fileServiceProxyImplementationFactory(id, proxy.fileDatabase)
            },
        ).also {
            it.executionContext.initPhotoService()
            it.executionContext.initFileService()
        }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )

        val repository = TenshuPhotosRepository(client, clockMs = { 1_000L }, reachability = null /* test: no reachability engine */)

        /**
         * Put one image in the realm's root, through the service's own upload op.
         *
         * ⚠ `uploadFile`, not a hand-built entity: the trash path moves a real row and reads its
         * `contentId` back, and a row assembled in a test would be free to omit whatever the code
         * under test happens to need.
         */
        /** Seed with an explicit capture time, for boundary cases. */
        suspend fun seedPhotoAt(name: String, capturedAtMs: Long) {
            assertTrue(
                client.fileService.defaultFileSystem.uploadFile(
                    "/", name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
                ).let { it is TResult.Success },
                "seeding /" + name + " should succeed",
            )
        }

        suspend fun seedPhoto(name: String) {
            assertTrue(
                client.fileService.defaultFileSystem.uploadFile(
                    "/", name, byteArrayOf(1, 2, 3), sourceModifiedAt = 1_000L,
                ).let { it is TResult.Success },
                "seeding /" + name + " should succeed",
            )
        }
    }

    @Test
    fun anAlbumWrittenThroughTheRealOpsIsReadBackByTheRealRepository() = runTest {
        // ⚠ The whole point: write with the service's own op, read with the repository's own
        // projection. A repository that selects fields its own reader does not use, or reads one
        // the projection never asked for, is green in every mock-backed test and empty on screen.
        val stack = Stack(this)

        val albumId = stack.repository.createAlbum("Trip to France")
        assertTrue(albumId != null && albumId.isNotEmpty(), "createAlbum returned $albumId")

        when (val result = stack.repository.albums()) {
            is AlbumsResult.Albums -> {
                assertEquals(listOf("Trip to France"), result.albums.map { it.name })
                assertEquals(listOf(albumId), result.albums.map { it.albumId })
                // ⚠ Read off the same row the write produced — `photoCount` is derived from the
                // entries, and an empty album must say 0 rather than fail to project.
                assertEquals(listOf(0), result.albums.map { it.photoCount })
            }

            else -> throw AssertionError("expected albums, got $result")
        }
    }

    /**
     * ⚠⚠ **Trashing a photograph on a realm with no `/Trash` folder.** Which is every realm:
     * nothing in the codebase creates `/Trash`, so a bare `moveFile` answers `notFound` and the
     * trash never works. It fails *silently* — the repository returns `false`, the view model
     * drops it, and the viewer pops as though the photograph had gone.
     *
     * ⚠ Only the real stack can see this. Every other trash test injects `MockPhotosRepository`,
     * which replaces the whole class with an in-memory list where a destination folder is not a
     * concept — so they stay green over a trash that does not work at all.
     */
    @Test
    fun aPhotographIsTrashedOnARealmThatHasNoTrashFolderYet() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("IMG_0001.jpg")

        val page = stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10)
        val photo = (page as PhotoPageResult.Page).photos.single()

        assertTrue(
            stack.repository.trash(photo),
            "trashing must succeed on a realm that has no /Trash folder yet",
        )

        // The feed no longer holds it…
        val after = stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10)
        assertEquals(emptyList(), (after as PhotoPageResult.Page).photos.map { it.path })

        // …and the trash does, with where it came from, so a restore has somewhere to put it back.
        when (val trashed = stack.repository.trashed()) {
            is TrashResult.Items -> assertEquals(
                listOf("/IMG_0001.jpg"),
                trashed.items.map { it.originalPath },
            )

            else -> throw AssertionError("expected trash items, got $trashed")
        }
    }

    /**
     * ⚠ The second trash must not fail on the folder the first one created. `createFolder` throws
     * `AlreadyExistsError` rather than shrugging, so treating that as a failure would make trashing
     * work exactly once per realm — a subtler version of the same bug.
     */
    @Test
    fun trashingASecondPhotographReusesTheFolderRatherThanFailingOnIt() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("IMG_0001.jpg")
        stack.seedPhoto("IMG_0002.jpg")

        val photos = (stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10)
            as PhotoPageResult.Page).photos
        assertEquals(2, photos.size)

        assertTrue(stack.repository.trash(photos[0]), "the first trash creates /Trash")
        assertTrue(stack.repository.trash(photos[1]), "the second must reuse it")

        when (val trashed = stack.repository.trashed()) {
            is TrashResult.Items -> assertEquals(2, trashed.items.size)
            else -> throw AssertionError("expected trash items, got $trashed")
        }
    }

    /** A restore puts it back where it came from, which is the other half of the move. */
    @Test
    fun aTrashedPhotographRestoresToItsOriginalPath() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("IMG_0001.jpg")

        val photo = (stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10)
            as PhotoPageResult.Page).photos.single()
        assertTrue(stack.repository.trash(photo))
        assertTrue(stack.repository.restore(photo.contentId))

        val back = stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10)
        assertEquals(
            listOf("/IMG_0001.jpg"),
            (back as PhotoPageResult.Page).photos.map { it.path },
        )
    }

    /**
     * ⚠⚠ Does the feed's window actually include its cursor?
     *
     * Every comment in the paging path says it does — "`inRange` above is INCLUSIVE of `beforeMs`,
     * deliberately, so photographs sharing the boundary millisecond are not lost" — and the tie
     * skip in `:core-paging` is built on exactly that. But `inRangeLogic` is half-open
     * (`start <= v < end`), and its parameter is named `endExclusive`.
     *
     * Three photographs sharing one capture time, paged two at a time. If the window is inclusive
     * the third arrives on page 2. If it is exclusive, page 2's window excludes the tie value
     * entirely and the third is LOST — silently, which is how it would reach a user.
     */
    @Test
    fun theFeedWindowIncludesItsCursor_soTiedPhotographsSurviveAPageBoundary() = runTest {
        val stack = Stack(this)
        stack.seedPhotoAt("a.jpg", 5_000L)
        stack.seedPhotoAt("b.jpg", 5_000L)
        stack.seedPhotoAt("c.jpg", 5_000L)

        val first = (stack.repository.feedPage(Long.MAX_VALUE, 0, 2) as PhotoPageResult.Page).photos
        assertEquals(2, first.size, "page 1 holds two of the three")

        val boundary = first.last().capturedAtMs
        val held = first.count { it.capturedAtMs == boundary }
        val second = (stack.repository.feedPage(boundary, held, 2) as PhotoPageResult.Page).photos

        assertEquals(
            listOf("/a.jpg", "/b.jpg", "/c.jpg").sorted(),
            (first + second).map { it.path }.sorted(),
            "every tied photograph must be served exactly once across the boundary",
        )
    }

    /**
     * The same boundary, through the path the SCREEN uses.
     *
     * ⚠⚠ `feedPage` and `feedPaging` share `feedWindow`, so one exclusive bound broke both — but
     * they are different pagers and only this one is what a user scrolls. `:core-paging`'s tie skip
     * assumes an inclusive window by contract (`KeysetKey`), so an exclusive one made the skip
     * subtract from an exclusion that had already happened: rows dropped twice.
     */
    @Test
    fun thePagedFeedAlsoKeepsEveryPhotographTiedAtAPageBoundary() = runTest {
        // ⚠⚠ Two things this fixture has to get right, and it got both wrong first:
        //
        // 1. It must be LONGER than `initialLoadSize` (one page since `photos-app.md` §38; it was
        //    3 × pageSize when this was written). A fixture that fits in the
        //    first load never reaches a page boundary, so it cannot see a boundary bug — it passed
        //    against the exclusive window while proving nothing.
        // 2. The page before the boundary must hold MIXED capture times. When a page is entirely
        //    one tie run `:core-paging` hands back a POSITIONAL key rather than a cursor (its
        //    documented fallback), and a positional read has no bound to be exclusive about.
        //
        // pageSize 2 ⇒ every load is 2 rows, so pages end at 2, 4, 6. The 5000 tie straddles the
        // boundary at 6, and the page before it (5500, 5000) is mixed.
        val stack = Stack(this)
        listOf(9_000L, 8_000L, 7_000L, 6_000L, 5_500L).forEachIndexed { i, t ->
            stack.seedPhotoAt("d$i.jpg", t)
        }
        stack.seedPhotoAt("tieA.jpg", 5_000L)
        stack.seedPhotoAt("tieB.jpg", 5_000L)
        stack.seedPhotoAt("older.jpg", 1_000L)

        // ⚠⚠ 3. And it must actually SCROLL. A bare `asSnapshot()` returns the initial load and
        //    stops, so without this it reports the first six rows and calls that the whole feed —
        //    which looks exactly like the data loss it is meant to detect.
        val served = stack.repository.feedPaging(pageSize = 2)
            .asSnapshot { appendScrollWhile { true } }

        assertEquals(
            ((0..4).map { "/d$it.jpg" } + listOf("/tieA.jpg", "/tieB.jpg", "/older.jpg")).sorted(),
            served.map { it.path }.sorted(),
            "a tie straddling a page boundary must survive the cursor hand-off",
        )
    }

    /**
     * The scrubber's track is only honest if its length equals the number of photographs the feed
     * will actually serve.
     *
     * ⚠⚠ A feed that loses rows at page boundaries breaks it (§23): a scan then counts more
     * photographs than the paged feed serves. With placeholders ON that gap stops being a counting
     * curiosity — it is grey tiles at the end of the track that nothing ever fills.
     *
     * ⚠ Deliberately over a fixture with a tie run crossing a page boundary, because that is
     * exactly where the two come apart.
     */
    @Test
    fun theTrackLengthEqualsTheNumberOfPhotographsTheFeedServes() = runTest {
        val stack = Stack(this)
        listOf(9_000L, 8_000L, 7_000L, 6_000L, 5_500L).forEachIndexed { i, t ->
            stack.seedPhotoAt("d$i.jpg", t)
        }
        stack.seedPhotoAt("tieA.jpg", 5_000L)
        stack.seedPhotoAt("tieB.jpg", 5_000L)
        stack.seedPhotoAt("older.jpg", 1_000L)

        val served = stack.repository.feedPaging(pageSize = 2)
            .asSnapshot { appendScrollWhile { true } }

        assertEquals(
            served.size,
            stack.repository.feedTotal(),
            "the placeholder total and the rows the feed serves must be the same number",
        )
        assertEquals(8, served.size, "and that number is every photograph in the realm")
    }

    /**
     * The density map is one read over the same window the pages come from.
     *
     * ⚠⚠ **The projection is the risky half.** `feedCaptureTimes` asks for
     * `relations(CreatedAt)` and nothing else — no `fields(PATH)` — and whether a terminal
     * accepts that is a property of the real chain, not of a mock. `feedRowProjection` carries a
     * comment saying PATH "is not optional" because the *streaming* terminal derives a row's DataId
     * from it, so a reasonable reader would expect this read to fail the same way. It does not, and
     * only a real-stack read can say so.
     *
     * ⚠ Ordering is asserted, not assumed. `feedYearMarks` refuses to sort so that an unordered
     * caller produces visibly wrong marks — which makes the chain's `sortByDescending`
     * load-bearing rather than incidental.
     */
    @Test
    fun theDensityMapReadsEveryCaptureTimeNewestFirst() = runTest {
        val stack = Stack(this)
        // Deliberately seeded out of order, so a pass-through of insertion order would fail.
        listOf(5_000L, 9_000L, 1_000L, 7_000L).forEachIndexed { i, t ->
            stack.seedPhotoAt("p" + i + ".jpg", t)
        }

        assertEquals(
            listOf(9_000L, 7_000L, 5_000L, 1_000L),
            stack.repository.feedCaptureTimes(),
            "the density map must be every capture time, newest first",
        )
    }

    /**
     * ⚠⚠ The map and the rows must agree about which photographs exist, for the same reason
     * the total must (§23): a pill is placed by counting, and counting a looser set than the feed
     * serves puts every pill below it in the wrong place.
     */
    @Test
    fun theDensityMapCountsExactlyThePhotographsTheFeedServes() = runTest {
        val stack = Stack(this)
        listOf(9_000L, 8_000L, 7_000L, 6_000L, 5_500L).forEachIndexed { i, t ->
            stack.seedPhotoAt("d" + i + ".jpg", t)
        }
        stack.seedPhotoAt("tieA.jpg", 5_000L)
        stack.seedPhotoAt("tieB.jpg", 5_000L)
        stack.seedPhotoAt("older.jpg", 1_000L)

        val served = stack.repository.feedPaging(pageSize = 2)
            .asSnapshot { appendScrollWhile { true } }

        assertEquals(
            served.map { it.capturedAtMs },
            stack.repository.feedCaptureTimes(),
            "the density map and the paged feed must be the same photographs in the same order",
        )
    }

    /**
     * ⚠⚠ **A jump must not re-count the library** (`core-keyset-paging.md` §11).
     *
     * Paging builds a new `PagingSource` every time it invalidates, and a **jump invalidates** — so
     * a total memoised on the source is discarded exactly when the reader seeks — a full-tree count
     * on top of the refresh before any row appears, which is most of the grey that follows a fast
     * scrub. The memo belongs to the generation instead.
     *
     * ⚠ It is pinned **here** rather than in `:core-paging` because that module's fixture cannot
     * serve `stream/start`, so `reloadSignals()` — and therefore the whole flow — will not run
     * against it. This stack can, which is the same reason the trash bug was only visible here.
     *
     * ⚠ The count is driven through `keysetPagingSource` directly rather than through
     * `feedPaging`, because the assertion is about **how many times the counter runs** and that has
     * to be observable. The chain and the config are the feed's.
     */
    @Test
    fun aJumpDoesNotRecountTheLibrary() = runTest {
        val stack = Stack(this)
        repeat(40) { i -> stack.seedPhotoAt("p" + i + ".jpg", 10_000L - i) }

        var counted = 0
        val paged = stack.client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .keysetPagingSource(
                // ⚠ The capture time is the mapped row: this test counts COUNTS, and a
                // richer projection would only add ways for the fixture to fail.
                mapper = { row -> row.get(File.CreatedAt)?.get()?.toString() ?: "?" },
                cursorOf = { row -> row.get(File.CreatedAt)?.get() ?: 0L },
                pagingConfig = seekablePagingConfig(pageSize = 2),
                totalCount = { counted++; 40 },
                window = { list, before ->
                    list.filter<File> { category equalTo FileCategory.Image }
                        .filter<File> {
                            createdAt.inRange(0L, if (before == null) Long.MAX_VALUE else before + 1)
                        }
                        .sortByDescending(File.CreatedAt)
                },
                projection = { it.select { fields(File.Field.PATH); relations(File.CreatedAt) } },
            )
            // ⚠⚠ `flingTo`, NOT `scrollTo`. `scrollTo` appends its way to the index one page at a
            // time, so the source is never recreated and the memo never matters — a mutation check
            // caught this test passing for exactly that reason. A fling past `jumpThreshold`
            // (pageSize × 3 = 6) is what makes Paging invalidate and rebuild the source.
            .asSnapshot { flingTo(35) }

        // ⚠⚠ The two assertions below are the jump WITNESS, and without them the count assertion
        // proves nothing. A sequential walk to index 35 loads every page down to it and returns
        // ~36 rows; a jump loads one window at the target and returns a handful. `scrollTo` does
        // the former — a mutation check caught this test passing against the unfixed code for
        // exactly that reason.
        assertTrue(
            paged.size < 20,
            "a fling must load a window at the target, not every page down to it: " + paged.size,
        )
        assertTrue(
            paged.all { it.toLong() < 9_990L },
            "and that window is down at the target rather than back at the head: " + paged,
        )

        assertEquals(1, counted, "the library was counted once, not once per source a jump created")
    }

    // ── where a page is read (`photos-ios.md` §8.22.2) ──────────────────────────────────────────────

    /** Counts what is dispatched to it, and marks what it runs — the stand-in for "not the caller". */
    private class ReadLane(private val inner: kotlinx.coroutines.CoroutineDispatcher) : kotlinx.coroutines.CoroutineDispatcher() {
        var dispatched = 0
        var running = false
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: kotlinx.coroutines.Runnable) {
            dispatched++
            inner.dispatch(context, kotlinx.coroutines.Runnable {
                running = true
                try { block.run() } finally { running = false }
            })
        }
    }

    /**
     * The feed's pages are read where the shell asks, and they are the SAME pages in the same order.
     *
     * ⚠⚠ On iOS route 1 a page is a store query on the thread Paging calls `load` from — the main
     * thread — and a page is a walk of the library. Only the read moves: the keys and the order pages
     * are placed in are Paging's, untouched, which is what the comparison with the plain read pins.
     */
    @Test
    fun theFeedsPagesAreReadInTheContextAskedFor_andAreTheSamePagesInTheSameOrder() = runTest {
        val stack = Stack(this)
        repeat(9) { i -> stack.seedPhotoAt("p" + i + ".jpg", 10_000L - i * 100) }
        stack.seedPhotoAt("tieA.jpg", 5_000L)
        stack.seedPhotoAt("tieB.jpg", 5_000L)

        val plain = stack.repository.feedPaging(pageSize = 2).asSnapshot { appendScrollWhile { true } }
        val lane = ReadLane(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val moved = stack.repository.feedPaging(pageSize = 2, readContext = lane).asSnapshot { appendScrollWhile { true } }

        assertEquals(11, plain.size)
        assertEquals(plain.map { it.path }, moved.map { it.path }, "moving the read changed the pages or their order")
        // Six pages of two, at least: each one crossed onto the lane.
        assertTrue(lane.dispatched >= 6, "the pages were not read on the lane: " + lane.dispatched + " dispatches")
    }

    /** The mapping of a page's rows and the count beside it run there too — and nowhere when not asked. */
    @Test
    fun aPagesRowsAreMappedAndCountedInTheReadContext() = runTest {
        val stack = Stack(this)
        repeat(8) { i -> stack.seedPhotoAt("p" + i + ".jpg", 10_000L - i) }
        val lane = ReadLane(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val mapped = mutableListOf<Boolean>()
        val counted = mutableListOf<Boolean>()
        fun paged(readContext: kotlin.coroutines.CoroutineContext) = stack.client.fileService.defaultFileSystem.root
            .files
            .traverse()
            .keysetPagingSource(
                mapper = { row -> mapped += lane.running; row.get(File.CreatedAt)?.get()?.toString() ?: "?" },
                cursorOf = { row -> row.get(File.CreatedAt)?.get() ?: 0L },
                pagingConfig = seekablePagingConfig(pageSize = 2),
                totalCount = { counted += lane.running; 8 },
                window = { list, before ->
                    list.filter<File> { category equalTo FileCategory.Image }
                        .filter<File> { createdAt.inRange(0L, if (before == null) Long.MAX_VALUE else before + 1) }
                        .sortByDescending(File.CreatedAt)
                },
                projection = { it.select { fields(File.Field.PATH); relations(File.CreatedAt) } },
                readContext = readContext,
            )

        val rows = paged(lane).asSnapshot { appendScrollWhile { true } }
        assertEquals(8, rows.size)
        assertEquals(8, mapped.size)
        assertTrue(mapped.all { it }, "rows were mapped in the caller")
        assertTrue(counted.isNotEmpty() && counted.all { it }, "the library was counted in the caller")

        mapped.clear()
        val dispatchedBefore = lane.dispatched
        paged(kotlin.coroutines.EmptyCoroutineContext).asSnapshot { appendScrollWhile { true } }
        assertEquals(8, mapped.size)
        assertTrue(mapped.none { it }, "with no context asked for, the read stays where Paging calls it")
        assertEquals(dispatchedBefore, lane.dispatched)
    }

    // ── the library watch (`photos-ios.md` §8.22.3) ─────────────────────────────────────────────────

    /**
     * The REAL watch, over the real streams: it vouches only while a feed is collected, and every
     * kind of change Collections draws moves it.
     *
     * ⚠ The rename and the favourite change a row in place. They move the watch here whether it
     * observes the projected chains (as built) or the bare lists (tried as a mutation: still green),
     * so this does NOT pin the projection — it pins that each kind of change is seen.
     * ⚠ Seen red: without the feed's reload hook nothing vouches for the files, and it never watches.
     */
    @Test
    fun theLibraryWatchVouchesWhileAFeedIsCollected_andEveryKindOfChangeMovesIt() = runTest {
        val stack = Stack(this, placesAndLabels = true)
        stack.seedPhoto("a.jpg")
        val repository = TenshuPhotosRepository(
            stack.client, clockMs = { 1_000L }, reachability = null /* test */, watchScope = backgroundScope,
        )
        // ⚠ The observers live in `backgroundScope`, whose delays `advanceUntilIdle` does not run: the
        // feed's reload debounce among them. Time is moved by hand, past its ceiling.
        fun settle() {
            testScheduler.advanceTimeBy(5_000)
            testScheduler.runCurrent()
        }
        assertEquals(null, stack.repository.libraryWatch(), "no scope to keep observers in: nothing is promised")
        val watch = repository.libraryWatch()!!
        settle()
        assertFalse(watch.watching.value, "no feed is collected, so nothing vouches for the files")

        val feed = backgroundScope.launch { repository.feedPaging(pageSize = 2).collect { } }
        settle()
        assertTrue(watch.watching.value, "the feed's observer and the five lists' are live")

        var seen = watch.revision.value
        suspend fun moved(what: String, change: suspend () -> Unit) {
            change()
            settle()
            assertTrue(watch.revision.value != seen, what + " did not move the watch")
            assertTrue(watch.watching.value, what + " ended the watch")
            seen = watch.revision.value
        }
        moved("a photograph arriving") { stack.seedPhoto("b.jpg") }
        val photo = repository.allPhotosRead()!!.first { it.path == "/a.jpg" }
        var album = ""
        moved("a new album") { album = repository.createAlbum("Trip")!! }
        moved("a rename") { assertTrue(repository.renameAlbum(album, "Journey")) }
        moved("a photograph filed in an album") { assertTrue(repository.addToAlbum(album, photo)) }
        moved("a favourite") { assertTrue(repository.setFavourite(photo.contentId, true)) }
        moved("a favourite taken back") { assertTrue(repository.setFavourite(photo.contentId, false)) }
        moved("a photograph trashed") { assertTrue(repository.trash(photo)) }
        // ⚠ Written as their own services write them (`ExecutionContext.saveData`, which fires the
        // streams — `Database.saveData` would bypass them): this stack runs no place or inference op.
        // The labels are the only signal for Things and People, so a dead labels or places observer
        // must fail this test.
        moved("a place") {
            stack.proxy.executionContext.saveData(
                org.tenshu.core.place.Place.create(
                    context = stack.proxy.tContext,
                    placeId = "cap:490320000,209030000",
                    latE7 = 490_320_000L,
                    lngE7 = 209_030_000L,
                    capturedAt = org.tenshu.core.common.impl.UnixInstantDto(1_000L),
                    label = "Café",
                    labelSource = org.tenshu.core.place.LabelSource.USER,
                    accuracyM = 50,
                ),
            )
        }
        moved("a label") {
            stack.proxy.executionContext.saveData(
                org.tenshu.core.entity.Entity.create<org.tenshu.inference.MediaLabel>(
                    context = stack.proxy.tContext,
                    composite = org.tenshu.inference.MediaLabel.create(
                        context = stack.proxy.tContext,
                        filePath = "/b.jpg",
                        capability = org.tenshu.inference.InferenceCapability.FACE_CLUSTERING,
                        label = "Ada",
                        confidencePerMille = 1_000,
                        producedBy = org.tenshu.inference.ModelProvenance.User,
                    ),
                ),
            )
        }

        // Nothing changed: it stays where it is.
        settle()
        assertEquals(seen, watch.revision.value)

        feed.cancel()
        settle()
        assertFalse(watch.watching.value, "the feed stopped being collected")
        assertTrue(watch.revision.value != seen, "and whoever read before that is not told it is clean")
    }

    @Test
    fun anEmptyRealmReadsAsAnEmptyPageAndNotAsAFailure() = runTest {
        // ⚠⚠ The distinction `PhotoPageResult` exists for. "Nothing here" and "could not look" are
        // indistinguishable on screen, and only one of them is true for a realm with no photos.
        val stack = Stack(this)

        val page = stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 60)

        assertTrue(page is PhotoPageResult.Page, "an empty realm must page, not fail: $page")
        assertEquals(emptyList(), page.photos)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun favouritingRoundTripsThroughTheRealFlagOp() = runTest {
        // ⚠ The write the viewer's star makes, over the real `SetPhotoFlags` op rather than
        // a fake that always says yes.
        val stack = Stack(this)

        assertEquals(false, stack.repository.isFavourite("content-1"))

        assertTrue(stack.repository.setFavourite("content-1", favourite = true))
        assertEquals(true, stack.repository.isFavourite("content-1"))

        assertTrue(stack.repository.setFavourite("content-1", favourite = false))
        assertEquals(false, stack.repository.isFavourite("content-1"))
    }

    @Test
    fun addingToAnAlbumIsReadBackByTheAlbumsProjection() = runTest {
        // ⚠ Write with the real `AddToAlbum` op, read with the repository's own entry projection.
        // The count is derived from those entries, so this is the pair that has to agree.
        val stack = Stack(this)
        val albumId = requireNotNull(stack.repository.createAlbum("Cats"))

        val photo = app.tenshu.photos.model.Photo(
            path = "/cat.jpg",
            capturedAtMs = 1_000L,
            isVideo = false,
            contentId = "content-cat",
        )
        assertTrue(stack.repository.addToAlbum(albumId, photo))

        val albums = stack.repository.albums()
        assertTrue(albums is AlbumsResult.Albums, "got $albums")
        assertEquals(listOf(1), albums.albums.map { it.photoCount })
    }

    @Test
    fun aRenameKeepsTheChosenCover() = runTest {
        // ⚠⚠ `SaveAlbum` REPLACES the row, so a rename that passes no cover writes the default ""
        // and resets the cover to the album's first photograph.
        val stack = Stack(this)
        val albumId = requireNotNull(stack.repository.createAlbum("Pets"))
        val cat = app.tenshu.photos.model.Photo("/cat.jpg", 1_000L, isVideo = false, contentId = "content-cat")
        val dog = app.tenshu.photos.model.Photo("/dog.jpg", 1_000L, isVideo = false, contentId = "content-dog")
        assertTrue(stack.repository.addToAlbum(albumId, cat))
        assertTrue(stack.repository.addToAlbum(albumId, dog))
        // The cover chosen: the SECOND photograph, so the first-entry fallback cannot pass for it.
        assertTrue(stack.client.photoService.saveAlbum(albumId, "Pets", coverContentId = "content-dog") is TResult.Success)
        fun coverNow(albums: AlbumsResult) = (albums as AlbumsResult.Albums).albums.single().coverPath
        assertEquals("/dog.jpg", coverNow(stack.repository.albums()), "precondition: the cover is chosen")

        assertTrue(stack.repository.renameAlbum(albumId, "Our pets"))

        val after = stack.repository.albums() as AlbumsResult.Albums
        assertEquals("Our pets", after.albums.single().name)
        assertEquals("/dog.jpg", after.albums.single().coverPath, "the rename kept the chosen cover")
    }

    @Test
    fun theStoreSaysWhetherACopyIsStillAtItsPath_andATrashedOneIsNot() = runTest {
        // ⚠⚠ Duplicates asks this of the copy it is about to KEEP before it removes
        // any other. Pinned on the real stack — the mock's answer is a list lookup that cannot be
        // wrong about a move — and the load-bearing half is the last one: a copy `trash` moved is not
        // at its path any more, whatever the screen still shows.
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        stack.seedPhoto("a copy.jpg")
        val copies = (stack.repository.duplicates() as DuplicatesResult.Groups).groups.single().photos
        val one = copies.first { it.path == "/a.jpg" }
        assertTrue(one.contentHash.isNotEmpty(), "precondition: the scan carries the hash")

        assertTrue(stack.repository.holdsContent("/a.jpg", one.contentHash))
        assertFalse(stack.repository.holdsContent("/a.jpg", "not-this-content"), "other bytes at the path")
        assertFalse(stack.repository.holdsContent("/gone.jpg", one.contentHash), "no such path")
        assertFalse(stack.repository.holdsContent("/a.jpg", ""), "an absent hash matches nothing")

        assertTrue(stack.repository.trash(one), "precondition: trashed")
        assertFalse(stack.repository.holdsContent("/a.jpg", one.contentHash), "moved to the trash: not at its path")
        assertTrue(stack.repository.holdsContent("/a copy.jpg", one.contentHash), "the other copy is untouched")
    }

    @Test
    fun aSearchHitMayOnlyCiteAPhotographThatIsStillInTheLibrary() = runTest {
        // ⚠⚠ `media-processing-pipeline-sweep.md` F12, for Photos' own search
        // (`photos-ios.md` §8.19). A `MediaLabel` row and a vector outlive their file — nothing deletes
        // them yet — so the join every model-backed hit goes through is what keeps a deleted or trashed
        // photograph out of the results. Pinned on the real stack: the mock's join is a list lookup.
        val stack = Stack(this)
        stack.seedPhoto("dog.jpg")
        stack.seedPhoto("dog too.jpg")
        val dog = (stack.repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10) as PhotoPageResult.Page)
            .photos.first { it.path == "/dog.jpg" }

        assertEquals("/dog.jpg", stack.repository.citablePhotoAt("/dog.jpg")?.path)
        assertEquals(null, stack.repository.citablePhotoAt("/never-there.jpg"), "a label for a deleted file cites nothing")
        assertEquals(
            listOf("/dog too.jpg", "/dog.jpg"),
            stack.repository.searchByNameRead("DOG")?.map { it.path }?.sorted(),
            "precondition: the name search finds both, case-insensitively",
        )

        assertTrue(stack.repository.trash(dog), "precondition: trashed")
        val trashPath = (stack.repository.trashed() as TrashResult.Items).items.single().photo.path
        assertTrue(trashPath.startsWith("/Trash/"), "precondition: it lives at $trashPath now")

        assertEquals(null, stack.repository.citablePhotoAt("/dog.jpg"), "the path it had holds nothing")
        // ⚠⚠ The load-bearing half: the row at the trash path is LIVE, and D14 will key a typed name
        // there. Without the trash check this returns the trashed photograph.
        assertEquals(null, stack.repository.citablePhotoAt(trashPath), "a trashed photograph is not cited")
        assertEquals(
            listOf("/dog too.jpg"), stack.repository.searchByNameRead("dog")?.map { it.path },
            "nor does the name search find it",
        )
    }

    @Test
    fun aSurvivorReadThatThrowsIsNotShownToHoldIt_andACancelledOneStillCancels() = runTest {
        // ⚠⚠ Control gone mid-read reaches the client as a THROW, not as a failed result, so
        // `getOrNull()` is never reached and the throw must not go on into the caller's lock. The
        // answer gates a permanent delete: "could not look" is `false`.
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        stack.seedPhoto("a copy.jpg")
        val hash = (stack.repository.duplicates() as DuplicatesResult.Groups).groups.single().contentHash
        assertTrue(stack.repository.holdsContent("/a.jpg", hash), "precondition: it is there, and a read that works says so")

        // The same store, over a transport that throws on every request.
        fun overATransportThatThrows(thrown: Throwable) = TenshuPhotosRepository(
            DefaultTenshuClient(
                TenshuEnvironment(
                    taskExecutor = stack.proxy.localTaskExecutor,
                    schemaRegistry = stack.proxy.schemaRegistry,
                    tContext = stack.proxy.tContext,
                    requestChainFactory = { throw thrown },
                ),
            ),
            clockMs = { 1_000L },
            reachability = null /* test: no reachability engine */,
        )

        assertFalse(overATransportThatThrows(IllegalStateException("Control went away")).holdsContent("/a.jpg", hash))
        // ⚠ And a cancelled caller is not told NO — it stops (`runCatchingCancellable`).
        assertFailsWith<CancellationException> {
            overATransportThatThrows(CancellationException("the screen went")).holdsContent("/a.jpg", hash)
        }
    }

    @Test
    fun aSecondAlbumIsASecondAlbum_notAnOverwriteOfTheFirst() = runTest {
        // ⚠⚠ DATA LOSS. An id minted as `"album-${'$'}{clockMs()}-${'$'}{name.hashCode()...}"` —
        // the escaped-dollar idiom — is a LITERAL, so **every album the user ever creates gets the
        // same id**, and since `saveAlbum` is an upsert, making a second album silently renames the
        // first.
        //
        // ⚠⚠ Why it takes two: the test above creates ONE album and asserts the id it was handed
        // equals the id it reads back — both sides the same constant, so it is green for
        // any id whatsoever, including a broken one. It takes a SECOND row to make an id mean
        // anything. (`one-row-test-pass-without` — the fixture has to be able to collide.)
        //
        // ⚠ Note the harness pins `clockMs = { 1_000L }`, so the two ids differ by the name hash
        // alone. That is deliberate: it is the half that a frozen clock cannot paper over.
        val stack = Stack(this)

        val france = requireNotNull(stack.repository.createAlbum("Trip to France"))
        val cats = requireNotNull(stack.repository.createAlbum("Cats"))

        assertTrue(france != cats, "two albums, one id: the second overwrites the first ($france)")

        when (val result = stack.repository.albums()) {
            is AlbumsResult.Albums ->
                assertEquals(
                    listOf("Cats", "Trip to France"),
                    result.albums.map { it.name }.sorted(),
                    "both albums must survive; one row here means the second replaced the first",
                )

            else -> throw AssertionError("expected albums, got $result")
        }
    }
    /**
     * ⚠⚠ A tile picks its thumbnail class by the photograph's **shape** (`drive-synced-thumbnails.md`
     * §16), and the grid is drawn from several different reads: the paged feed, the library read
     * (All photos, Favourites), the album list's covers and the Duplicates scan. A shape that reaches
     * one of them and not the others is a screen that silently guesses 4:3 — so all three are asked,
     * over the real projections, for the same 16:9 photograph.
     */
    @Test
    fun aPhotographsShapeReachesEveryReadTheGridDrawsFrom() = runTest {
        val stack = Stack(this)
        assertTrue(
            stack.client.fileService.defaultFileSystem
                .uploadFile("/", "wide.png", pngHeader(width = 1920, height = 1080), sourceModifiedAt = 1_000L)
                .let { it is TResult.Success },
            "seeding /wide.png should succeed",
        )
        // A second copy of the same bytes, so the Duplicates scan has a group to draw.
        assertTrue(
            stack.client.fileService.defaultFileSystem
                .uploadFile("/", "wide copy.png", pngHeader(width = 1920, height = 1080), sourceModifiedAt = 900L)
                .let { it is TResult.Success },
            "seeding /wide copy.png should succeed",
        )
        val indexed = TenshuPhotosRepository(stack.client, clockMs = { 1_000L }, reachability = null /* test: no reachability engine */)

        val fromFeed = (indexed.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 10) as PhotoPageResult.Page)
            .photos.first { it.path == "/wide.png" }
        assertEquals(1920 to 1080, fromFeed.pixelWidth to fromFeed.pixelHeight, "the paged feed")

        val fromLibrary = indexed.allPhotos().first { it.path == "/wide.png" }
        assertEquals(1920 to 1080, fromLibrary.pixelWidth to fromLibrary.pixelHeight, "the library read")

        val fromDuplicates = (indexed.duplicates() as DuplicatesResult.Groups).groups.single().photos
        assertEquals(
            listOf(1920 to 1080, 1920 to 1080),
            fromDuplicates.map { it.pixelWidth to it.pixelHeight },
            "the Duplicates scan",
        )

        val albumId = indexed.createAlbum("Wide")!!
        assertTrue(indexed.addToAlbum(albumId, fromFeed), "filing the photograph")
        val cover = (indexed.albums() as AlbumsResult.Albums).albums.single()
        assertEquals(1920 to 1080, cover.coverPixelWidth to cover.coverPixelHeight, "the album cover")
    }

    /**
     * ⚠⚠ The Collections tab's reads — the library, favourites, the albums' covers — and the
     * Duplicates scan never open a whole-realm stream (`photos-app.md` §39/§40).
     *
     * The shared content index was one: every file in the realm, nine properties each, in ONE
     * emission — ~6 s and 995 KB on a 1,401-photo phone, a whisker under Binder's ~1 MB transaction
     * ceiling, so a slightly larger library got no answer at all. It is gone; these reads page
     * instead (their byte bound is pinned by `DuplicatesBoundedReadTest` on the real store), and
     * this pins that none of them quietly goes back to a subscription.
     */
    @Test
    fun theCollectionsAndDuplicatesReadsNeverOpenAWholeRealmStream() = runTest {
        val stack = Stack(this)
        val repository = TenshuPhotosRepository(stack.client, clockMs = { 1_000L }, reachability = null /* test: no reachability engine */)
        listOf("a.jpg" to 3_000L, "b.jpg" to 2_000L, "c.jpg" to 1_000L).forEach { (name, at) ->
            stack.seedPhotoAt(name, at)
        }
        val b = (repository.feedPage(Long.MAX_VALUE, pageSize = 10) as PhotoPageResult.Page)
            .photos.single { it.path == "/b.jpg" }
        assertTrue(repository.setFavourite(b.contentId, favourite = true))
        val albumId = requireNotNull(repository.createAlbum("Trip"))
        assertTrue(repository.addToAlbum(albumId, b))

        assertEquals(listOf("/a.jpg", "/b.jpg", "/c.jpg"), repository.allPhotosRead()?.map { it.path })
        assertEquals(listOf("/b.jpg"), (repository.favourites() as PhotoPageResult.Page).photos.map { it.path })
        assertEquals(listOf("/b.jpg"), (repository.albums() as AlbumsResult.Albums).albums.map { it.coverPath })
        assertTrue(repository.duplicates() is DuplicatesResult.Groups)

        assertEquals(
            0,
            stack.proxy.executionContext.streamRegistry.activeStreamCount.value,
            "a Collections or Duplicates read subscribed a whole-realm stream",
        )
    }

    /**
     * The library read pages (§39), and its cursor must neither lose nor repeat a photograph —
     * least of all across a run of identical capture times longer than a page, which is the case
     * whose skip arithmetic decides whether the loop terminates at all.
     */
    @Test
    fun theLibraryReadKeepsEveryPhotographAcrossPages_includingATieLongerThanAPage() = runTest {
        val stack = Stack(this)
        val tied = TenshuPhotosRepository.LIBRARY_PAGE_SIZE + 37
        repeat(tied) { i -> stack.seedPhotoAt("tie$i.jpg", 50_000L) }
        repeat(60) { i -> stack.seedPhotoAt("old$i.jpg", 10_000L - i) }
        repeat(30) { i -> stack.seedPhotoAt("new$i.jpg", 90_000L + i) }
        assertTrue(
            stack.client.fileService.defaultFileSystem
                .uploadFile("/", "notes.pdf", byteArrayOf(1, 2, 3), sourceModifiedAt = 60_000L)
                .let { it is TResult.Success },
        )

        val library = requireNotNull(stack.repository.allPhotosRead())

        assertEquals(tied + 90, library.size, "every photograph, and nothing that is not one")
        assertEquals(library.size, library.map { it.path }.toSet().size, "no photograph twice")
        assertEquals(library.map { it.capturedAtMs }.sortedDescending(), library.map { it.capturedAtMs }, "newest first")
    }

    /**
     * ⚠⚠ `sync-entity-identity.md` Q5 — **does an in-place overwrite drop a photo from its albums?**
     *
     * `AlbumEntry` is keyed `"$albumId|$contentId"`, and an overwrite (`ReplaceFile`) keeps the
     * path but mints a fresh `contentId` (`file-content-addressing.md`, *"contentId is minted per
     * content write"*). These pin what that does on the real stack, through the real ops: the
     * listing survives (path hint), but every contentId-keyed lookup of the listed photo misses.
     */
    private suspend fun Stack.photoAt(path: String): app.tenshu.photos.model.Photo =
        (repository.feedPage(beforeMs = Long.MAX_VALUE, pageSize = 60) as PhotoPageResult.Page)
            .photos.single { it.path == path }

    private suspend fun Stack.overwrite(name: String) = assertTrue(
        client.fileService.defaultFileSystem.replaceFile("/", name, byteArrayOf(9, 9, 9, 9))
            .let { it is TResult.Success },
        "overwriting /$name should succeed",
    )

    private suspend fun Stack.albumPaths(albumId: String): List<String> =
        (repository.albumPhotos(albumId) as PhotoPageResult.Page).photos.map { it.path }

    @Test
    fun q5_anOverwrittenPhotographIsStillListedByItsAlbum() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        val before = stack.photoAt("/a.jpg")
        val albumId = requireNotNull(stack.repository.createAlbum("Trip"))
        assertTrue(stack.repository.addToAlbum(albumId, before))

        stack.overwrite("a.jpg")

        val after = stack.photoAt("/a.jpg")
        // Precondition: the replace minted a new content id, so the entry's key no longer names the
        // file. Without this the pass below would prove nothing.
        assertTrue(before.contentId != after.contentId, "an overwrite mints a fresh contentId")
        // ⚠ Passes **only** because `photoFor` resolves the entry's path HINT first, and an
        // overwrite keeps the path. The key itself now names content that no file holds.
        assertEquals(listOf("/a.jpg"), stack.albumPaths(albumId), "the album still lists the photo")
    }

    /** Control for the next test: a move alone is survived, via the content-id fallback. */
    @Test
    fun q5_aMovedPhotographIsStillListedByItsAlbum() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        val albumId = requireNotNull(stack.repository.createAlbum("Trip"))
        assertTrue(stack.repository.addToAlbum(albumId, stack.photoAt("/a.jpg")))

        assertTrue(stack.client.fileService.defaultFileSystem.moveFile("/a.jpg", "/b.jpg") is TResult.Success)

        assertEquals(listOf("/b.jpg"), stack.albumPaths(albumId))
    }

    @Ignore // Q5 (sync-entity-identity.md): AlbumEntry keys on contentId; an overwrite re-mints it.
    @Test
    fun q5_anOverwrittenThenMovedPhotographIsStillListedByItsAlbum() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        val albumId = requireNotNull(stack.repository.createAlbum("Trip"))
        assertTrue(stack.repository.addToAlbum(albumId, stack.photoAt("/a.jpg")))

        stack.overwrite("a.jpg")
        assertTrue(stack.client.fileService.defaultFileSystem.moveFile("/a.jpg", "/b.jpg") is TResult.Success)

        // ⚠⚠ The path hint misses (moved) AND the content-id fallback misses (overwritten), so
        // `albumPhotos` drops the entry as "file deleted" — the photo silently leaves the album.
        assertEquals(listOf("/b.jpg"), stack.albumPaths(albumId))
    }

    @Ignore // Q5 (sync-entity-identity.md): AlbumEntry keys on contentId; an overwrite re-mints it.
    @Test
    fun q5_anOverwrittenPhotographsAboutSheetNamesItsAlbum() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        val albumId = requireNotNull(stack.repository.createAlbum("Trip"))
        assertTrue(stack.repository.addToAlbum(albumId, stack.photoAt("/a.jpg")))

        stack.overwrite("a.jpg")
        // The photo exactly as the album screen holds it — so with the NEW content id.
        val listed = (stack.repository.albumPhotos(albumId) as PhotoPageResult.Page).photos.single()

        // The About sheet's "In albums" row asks by the photo's content id.
        assertEquals(listOf(albumId), stack.repository.albumsFor(listed.contentId).map { it.albumId })
    }

    @Ignore // Q5 (sync-entity-identity.md): AlbumEntry keys on contentId; an overwrite re-mints it.
    @Test
    fun q5_anOverwrittenPhotographCanBeRemovedFromItsAlbum() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        val albumId = requireNotNull(stack.repository.createAlbum("Trip"))
        assertTrue(stack.repository.addToAlbum(albumId, stack.photoAt("/a.jpg")))

        stack.overwrite("a.jpg")
        val listed = (stack.repository.albumPhotos(albumId) as PhotoPageResult.Page).photos.single()

        // ⚠⚠ AlbumDetailViewModel.remove passes `photo.contentId` — the NEW one. RemoveFromAlbum
        // deletes a key that does not exist and reports success, so the photo stays.
        assertTrue(stack.repository.removeFromAlbum(albumId, listed.contentId))
        assertEquals(emptyList(), stack.albumPaths(albumId), "removing the listed photo removes it")
    }

    @Ignore // Q5 (sync-entity-identity.md): PhotoFlags keys on contentId, with no path hint at all.
    @Test
    fun q5_anOverwrittenFavouriteIsStillAFavourite() = runTest {
        val stack = Stack(this)
        stack.seedPhoto("a.jpg")
        assertTrue(stack.repository.setFavourite(stack.photoAt("/a.jpg").contentId, favourite = true))

        stack.overwrite("a.jpg")

        assertEquals(true, stack.repository.isFavourite(stack.photoAt("/a.jpg").contentId))
    }

    /** Just enough PNG for the metadata pass: the signature and an IHDR naming the size. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            u32(13) + "IHDR".encodeToByteArray() + u32(width) + u32(height) +
            byteArrayOf(8, 2, 0, 0, 0) + u32(0)
    }
}
