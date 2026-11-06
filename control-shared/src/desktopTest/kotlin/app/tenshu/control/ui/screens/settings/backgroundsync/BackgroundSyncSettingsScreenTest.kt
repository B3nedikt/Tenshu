package app.tenshu.control.ui.screens.settings.backgroundsync

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundSyncSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysCurrentValues() {
        composeTestRule.setContent {
            BackgroundSyncSettingsScreenContent(
                uiState = BackgroundSyncUiState(wifiOnly = true),
                onWifiOnlyChange = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Use Wi-Fi only for background data").assertIsDisplayed()
    }

    @Test
    fun togglingWifiRowInvokesCallbackWithNegation() {
        var toggled: Boolean? = null
        composeTestRule.setContent {
            BackgroundSyncSettingsScreenContent(
                uiState = BackgroundSyncUiState(wifiOnly = false),
                onWifiOnlyChange = { toggled = it },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Use Wi-Fi only for background data").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun backButtonInvokesCallback() {
        var backed = false
        composeTestRule.setContent {
            BackgroundSyncSettingsScreenContent(
                uiState = BackgroundSyncUiState(),
                onWifiOnlyChange = {},
                onBack = { backed = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }
}
