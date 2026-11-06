package app.tenshu.control.ui.screens.settings.ingest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.IngestSourceModel
import org.junit.Rule
import org.junit.Test
import org.tenshu.core.ingest.IngestSource
import kotlin.test.assertEquals

class BackgroundIngestScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun source(enabled: Boolean = true) = IngestSourceModel(
        key = "drive.gallery@dev1",
        sourceId = "drive.gallery",
        appId = "app.tenshu.drive",
        deviceId = "dev1",
        displayName = "Photos & Videos",
        sourceKind = IngestSource.KIND_GALLERY,
        enabled = enabled,
        status = IngestSource.STATUS_UP_TO_DATE,
        pendingCount = 0,
        watchedPaths = "",
    )

    @Test
    fun rendersSourceRow() {
        composeTestRule.setContent {
            BackgroundIngestScreenContent(
                uiState = BackgroundIngestUiState(loading = false, sources = listOf(source())),
                onEnabledChange = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Photos & Videos").assertIsDisplayed()
        composeTestRule.onNodeWithText("Up to date").assertIsDisplayed()
    }

    @Test
    fun togglingRowInvokesCallbackWithNegation() {
        var captured: Pair<String, Boolean>? = null
        composeTestRule.setContent {
            BackgroundIngestScreenContent(
                uiState = BackgroundIngestUiState(loading = false, sources = listOf(source(enabled = true))),
                onEnabledChange = { src, enabled -> captured = src.sourceId to enabled },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Photos & Videos").performClick()
        assertEquals("drive.gallery" to false, captured)
    }

    @Test
    fun showsEmptyState() {
        composeTestRule.setContent {
            BackgroundIngestScreenContent(
                uiState = BackgroundIngestUiState(loading = false, sources = emptyList()),
                onEnabledChange = { _, _ -> },
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("No background ingest yet").assertIsDisplayed()
    }
}
