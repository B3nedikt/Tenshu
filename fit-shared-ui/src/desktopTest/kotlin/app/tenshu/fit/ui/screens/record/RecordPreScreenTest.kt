package app.tenshu.fit.ui.screens.record

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import app.tenshu.fit.data.MockFitRepository
import app.tenshu.fit.data.MockRecordingEngine
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import app.tenshu.fit.composeVmTest

/**
 * Compose-render coverage for Record — pre-start.
 *
 * ⚠⚠ **This file exists because Record was the one tab §18.15 converted that had no render test,
 * and it is the one the conversion broke.** Start is a *sibling* of the scrolling area, pinned to
 * the bottom; §18.15 replaced the outer `Column` with the `Scaffold` content slot, which is not a
 * Column — `ScaffoldLayout` places every body measurable at (0, 0) — and gave the scroll area
 * `fillMaxSize()`. Start ended up behind the activity chips at the top of the screen, which from
 * the user's side reads as "the Start button vanished". Every other converted tab had exactly one
 * child in that slot and was unaffected.
 *
 * ⚠ `assertIsDisplayed` alone does NOT catch it — the button was still composed, still on screen,
 * merely in the wrong place and underneath other content. The position assertion is the part that
 * fails on the regression, so do not "simplify" it away.
 */
@OptIn(ExperimentalTestApi::class)
class RecordPreScreenTest {

    @Test
    fun startButtonIsPinnedBelowTheScrollArea() = composeVmTest { vms ->
        val vm = vms.own(RecordViewModel(MockRecordingEngine(MockFitRepository())))
        setContent {
            TenshuTheme { RecordPreScreen(viewModel = vm, onStart = {}, onReview = {}) }
        }

        onNodeWithText("Record", useUnmergedTree = true).assertIsDisplayed()
        waitUntil {
            onAllNodesWithText("Start", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Start", useUnmergedTree = true).assertIsDisplayed()

        // The activity picker is the FIRST thing in the scroll area; Start sits after all of it.
        // Under the regression both were placed at the slot's origin, so Start's top landed above
        // the chips rather than below them.
        val start = onNodeWithText("Start", useUnmergedTree = true).getBoundsInRoot()
        val chip = onNodeWithText("Run", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            start.top > chip.bottom,
            "Start must be pinned below the scroll area, but its top (${start.top}) was not " +
                "below the activity chips' bottom (${chip.bottom}) — the Scaffold content slot " +
                "overlaps its children instead of stacking them.",
        )

        // And it must be at the BOTTOM, not merely after the chips: the scroll area takes
        // `weight(1f)` precisely so Start keeps the space under it.
        val root = onRoot().getBoundsInRoot()
        val rootHeight = root.bottom - root.top
        assertTrue(
            start.bottom > rootHeight * 0.6f,
            "Start must sit in the lower part of the screen, but its bottom (${start.bottom}) " +
                "was high up in a root of height $rootHeight.",
        )
    }
}
