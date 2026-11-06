package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.AlbumSummary
import app.tenshu.photos.model.Photo
import app.tenshu.photos.model.PhotoPlace
import app.tenshu.photos.ui.screens.viewer.PHOTO_PAGER_TAG
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.emptyFlow

/**
 * The About sheet on screen (`photos-viewer-about-screen.md` PH-10).
 *
 * ⚠⚠ The load-bearing one is [thePagerStaysFreeWithThePanelOpen], and it asserts the **opposite**
 * of what it used to. PH-10 locked the pager while the panel was open, because an overlay
 * describing one photograph above a pager showing another puts every field on the wrong picture.
 * §11 made the panel part of the page instead, so paging swaps photograph and details together and
 * that staleness became unrepresentable — the lock had nothing left to protect. The rule did not
 * weaken; the architecture stopped needing it.
 */
@OptIn(ExperimentalTestApi::class)
class AboutSheetTest {

    /**
     * A right-to-left swipe across the **top** of the viewer, clear of the About sheet.
     *
     * ⚠ The sheet occupies the lower 72%, so a centred gesture never reaches the pager whether it
     * is locked or not — which is how the lock test came to pass without the lock.
     */
    private fun androidx.compose.ui.test.TouchInjectionScope.swipeAcrossTheTop() {
        val y = height * 0.08f
        down(Offset(width * 0.9f, y))
        moveTo(Offset(width * 0.5f, y))
        moveTo(Offset(width * 0.1f, y))
        up()
    }

    private fun photos(n: Int) = List(n) { i ->
        Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")
    }

    private fun screen(
        state: PhotoViewerUiState,
        photos: List<Photo>,
        onPhotoShown: (Photo) -> Unit = {},
    ) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            PhotoViewerScreen(
                photos = photos,
                initialIndex = 0,
                state = state,
                loadHero = { _, _, _ -> emptyFlow() },
                onBack = {},
                onToggleInfo = {},
                onPhotoShown = onPhotoShown,
                formatCapturedAt = { "Thu, 9 Apr 2026  ·  21:26" },
            )
        }
    }

    @Test
    fun theSheetLeadsWithWhenTheDayWas() = runComposeUiTest {
        val all = photos(1)
        setContent(screen(PhotoViewerUiState(photo = all[0], infoOpen = true), all))

        waitUntil {
            onAllNodesWithText("Thu, 9 Apr 2026  ·  21:26").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun albumMembershipIsNamedAndCounted() = runComposeUiTest {
        val all = photos(1)
        setContent(
            screen(
                PhotoViewerUiState(
                    photo = all[0],
                    infoOpen = true,
                    albums = listOf(
                        AlbumSummary("a1", "Trip to France", 0L, photoCount = 28, coverPath = "/c.jpg"),
                    ),
                ),
                all,
            )
        )

        waitUntil { onAllNodesWithText("Albums").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Trip to France  ·  28 items").assertExists()
    }

    @Test
    fun aPhotoInNoAlbumGetsNoAlbumsHeading() = runComposeUiTest {
        // ⚠ An empty "Albums" heading is a section that exists to say nothing.
        val all = photos(1)
        setContent(
            screen(PhotoViewerUiState(photo = all[0], infoOpen = true, albums = emptyList()), all)
        )

        waitUntil { onAllNodesWithText("Details").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Albums").assertDoesNotExist()
    }

    @Test
    fun aPlaceIsShownOnlyWhenOneIsNamed() = runComposeUiTest {
        // ⚠⚠ D2. PH-G7 is unshipped, so most photographs have no named place and there is no honest
        // fallback — the section is absent rather than filled with a country or a coordinate.
        val all = photos(1)
        setContent(screen(PhotoViewerUiState(photo = all[0], infoOpen = true), all))
        waitUntil { onAllNodesWithText("Details").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Place").assertDoesNotExist()

        setContent(
            screen(
                PhotoViewerUiState(
                    photo = all[0],
                    infoOpen = true,
                    place = PhotoPlace("p1", "Eiffel Tower", "Paris", 0L, 0L),
                ),
                all,
            )
        )
        waitUntil { onAllNodesWithText("Place").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Eiffel Tower, Paris").assertExists()
    }

    @Test
    fun thePagerStaysFreeWithThePanelOpen() = runComposeUiTest {
        // ⚠⚠ The inverted rule (§11). Verified against Google Photos first: swiping sideways with
        // its info panel open pages the photograph AND updates the panel, because the panel belongs
        // to the page. Ours does the same, so the lock this test used to assert is gone.
        //
        // ⚠ What still must not happen is the panel describing the wrong photograph — but that is
        // now prevented by construction rather than by a lock: `about` is passed only to the page
        // that is current, so a neighbour has no details to be stale.
        val shown = mutableListOf<String>()
        val all = photos(4)
        setContent(
            screen(
                PhotoViewerUiState(photo = all[0], infoOpen = true),
                all,
                onPhotoShown = { shown += it.path },
            )
        )
        waitForIdle()
        shown.clear()

        // ⚠ Still swiped across the TOP rather than the centre, and the reason survives the
        // inversion: the gesture has to land on the photograph rather than on the panel, or it is
        // the panel's scroll being tested and not the pager.
        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeAcrossTheTop() }
        waitForIdle()

        assertEquals(
            listOf("/p1.jpg"),
            shown,
            "the pager must still page with the panel open — the details travel with the page",
        )

        // ⚠⚠ **The half this test was missing, and a device found the bug in the gap.** Asserting
        // only that the page moved is compatible with the panel SNAPPING SHUT as it moves — which
        // is exactly what happened: every page gets a fresh `ScrollState` at 0, so the incoming
        // page's scroll→flag binding reported "closed" and closed the panel the user had open.
        // Paging with it open must leave it open, as Google Photos does.
        onNodeWithText("Path", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aSwipeOverThePhotographDoesNotDismissTheSheet() {
        // ⚠⚠ Found on a device, and caused by PH-10's own lock. `clickable`'s tap detection cancels
        // when another gesture CONSUMES the pointer, not when it merely moves — so while the pager
        // was scrollable it swallowed every horizontal drag and the click never fired. Locking it
        // stopped that, and a swipe over the photograph then ended as a click that toggled the
        // chrome, taking the sheet with it.
        val toggles = mutableListOf<Unit>()
        val all = photos(4)
        runComposeUiTest {
            setContent {
                TenshuTheme {
                    PhotoViewerScreen(
                        photos = all,
                        initialIndex = 0,
                        state = PhotoViewerUiState(photo = all[0], infoOpen = true),
                        loadHero = { _, _, _ -> emptyFlow() },
                        onBack = {},
                        onToggleInfo = {},
                        onToggleChrome = { toggles += Unit },
                        formatCapturedAt = { "Thu, 9 Apr 2026  ·  21:26" },
                    )
                }
            }
            waitForIdle()

            onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeAcrossTheTop() }
            waitForIdle()

            assertEquals(
                emptyList(),
                toggles,
                "a drag over the photograph must not register as a tap while About is open",
            )
        }
    }

    @Test
    fun theSameSwipeMovesWithTheSheetClosed() = runComposeUiTest {
        // ⚠ The other half: the lock must be the *sheet*, not a pager that stopped working.
        val shown = mutableListOf<String>()
        val all = photos(4)
        setContent(
            screen(
                PhotoViewerUiState(photo = all[0], infoOpen = false),
                all,
                onPhotoShown = { shown += it.path },
            )
        )
        waitForIdle()
        shown.clear()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeAcrossTheTop() }
        waitForIdle()

        assertEquals("/p1.jpg", shown.lastOrNull(), "swiped to: $shown")
    }

    // ── §11: the panel is part of the page, not an overlay over it ───────────────────────────

    @Test
    fun thePanelSitsBelowTheFoldUntilItIsOpened() = runComposeUiTest {
        // ⚠⚠ The shape of §11 in one assertion. The panel is composed the whole time — it is the
        // next thing down the page's own scrolling column — so "closed" is a SCROLL POSITION, not
        // an absence. Closed means below the fold; open means the binding scrolled it into view.
        //
        // ⚠ `useUnmergedTree` throughout: the merged tree resolves these to an ancestor whose
        // bounds are the whole screen, which would answer "is the page on screen" instead.
        val all = photos(1)
        setContent(screen(PhotoViewerUiState(photo = all[0], infoOpen = false), all))
        waitForIdle()

        onNodeWithText("Path", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test
    fun openingScrollsThePanelIntoView() = runComposeUiTest {
        // ⚠⚠ The other half, and the one that proves `PanelScrollBinding` runs at all: with the
        // flag set, the same row that was below the fold above must now be on screen. Break the
        // flag→scroll direction and this is what objects.
        val all = photos(1)
        setContent(screen(PhotoViewerUiState(photo = all[0], infoOpen = true), all))
        waitForIdle()

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Path", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .any { it.layoutInfo.isPlaced }
        }
        onNodeWithText("Path", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun onlyThePageOnScreenIsGivenItsDetails() = runComposeUiTest {
        // ⚠⚠ **This is what replaced the pager lock, so it is the load-bearing test of §11.**
        // PH-10 stopped the pager moving because an overlay could describe the wrong photograph.
        // The panel now belongs to the page, and the guarantee is that only the CURRENT page is
        // handed the view model's details — a neighbour composed ahead by `beyondViewportPageCount`
        // gets `about = null` and is simply a photograph.
        //
        // ⚠ Without that, page N's details would render under page N+1's picture — the same
        // staleness, moved rather than fixed, and invisible until someone swiped.
        val all = photos(4)
        setContent(screen(PhotoViewerUiState(photo = all[0], infoOpen = true), all))
        waitForIdle()

        val panels = onAllNodesWithText("Path", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, panels.size, "exactly one page may carry the details, got ${panels.size}")
    }
}
