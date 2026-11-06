package app.tenshu.control.integration

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.tenshu.control.integration.utils.startAppIntegrationKoin
import app.tenshu.control.integration.utils.stopAppIntegrationKoin
import app.tenshu.control.ui.screens.settings.SettingsScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

class SettingsScreenIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Before
    fun setup() {
        startAppIntegrationKoin(clientId = "app.tenshu.control.test")
    }

    @After
    fun tearDown() {
        viewModelStoreOwner.viewModelStore.clear()
        stopAppIntegrationKoin()
    }

    @Test
    fun testNavigationToSyncDashboard() {
        var navigated = false
        composeTestRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelStoreOwner
            ) {
                SettingsScreen(
                    onSyncClick = { navigated = true },
                    onBackgroundSyncClick = {},
                    onRecoverySetupClick = {},
                onForgetDeviceClick = {},
                    onUnlockClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Synchronization & Devices").performClick()
        assertTrue(navigated)
    }
}
