package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.data.PhotoPageResult
import androidx.compose.runtime.remember
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.feed.PhotosFeedScreen
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import app.tenshu.photos.ui.screens.feed.FEED_GRID_TAG
import app.tenshu.photos.ui.screens.feed.FeedDensity
import app.tenshu.photos.ui.screens.feed.FeedLayout
import app.tenshu.photos.ui.screens.feed.FeedLine
import app.tenshu.photos.model.FeedItem
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.math.abs
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import app.tenshu.photos.ui.screens.feed.MAX_BASE_TILE_DP
import app.tenshu.photos.data.PhotoPreview
import app.tenshu.photos.ui.components.PreviewCache
import app.tenshu.photos.ui.components.PreviewLoader
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import app.tenshu.photos.ui.components.PHOTO_TILE_SELECTED_TAG
import app.tenshu.photos.ui.components.PHOTO_TILE_TAG
import app.tenshu.photos.ui.screens.feed.FEED_STICKY_HEADER_TAG
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeAndResetMain

/**
 * What the feed *renders* in each state (`photos-app.md` S1).
 *
 * ⚠⚠ The load-bearing case is the last one: an unreachable proxy must never render as an empty
 * library. The state machine's own test pins the flag; this pins the pixels, because the two can
 * disagree — a screen is free to ignore `isEmpty` and show its empty copy anyway.
 */
@OptIn(ExperimentalTestApi::class)
class PhotosFeedScreenTest {

    // ⚠⚠ `viewModelScope` needs a Main dispatcher and `runComposeUiTest` installs none — without
    // this every test here fails with "Dispatchers.Main was accessed when the platform dispatcher
    // was absent", which reads like a paging failure and is not one: it comes from the screen's
    // view model.
    //
    // ⚠ `Unconfined`, not a `StandardTestDispatcher`: the compose test drives its own clock, so a
    // scheduler nothing advances would leave the pager's first load pending forever and every
    // assertion would read an empty feed. It does mean this file cannot observe a load *in
    // flight* — a test that needs that wants a scheduler and no renderer.
    @BeforeTest
    fun setUpMain() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDownMain() = viewModels.closeAndResetMain()

    /** Owns every view model these tests build; [closeAndResetMain] joins them before `resetMain()`. */
    private val viewModels = OwnedViewModels()

    private val day = 86_400_000L

    private fun dayStart(ms: Long) = ms - (ms % day)
    private fun dayLabel(ms: Long) = "Day ${ms / day}"

    /**
     * The screen over a real [PhotosFeedViewModel] and a [MockPhotosRepository].
     *
     * ⚠⚠ Not a hand-built `PagingData`. The day headers are produced by the view model's separator
     * rule, so a test that assembled its own `FeedItem` list would assert the header layout while
     * bypassing the thing that decides it — the shape of "green because the fixture said so". The
     * repository is a mock; everything above it is production.
     *
     * ⚠ The view model is `remember`ed, so one instance spans the recompositions a load causes;
     * constructing it inline would restart the pager on every frame.
     */
    private fun feed(
        photos: List<Photo> = emptyList(),
        outcome: PhotoPageResult? = null,
        onRetry: () -> Unit = {},
        onPhotoClick: (Photo) -> Unit = {},
        onAddToAlbum: (Photo) -> Unit = {},
        reason: UnreachableReason? = null,
        loadPreview: PreviewLoader = { _, _ -> null },
        selected: Set<String> = emptySet(),
        onToggleSelection: (Photo) -> Unit = {},
        density: FeedDensity = FeedDensity.Mosaic,
        gridState: androidx.compose.foundation.lazy.grid.LazyGridState? = null,
        onVisibleVideos: (List<String>) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        val viewModel = remember {
            viewModels.own(
                PhotosFeedViewModel(
                    repository = MockPhotosRepository(photos = photos, outcome = outcome),
                    sectionStartOf = { _, ms -> dayStart(ms) },
                ),
            )
        }
        TenshuTheme {
            PhotosFeedScreen(
                items = viewModel.items.collectAsLazyPagingItems(),
                loadPreview = loadPreview,
                onPhotoClick = onPhotoClick,
                onAddToAlbum = onAddToAlbum,
                onRetry = onRetry,
                reason = reason,
                sectionLabel = { _, ms -> dayLabel(ms) },
                selected = selected,
                onToggleSelection = onToggleSelection,
                density = density,
                gridState = gridState
                    ?: androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
                onVisibleVideos = onVisibleVideos,
            )
        }
    }

    /**
     * The rotation's only view of the grid (`photos-video-support.md` §5) is the list of video
     * tiles on screen, in document order. ⚠ Stills are not reported — a rotation over every tile
     * would ask the proxy for a clip nothing can have.
     */
    @Test
    fun visibleVideoTilesAreReportedInDocumentOrder() = runComposeUiTest {
        // ⚠ One day, so one header: five days would be five full-width headers and the fifth
        // tile would sit below the test viewport — which is not the case under test.
        val photos = listOf(
            Photo("/p0.jpg", 6 * day + 5_000, isVideo = false),
            Photo("/v1.mp4", 6 * day + 4_000, isVideo = true),
            Photo("/p2.jpg", 6 * day + 3_000, isVideo = false),
            Photo("/v3.mp4", 6 * day + 2_000, isVideo = true),
            Photo("/p4.jpg", 6 * day + 1_000, isVideo = false),
        )
        val reports = mutableListOf<List<String>>()

        setContent(feed(photos = photos, onVisibleVideos = { reports += it }))
        waitForIdle()

        assertEquals(listOf("/v1.mp4", "/v3.mp4"), reports.last())
    }

    /**
     * The measurement. ⚠⚠ **A tile that scrolls away and comes back re-reads its bytes.**
     *
     * `PhotoTile` keys `produceState` on the path, which is right — it stops the view model holding
     * a bitmap per row, which on a real library is hundreds of megabytes. But nothing between the
     * tile and the chain remembers anything, so the *request* is repeated: `loadPreview` is a raw
     * `repository.preview(...)`, which is a `navigateTo` + `search` + an eager `File.Preview` per
     * call, over IPC.
     *
     * ⚠ This test asserts the tile's own number, so it is a measurement first and a guard second.
     * What it must never say is "loads == distinct paths"; that is the cache's job, pinned by the
     * test below.
     */
    @Test
    fun aTileScrolledAwayAndBackReReadsItsBytes() = runComposeUiTest {
        val photos = List(60) { i -> Photo("/p$i.jpg", (60 - i) * day, isVideo = false) }
        val loads = mutableListOf<String>()

        setContent(feed(photos = photos, loadPreview = { path, _ -> loads += path; null }))
        waitForIdle()

        val afterFirstPaint = loads.size
        val distinctAtFirstPaint = loads.distinct().size

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeDown() }
        waitForIdle()

        // ⚠ The numbers themselves are the finding, so they are reported rather than hidden behind
        // a boolean: a reader of a failure here should see how bad it is, not just that it changed.
        println(
            "PH-G9 loads=" + loads.size + " distinct=" + loads.distinct().size +
                " firstPaint=" + afterFirstPaint + "/" + distinctAtFirstPaint
        )

        assertTrue(
            loads.size > loads.distinct().size,
            "a path is read more than once across a scroll round trip: " +
                loads.size + " loads for " + loads.distinct().size + " paths",
        )
    }

    /**
     * The cache — the **same scroll, the same fixture**, with the loader `App` actually
     * supplies. Every photograph is read once.
     *
     * ⚠⚠ Paired with the measurement above on purpose. That one pins the tile's behaviour (a
     * recycled tile re-runs its loader, which is what keeps decoded bitmaps out of the view model);
     * this one pins that the *request* does not reach the chain twice. If the pair ever agrees,
     * something has changed that neither test alone would explain.
     */
    @Test
    fun aCachedLoaderReadsEachPhotographOnceAcrossAScrollRoundTrip() = runComposeUiTest {
        val photos = List(60) { i -> Photo("/p$i.jpg", (60 - i) * day, isVideo = false) }
        val loads = mutableListOf<String>()
        val cache = PreviewCache()

        setContent(
            feed(
                photos = photos,
                loadPreview = { path, px -> cache.bytes(previewCacheKey(path, px)) { loads += path; PhotoPreview(ByteArray(4)) } },
            )
        )
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeDown() }
        waitForIdle()

        assertEquals(
            loads.distinct().size,
            loads.size,
            "every photograph reached the chain exactly once: " + loads,
        )
        assertTrue(loads.isNotEmpty(), "and the scroll did read something")
    }

    /**
     * ⚠⚠ A tile loads only once layout has told it its size (`drive-synced-thumbnails.md` §16), and a
     * lazy grid hands a scrolled-off tile's node to the one scrolling on — **at the same size**. A
     * size callback that fired only on a *change* would leave every recycled tile unmeasured and
     * blank for good, which no single-tile test can see. One day, so every tile shares one reuse pool.
     */
    @Test
    // ⚠ A phone-width window: the line arithmetic below is three columns, and a wider grid has more.
    fun tilesScrolledIntoViewOnRecycledNodesStillLoad() = runDesktopComposeUiTest(width = 420, height = 768) {
        val photos = List(90) { i -> Photo("/p$i.jpg", day + i, isVideo = false) }
        val loads = mutableListOf<String>()

        // ⚠ The uniform day view: the mosaic's features are a second content type, so a second
        // reuse pool, and this case is about one.
        setContent(
            feed(photos = photos, density = FeedDensity.Day, loadPreview = { path, _ -> loads += path; null })
        )
        waitForIdle()
        assertTrue("/p0.jpg" !in loads, "precondition: the oldest photograph starts off screen")

        // One header line, then thirty lines of three newest first — the last holds the oldest.
        // ⚠ The grid's indices are LINES (`FeedLayout`), not photographs.
        onNodeWithTag(FEED_GRID_TAG).performScrollToIndex(photos.size / 3)
        waitForIdle()

        assertTrue("/p0.jpg" in loads, "the tile scrolled into view loaded: " + loads.takeLast(12))
    }

    // ── multi-select (S1) ────────────────────────────────────────────────────────────────────

    @Test
    fun aLongPressBeginsASelectionRatherThanOpeningTheAlbumSheet() = runComposeUiTest {
        // ⚠⚠ S1 gives long-press to selection, and add-to-album lives in the selection bar where
        // it also works for many. The test names the sheet so opening it from a long-press reads
        // as the deliberate non-behaviour it is.
        val photos = listOf(Photo("/a.jpg", 5 * day, isVideo = false, contentId = "c0"))
        val toggled = mutableListOf<String>()
        var albumSheetOpened = false

        setContent(
            feed(
                photos = photos,
                onAddToAlbum = { albumSheetOpened = true },
                onToggleSelection = { toggled += it.path },
            )
        )
        waitForIdle()

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performTouchInput { longClick() }
        waitForIdle()

        assertEquals(listOf("/a.jpg"), toggled, "the long press selected the photograph")
        assertTrue(!albumSheetOpened, "and did not open the single-photo album sheet")
    }

    @Test
    fun aTapTogglesWhileSelectingAndOpensTheViewerOtherwise() = runComposeUiTest {
        // ⚠⚠ Opening a photograph mid-selection would bury the selection behind a full-screen page.
        val photos = listOf(Photo("/a.jpg", 5 * day, isVideo = false, contentId = "c0"))
        val opened = mutableListOf<String>()
        val toggled = mutableListOf<String>()

        setContent(
            feed(
                photos = photos,
                onPhotoClick = { opened += it.path },
                selected = setOf("/a.jpg"),
                onToggleSelection = { toggled += it.path },
            )
        )
        waitForIdle()

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performTouchInput { click() }
        waitForIdle()

        assertEquals(listOf("/a.jpg"), toggled, "a tap toggles while a selection is running")
        assertEquals(emptyList(), opened, "and does not open the viewer")
    }

    @Test
    fun aTapOpensTheViewerWhenNothingIsSelected() = runComposeUiTest {
        // ⚠ The control for the test above: without it, "never opens the viewer" would pass.
        val photos = listOf(Photo("/a.jpg", 5 * day, isVideo = false, contentId = "c0"))
        val opened = mutableListOf<String>()

        setContent(feed(photos = photos, onPhotoClick = { opened += it.path }))
        waitForIdle()

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performTouchInput { click() }
        waitForIdle()

        assertEquals(listOf("/a.jpg"), opened)
    }

    @Test
    fun onlyTheSelectedPhotographsAreMarked() = runComposeUiTest {
        val photos = listOf(
            Photo("/a.jpg", 5 * day + 2, isVideo = false, contentId = "c0"),
            Photo("/b.jpg", 5 * day + 1, isVideo = false, contentId = "c1"),
            Photo("/c.jpg", 5 * day, isVideo = false, contentId = "c2"),
        )

        setContent(feed(photos = photos, selected = setOf("/a.jpg", "/c.jpg")))
        waitForIdle()

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size >= 3 }
        assertEquals(
            2,
            // ⚠⚠ `useUnmergedTree`: `combinedClickable` merges the tile's descendants into one
            // node, so the mark is not addressable on the merged tree and this read 0.
            onAllNodesWithTag(PHOTO_TILE_SELECTED_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
            "one mark per selected photograph, and none on the others",
        )
    }

    // ── the mosaic (§32) ─────────────────────────────────────────────────────────────────────

    /** Every tile's bounds, in the order the grid lays them out — which is the paged order. */
    private fun SemanticsNodeInteractionsProvider.tileBounds(): List<Rect> =
        onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 0.5f, "$what: expected $expected, was $actual")

    /**
     * ⚠⚠ The feature, measured. A day of three photographs is one line: the newest drawn over 2×2
     * cells, the other two stacked beside it — and the hero is exactly two cells and a gap, which is
     * what lets it line up with the rows above and below it.
     */
    @Test
    fun theMosaicDrawsADaysFirstPhotographOverTwoByTwoCells() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            // ⚠ The top of the library is slot 0 of the rhythm: a feature, hero on the left.
            val photos = List(6) { i -> Photo("/p$i.jpg", 6 * day + (6 - i), isVideo = false) }
            setContent(feed(photos = photos))
            waitUntil { tileBounds().size >= 6 }

            val gap = with(density) { 4.dp.toPx() }
            // ⚠ Indexed, not destructured: a list destructures into five components at most.
            val tiles = tileBounds()
            val (hero, top, bottom) = tiles
            val rowFirst = tiles[3]
            val rowLast = tiles[5]

            assertNear(hero.width, hero.height, "the hero is square")
            assertNear(top.width * 2 + gap, hero.width, "and two cells and a gap across")
            assertNear(hero.right + gap, top.left, "the two small photographs sit beside it")
            assertNear(top.left, bottom.left, "stacked in one column")
            assertNear(top.bottom + gap, bottom.top, "one above the other")
            assertNear(hero.bottom, bottom.bottom, "and together exactly as tall as the hero")
            // ⚠ The next line is a plain row, and its cells must meet the feature's edges — two
            // lazy items aligned by nothing but arithmetic.
            assertNear(hero.left, rowFirst.left, "the row below starts under the hero")
            assertNear(top.left, rowLast.left, "and its last cell is under the small column")
            assertNear(top.width, rowLast.width, "at the same cell size")
        }

    /**
     * ⚠ A hero on the right is the line's MIDDLE item, so the grid's order — and with it the video
     * rotation's document order — stays the paged order: top-left, hero, then the one below.
     */
    @Test
    fun aHeroOnTheRightIsTheMiddlePhotographOfItsLine() =
        runDesktopComposeUiTest(width = 420, height = 1000) {
            // ⚠ The rhythm's second feature is the fourth line — photographs 9 to 11 — and it puts
            // its hero on the right. Everything before it is a still, so the videos are its own.
            val photos = List(12) { i ->
                val video = i == 10 || i == 11
                Photo(if (video) "/v$i.mp4" else "/p$i.jpg", 7 * day + (12 - i), isVideo = video)
            }
            val reports = mutableListOf<List<String>>()
            setContent(feed(photos = photos, onVisibleVideos = { reports += it }))
            waitUntil { tileBounds().size >= 12 }

            val tiles = tileBounds()
            val (first, hero, last) = tiles.subList(9, 12)
            assertTrue(hero.width > first.width * 1.5f, "the middle photograph is the hero")
            assertTrue(hero.left > first.right, "on the right")
            assertNear(first.left, last.left, "the other two stack on the left")
            assertEquals(listOf("/v10.mp4", "/v11.mp4"), reports.last(), "in paged order")
        }

    /**
     * ⚠⚠ A feature is two rows tall, so its line intersecting the viewport does not
     * put both small photographs on screen. Scrolled until the upper one is wholly past the top, it
     * must leave the video rotation rather than be reported with every photograph of a visible
     * line.
     */
    @Test
    fun aFeaturesSmallPhotographScrolledPastTheTopIsNotOfferedToTheRotation() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            // A feature at the top — a still hero, a video in each small cell — and enough stills
            // below it to scroll.
            val photos = List(30) { i ->
                val video = i == 1 || i == 2
                Photo(if (video) "/v$i.mp4" else "/p$i.jpg", 6 * day + (30 - i), isVideo = video)
            }
            val reports = mutableListOf<List<String>>()
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                feed(photos = photos, gridState = grid, onVisibleVideos = { reports += it })()
            }
            waitUntil { tileBounds().size >= 3 }
            assertEquals(listOf("/v1.mp4", "/v2.mp4"), reports.last(), "the control: both on screen")

            val gap = with(density) { 4.dp.toPx() }
            val small = tileBounds()[1].height
            // Line 0 is the header, line 1 the feature: past its upper row, into its lower one.
            runBlocking { grid.scrollToItem(1, (small + gap + 10f).toInt()) }
            waitForIdle()

            assertTrue(tileBounds()[1].bottom <= 0f, "precondition: the upper small photograph is off screen")
            assertEquals(listOf("/v2.mp4"), reports.last(), "only the one still on screen")
        }

    /**
     * ⚠⚠ The grid state holds a LINE index, and a line is three, five or seven photographs.
     * Zooming out from the day view to the month view while keeping the index would land hundreds
     * of photographs away. The top photograph's day must still be the one on top.
     *
     * ⚠ The density is changed on the VIEW MODEL, with the screen reading it back, exactly as `App`
     * wires it — so the rebuilt paged stream lands in the middle of the re-anchor, as in production.
     */
    @Test
    fun aDensityChangeKeepsTheTopPhotographsDayOnTop() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            // Five photographs a day for 120 days: three lines a day at three columns, two at five.
            val photos = List(600) { i ->
                Photo("/p$i.jpg", (120 - i / 5) * day + (5 - i % 5), isVideo = false)
            }
            val vm = viewModels.own(
                PhotosFeedViewModel(
                    repository = MockPhotosRepository(photos = photos),
                    sectionStartOf = { _, ms -> dayStart(ms) },
                ),
            )
            vm.setDensity(FeedDensity.Day)
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val density by vm.density.collectAsState()
                TenshuTheme {
                    PhotosFeedScreen(
                        items = vm.items.collectAsLazyPagingItems(),
                        loadPreview = { _, _ -> null },
                        onPhotoClick = {},
                        onAddToAlbum = {},
                        onRetry = {},
                        sectionLabel = { _, ms -> dayLabel(ms) },
                        density = density,
                        gridState = grid,
                    )
                }
            }
            waitUntil { tileBounds().isNotEmpty() }

            // Line 200 is the SECOND row of a day, so the sticky header names that day. ⚠⚠ Not a
            // day's first row: that photograph starts a line at every density, so the grid keeps
            // its place by KEY and the test would pass with no re-anchor at all. A row
            // starting mid-day has no line starting with it at five columns, which is the case.
            runBlocking { grid.scrollToItem(200) }
            waitForIdle()
            val before = stickyText()
            assertTrue(before != null, "precondition: a row is on top, so the sticky header names its day")

            vm.setDensity(FeedDensity.Month)
            waitForIdle()

            assertEquals(before, stickyText(), "the day on top before zooming out is the day on top after")
        }

    // ── a wider grid (§33) ───────────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The blurry-desktop half of §33: a fixed three columns in a wide window drew tiles larger
     * than any thumbnail class. A wide grid multiplies its columns instead, and the mosaic's features
     * come in whole blocks — each still exactly two cells and a gap across.
     */
    @Test
    fun aWideGridDrawsTheMosaicInBlocks_withNoTileWiderThanTheCap() =
        runDesktopComposeUiTest(width = 1500, height = 900) {
            // ⚠ 1500 dp needs three times the phone's columns, and gets four: twelve divides the
            // 60-photograph page where nine does not (`gridWidthScale`).
            val photos = List(24) { i -> Photo("/p$i.jpg", 6 * day + (24 - i), isVideo = false) }
            setContent(feed(photos = photos))
            waitUntil { tileBounds().size >= 24 }

            val tiles = tileBounds()
            val cap = with(density) { MAX_BASE_TILE_DP.dp.toPx() }
            val cell = tiles.minOf { it.width }
            val gap = with(density) { 4.dp.toPx() }
            assertTrue(cell <= cap, "a cell is $cell px, over the $cap px cap")
            val heroes = tiles.take(12).filter { it.width > cell * 1.5f }
            assertEquals(4, heroes.size, "a twelve-column feature is four blocks, each with its hero")
            heroes.forEach { assertNear(cell * 2 + gap, it.width, "a hero is two cells and a gap") }
            assertEquals(12, tiles.drop(12).count { abs(it.top - tiles[12].top) < 0.5f }, "and the row under it is twelve wide")
        }

    /**
     * ⚠⚠ A window dragged wider re-cuts every line, which is the density change's problem again:
     * the grid state holds a line index, and line 200 at three columns is a different photograph
     * from line 200 at twelve. The day on top must stay on top.
     */
    @Test
    fun aGridGrownWiderKeepsTheTopPhotographsDayOnTop() =
        runDesktopComposeUiTest(width = 1600, height = 900) {
            // Five photographs a day for 120 days: three lines a day at three columns, two at twelve.
            val photos = List(600) { i ->
                Photo("/p$i.jpg", (120 - i / 5) * day + (5 - i % 5), isVideo = false)
            }
            var wide by mutableStateOf(false)
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.width(if (wide) 1500.dp else 420.dp),
                ) {
                    feed(photos = photos, density = FeedDensity.Day, gridState = grid)()
                }
            }
            waitUntil { tileBounds().isNotEmpty() }

            // ⚠ The SECOND row of a day, for the density test's reason: a day's first row starts a
            // line at every width, so the grid would keep its place by key with no re-anchor at all.
            runBlocking { grid.scrollToItem(200) }
            waitForIdle()
            val before = stickyText()
            assertTrue(before != null, "precondition: a row is on top, so the sticky header names its day")

            wide = true
            waitForIdle()

            val tiles = tileBounds()
            val firstTop = tiles.minOf { it.top }
            assertTrue(tiles.count { abs(it.top - firstTop) < 0.5f } > 3, "precondition: the grid did widen")
            assertEquals(before, stickyText(), "the day on top before the window grew is the day on top after")
        }

    /**
     * A feed whose library can grow: each value of [library] is a new generation of the paged
     * stream, which is what the chain's change signal produces when a photograph syncs in.
     *
     * ⚠ Everything but the feed is the mock's, by delegation — the view model above it is production.
     */
    private class GrowingFeed(
        val library: kotlinx.coroutines.flow.MutableStateFlow<List<Photo>>,
    ) : app.tenshu.photos.data.PhotosRepository by MockPhotosRepository(photos = library.value) {
        override fun feedPaging(
            pageSize: Int,
            readContext: kotlin.coroutines.CoroutineContext,
        ): kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Photo>> =
            library.map { photos ->
                androidx.paging.PagingData.from(
                    photos,
                    sourceLoadStates = androidx.paging.LoadStates(
                        refresh = androidx.paging.LoadState.NotLoading(endOfPaginationReached = false),
                        prepend = androidx.paging.LoadState.NotLoading(endOfPaginationReached = true),
                        append = androidx.paging.LoadState.NotLoading(endOfPaginationReached = true),
                    ),
                )
            }
    }

    /** Sixty days of five photographs, newest first — long enough that the grid scrolls. */
    private fun sixtyDays() = List(300) { i ->
        Photo("/p$i.jpg", (120 - i / 5) * day + (5 - i % 5), isVideo = false)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.growingFeed(
        library: kotlinx.coroutines.flow.MutableStateFlow<List<Photo>>,
    ): () -> androidx.compose.foundation.lazy.grid.LazyGridState {
        val vm = viewModels.own(
            PhotosFeedViewModel(repository = GrowingFeed(library), sectionStartOf = { _, ms -> dayStart(ms) }),
        )
        lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
        setContent {
            grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            TenshuTheme {
                PhotosFeedScreen(
                    items = vm.items.collectAsLazyPagingItems(),
                    loadPreview = { _, _ -> null },
                    onPhotoClick = {},
                    onAddToAlbum = {},
                    onRetry = {},
                    sectionLabel = { _, ms -> dayLabel(ms) },
                    gridState = grid,
                )
            }
        }
        waitUntil { tileBounds().isNotEmpty() }
        return { grid }
    }

    /**
     * ⚠⚠ A photograph that syncs in while the feed rests at the top is SHOWN. The grid keeps its
     * first visible line by key, so the arrival's header and row were drawn just above the viewport
     * and the desktop app looked as if it never received the photograph — it had, one line up.
     */
    @Test
    fun aPhotographArrivingAtTheTopIsShown_whenTheFeedRestsThere() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val library = kotlinx.coroutines.flow.MutableStateFlow(sixtyDays())
            val grid = growingFeed(library)
            onNodeWithText(dayLabel(120 * day)).assertIsDisplayed()

            // A new day's first photograph — its own header, so two lines arrive above the old top.
            library.value = listOf(Photo("/arrival.jpg", 121 * day + 1, isVideo = false)) + library.value
            waitForIdle()

            assertEquals(0, grid().firstVisibleItemIndex, "the feed is still at its top")
            onNodeWithText(dayLabel(121 * day)).assertIsDisplayed()
        }

    /**
     * ⚠⚠ The other half, and the reason the rule is "at rest at the top" rather than "always": a
     * reader further down keeps the photograph they were looking at when one lands above them.
     */
    @Test
    fun aPhotographArrivingAtTheTopDoesNotMoveAReaderFurtherDown() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val library = kotlinx.coroutines.flow.MutableStateFlow(sixtyDays())
            val grid = growingFeed(library)
            onNodeWithTag(FEED_GRID_TAG).performScrollToIndex(20)
            waitForIdle()
            val before = stickyText()
            val line = grid().firstVisibleItemIndex

            library.value = listOf(Photo("/arrival.jpg", 121 * day + 1, isVideo = false)) + library.value
            waitForIdle()

            assertEquals(before, stickyText(), "the day the reader was in is still the day on top")
            assertEquals(line + 2, grid().firstVisibleItemIndex, "two lines further down the list, by the arrival")
        }

    /**
     * ⚠ The case every user hits first: the feed opens with a header on top, and a density change
     * must not re-anchor to the row under it and scroll that header away.
     */
    @Test
    fun aDensityChangeAtTheTopKeepsTheFirstHeaderOnTop() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val photos = List(600) { i ->
                Photo("/p$i.jpg", (120 - i / 5) * day + (5 - i % 5), isVideo = false)
            }
            val vm = viewModels.own(
                PhotosFeedViewModel(
                    repository = MockPhotosRepository(photos = photos),
                    sectionStartOf = { _, ms -> dayStart(ms) },
                ),
            )
            vm.setDensity(FeedDensity.Day)
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val density by vm.density.collectAsState()
                TenshuTheme {
                    PhotosFeedScreen(
                        items = vm.items.collectAsLazyPagingItems(),
                        loadPreview = { _, _ -> null },
                        onPhotoClick = {},
                        onAddToAlbum = {},
                        onRetry = {},
                        sectionLabel = { _, ms -> dayLabel(ms) },
                        density = density,
                        gridState = grid,
                    )
                }
            }
            waitUntil { tileBounds().isNotEmpty() }

            vm.setDensity(FeedDensity.Month)
            waitForIdle()

            assertEquals(0, grid.firstVisibleItemIndex, "still at the very top")
            assertEquals(null, stickyText(), "with the first header itself on screen")
        }

    /**
     * ⚠⚠ The year pills are placed by photographs, so a seek must move through
     * photographs too — the grid's lines are not proportional to them. Dragged to where the dense
     * era's first day sits as a fraction of the LIBRARY, the list must show that day.
     */
    @Test
    fun aScrubLandsWhereItsFractionOfThePhotographsIs() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            // 150 photographs one a day, then 450 at fifteen a day: in the mosaic that is 300 lines
            // above 180, so a quarter of the photographs is well under a quarter of the lines.
            val photos = List(600) { i ->
                if (i < 150) Photo("/s$i.jpg", (400 - i) * day + 1, isVideo = false)
                else (i - 150).let { j -> Photo("/d$i.jpg", (250 - j / 15) * day + (15 - j % 15), isVideo = false) }
            }
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                feed(photos = photos, gridState = grid)()
            }
            waitForIdle()

            // Wake the thumb with the list at the very top, so the drag starts from zero: a little
            // way down, then a user fling back up. ⚠ Not `scrollToItem(0)` after a swipe — that
            // races the swipe's own fling for the scroll mutex and is cancelled.
            runBlocking { grid.scrollToItem(3) }
            waitForIdle()
            onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeDown() }
            waitUntil(timeoutMillis = 5_000) {
                !grid.isScrollInProgress && grid.firstVisibleItemIndex == 0 &&
                    grid.firstVisibleItemScrollOffset == 0 &&
                    onAllNodesWithTag(app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            val thumb = onNodeWithTag(app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_TAG).fetchSemanticsNode().boundsInRoot
            val track = onNodeWithTag(FEED_GRID_TAG).fetchSemanticsNode().boundsInRoot.height -
                with(density) { 16.dp.toPx() }
            val fraction = 155f / (photos.size - 1)
            onNodeWithTag(app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_TAG).performTouchInput {
                down(center)
                // ⚠ Plus the touch slop, which the drag detector swallows before it reports motion.
                moveBy(androidx.compose.ui.geometry.Offset(0f, fraction * (track - thumb.height) + viewConfiguration.touchSlop))
            }
            waitForIdle()

            onNodeWithTag(app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_BUBBLE_TAG, useUnmergedTree = true)
                .assertTextEquals("Day 250")
        }

    /**
     * ⚠⚠ Scrolled into the LOWER half of a feature, the line on top is
     * the feature but its first photograph is already above the viewport. A density change anchored
     * there jumps back half a feature. The grid must land on the line drawing the photograph that
     * was actually leading the view.
     *
     * ⚠ Asserted as a line index computed from `FeedLayout` itself rather than in pixels: the two
     * candidate photographs sit in different lines at five columns, which is the whole difference.
     */
    @Test
    fun aDensityChangeFromTheLowerHalfOfAFeatureAnchorsOnThePhotographLeadingTheView() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            // ⚠ Long enough that the list still scrolls at FIVE columns: with 30 photographs the
            // whole month view fits the viewport, the grid can only be at line 0, and the test
            // asserts something no layout could satisfy.
            val photos = List(120) { i -> Photo("/p$i.jpg", 6 * day + (120 - i), isVideo = false) }
            val vm = viewModels.own(
                PhotosFeedViewModel(
                    repository = MockPhotosRepository(photos = photos),
                    sectionStartOf = { _, ms -> dayStart(ms) },
                ),
            )
            lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
            setContent {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val density by vm.density.collectAsState()
                TenshuTheme {
                    PhotosFeedScreen(
                        items = vm.items.collectAsLazyPagingItems(),
                        loadPreview = { _, _ -> null },
                        onPhotoClick = {},
                        onAddToAlbum = {},
                        onRetry = {},
                        sectionLabel = { _, ms -> dayLabel(ms) },
                        density = density,
                        gridState = grid,
                    )
                }
            }
            waitUntil { tileBounds().isNotEmpty() }

            // The same cut the screen makes: one header, then the day's photographs.
            val items = listOf(FeedItem.DayHeader(dayStart(photos.first().capturedAtMs))) +
                photos.map { FeedItem.Tile(it) }
            val mosaic = FeedLayout.of(0, items, 0, FeedDensity.Mosaic)
            // The second feature of the day — far enough down that a line below it exists.
            val feature = (0 until mosaic.lineCount).filter { mosaic[it] is FeedLine.Feature }[1]
            val leading = mosaic.photoAt(feature)!! + 2

            val small = tileBounds()[1].height
            runBlocking { grid.scrollToItem(feature, (small + with(density) { 4.dp.toPx() } + 10f).toInt()) }
            waitForIdle()
            assertEquals(feature, grid.firstVisibleItemIndex, "precondition: the feature's lower half is on top")

            vm.setDensity(FeedDensity.Month)
            waitForIdle()

            val month = FeedLayout.of(0, items, 0, FeedDensity.Month)
            assertEquals(month.lineOfPhoto(leading), grid.firstVisibleItemIndex)
            assertTrue(
                month.lineOfPhoto(leading) != month.lineOfPhoto(leading - 2),
                "and the two candidates really do differ at five columns",
            )
        }

    /** The sticky header's text, or `null` while a header line itself is on top. */
    private fun SemanticsNodeInteractionsProvider.stickyText(): String? =
        onAllNodesWithTag(FEED_STICKY_HEADER_TAG).fetchSemanticsNodes().firstOrNull()?.config
            ?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
            ?.joinToString { it.text }

    /** The control: the uniform day view draws the same photographs all one size. */
    @Test
    fun theDayViewDrawsEveryPhotographTheSameSize() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            val photos = List(6) { i -> Photo("/p$i.jpg", 6 * day + (6 - i), isVideo = false) }
            setContent(feed(photos = photos, density = FeedDensity.Day))
            waitUntil { tileBounds().size >= 6 }

            val widths = tileBounds().map { it.width }
            assertTrue(widths.all { abs(it - widths.first()) < 0.5f }, "one size: $widths")
        }

    // ── the sticky section header (S1) ───────────────────────────────────────────────────────

    /**
     * ⚠⚠ **At the top of the feed the first row IS a header, so the overlay stays out of its way.**
     * That is what makes it read as sticky rather than as a duplicate line saying the same thing
     * one row above the thing it repeats.
     */
    @Test
    fun theStickyHeaderIsAbsentWhileASectionsOwnHeaderIsOnScreen() = runComposeUiTest {
        val photos = List(6) { i -> Photo("/p$i.jpg", (6 - i) * day, isVideo = false) }

        setContent(feed(photos = photos))
        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(FEED_STICKY_HEADER_TAG).assertDoesNotExist()
    }

    /**
     * ⚠ Once the section's own header has scrolled off, the overlay names the section the top row
     * belongs to — which is the whole point of a sticky header.
     */
    @Test
    fun theStickyHeaderNamesTheSectionOnceItsOwnHeaderHasScrolledAway() = runComposeUiTest {
        val photos = List(40) { i -> Photo("/p$i.jpg", (40 - i) * day, isVideo = false) }

        setContent(feed(photos = photos))
        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitForIdle()

        onNodeWithTag(FEED_STICKY_HEADER_TAG).assertExists()
    }

    /**
     * ⚠⚠ **Pins the §29.2 gap.** "Suppress the overlay whenever the top item IS a header" leaves a
     * window while that header is half scrolled past the top: clipped, unreadable, and with nothing
     * taking over. The rule is about the header's OFFSET, not merely its index.
     *
     * ⚠ `scrollToItem(index, scrollOffset)` rather than a swipe: putting a header *partially* off
     * the top is the whole case, and no gesture hits a given number of pixels deterministically.
     */
    @Test
    fun theStickyHeaderTakesOverAsASectionsOwnHeaderStartsToScrollAway() = runComposeUiTest {
        val photos = List(40) { i -> Photo("/p$i.jpg", (40 - i) * day, isVideo = false) }
        lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState

        setContent {
            grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            feed(photos = photos, gridState = grid)()
        }
        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isNotEmpty() }

        // ⚠ One photograph a day, so the lines are header, row, header, row… — index 2 is a
        // header, and this is the control: fully on screen, so the overlay stays away.
        runBlocking { grid.scrollToItem(2, 0) }
        waitForIdle()
        onNodeWithTag(FEED_STICKY_HEADER_TAG).assertDoesNotExist()

        // The same header, 20 px past the top: the index-only rule shows nothing at all here.
        runBlocking { grid.scrollToItem(2, 20) }
        waitForIdle()
        onNodeWithTag(FEED_STICKY_HEADER_TAG).assertExists()
    }

    @Test
    fun photosAreSectionedByDay() = runComposeUiTest {
        val photos = listOf(
            Photo("/a.jpg", 5 * day + 10, isVideo = false),
            Photo("/b.jpg", 5 * day + 5, isVideo = false),
            Photo("/c.jpg", 2 * day + 1, isVideo = false),
        )

        setContent(feed(photos = photos))

        waitUntil {
            onAllNodesWithText("Day 5", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Day 5", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Day 2", useUnmergedTree = true).assertIsDisplayed()
        // Two photos on day 5 share one header rather than getting one each.
        assertEquals(
            1,
            onAllNodesWithText("Day 5", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun anEmptyRealmSaysWherePhotosComeFrom() = runComposeUiTest {
        setContent(feed(photos = emptyList()))

        waitUntil {
            onAllNodesWithText("No photos yet.\nPhotos backed up from your devices appear here.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anUnreachableProxyShowsTheProxyMessageAndARetry_neverTheEmptyState() = runComposeUiTest {
        var retried = false
        setContent(
            feed(
                photos = emptyList(),
                outcome = PhotoPageResult.Unreachable,
                onRetry = { retried = true },
            )
        )

        waitUntil {
            onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠⚠ The whole point: the empty-library copy must be nowhere on screen.
        assertTrue(
            onAllNodesWithText("No photos yet.\nPhotos backed up from your devices appear here.")
                .fetchSemanticsNodes().isEmpty(),
            "an unreachable proxy rendered as an empty library tells the user their photos are gone",
        )

        onNodeWithText("Try again").performClick()
        assertTrue(retried, "the error state must offer a way out of itself")
    }

    @Test
    fun aFailedReadAlsoKeepsTheEmptyCopyOffScreen() = runComposeUiTest {
        setContent(feed(outcome = PhotoPageResult.Failed("boom")))

        waitUntil {
            onAllNodesWithText("Something went wrong loading your photos.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("No photos yet.\nPhotos backed up from your devices appear here.")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * A guard on the fixture every test above leans on: `MockPhotosRepository` must page like the
     * real reader.
     *
     * ⚠⚠ The real reader asks for `createdAt.inRange(0, beforeMs)`, which is **inclusive**. A mock
     * filtering with `capturedAtMs < beforeMs` — EXCLUSIVE — disagrees at exactly the boundary,
     * and "no overlap" would pass on the mock's side of that disagreement.
     *
     * ⚠ Neither alone is right: inclusive-without-a-skip repeats rows (and cannot advance at all
     * through a page that shares one millisecond), exclusive loses every photograph tied at the
     * boundary. The mock mirrors production — inclusive plus an explicit `skip` — and this test
     * passes that skip the way `PhotosFeedViewModel` does, so "no overlap" means what it says.
     */
    @Test
    fun theMockRepositoryPagesLikeTheRealOne() = runComposeUiTest {
        val repo = MockPhotosRepository()
        var page: PhotoPageResult? = null
        kotlinx.coroutines.runBlocking { page = repo.feedPage(Long.MAX_VALUE, skip = 0, pageSize = 4) }
        val first = (page as PhotoPageResult.Page).photos

        // ⚠ The cursor is a (time, skip) pair, exactly as the view model keeps it: how many held
        // rows share the boundary time. With a distinct time per row this is 1, but writing it as a
        // count rather than a constant is what makes the test survive a fixture with ties.
        val boundary = first.last().capturedAtMs
        val consumed = first.count { it.capturedAtMs == boundary }
        kotlinx.coroutines.runBlocking {
            page = repo.feedPage(boundary, skip = consumed, pageSize = 4)
        }
        val second = (page as PhotoPageResult.Page).photos

        assertTrue(
            first.map { it.path }.intersect(second.map { it.path }.toSet()).isEmpty(),
            "the boundary row was served twice: ${first.map { it.path }} then ${second.map { it.path }}",
        )
    }

    /**
     * ⚠⚠ An uninstalled Control must not render this screen with a **Try again** button underneath
     * a banner saying Control is not installed. Retrying cannot install anything, so the button
     * would be an affordance that could never work — ADR 0025's own defect one layer down.
     */
    @Test
    fun anUninstalledControlOffersNoRetry_becauseRetryingCannotInstallAnything() = runComposeUiTest {
        setContent(
            feed(
                photos = emptyList(),
                outcome = PhotoPageResult.Unreachable,
                reason = UnreachableReason.NotInstalled,
            )
        )

        waitUntil {
            onAllNodesWithText("Can't reach Tenshu Control.\nYour photos are safe — they just can't load right now.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("Try again").fetchSemanticsNodes().isEmpty(),
            "a retry under \"Control isn't installed\" is a button that cannot work",
        )
    }

    /** ⚠ And a schema refusal is the same: the user has to update something, not tap again. */
    @Test
    fun aSchemaSkewOffersNoRetryEither() = runComposeUiTest {
        setContent(
            feed(
                photos = emptyList(),
                outcome = PhotoPageResult.Unreachable,
                reason = UnreachableReason.Skew(UpdateTarget.CONTROL),
            )
        )

        waitUntil {
            onAllNodesWithText("Can't reach Tenshu Control.\nYour photos are safe — they just can't load right now.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("Try again").fetchSemanticsNodes().isEmpty(),
            "retrying does not update anything",
        )
    }

    /**
     * ⚠⚠ The other direction, and the reason the default is "offer it": a transport outage really
     * does clear itself, so removing the retry everywhere would have been the opposite mistake.
     */
    @Test
    fun aTransportOutageKeepsTheRetry() = runComposeUiTest {
        var retried = false
        setContent(
            feed(
                photos = emptyList(),
                outcome = PhotoPageResult.Unreachable,
                onRetry = { retried = true },
                reason = UnreachableReason.Transport,
            )
        )

        waitUntil { onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Try again").performClick()
        assertTrue(retried, "a self-recovering outage must still offer a way out of itself")
    }
}
