package app.tenshu.photos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.swipeUp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FEED_GRID_TAG
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.photos.data.MockPhotosRepository
import app.tenshu.photos.ui.screens.feed.PhotosFeedViewModel
import app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_BUBBLE_TAG
import app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_YEAR_PILL_TAG
import app.tenshu.photos.ui.screens.feed.FeedYearMark
import app.tenshu.photos.ui.screens.feed.FeedScrubber
import app.tenshu.photos.ui.screens.feed.FEED_SCRUBBER_TAG
import app.tenshu.photos.ui.screens.feed.PhotosFeedScreen
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels
import org.tenshu.viewmodel.test.closeAndResetMain

/**
 * The feed's fast-scroll thumb (`photos-feed-scrubber.md`).
 *
 * ⚠ What these can and cannot see. A scrubber is mostly *feel* — whether a seek keeps up with a
 * thumb is a device question, and the drag was checked on a OnePlus CPH2581. What a harness can
 * pin is the part that would silently rot: that the thumb is absent when there is nothing to
 * scroll, present when there is, and that dragging it actually moves the list.
 */
@OptIn(ExperimentalTestApi::class)
class FeedScrubberTest {

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

    private fun photos(n: Int) = List(n) { i ->
        Photo("/p$i.jpg", (n - i) * DAY_MS, isVideo = false, contentId = "c$i")
    }

    private fun feed(count: Int): @androidx.compose.runtime.Composable () -> Unit = {
        // ⚠ The fixture is unchanged from before the paging migration — one photograph per day,
        // newest first — so `TOP_DAY` still names the row this test steers by.
        val viewModel = remember {
            viewModels.own(
                PhotosFeedViewModel(
                    repository = MockPhotosRepository(photos = photos(count)),
                    sectionStartOf = { _, ms -> ms / DAY_MS * DAY_MS },
                ),
            )
        }
        TenshuTheme {
            PhotosFeedScreen(
                items = viewModel.items.collectAsLazyPagingItems(),
                loadPreview = { _, _ -> null },
                onPhotoClick = {},
                onAddToAlbum = {},
                onRetry = {},
                sectionLabel = { _, ms -> "day ${ms / DAY_MS}" },
            )
        }
    }

    @Test
    fun aFeedThatFitsOnScreenGetsNoThumb() {
        // ⚠⚠ The control, and the one that stops this passing for the wrong reason. A thumb that is
        // ALWAYS composed would satisfy the drag test below just as well, and would put a
        // fast-scroll control on a screen with nothing to scroll.
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent(feed(2))
            waitForIdle()
            onNodeWithTag(FEED_SCRUBBER_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun theThumbAppearsWhenTheListIsScrolled() {
        // ⚠⚠ Was `aScrollableFeedGetsAThumb`, asserting the thumb exists on a still list. That was
        // the wrong contract and it shipped: the reference app's thumb is *scroll-activated* and
        // fades when the list stops, and ours sat there permanently. The KDoc even claimed Google's
        // did not fade, from a capture taken while the list was still settling.
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent(feed(200))
            waitForIdle()

            onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun theThumbFadesWhenTheListGoesStill() {
        // ⚠ The other half, and the one a "does it appear" test alone would let regress back to a
        // permanently-visible control.
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent(feed(200))
            waitForIdle()

            onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    /**
     * The bubble names the day under the thumb while it is held.
     *
     * ⚠ Asserted against the day header's own text, not a hard-coded string: the label comes from
     * the same `dayLabel` the grid uses, so pinning a literal here would pass while the two drifted
     * apart.
     */
    @Test
    fun draggingShowsTheDayUnderTheThumb() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent(feed(200))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        // ⚠ A press-and-hold with movement, not a tap: the bubble is bound to `dragging`, and a
        // gesture that never moves would leave the thumb grabbed but the list unscrolled.
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f))
        }
        waitForIdle()

        onNodeWithTag(FEED_SCRUBBER_BUBBLE_TAG).assertExists()
    }

    /**
     * ⚠ At rest the thumb is visible but the bubble is not: the grid's own day headers already say
     * where you are, and a permanent bubble would cover the photographs it is describing.
     *
     * ⚠⚠ Added because a mutation sweep found nothing asserted it — the bubble could have been
     * shown always and every other test here would still have passed.
     */
    @Test
    fun theBubbleIsAbsentWhenTheThumbIsMerelyVisible() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent(feed(200))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(FEED_SCRUBBER_BUBBLE_TAG).assertDoesNotExist()
    }

    /**
     * ⚠⚠ The half the bubble exists to get right. `dateAt` answers `null` for a row that is not
     * loaded, and the bubble must then show NOTHING rather than the last date it knew — a held
     * label is a different photograph's date presented as this one's, which is the failure
     * `photos-feed-scrubber.md` calls out by name.
     */
    @Test
    fun theBubbleGoesQuietOverAnUnloadedRow_ratherThanHoldingTheLastDate() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent {
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            TenshuTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        state = gridState,
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .testTag(FEED_GRID_TAG),
                    ) {
                        items(300) {
                            androidx.compose.foundation.layout.Box(
                                androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                        }
                    }
                    // Every row reports "not loaded", which is what a placeholder range looks like.
                    FeedScrubber(gridState = gridState, dateAt = { null })
                }
            }
        }
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f))
        }
        waitForIdle()

        onNodeWithTag(FEED_SCRUBBER_BUBBLE_TAG).assertDoesNotExist()
    }

    /**
     * ⚠⚠ **Why the label is derived from the grid rather than from the gesture.** Derived from the
     * gesture, the bubble names a different day from the grid's top header, and stays wrong for as
     * long as the thumb is held still.
     *
     * Both indices come from the same `progress`, but not from the same `totalItemsCount`: the seek
     * uses the count as it was when the drag event fired, a gesture-derived label the count as it
     * is now, and the count moves — `insertSeparators` adds a day header only next to LOADED rows,
     * so every page that lands grows the list. A still finger sends no further drag events, so
     * nothing ever reconciles them.
     *
     * ⚠ The fixture grows at the END, which is not where a separator lands; what it reproduces is
     * the count changing under a held finger, which is the part that breaks the arithmetic. The
     * invariant asserted is the user-facing one and is independent of where the growth happens:
     * **the bubble names the row the grid is showing.**
     */
    @Test
    fun theBubbleNamesTheRowOnScreen_evenWhenTheItemCountGrowsUnderAStillFinger() =
        runDesktopComposeUiTest(width = 420, height = 900) {
            lateinit var gridState: androidx.compose.foundation.lazy.grid.LazyGridState
            var count by androidx.compose.runtime.mutableStateOf(300)

            setContent {
                val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                gridState = state
                TenshuTheme {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize()
                    ) {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                            state = state,
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .testTag(FEED_GRID_TAG),
                        ) {
                            items(count) {
                                androidx.compose.foundation.layout.Box(
                                    androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                )
                            }
                        }
                        // Every row is loaded and names itself, so a wrong index is a wrong label
                        // rather than a silence.
                        FeedScrubber(gridState = state, dateAt = { "row $it" })
                    }
                }
            }
            waitForIdle()

            onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
                down(center)
                moveBy(Offset(0f, 200f))
            }
            waitForIdle()

            // ⚠ The control: with the count still still, the old arithmetic agreed too. If this
            // line ever fails the fixture stopped seeking, and the assertion below proves nothing.
            onNodeWithTag(FEED_SCRUBBER_BUBBLE_TAG)
                .assertTextEquals("row ${gridState.firstVisibleItemIndex}")

            // A page lands. The finger has not moved, so no seek re-runs.
            count = 900
            waitForIdle()

            onNodeWithTag(FEED_SCRUBBER_BUBBLE_TAG)
                .assertTextEquals("row ${gridState.firstVisibleItemIndex}")
        }

    // ── the year pills ───────────────────────────────────────────────────────────────────────

    /**
     * A feed whose scrubber has a density map, over a bespoke grid so the marks and the rows can be
     * stated independently of each other.
     */
    private fun tracked(
        items: Int,
        marks: List<FeedYearMark>,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        TenshuTheme {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                    state = gridState,
                    modifier = androidx.compose.ui.Modifier.fillMaxSize().testTag(FEED_GRID_TAG),
                ) {
                    items(items) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxWidth().aspectRatio(1f)
                        )
                    }
                }
                FeedScrubber(gridState = gridState, yearMarks = marks)
            }
        }
    }

    /**
     * Three years whose pills all sit in the TOP half of the track.
     *
     * ⚠⚠ Deliberately clear of the bottom, because these tests drag the thumb there and a pill
     * under the thumb is hidden by design (`hideUnderThumb`). Years spread evenly would make the
     * count assertion below depend on where the grab happened to leave the thumb — reading 2
     * instead of 3 and looking like the pills were broken.
     */
    private fun wideYears() = listOf(
        FeedYearMark(2026, startIndex = 0, count = 60),
        FeedYearMark(2024, startIndex = 60, count = 60),
        FeedYearMark(2021, startIndex = 120, count = 180),
    )

    @Test
    fun theYearPillsAppearWhileTheThumbIsHeld() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent(tracked(items = 300, marks = wideYears()))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // ⚠ Dragged to the very bottom, where `progress` clamps to 1 — so the thumb's position is
        // a known quantity rather than whatever the grab inherited from the grid's scroll offset.
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 2_000f))
        }
        waitForIdle()

        assertEquals(
            3,
            onAllNodesWithTag(FEED_SCRUBBER_YEAR_PILL_TAG).fetchSemanticsNodes().size,
            "one pill per year, on a track with room for all of them",
        )
    }

    @Test
    fun thePillUnderTheThumbIsNotDrawn() = runDesktopComposeUiTest(width = 420, height = 900) {
        // ⚠⚠ The bubble is 8 % transparent, so a pill behind it does not disappear — it
        // ghosts through the date.
        //
        // 2017 holds the single oldest photograph, so its pill sits at the very bottom of the
        // track — exactly where the drag below parks the thumb.
        val bottomHeavy = listOf(
            FeedYearMark(2026, startIndex = 0, count = 299),
            FeedYearMark(2017, startIndex = 299, count = 1),
        )
        setContent(tracked(items = 300, marks = bottomHeavy))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 2_000f))
        }
        waitForIdle()

        // ⚠ One pill, not zero: 2026 is at the top of the track and must survive. A rule that hid
        // everything would pass a bare "2017 is absent" assertion.
        assertEquals(
            1,
            onAllNodesWithTag(FEED_SCRUBBER_YEAR_PILL_TAG).fetchSemanticsNodes().size,
            "the pill beneath the thumb is hidden and the far one is not",
        )
        onNodeWithText("2026").assertExists()
    }

    @Test
    fun theYearPillsAreAbsentWhenTheThumbIsMerelyVisible() = runDesktopComposeUiTest(width = 420, height = 900) {
        // ⚠⚠ The half that matters for the photographs. The pills are drawn down the right-hand
        // edge OVER the grid, so a column of years left permanently on screen covers a strip of
        // every row for a control nobody is touching.
        setContent(tracked(items = 300, marks = wideYears()))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(FEED_SCRUBBER_YEAR_PILL_TAG).assertDoesNotExist()
    }

    @Test
    fun aFeedWithNoDensityMapDragsWithNoPillsAtAll() = runDesktopComposeUiTest(width = 420, height = 900) {
        // ⚠ The read can fail, and a library can be one year old. Either way the thumb must behave
        // as a bare thumb rather than draw an empty track decoration.
        setContent(tracked(items = 300, marks = emptyList()))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
        }
        waitForIdle()

        onNodeWithTag(FEED_SCRUBBER_YEAR_PILL_TAG).assertDoesNotExist()
        // ⚠ …and the thumb is still there. A crash or an early return inside the pill block would
        // take the control with it, and the assertion above would pass for that too.
        onNodeWithTag(FEED_SCRUBBER_TAG).assertExists()
    }

    @Test
    fun yearsTooCloseTogetherToDrawAreThinnedRatherThanStacked() = runDesktopComposeUiTest(width = 420, height = 900) {
        // ⚠⚠ Three thin years at the bottom, which is what a real library looks like — most
        // photographs are recent. Without thinning all four pills draw, three of them overlapping.
        val crowded = listOf(
            FeedYearMark(2026, startIndex = 0, count = 291),
            FeedYearMark(2023, startIndex = 291, count = 3),
            FeedYearMark(2019, startIndex = 294, count = 3),
            FeedYearMark(2017, startIndex = 297, count = 3),
        )
        setContent(tracked(items = 300, marks = crowded))
        waitForIdle()

        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
        }
        waitForIdle()

        val drawn = onAllNodesWithTag(FEED_SCRUBBER_YEAR_PILL_TAG).fetchSemanticsNodes().size
        assertTrue(drawn in 1..3, "four crowded years must not all draw, got $drawn")
    }

    @Test
    fun draggingTheThumbMovesTheFeed() {
        // ⚠ Asserted against the day header rather than an index: the grid interleaves headers with
        // photographs, so an index assertion would pin arithmetic rather than behaviour. The newest
        // day being gone is what "the list moved" means to a reader.
        runDesktopComposeUiTest(width = 420, height = 900) {
            setContent(feed(200))
            waitForIdle()
            onNodeWithText(TOP_DAY).assertExists()

            // ⚠ The thumb is scroll-activated, so it has to be woken before it can be grabbed.
            onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
                down(center)
                moveTo(center + Offset(0f, 400f))
                moveTo(center + Offset(0f, 800f))
                up()
            }
            waitForIdle()

            onNodeWithText(TOP_DAY).assertDoesNotExist()
        }
    }

    /**
     * A drag to the bottom leaves the thumb AT the bottom once the finger lifts.
     *
     * ⚠⚠ The grid clamps at its end, where the top row is still rows short of the last photograph,
     * so a resting position read from `progressAt` alone sits above the end the grid is at and the
     * thumb jumps up on release.
     */
    @Test
    fun aDragToTheBottomLeavesTheThumbAtTheBottom() = runDesktopComposeUiTest(width = 420, height = 900) {
        setContent(feed(200))
        waitForIdle()
        onNodeWithTag(FEED_GRID_TAG).performTouchInput { swipeUp() }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(FEED_SCRUBBER_TAG).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 2_000f))
        }
        waitForIdle()
        val held = onNodeWithTag(FEED_SCRUBBER_TAG).getUnclippedBoundsInRoot().bottom

        // ⚠ The clock held, so the fade cannot hide the thumb before it is measured.
        mainClock.autoAdvance = false
        onNodeWithTag(FEED_SCRUBBER_TAG).performTouchInput { up() }
        mainClock.advanceTimeBy(100)
        val resting = onNodeWithTag(FEED_SCRUBBER_TAG).getUnclippedBoundsInRoot().bottom

        assertEquals(held.value, resting.value, absoluteTolerance = 1f, "the thumb jumped on release")
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val TOP_DAY = "day 200"
    }
}
