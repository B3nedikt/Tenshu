package app.tenshu.control.ui.screens.home

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the §7.2 Level 0 banner's two wordings and their actions. State (visibility, which
 * wording) lives in [RecoveryAffordanceViewModelTest].
 *
 * ⚠ The banner once said *"isn't synced anywhere"* and offered *"Add another device"* on a realm
 * that synced — copy left over from the device-count trigger. The `doesNotExist` checks below are
 * what keep either sentence from coming back into the phrase wording.
 */
class RecoveryAffordanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var saveClicks = 0
    private var syncClicks = 0
    private var dismissClicks = 0

    private fun show(localOnly: Boolean, width: Dp? = null) = composeTestRule.setContent {
        RecoveryAffordanceContent(
            localOnly = localOnly,
            onSavePhrase = { saveClicks += 1 },
            onSetUpSync = { syncClicks += 1 },
            onDismiss = { dismissClicks += 1 },
            modifier = if (width != null) Modifier.width(width) else Modifier,
        )
    }

    private fun boundsOf(text: String) = composeTestRule.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun phraseWording_asksForThePhrase_andClaimsNothingAboutSync() {
        show(localOnly = false)

        composeTestRule.onNodeWithText("Save your recovery phrase").assertIsDisplayed()
        composeTestRule.onNodeWithText("synced anywhere", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("only on this device", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Add another device").assertDoesNotExist()
        composeTestRule.onNodeWithText("Set up sync").assertDoesNotExist()

        composeTestRule.onNodeWithText("Save recovery phrase").performClick()
        assertEquals(1, saveClicks)
        assertEquals(0, syncClicks)
    }

    @Test
    fun localOnlyWording_leadsWithSync_andKeepsThePhraseReachable() {
        show(localOnly = true)

        composeTestRule.onNodeWithText("Your data is only on this device").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add another device").assertDoesNotExist()

        composeTestRule.onNodeWithText("Set up sync").performClick()
        composeTestRule.onNodeWithText("Save recovery phrase").performClick()
        assertEquals(1, syncClicks)
        assertEquals(1, saveClicks)
    }

    @Test
    fun laterDismisses() {
        show(localOnly = false)

        composeTestRule.onNodeWithText("Later").performClick()

        assertEquals(1, dismissClicks)
        assertEquals(0, saveClicks)
    }

    /** Room to spare: one row, primary at its right end — the other Home cards' layout. */
    @Test
    fun wide_actionsShareOneRow_primaryOnTheRight() {
        show(localOnly = false, width = 600.dp)

        val later = boundsOf("Later")
        val primary = boundsOf("Save recovery phrase")
        assertEquals(later.top, primary.top, "one row")
        assertTrue(primary.left > later.right, "primary to the right of Later")
    }

    /**
     * ⚠⚠ Too narrow for one row: the primary must stack on **top**. A `FlowRow` here wrapped the
     * last items down and put "Later" above the primary — this is the test that catches it.
     */
    @Test
    fun narrow_actionsStack_primaryOnTop() {
        show(localOnly = true, width = 300.dp)

        val primary = boundsOf("Set up sync")
        val phrase = boundsOf("Save recovery phrase")
        val later = boundsOf("Later")
        assertTrue(primary.bottom <= phrase.top, "primary above the secondary action")
        assertTrue(phrase.bottom <= later.top, "dismiss last")
    }
}
