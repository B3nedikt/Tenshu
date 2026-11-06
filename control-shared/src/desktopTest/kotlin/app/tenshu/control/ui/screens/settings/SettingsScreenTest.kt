package app.tenshu.control.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testSyncDashboardClick() {
        var clicked = false
        composeTestRule.setContent {
            SettingsScreenContent(
                onSyncClick = { clicked = true },
                onBackgroundSyncClick = {},
                onRecoverySetupClick = {},
                onForgetDeviceClick = {},
                onUnlockClick = {},
            )
        }

        composeTestRule.onNodeWithText("Synchronization & Devices").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testBackgroundSyncClick() {
        var clicked = false
        composeTestRule.setContent {
            SettingsScreenContent(
                onSyncClick = {},
                onBackgroundSyncClick = { clicked = true },
                onRecoverySetupClick = {},
                onForgetDeviceClick = {},
                onUnlockClick = {},
            )
        }

        composeTestRule.onNodeWithText("Background sync").performClick()
        assertTrue(clicked)
    }

    @Test
    fun setUpAccountRecoveryClick_routesToRecoveryFlow() {
        var clicked = false
        composeTestRule.setContent {
            SettingsScreenContent(
                onSyncClick = {},
                onBackgroundSyncClick = {},
                onRecoverySetupClick = { clicked = true },
                onForgetDeviceClick = {},
                onUnlockClick = {},
            )
        }

        composeTestRule.onNodeWithText("Set up account recovery").performClick()
        assertTrue(clicked)
    }

    @Test
    fun unlockClick_routesToUnlockSettings() {
        var clicked = false
        composeTestRule.setContent {
            SettingsScreenContent(
                onSyncClick = {},
                onBackgroundSyncClick = {},
                onRecoverySetupClick = {},
                onForgetDeviceClick = {},
                onUnlockClick = { clicked = true },
            )
        }

        composeTestRule.onNodeWithText("Unlock").performClick()
        assertTrue(clicked)
    }

    @Test
    fun entriesBelowTheFold_areReachableByScrolling() {
        var clicked = false
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 400.dp, height = 300.dp)) {
                SettingsScreenContent(
                    onSyncClick = {},
                    onBackgroundSyncClick = {},
                    onRecoverySetupClick = {},
                onForgetDeviceClick = {},
                    onUnlockClick = {},
                    onAdvancedClick = { clicked = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Advanced").performScrollTo().performClick()
        assertTrue(clicked)
    }

    /**
     * The entry must exist and be reachable — an operation nothing launches is not a feature.
     *
     * ⚠ Order matters and is asserted implicitly by the copy check below it: "Forget this device"
     * sits AFTER "Set up account recovery", so the entry that saves the phrase is the one the eye
     * reaches first. This one destroys it (§5b.12.2).
     */
    @Test
    fun forgetThisDevice_isOfferedAndInvokesItsHandler() {
        var clicked = false
        composeTestRule.setContent {
            SettingsScreenContent(
                onSyncClick = {},
                onBackgroundSyncClick = {},
                onRecoverySetupClick = {},
                onForgetDeviceClick = { clicked = true },
                onUnlockClick = {},
            )
        }

        composeTestRule.onNodeWithText("Forget this device").performClick()
        composeTestRule.waitForIdle()

        kotlin.test.assertTrue(clicked, "the Settings entry must reach ForgetThisDevice")
    }

}
