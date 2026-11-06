package app.tenshu.photos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.viewer.PHOTO_PAGER_TAG
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf

/**
 * Gesture priority in the viewer (PH-15 / D4): **while zoomed in, the drag belongs to the pan.**
 *
 * ⚠⚠ These are about **who consumes what**, which is the whole of the arbitration and the thing
 * that has already gone wrong once. PH-10's pager lock stopped the pager consuming horizontal
 * drags, and an unconsumed drag then registered as a *click* that dismissed all the chrome (§9.3b).
 * A gesture change is never local: it moves what everyone else sees.
 *
 * ⚠⚠ **What these do NOT guard, stated plainly.** They pin the fit-scale contract — the pager still
 * pages, a drag is not a tap, a tap still is one — and the image is real, so the gesture modifier is
 * genuinely in the tree (an earlier version passed `loadHero = { _, _, _ -> null }`, rendered `HeroState.Absent`
 * and tested an empty page).
 *
 * But the **consumption rule itself is not falsifiable from here**: making the gesture loop consume
 * unconditionally leaves all three green, so this harness cannot tell whether the child is stealing
 * the pager's drags. Whatever the reason in Compose's dispatch, the consequence for a reader is what
 * matters — *the arbitration is device-verified, not test-verified*, and a green run here is not
 * evidence that zoom and paging still coexist. Check it on a phone.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerZoomGestureTest {

    /**
     * A real, decodable 1×1 PNG.
     *
     * ⚠⚠ Not decoration — the first version of this file passed `loadHero = { _, _, _ -> null }`, so every page
     * rendered `HeroState.Absent`, **no `Image` was composed, and the zoom modifier was never in the
     * tree**. The tests therefore passed with the gesture loop consuming unconditionally: they were
     * exercising an empty page. Real bytes are what makes them about the gesture at all.
     */
    private val onePixelPng = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, 0, 0, 3, 1, 1, 0, -55, -2, -110, -17, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private fun photos(n: Int) = List(n) { i ->
        Photo("/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i")
    }

    /**
     * Block until the first page's `Image` is actually composed.
     *
     * ⚠⚠ **Not politeness — the gesture modifier lives on the `Image`, and the `Image` only exists
     * once `produceState` has decoded the hero.** Until then the page is a bare `Box` carrying
     * nothing but the parent's `clickable`, so an injected drag reaches *that* and arrives as a
     * TAP. `waitForIdle()` alone does not cover it: the decode is real work on another dispatcher.
     *
     * ⚠⚠ This is not hypothetical and it is why the helper exists: `aDownwardDragIsNotAlsoATap`
     * passed six isolated runs and went red **under full-gate load**, where the decode had not
     * finished before the touch was injected. An isolated re-run cannot discharge a failure whose
     * cause is contention — and a test that silently exercises an empty page is the failure mode
     * this file's own history is made of.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.awaitHero() {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("p0.jpg").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A one-finger horizontal drag across the top, clear of any sheet. */
    private fun androidx.compose.ui.test.TouchInjectionScope.oneFingerSwipe() {
        val y = height * 0.5f
        down(Offset(width * 0.9f, y))
        moveTo(Offset(width * 0.5f, y))
        moveTo(Offset(width * 0.1f, y))
        up()
    }

    private fun screen(
        shown: MutableList<String>,
        toggles: MutableList<Unit>,
        backs: MutableList<Unit> = mutableListOf(),
        infoOpen: Boolean = false,
    ) = @androidx.compose.runtime.Composable {
        val all = photos(4)
        TenshuTheme {
            PhotoViewerScreen(
                photos = all,
                initialIndex = 0,
                state = PhotoViewerUiState(photo = all[0], infoOpen = infoOpen),
                loadHero = { _, _, _ -> flowOf(onePixelPng) },
                onBack = { backs += Unit },
                onPhotoShown = { shown += it.path },
                onToggleChrome = { toggles += Unit },
            )
        }
    }

    /** A one-finger drag straight down, [fraction] of the node's height. */
    private fun androidx.compose.ui.test.TouchInjectionScope.dragDown(fraction: Float) {
        val x = width * 0.5f
        down(Offset(x, height * 0.15f))
        // ⚠ Several steps rather than one: the gesture loop accumulates travel across events and
        // claims only after slop, so a single jump would test a shape the finger never makes.
        val total = height * fraction
        repeat(5) { i -> moveTo(Offset(x, height * 0.15f + total * (i + 1) / 5f)) }
        up()
    }

    @Test
    fun atFitScaleAOneFingerDragStillBelongsToThePager() = runComposeUiTest {
        // ⚠⚠ The regression this file exists for. The obvious way to add zoom —
        // `detectTransformGestures` — consumes a one-finger pan the moment it passes slop, which
        // would take every page swipe with it. The gesture loop therefore consumes only for two
        // fingers or when already magnified; this is what proves it left fit-scale alone.
        val shown = mutableListOf<String>()
        val toggles = mutableListOf<Unit>()
        setContent(screen(shown, toggles))
        waitForIdle()
        awaitHero()
        shown.clear()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { oneFingerSwipe() }
        waitForIdle()

        assertEquals("/p1.jpg", shown.lastOrNull(), "the pager must still page at fit-scale: $shown")
    }

    @Test
    fun aDragIsNotAlsoATap() = runComposeUiTest {
        // ⚠ The other half of §9.3b, kept honest here too: a drag that the pager consumes must not
        // additionally register as a chrome toggle.
        val shown = mutableListOf<String>()
        val toggles = mutableListOf<Unit>()
        setContent(screen(shown, toggles))
        waitForIdle()
        awaitHero()
        toggles.clear()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { oneFingerSwipe() }
        waitForIdle()

        assertEquals(emptyList(), toggles, "a page swipe must not toggle the chrome")
    }

    @Test
    fun aTapIsStillATap() = runComposeUiTest {
        // ⚠ The control. Without it, "no toggles" above would also pass for a build where tapping
        // the photograph had stopped working altogether.
        val shown = mutableListOf<String>()
        val toggles = mutableListOf<Unit>()
        setContent(screen(shown, toggles))
        waitForIdle()
        awaitHero()
        toggles.clear()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput {
            down(Offset(width * 0.5f, height * 0.5f))
            up()
        }
        waitForIdle()

        assertEquals(1, toggles.size, "a tap must still toggle the chrome")
    }

    // ── PH-15's second half: drag down to dismiss ────────────────────────────────────────────

    @Test
    fun aLongDownwardDragDismissesTheViewer() = runComposeUiTest {
        val backs = mutableListOf<Unit>()
        setContent(screen(mutableListOf(), mutableListOf(), backs))
        waitForIdle()
        awaitHero()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { dragDown(0.5f) }
        waitForIdle()

        assertEquals(1, backs.size, "a drag past the threshold must dismiss")
    }

    @Test
    fun aShortDownwardDragSpringsBackInsteadOfDismissing() = runComposeUiTest {
        // ⚠⚠ The control, and the more important of the pair. Without it the test above passes for
        // a build that dismisses on ANY downward movement — which would make the viewer impossible
        // to hold on to, and would look like the feature working.
        val backs = mutableListOf<Unit>()
        setContent(screen(mutableListOf(), mutableListOf(), backs))
        waitForIdle()
        awaitHero()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { dragDown(0.05f) }
        waitForIdle()

        assertEquals(emptyList(), backs, "a short drag must spring back, not dismiss")
    }

    @Test
    fun aDownwardDragIsNotAlsoATap() = runComposeUiTest {
        // ⚠ §9.3b again, for the new gesture: a drag the page consumes must not additionally
        // register as a chrome toggle. This is the failure that shipped once already.
        val toggles = mutableListOf<Unit>()
        setContent(screen(mutableListOf(), toggles))
        waitForIdle()
        awaitHero()
        toggles.clear()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { dragDown(0.5f) }
        waitForIdle()

        assertEquals(emptyList(), toggles, "a dismiss drag must not toggle the chrome")
    }

    @Test
    fun theDismissIsOffWhileTheAboutSheetIsOpen() = runComposeUiTest {
        // ⚠ The sheet is the interaction target while it is up; pulling the picture out from under
        // it is not what the gesture means there.
        val backs = mutableListOf<Unit>()
        setContent(screen(mutableListOf(), mutableListOf(), backs, infoOpen = true))
        waitForIdle()
        awaitHero()

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { dragDown(0.5f) }
        waitForIdle()

        assertEquals(emptyList(), backs, "an open sheet must not be dismissed by dragging behind it")
    }
}
