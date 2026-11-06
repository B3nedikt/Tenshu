package app.tenshu.ui.feedback

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR 0025 §8.6 (Codex P1 on 5f82133bc): what a root shows instead of its shell while the realm
 * question is unanswered. Blank first (no flash), then a spinner, and never a spinner forever: past
 * the bound it becomes a wall with "Try again", which re-arms the wait.
 */
@OptIn(ExperimentalTestApi::class)
class AppControlCheckingGateTest {

    @Test
    fun blankThenSpinnerThenARetryWallPastTheBound() = runComposeUiTest {
        mainClock.autoAdvance = false
        var retries = 0
        setContent { TenshuTheme { AppControlCheckingGate(onRetry = { retries++ }, quietMs = 300, boundMs = 20_000) } }

        // The first 300 ms: nothing at all — a realm read normally answers inside it.
        mainClock.advanceTimeByFrame()
        onNodeWithTag(TAG_BLANK).assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag(TAG_SPINNER).fetchSemanticsNodes().size, "no flash of a spinner")

        mainClock.advanceTimeBy(400)
        onNodeWithTag(TAG_SPINNER).assertIsDisplayed()
        onNodeWithText("Checking Tenshu Control…").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("Try again").fetchSemanticsNodes().size)

        // Past the bound: a wall with a retry, not a spinner forever.
        mainClock.advanceTimeBy(20_000)
        onNodeWithText("Can't confirm Tenshu Control is set up").assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed()

        // "Try again" asks the engine again, and re-arms the bounded wait.
        assertEquals(0, retries)
        onNodeWithText("Try again").performClick()
        assertEquals(1, retries, "the tap reaches the caller's recheck")
        mainClock.advanceTimeBy(100) // the effect restarts and recomposes, still inside the quiet window
        onNodeWithTag(TAG_BLANK).assertIsDisplayed()
        mainClock.advanceTimeBy(400)
        onNodeWithTag(TAG_SPINNER).assertIsDisplayed()
    }

    /**
     * ⚠⚠ Codex P2 on 67e1533a4: a root that reads `ControlReachability.reason` the way the nine app
     * roots do must never compose its shell while the verdict is PENDING — not even for the first
     * frame. The reason is a `StateFlow` with a synchronous value, so the first composition already
     * sees Checking.
     */
    @Test
    fun theFirstCompositionUnderAPendingVerdictIsNeverTheShell() = runComposeUiTest {
        val engineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val reach = app.tenshu.reachability.ControlReachability(
                probe = { true },
                scope = engineScope,
                probeIntervalMs = 60_000,
                // Never answers, so the verdict stays PENDING for the whole test.
                controlHasRealm = app.tenshu.reachability.RealmProbe.Wired { kotlinx.coroutines.awaitCancellation() },
            )
            var shellCompositions = 0
            val firstReasons = mutableListOf<app.tenshu.reachability.UnreachableReason?>()
            mainClock.autoAdvance = false
            setContent {
                TenshuTheme {
                    val reason by reach.reason.collectAsState()
                    firstReasons += reason
                    if (reason == app.tenshu.reachability.UnreachableReason.Checking) {
                        AppControlCheckingGate(onRetry = reach::recheck)
                    } else {
                        shellCompositions++
                        androidx.compose.material3.Text("shell")
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            assertEquals(app.tenshu.reachability.UnreachableReason.Checking, firstReasons.first(), "the first composition")
            assertEquals(0, shellCompositions, "the shell was never composed")
            assertEquals(0, onAllNodesWithText("shell").fetchSemanticsNodes().size)
            onNodeWithTag(TAG_BLANK).assertIsDisplayed()
        } finally {
            engineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
