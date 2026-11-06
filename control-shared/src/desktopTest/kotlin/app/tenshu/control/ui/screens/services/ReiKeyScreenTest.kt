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
 * §2.3 redemption-code entry: Content composable surface. The redeem-result behaviour is exercised
 * by [app.tenshu.control.data.MockPublisherClientPhase3bTest].
 */
class ReiKeyScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersExplainerCopy() {
        composeTestRule.setContent {
            ReiKeyScreenContent(
                key = "",
                busy = false,
                error = null,
                onKeyChange = {},
                onContinue = {},
                onCancel = {},
            )
        }

        composeTestRule.onNodeWithText("Have a code?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Redemption code").assertIsDisplayed()
    }

    @Test
    fun continueDisabledOnBlankKey() {
        composeTestRule.setContent {
            ReiKeyScreenContent(
                key = "",
                busy = false,
                error = null,
                onKeyChange = {},
                onContinue = {},
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Redeem").assertIsNotEnabled()
    }

    @Test
    fun continueFires_whenKeyEntered() {
        var continues = 0
        composeTestRule.setContent {
            ReiKeyScreenContent(
                key = "FOUNDER-1234",
                busy = false,
                error = null,
                onKeyChange = {},
                onContinue = { continues += 1 },
                onCancel = {},
            )
        }
        composeTestRule.onNodeWithText("Redeem").performClick()
        assertEquals(1, continues)
    }

    @Test
    fun invalidCodeError_surfaces() {
        composeTestRule.setContent {
            ReiKeyScreenContent(
                key = "BOGUS",
                busy = false,
                error = "This code isn't valid. Check it and try again.",
                onKeyChange = {},
                onContinue = {},
                onCancel = {},
            )
        }
        composeTestRule
            .onNodeWithText("isn't valid", substring = true)
            .assertIsDisplayed()
    }
}
