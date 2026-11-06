package app.tenshu.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.photos.data.heroPreviewRequestPx
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PHOTO_PAGER_TAG
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * The viewer as a pager (`photos-app.md` S3 / PH-8).
 *
 * ⚠⚠ The two load-bearing tests are the ones that are not about the swipe: **the neighbour's bytes
 * are already being read** before the finger goes down, and **the list grows underneath the pager**
 * — those are what make the swipe feel instantaneous and endless rather than merely possible.
 */
@OptIn(ExperimentalTestApi::class)
class PhotoViewerPagerTest {

    private fun photos(n: Int) = List(n) { i -> photoAt(i) }

    private fun photoAt(i: Int) =
        Photo(path = "/Backup/Phone/Camera/p$i.jpg", capturedAtMs = 1_000L - i, isVideo = false)

    /** Records what the viewer asked for, in order. ⚠ Returns no bytes: this file is about *which*
     * reads happen, and a decodable JPEG would only add a decoder to the assertion. */
    private class Loader {
        val asked = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        fun load(path: String, requestPx: Int, mayMint: Flow<Unit>): Flow<ByteArray> = flow {
            asked += path
            sizes += requestPx
        }
    }

    @Test
    fun theNeighbourIsReadBeforeItIsSwipedTo() = runComposeUiTest {
        val loader = Loader()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(6),
                    initialIndex = 2,
                    state = PhotoViewerUiState(photo = photoAt(2)),
                    loadHero = loader::load,
                    onBack = {},
                )
            }
        }
        waitForIdle()

        // ⚠⚠ Both sides, and only both sides. One page either side is the deal PH-8 struck: fewer
        // and the swipe shows a spinner, more and the viewer holds decoded heroes the user never
        // looked at (the reason the bytes are not in the view model at all).
        assertTrue("/Backup/Phone/Camera/p2.jpg" in loader.asked, "the opened photo: ${loader.asked}")
        assertTrue("/Backup/Phone/Camera/p3.jpg" in loader.asked, "the next photo: ${loader.asked}")
        assertTrue("/Backup/Phone/Camera/p1.jpg" in loader.asked, "the previous photo: ${loader.asked}")
        assertFalse("/Backup/Phone/Camera/p5.jpg" in loader.asked, "the far end: ${loader.asked}")
    }

    @Test
    fun swipingMovesToTheNextPhoto() = runComposeUiTest {
        val shown = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(6),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photoAt(0)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onPhotoShown = { shown += it.path },
                )
            }
        }
        waitForIdle()
        assertEquals(listOf("/Backup/Phone/Camera/p0.jpg"), shown, "the tapped photo is reported first")

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeLeft() }
        waitForIdle()

        // ⚠ The info sheet reads its photo from this callback, so a pager that moved without
        // reporting would print one photograph's camera under another's name.
        assertEquals("/Backup/Phone/Camera/p1.jpg", shown.last(), "swiped to: $shown")
    }

    /**
     * §34: the viewer asks for the size it draws, and a window grown past a step asks again. ⚠⚠ The
     * picture already on screen stays while the larger one loads — a spinner in its place would read
     * as the photograph disappearing on every window drag.
     */
    @Test
    fun aViewerGrownPastAStepAsksAgain_andKeepsItsPictureMeanwhile() = runDesktopComposeUiTest(width = 3000, height = 800) {
        val asked = mutableListOf<Int>()
        val larger = CompletableDeferred<ByteArray>()
        var width by mutableStateOf(1_000)
        setContent {
            TenshuTheme {
                Box(Modifier.size(width.dp, 800.dp)) {
                    PhotoViewerScreen(
                        photos = photos(1),
                        initialIndex = 0,
                        state = PhotoViewerUiState(photo = photoAt(0)),
                        loadHero = { _, requestPx, _ ->
                            flow {
                                asked += requestPx
                                emit(if (asked.size == 1) onePixelPng else larger.await())
                            }
                        },
                        onBack = {},
                    )
                }
            }
        }
        waitUntil { spinners() == 0 && asked.size == 1 }
        val px = with(density) { 1_000.dp.roundToPx() }
        assertEquals(listOf(heroPreviewRequestPx(px, with(density) { 800.dp.roundToPx() })), asked, "the first request is the drawn size")

        width = 2_600
        waitUntil { asked.size == 2 }
        assertTrue(asked[1] > asked[0], "a wider window asks for more: $asked")
        assertEquals(0, spinners(), "the smaller picture stays on screen while the larger one loads")

        larger.complete(onePixelPng)
        waitForIdle()
        assertEquals(2, asked.size, "and nothing asks a third time")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.spinners(): Int =
        onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        ).fetchSemanticsNodes().size

    /** A 1 × 1 PNG: decodable, so the page reaches its Ready state. */
    /**
     * ⚠⚠ **One page, drawn twice** — `drive-xxlarge-thumbnail-rung.md` §4.5. The viewer's loader is a
     * stream: the best local class arrives first so the page is not blank, and the named rung
     * replaces it when the pull lands. Before X-3 the page awaited exactly one answer, and a second
     * would have been dropped on the floor.
     *
     * ⚠ How it is observed without reaching inside the private `HeroState`: `emit` suspends until
     * the collector has processed the value, so the flow reaching its second `emit` **is** the proof
     * that the first was consumed, and reaching the end is the proof that the second was. A
     * collector that took only the first value would cancel the flow and `applied` would stop at 1.
     */
    @Test
    fun aSecondEmissionUpgradesThePageInPlace() = runComposeUiTest {
        val applied = mutableListOf<Int>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(1),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photoAt(0)),
                    loadHero = { _, _, _ ->
                        flow {
                            emit(onePixelPng)
                            applied += 1
                            emit(onePixelPng.copyOf())
                            applied += 2
                        }
                    },
                    onBack = {},
                )
            }
        }
        waitUntil { applied.size == 2 }

        assertEquals(listOf(1, 2), applied, "both emissions must reach the page, in order")
        assertEquals(0, spinners(), "and the page is showing a picture, not a spinner")
    }

    private val onePixelPng = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, 0, 0, 3, 1, 1, 0, -55, -2, -110, -17, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    @Test
    fun nearingTheEndAsksTheOwnerForMore() = runComposeUiTest {
        var loadMores = 0
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    // Opened on the second-to-last row of what the feed has loaded — inside the
                    // two-photo prefetch window.
                    photos = photos(4),
                    initialIndex = 2,
                    state = PhotoViewerUiState(photo = photoAt(2)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onLoadMore = { loadMores++ },
                )
            }
        }
        waitForIdle()

        assertEquals(1, loadMores, "the pager asks for the next page before the user reaches the end")
    }

    @Test
    fun aWholeListIsNeverAskedForMore() = runComposeUiTest {
        // ⚠ An album, a smart section and a search result have no next page. The viewer must not
        // ask — `onLoadMore` is null for them, and this is the test that the wiring stays that way.
        var loadMores = 0
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(3),
                    initialIndex = 2,
                    state = PhotoViewerUiState(photo = photoAt(2)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onLoadMore = null,
                )
            }
        }
        waitForIdle()

        assertEquals(0, loadMores)
    }

    @Test
    fun theSwipeReachesPhotosTheListGainedAfterItOpened() = runComposeUiTest {
        // ⚠⚠ This is what "endless" means here: the feed's `loadMore` appends, the pager's page
        // count is a lambda over the same list, and the photo that did not exist when the user
        // tapped is simply there. A viewer holding its own copy would stop at the fourth photo.
        var list by mutableStateOf(photos(4))
        val shown = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = list,
                    initialIndex = 3,
                    state = PhotoViewerUiState(photo = photoAt(3)),
                    loadHero = { _, _, _ -> emptyFlow() },
                    onBack = {},
                    onPhotoShown = { shown += it.path },
                    onLoadMore = { list = photos(8) },
                )
            }
        }
        waitForIdle()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeLeft() }
        waitForIdle()

        assertEquals("/Backup/Phone/Camera/p4.jpg", shown.last(), "swiped into a page that arrived later: $shown")
    }
}
