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
     * ⚠⚠ The forget outcome's only user-visible home — in plain words, never the op's own string.
     *
     * The outcome below is verbatim what a real phone's forget returned (2026-11-12): shown as-is it
     * was a red "forgotten — ⚠ …; 177 queued item(s) dropped; local store dropped" banner on the one
     * screen a user reaches expecting everything to be fine.
     *
     * ⚠ A modal was tried first and was invisible on macOS three fixes running; a banner on the
     * screen the flow already reaches needs no raising to be seen.
     */
    @Test
    fun aLeftoverRow_isShownInPlainWords_notAsTheOutcomeString() {
        val outcome = "forgotten — ⚠ this device is still listed in your realm — " +
            "remove it from another device; 177 queued item(s) dropped; local store dropped"
        composeTestRule.setContent {
            WelcomeScreen(
                onExistingDevice = {},
                onNewUser = {},
                onRecover = {},
                notices = forgetNoticesFor(outcome),
            )
        }

        composeTestRule
            .onNodeWithText("This device may still appear in your device list.", substring = true)
            .assertIsDisplayed()
        // The remedy survives the rewording: where to go, and what to do there.
        composeTestRule
            .onNodeWithText("open Tenshu Control on another device and remove it", substring = true)
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("queued item", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("⚠", substring = true).assertCountEquals(0)
        // ⚠ And it does not replace the chooser — the user still has to be able to start over.
        composeTestRule.onNodeWithText("Start fresh on this device").assertIsDisplayed()
    }

    @Test
    fun aForgetThatDidNotFinish_saysToTryAgain() {
        composeTestRule.setContent {
            WelcomeScreen(onExistingDevice = {}, onNewUser = {}, onRecover = {}, notices = listOf(ForgetNotice.NotFinished))
        }

        composeTestRule.onNodeWithText("didn't finish", substring = true).assertIsDisplayed()
    }

    /** Each outcome the op can produce, mapped. A clean one maps to nothing. */
    @Test
    fun forgetNoticesFor_mapsEveryOutcome() {
        assertEquals(
            emptyList(),
            forgetNoticesFor("forgotten — removed from your realm; 3 queued item(s) dropped; local store dropped"),
        )
        assertEquals(emptyList(), forgetNoticesFor("forgotten — no realm to leave; queues not reachable; local store dropped"))
        assertEquals(
            listOf(ForgetNotice.MayStillBeListed),
            forgetNoticesFor(
                "forgotten — ⚠ could not confirm this device left your realm — check your devices from " +
                    "another one; 0 queued item(s) dropped; local store dropped",
            ),
        )
        assertEquals(
            listOf(ForgetNotice.NotFinished),
            forgetNoticesFor(
                "⚠ not finished — ⚠ this device is still listed in your realm — remove it from another " +
                    "device; 2 queued item(s) dropped; the local store's wipe did not finish, try again",
            ),
        )
        assertEquals(
            listOf(ForgetNotice.DataNotCleared),
            forgetNoticesFor("forgotten — removed from your realm; queues not reachable; ⚠ local store NOT dropped"),
        )
        // ⚠⚠ A key that could not be saved is its own notice and does NOT stand in for the others:
        // custody is already gone, so this outcome is the only report of the row and the store.
        assertEquals(
            listOf(ForgetNotice.KeyNotSaved),
            forgetNoticesFor(
                "forgotten — removed from your realm; 0 queued item(s) dropped; local store dropped; " +
                    "⚠ new device key NOT saved — quit and reopen Tenshu Control before pairing",
            ),
        )
        assertEquals(
            listOf(ForgetNotice.KeyNotSaved, ForgetNotice.DataNotCleared, ForgetNotice.MayStillBeListed),
            forgetNoticesFor(
                "forgotten — ⚠ this device is still listed in your realm — remove it from another device; " +
                    "0 queued item(s) dropped; ⚠ local store NOT dropped; " +
                    "⚠ new device key NOT saved — quit and reopen Tenshu Control before pairing",
            ),
        )
        // No outcome at all is unknown, never success.
        assertEquals(listOf(ForgetNotice.MayStillBeListed), forgetNoticesFor(null))
    }

    /**
     * ⚠⚠ Both problems at once, and both must be said. Custody is already gone here, so a second
     * forget would never report the row again — showing only the store notice hides it for good.
     */
    @Test
    fun aLeftoverRowAndAnUnclearedStore_areBothShown() {
        val outcome = "forgotten — ⚠ this device is still listed in your realm — remove it from another " +
            "device; 4 queued item(s) dropped; ⚠ local store NOT dropped"
        assertEquals(listOf(ForgetNotice.DataNotCleared, ForgetNotice.MayStillBeListed), forgetNoticesFor(outcome))

        composeTestRule.setContent {
            WelcomeScreen(onExistingDevice = {}, onNewUser = {}, onRecover = {}, notices = forgetNoticesFor(outcome))
        }
        composeTestRule.onNodeWithText("couldn't be removed from this device", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("may still appear in your device list", substring = true).assertIsDisplayed()
    }

    /**
     * ⚠⚠ The half that rots quietly. A clean removal must show nothing: a warning banner on every
     * successful fresh start is how people learn to ignore warnings, and this screen is the one
     * place a user arrives at *expecting* everything to be fine.
     */
    @Test
    fun noNotice_leavesTheChooserUnadorned() {
        composeTestRule.setContent {
            WelcomeScreen(onExistingDevice = {}, onNewUser = {}, onRecover = {}, notices = emptyList())
        }

        composeTestRule.onNodeWithText("Welcome to Tenshu").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("⚠", substring = true).assertCountEquals(0)
    }

}
