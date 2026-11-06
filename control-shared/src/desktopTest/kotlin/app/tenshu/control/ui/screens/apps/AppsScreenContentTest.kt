package app.tenshu.control.ui.screens.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.InstalledApp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/** Stateless `AppsScreenContent` rendering + interaction (no VM / proxy). */
class AppsScreenContentTest {

    @get:Rule
    val rule = createComposeRule()

    private val drive = InstalledApp(
        appId = "app.tenshu.drive",
        name = "Drive",
        iconName = "drive",
        version = "1.0.0",
        shortDescription = "Files and folders.",
        installedAt = 1_716_000_000_000L,
        verified = true,
        deviceCount = 2,
    )
    private val devApp = drive.copy(
        appId = "dev.example.notes",
        name = "Notes (dev build)",
        verified = false,
    )

    @Test
    fun rendersCardsForEachApp() {
        rule.setContent {
            AppsScreenContent(
                AppsUiState(apps = listOf(drive, devApp), loading = false),
                onAppClick = {},
                onConnectApp = {},
            )
        }
        rule.onNodeWithText("Drive").assertIsDisplayed()
        rule.onNodeWithText("Notes (dev build)").assertIsDisplayed()
    }

    @Test
    fun showsUnverifiedBadgeForDevBuild() {
        rule.setContent {
            AppsScreenContent(
                AppsUiState(apps = listOf(devApp), loading = false),
                onAppClick = {},
                onConnectApp = {},
            )
        }
        rule.onNodeWithText("Unverified").assertIsDisplayed()
    }

    @Test
    fun emptyState_whenNoAppsAndLoaded() {
        rule.setContent {
            AppsScreenContent(
                AppsUiState(apps = emptyList(), loading = false),
                onAppClick = {},
                onConnectApp = {},
            )
        }
        rule.onNodeWithText("No apps yet").assertIsDisplayed()
    }

    @Test
    fun tappingCard_emitsAppId() {
        var clicked: String? = null
        rule.setContent {
            AppsScreenContent(
                AppsUiState(apps = listOf(drive), loading = false),
                onAppClick = { clicked = it },
                onConnectApp = {},
            )
        }
        rule.onNodeWithText("Drive").performClick()
        assertEquals("app.tenshu.drive", clicked)
    }

    @Test
    fun connectAction_isShownEvenWhenEmpty_andEmitsOnTap() {
        var connected = false
        rule.setContent {
            AppsScreenContent(
                AppsUiState(apps = emptyList(), loading = false),
                onAppClick = {},
                onConnectApp = { connected = true },
            )
        }
        rule.onNodeWithText("Connect an app on another device").performClick()
        assertEquals(true, connected)
    }
}
