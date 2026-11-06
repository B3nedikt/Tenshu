package app.tenshu.control.ui.screens.services

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.tenshu.control.data.CommerceProducts
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountPickerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun rendersAllThreeTiers() {
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = {},
                onSubscribe = {},
                onHaveCode = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Free").assertExists()
        composeTestRule.onNodeWithText("Basic").assertExists()
        composeTestRule.onNodeWithText("Premium").assertExists()
        composeTestRule.onNodeWithText("Recommended").assertExists()
        composeTestRule.onNodeWithText("Have a code?").assertExists()
    }

    @Test
    fun freeCta_fires() {
        var clicks = 0
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = { clicks += 1 },
                onSubscribe = {},
                onHaveCode = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Continue").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun paidSubscribeCtas_fireWithTheirProductId() {
        val subscribed = mutableListOf<String>()
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = {},
                onSubscribe = { subscribed += it },
                onHaveCode = {},
                onBack = {},
            )
        }

        // Both Basic and Premium render an enabled "Subscribe"; clicking each reports its product id.
        val subscribe = composeTestRule.onAllNodesWithText("Subscribe")
        subscribe.assertCountEquals(2)
        subscribe[0].performScrollTo().performClick() // Basic (first paid card)
        subscribe[1].performScrollTo().performClick() // Premium
        assertEquals(listOf(CommerceProducts.BASIC, CommerceProducts.PREMIUM), subscribed)
    }

    @Test
    fun paidTiers_hiddenWhenShowPaidTiersFalse() {
        // Store-mobile builds pass showPaidTiers=false: no in-app Subscribe CTA, but Free + redeem stay.
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = {},
                onSubscribe = {},
                onHaveCode = {},
                onBack = {},
                showPaidTiers = false,
            )
        }

        composeTestRule.onNodeWithText("Free").assertExists()
        composeTestRule.onNodeWithText("Have a code?").assertExists()
        composeTestRule.onAllNodesWithText("Basic").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Premium").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Subscribe").assertCountEquals(0)
    }

    @Test
    fun haveCode_fires() {
        var codeClicks = 0
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = {},
                onSubscribe = {},
                onHaveCode = { codeClicks += 1 },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Have a code?").performScrollTo().performClick()
        assertEquals(1, codeClicks)
    }

    @Test
    fun cancel_fires() {
        var cancels = 0
        composeTestRule.setContent {
            AccountPickerScreen(
                onPickFree = {},
                onSubscribe = {},
                onHaveCode = {},
                onBack = { cancels += 1 },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(cancels == 1)
    }
}
