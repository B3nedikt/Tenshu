package app.tenshu.control.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.lifecycle.DesktopControlSettings
import app.tenshu.control.lifecycle.WindowsStartupTask.State
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.tenshu.ui.test.NoMotion
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StartAtLoginPromptTest {

    @get:Rule
    val composeTestRule = createComposeRule(NoMotion)

    private val dir = Files.createTempDirectory("tenshu-login-prompt").toFile()
    private val settings = DesktopControlSettings(File(dir, "control.settings.properties"))

    // The task's calls run on a real IO thread, so each wait below is on the thing itself, in real time.
    private val reads = AtomicInteger()
    private val enables = AtomicInteger()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun show(state: State, afterEnable: State = State.ENABLED) {
        composeTestRule.setContent {
            StartAtLoginPrompt(
                active = true,
                settings = settings,
                readState = {
                    reads.incrementAndGet()
                    state
                },
                enable = {
                    enables.incrementAndGet()
                    afterEnable
                },
            )
        }
    }

    private fun awaitShown(text: String) = composeTestRule.waitUntilAtLeastOneExists(hasText(text), TIMEOUT_MS)

    private fun awaitGone(text: String) = composeTestRule.waitUntilDoesNotExist(hasText(text), TIMEOUT_MS)

    private fun awaitRead() {
        composeTestRule.waitUntil(TIMEOUT_MS) { reads.get() > 0 }
        composeTestRule.waitForIdle()
    }

    @Test
    fun onlyAnUndecidedOffTaskIsAsked() {
        assertEquals(StartAtLoginPromptDecision.ASK, startAtLoginPromptDecision(State.DISABLED))
        assertEquals(StartAtLoginPromptDecision.NOT_NOW, startAtLoginPromptDecision(State.UNAVAILABLE))
        for (state in listOf(State.ENABLED, State.ENABLED_BY_POLICY, State.DISABLED_BY_USER, State.DISABLED_BY_POLICY)) {
            assertEquals(StartAtLoginPromptDecision.NEVER, startAtLoginPromptDecision(state), "$state")
        }
    }

    @Test
    fun acceptingTurnsItOnAndIsRemembered() {
        show(State.DISABLED)
        awaitShown(TITLE)

        composeTestRule.onNodeWithText("Start when I sign in").performClick()
        awaitGone(TITLE)

        assertEquals(1, enables.get())
        assertTrue(settings.startAtLoginPromptAnswered())
    }

    @Test
    fun decliningNeverTurnsItOnAndIsRemembered() {
        show(State.DISABLED)
        awaitShown(TITLE)

        composeTestRule.onNodeWithText("Not now").performClick()
        awaitGone(TITLE)

        assertEquals(0, enables.get())
        assertTrue(settings.startAtLoginPromptAnswered())
    }

    @Test
    fun anAnsweredPromptIsNotAskedAgain() {
        settings.setStartAtLoginPromptAnswered(true)
        show(State.DISABLED)
        composeTestRule.waitForIdle()

        assertEquals(0, reads.get(), "a settled question does not even start the agent")
        composeTestRule.onAllNodesWithText(TITLE).assertCountEquals(0)
    }

    @Test
    fun aRefusedEnableSaysSoInsteadOfClaimingItWorked() {
        show(State.DISABLED, afterEnable = State.DISABLED_BY_POLICY)
        awaitShown(TITLE)

        composeTestRule.onNodeWithText("Start when I sign in").performClick()

        awaitShown("Couldn't turn this on")
        assertTrue(settings.startAtLoginPromptAnswered())
    }

    @Test
    fun anUnreadableTaskAsksNothingAndRecordsNothing() {
        show(State.UNAVAILABLE)
        awaitRead()

        composeTestRule.onAllNodesWithText(TITLE).assertCountEquals(0)
        assertFalse(settings.startAtLoginPromptAnswered())
    }

    @Test
    fun aHiddenWindowIsNotAskedUntilItIsShown() {
        var active by mutableStateOf(false)
        composeTestRule.setContent {
            StartAtLoginPrompt(
                active = active,
                settings = settings,
                readState = {
                    reads.incrementAndGet()
                    State.DISABLED
                },
                enable = { State.ENABLED },
            )
        }
        composeTestRule.waitForIdle()
        assertEquals(0, reads.get(), "a hidden window must not even look")
        assertFalse(settings.startAtLoginPromptAnswered())

        active = true
        awaitShown(TITLE)
    }

    private companion object {
        const val TITLE = "Keep syncing in the background?"
        const val TIMEOUT_MS = 10_000L
    }
}
