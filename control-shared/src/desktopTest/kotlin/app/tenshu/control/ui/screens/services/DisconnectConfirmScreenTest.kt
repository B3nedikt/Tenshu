package app.tenshu.control.ui.screens.services

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * §6.4 disconnect surface. Content composable level — the
 * passphrase-verification path lives in [DisconnectConfirmViewModel];
 * the screen here just dispatches callbacks.
 */
class DisconnectConfirmScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * ⚠⚠ The visible half of the unreachable-disconnect bug: with no local passphrase configured
     * the field must not be drawn at all. Rendering it was worse than useless — `unlockLocal`
     * returns false without an `UNLOCK_WRAPPER`, so the field could never be satisfied and the
     * screen gave no hint of that. The action must still be completable.
     */
    @Test
    fun withoutAPassphrase_theFieldIsNotRendered_andConfirmStillWorks() {
        var confirms = 0
        composeTestRule.setContent {
            DisconnectConfirmScreenContent(
                passphrase = "",
                requiresPassphrase = false,
                busy = false,
                error = null,
                onPassphraseChange = {},
                onConfirm = { confirms += 1 },
                onCancel = {},
            )
        }

        composeTestRule
            .onNodeWithText("Enter your passphrase to confirm")
            .assertDoesNotExist()
        // The destructive warning still has to be read, so the confirm step stays.
        composeTestRule
            .onNodeWithText("Your other devices will stop syncing", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Yes, disconnect").performClick()
        assertEquals(1, confirms)
    }

    @Test
    fun rendersDestructiveWarning() {
        composeTestRule.setContent {
            DisconnectConfirmScreenContent(
                passphrase = "",
                requiresPassphrase = true,
                busy = false,
                error = null,
                onPassphraseChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        composeTestRule
            .onNodeWithText("Disconnect from Tenshu Storage?")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Your other devices will stop syncing", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun cancel_firesCallback() {
        var cancels = 0
        composeTestRule.setContent {
            DisconnectConfirmScreenContent(
                passphrase = "",
                requiresPassphrase = true,
                busy = false,
                error = null,
                onPassphraseChange = {},
                onConfirm = {},
                onCancel = { cancels += 1 },
            )
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun disconnectDisabledOnBlankPassphrase() {
        composeTestRule.setContent {
            DisconnectConfirmScreenContent(
                passphrase = "",
                requiresPassphrase = true,
                busy = false,
                error = null,
                onPassphraseChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Yes, disconnect").assertIsNotEnabled()
    }

    @Test
    fun disconnectFires_whenPassphraseEntered() {
        var confirms = 0
        composeTestRule.setContent {
            DisconnectConfirmScreenContent(
                passphrase = "supersecret",
                requiresPassphrase = true,
                busy = false,
                error = null,
                onPassphraseChange = {},
                onConfirm = { confirms += 1 },
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Yes, disconnect").performClick()
        assertEquals(1, confirms)
    }
}
