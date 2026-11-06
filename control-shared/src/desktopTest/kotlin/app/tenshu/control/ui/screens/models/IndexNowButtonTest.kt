package app.tenshu.control.ui.screens.models

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * *Index now*'s three rules (§34.2). The view model owns what a press **does**; this owns when the
 * control is offered at all, which is a composition decision no state test can see.
 */
class IndexNowButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setScreen(state: ModelsUiState, onIndexNow: () -> Unit = {}) {
        composeTestRule.setContent {
            ModelsScreenContent(
                state = state,
                onDownload = {},
                onIndexNow = onIndexNow,
                onBack = {},
            )
        }
    }

    @Test
    fun anIdleDeviceOffersTheButtonAndPressingItStarts() {
        var pressed = false
        setScreen(ModelsUiState(loading = false)) { pressed = true }

        composeTestRule.onNodeWithText("Index now").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Index now").performClick()

        assertTrue(pressed)
    }

    /**
     * ⚠⚠ Absent while paused, never merely disabled: the answer to a paused device is *Resume*, and
     * a screen offering both is arguing with itself.
     */
    @Test
    fun aPausedDeviceOffersResumeInsteadOfASecondStart() {
        setScreen(ModelsUiState(loading = false, indexingPaused = true))

        composeTestRule.onNodeWithText("Resume").assertIsDisplayed()
        assertEquals(
            0,
            // ⚠ Counted, not `onNodeWithText`: that throws on absence, which is the case under test.
            composeTestRule.onAllNodesWithText("Index now").fetchSemanticsNodes().size,
            "a paused device must not offer a start beside the Resume that would undo the pause",
        )
    }

    /** ⚠ While a pass runs a second press would join it and report "started" — a no-op that reads as one. */
    @Test
    fun aRunningPassDisablesTheButton() {
        setScreen(
            ModelsUiState(
                loading = false,
                indexProgress = IndexProgressState(indexing = true, done = 12, total = 900),
            ),
        )

        composeTestRule.onNodeWithText("Index now").assertIsNotEnabled()
    }

    /** ⚠ And so does a press still in flight, so a double tap cannot send two. */
    @Test
    fun aStartInFlightDisablesTheButton() {
        setScreen(ModelsUiState(loading = false, startingIndex = true))

        composeTestRule.onNodeWithText("Index now").assertIsNotEnabled()
    }
}
