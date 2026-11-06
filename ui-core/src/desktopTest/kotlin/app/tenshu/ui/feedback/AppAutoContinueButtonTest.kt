package app.tenshu.ui.feedback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import app.tenshu.ui.accessibility.AccessibilityPreferences
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `control-pairing-ux.md` §9: the auto-continue button counts down only while armed, never fires
 * once cancelled, and starts its countdown when it is armed late (the funding check settling).
 */
@OptIn(ExperimentalTestApi::class)
class AppAutoContinueButtonTest {

    @Test
    fun armedFiresOnceAfterTheDuration() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent { TenshuTheme { AppAutoContinueButton(text = "Continue", onContinue = { fired++ }, armed = true) } }

        mainClock.advanceTimeByFrame()
        onNodeWithTag(TAG_AUTO_CONTINUE_RING).assertIsDisplayed()
        onNodeWithText("Stay").assertIsDisplayed()

        mainClock.advanceTimeBy(AUTO_CONTINUE_DURATION_MS - 500)
        assertEquals(0, fired, "not before the duration")

        mainClock.advanceTimeBy(1_000)
        assertEquals(1, fired, "fires by itself once the countdown completes")

        mainClock.advanceTimeBy(10_000)
        assertEquals(1, fired, "and only once")
        assertEquals(0, onAllNodesWithText("Stay").fetchSemanticsNodes().size)
    }

    @Test
    fun unarmedNeverFires() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent { TenshuTheme { AppAutoContinueButton(text = "Continue", onContinue = { fired++ }, armed = false) } }

        mainClock.advanceTimeBy(10_000)
        assertEquals(0, fired)
        assertEquals(0, onAllNodesWithTag(TAG_AUTO_CONTINUE_RING).fetchSemanticsNodes().size, "no ring")
        assertEquals(0, onAllNodesWithText("Stay").fetchSemanticsNodes().size, "no Stay")
        onNodeWithText("Continue").performClick()
        assertEquals(1, fired, "the plain button still continues")
    }

    @Test
    fun stayCancelsTheCountdown() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent { TenshuTheme { AppAutoContinueButton(text = "Continue", onContinue = { fired++ }, armed = true) } }

        mainClock.advanceTimeBy(1_000)
        onNodeWithText("Stay").performClick()
        mainClock.advanceTimeBy(10_000)
        assertEquals(0, fired, "a cancelled countdown never fires")
        assertEquals(0, onAllNodesWithTag(TAG_AUTO_CONTINUE_RING).fetchSemanticsNodes().size)
        onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun aTapAnywhereOnTheScreenCancels() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent {
            TenshuTheme {
                val state = rememberAutoContinueState()
                Column(Modifier.appCancelAutoContinueOnTouch(state)) {
                    Box(Modifier.fillMaxWidth().height(100.dp).testTag("body")) { Text("Device added") }
                    AppAutoContinueButton(text = "Continue", onContinue = { fired++ }, armed = true, state = state)
                }
            }
        }

        mainClock.advanceTimeBy(1_000)
        onNodeWithTag("body").performClick()
        mainClock.advanceTimeBy(10_000)
        assertEquals(0, fired, "touching the screen means 'I'm reading this'")
    }

    @Test
    fun lateArmingStartsTheCountdownThen() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        var armed by mutableStateOf(false)
        setContent { TenshuTheme { AppAutoContinueButton(text = "Continue", onContinue = { fired++ }, armed = armed) } }

        mainClock.advanceTimeBy(6_000)
        assertEquals(0, fired)

        armed = true
        mainClock.advanceTimeBy(AUTO_CONTINUE_DURATION_MS - 500)
        assertEquals(0, fired, "the countdown starts at arming, not at composition")
        mainClock.advanceTimeBy(1_000)
        assertEquals(1, fired)
    }

    @Test
    fun aScreenReaderMeansNoAutoContinue() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent {
            TenshuTheme {
                AppAutoContinueButton(
                    text = "Continue",
                    onContinue = { fired++ },
                    armed = true,
                    accessibility = AccessibilityPreferences(screenReaderActive = true),
                )
            }
        }
        mainClock.advanceTimeBy(10_000)
        assertEquals(0, fired)
        assertEquals(0, onAllNodesWithText("Stay").fetchSemanticsNodes().size)
    }

    @Test
    fun reducedMotionDropsTheRingButKeepsTheTimer() = runComposeUiTest {
        mainClock.autoAdvance = false
        var fired = 0
        setContent {
            TenshuTheme {
                AppAutoContinueButton(
                    text = "Continue",
                    onContinue = { fired++ },
                    armed = true,
                    accessibility = AccessibilityPreferences(reduceMotion = true),
                )
            }
        }
        mainClock.advanceTimeByFrame()
        assertEquals(0, onAllNodesWithTag(TAG_AUTO_CONTINUE_RING).fetchSemanticsNodes().size, "no ring animation")
        onNodeWithText("Stay").assertIsDisplayed()
        mainClock.advanceTimeBy(AUTO_CONTINUE_DURATION_MS + 500)
        assertEquals(1, fired)
    }
}
