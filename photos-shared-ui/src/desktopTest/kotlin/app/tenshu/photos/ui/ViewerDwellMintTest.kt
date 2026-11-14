package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.tenshu.photos.model.Photo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.tenshu.photos.ui.screens.viewer.DWELL_MS
import app.tenshu.photos.ui.screens.viewer.PHOTO_PAGER_TAG
import app.tenshu.photos.ui.screens.viewer.PhotoViewerScreen
import app.tenshu.photos.ui.screens.viewer.PhotoViewerUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ⚠⚠ **The dwell is D-18's whole policy, and it is about *which* photographs get downloaded**
 * (`drive-xxlarge-thumbnail-rung.md` §4.6). `EnsureThumbnailClass` may pull a 6–18 MB original, so
 * the difference between "a page that was composed" and "a page someone actually looked at" is the
 * difference between one download and three.
 *
 * ⚠ The pager composes a neighbour on each side (`beyondViewportPageCount = 1`) and every composed
 * page starts its loader immediately, so the naive place to time a dwell — composition — is exactly
 * the wrong one. These tests exist because that mistake is invisible in a screenshot and free in a
 * unit test: everything still works, it just costs two unseen originals a time.
 *
 * ⚠ Every clock step is sized from [DWELL_MS], never "a long time". Compose's test clock renders a
 * frame every 16 ms it is advanced, so a 5 s step is ~300 frames of a full-screen pager.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerDwellMintTest {

    private fun photos(n: Int) = List(n) { i ->
        Photo(path = "/Backup/Phone/Camera/p$i.jpg", capturedAtMs = 1_000L - i, isVideo = false)
    }

    @Test
    fun `only the settled page is ever asked for, never a composed neighbour`() = runDesktopComposeUiTest(width = 1_200, height = 900) {
        mainClock.autoAdvance = false
        val asked = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(5),
                    initialIndex = 2,
                    state = PhotoViewerUiState(photo = photos(5)[2]),
                    // ⚠ The loader is handed the licence itself, so the test observes the policy
                    // where it is decided rather than a side effect of it: a page whose `mayMint`
                    // never emits is a page that will never spend a download.
                    loadHero = { path, _, mayMint -> flow { mayMint.collect { asked += path } } },
                    onBack = {},
                )
            }
        }

        // Past the dwell: the neighbours at index 1 and 3 are composed and have been for the whole
        // time, so if composition were the trigger they would be in this list.
        mainClock.advanceTimeBy(PAST_THE_DWELL)

        assertEquals(
            listOf("/Backup/Phone/Camera/p2.jpg"),
            asked.distinct(),
            "a composed neighbour is not a photograph anyone looked at: $asked",
        )
    }

    /**
     * ⚠⚠ **A page reached by swiping must be licensed too** — and this is the case the other two
     * cannot see, because both only ever exercise the page the viewer opened on.
     *
     * The pager composes a neighbour *before* the swipe, with `isSettled = false`. A licence built
     * from that captured value freezes it, and `produceState` is deliberately not keyed on settling,
     * so nothing re-evaluates it when the page is actually reached. The result is mint-on-**open**:
     * one photograph per viewer session, whatever the user then looks at. That regression shipped in
     * this phase's first rework and both existing tests stayed green through it.
     */
    @Test
    fun `a page reached by swiping is licensed, not only the one opened on`() = runDesktopComposeUiTest(width = 1_200, height = 900) {
        mainClock.autoAdvance = false
        val asked = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(4),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photos(4)[0]),
                    loadHero = { path, _, mayMint -> flow { mayMint.collect { asked += path } } },
                    onBack = {},
                )
            }
        }
        mainClock.advanceTimeBy(PAST_THE_DWELL)
        assertEquals(listOf("/Backup/Phone/Camera/p0.jpg"), asked, "precondition: the opened page is licensed")

        onNodeWithTag(PHOTO_PAGER_TAG).performTouchInput { swipeLeft() }
        mainClock.advanceTimeBy(SNAP_SETTLE_MS)
        waitForIdle()
        // The dwell runs from settling, so it starts only now.
        mainClock.advanceTimeBy(PAST_THE_DWELL)

        assertTrue(
            "/Backup/Phone/Camera/p1.jpg" in asked,
            "the page swiped to must be licensed once it settles, not only the one opened on: $asked",
        )
    }

    @Test
    fun `nothing is asked for before the dwell has elapsed`() = runDesktopComposeUiTest(width = 1_200, height = 900) {
        mainClock.autoAdvance = false
        val asked = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(3),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photos(3)[0]),
                    // ⚠ The loader is handed the licence itself, so the test observes the policy
                    // where it is decided rather than a side effect of it: a page whose `mayMint`
                    // never emits is a page that will never spend a download.
                    loadHero = { path, _, mayMint -> flow { mayMint.collect { asked += path } } },
                    onBack = {},
                )
            }
        }

        mainClock.advanceTimeBy(DWELL_MS / 2)
        assertTrue(asked.isEmpty(), "a glance is not a dwell: $asked")

        mainClock.advanceTimeBy(PAST_THE_DWELL)
        assertEquals(1, asked.size, "and after the dwell, exactly once: $asked")
    }

    /**
     * ⚠⚠ **The dwell is measured from when the page settles, not from when the loader gets round to
     * asking.** D-18 says "a second of being looked at", but the loader does not subscribe until it
     * has been to the relay and back twice (§4.5 steps 1–2). Timing the wait from subscription makes
     * it `time-to-reach-step-3 + DWELL_MS` — several seconds on a slow network, and a policy that
     * depends on the network rather than on the user.
     *
     * The loader here subscribes [LATE_SUBSCRIPTION_MS] late, as a cold network read would, which is
     * after the dwell is earned. The licence must already be earned by then, not start counting: it
     * is checked again before `LATE_SUBSCRIPTION_MS + DWELL_MS`, where a subscription-timed dwell
     * would first emit.
     */
    @Test
    fun `the dwell runs from settling, not from the loader's subscription`() = runDesktopComposeUiTest(width = 1_200, height = 900) {
        mainClock.autoAdvance = false
        val asked = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(3),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photos(3)[0]),
                    loadHero = { path, _, mayMint ->
                        flow {
                            delay(LATE_SUBSCRIPTION_MS)
                            mayMint.collect { asked += path }
                        }
                    },
                    onBack = {},
                )
            }
        }

        mainClock.advanceTimeBy(LATE_SUBSCRIPTION_MS + 100)
        assertEquals(
            listOf("/Backup/Phone/Camera/p0.jpg"),
            asked,
            "the page settled at 0 and was earned at $DWELL_MS ms; a subscriber arriving at " +
                "$LATE_SUBSCRIPTION_MS ms is not made to wait until ${LATE_SUBSCRIPTION_MS + DWELL_MS}: $asked",
        )
    }

    /**
     * ⚠⚠ **A spent licence completes**, and the `Absent` branch depends on it. The repository awaits
     * `mayMint.firstOrNull()` and everything after it — including the read that decides the
     * photograph is not here — runs only once that returns. A licence flow that stays open after
     * emitting leaves the collector suspended for ever, so the page spins instead of reaching a
     * verdict. `take(1)` is what makes "emits once" true rather than merely usually-harmless.
     */
    @Test
    fun `the licence completes once spent, so the loader can reach a verdict`() = runDesktopComposeUiTest(width = 1_200, height = 900) {
        mainClock.autoAdvance = false
        val finished = mutableListOf<String>()
        setContent {
            TenshuTheme {
                PhotoViewerScreen(
                    photos = photos(3),
                    initialIndex = 0,
                    state = PhotoViewerUiState(photo = photos(3)[0]),
                    loadHero = { path, _, mayMint ->
                        flow {
                            mayMint.collect {}
                            finished += path
                        }
                    },
                    onBack = {},
                )
            }
        }

        mainClock.advanceTimeBy(PAST_THE_DWELL)
        assertEquals(
            listOf("/Backup/Phone/Camera/p0.jpg"),
            finished,
            "collecting the licence must return, not suspend for ever: $finished",
        )
    }

    private companion object {
        /** Past [DWELL_MS] by enough frames for the settled page's effect to have run. */
        const val PAST_THE_DWELL = DWELL_MS + 500

        /** Long enough for the pager's snap after a swipe; `waitForIdle()` then confirms it stopped. */
        const val SNAP_SETTLE_MS = 1_000L

        /** A loader that subscribes after the dwell is earned, but well before it would be earned twice. */
        const val LATE_SUBSCRIPTION_MS = PAST_THE_DWELL

        init {
            // The late-subscription case discriminates only while the loader subscribes after the
            // dwell is earned, and its check (100 ms later) lands before a subscription-timed dwell
            // could emit.
            check(LATE_SUBSCRIPTION_MS > DWELL_MS && 100 < DWELL_MS)
        }
    }
}
