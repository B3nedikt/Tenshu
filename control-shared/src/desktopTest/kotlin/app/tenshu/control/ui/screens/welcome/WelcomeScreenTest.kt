package app.tenshu.control.ui.screens.welcome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the §4.0 redesign: equal-weight CTAs ("Start fresh on this device"
 * + "I have Tenshu on another device"), the reworded body, and the
 * recovery tertiary. Each CTA's click handler is asserted so a future
 * navigation refactor can't silently rewire the chooser.
 */
class WelcomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersTitleAndRewordedBody() {
        composeTestRule.setContent {
            WelcomeScreen(onExistingDevice = {}, onNewUser = {}, onRecover = {})
        }

        composeTestRule.onNodeWithText("Welcome to Tenshu").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Tenshu is a private place for your files.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun startFreshCtaInvokesNewUserCallback() {
        var newUserClicks = 0
        var existingClicks = 0
        var recoverClicks = 0
        composeTestRule.setContent {
            WelcomeScreen(
                onExistingDevice = { existingClicks += 1 },
                onNewUser = { newUserClicks += 1 },
                onRecover = { recoverClicks += 1 },
            )
        }

        composeTestRule.onNodeWithText("Start fresh on this device").performClick()

        assertEquals(1, newUserClicks)
        assertEquals(0, existingClicks)
        assertEquals(0, recoverClicks)
    }

    @Test
    fun existingDeviceCtaInvokesExistingCallback() {
        var newUserClicks = 0
        var existingClicks = 0
        composeTestRule.setContent {
            WelcomeScreen(
                onExistingDevice = { existingClicks += 1 },
                onNewUser = { newUserClicks += 1 },
                onRecover = {},
            )
        }

        composeTestRule.onNodeWithText("I have Tenshu on another device").performClick()

        assertEquals(1, existingClicks)
        assertEquals(0, newUserClicks)
    }

    @Test
    fun recoverTextButtonStillReachable() {
        var recoverClicks = 0
        composeTestRule.setContent {
            WelcomeScreen(
                onExistingDevice = {},
                onNewUser = {},
                onRecover = { recoverClicks += 1 },
            )
        }

        composeTestRule.onNodeWithText("Recover from a recovery phrase").performClick()

        assertTrue(recoverClicks == 1)
    }

    /**
     * ⚠⚠ The forget outcome's only user-visible home. `ForgetThisDevice` never blocks the local wipe
     * on a retirement it could not complete, so this string is the one thing that can tell the user
     * a row was left behind — and its remedy is always *"from another device"*, which is precisely
     * why it lands here: they have just been dropped on this screen with nothing else to look at.
     *
     * ⚠ A modal was tried first and was invisible on macOS three fixes running; a banner on the
     * screen the flow already reaches needs no raising to be seen.
     */
    @Test
    fun aWarningOutcome_isShownOnTheWelcomeScreen() {
        val leftover = "forgotten — ⚠ this device is still listed in your realm — " +
            "remove it from another device; 3 queued item(s) dropped; local store dropped"
        composeTestRule.setContent {
            WelcomeScreen(
                onExistingDevice = {},
                onNewUser = {},
                onRecover = {},
                notice = leftover,
            )
        }

        // Verbatim: the remedy lives inside the string the op built, so paraphrasing would drop the
        // only actionable half.
        composeTestRule.onNodeWithText(leftover).assertIsDisplayed()
        // ⚠ And it does not replace the chooser — the user still has to be able to start over.
        composeTestRule.onNodeWithText("Start fresh on this device").assertIsDisplayed()
    }

    /**
     * ⚠⚠ The half that rots quietly. A clean removal must show nothing: a warning banner on every
     * successful fresh start is how people learn to ignore warnings, and this screen is the one
     * place a user arrives at *expecting* everything to be fine.
     */
    @Test
    fun noNotice_leavesTheChooserUnadorned() {
        composeTestRule.setContent {
            WelcomeScreen(onExistingDevice = {}, onNewUser = {}, onRecover = {}, notice = null)
        }

        composeTestRule.onNodeWithText("Welcome to Tenshu").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("⚠", substring = true).assertCountEquals(0)
    }

}
