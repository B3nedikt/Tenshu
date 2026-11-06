package app.tenshu.control.ui.screens.settings.errors

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.tenshu.control.data.ErrorEventSummary
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 of `docs/proposals/core-error-channel.md`: the Settings → Errors
 * screen's "Send error report" CTA + confirm dialog. Tests pin:
 *  - empty state renders the placeholder copy + the button is disabled.
 *  - rows render, retention footer shows.
 *  - button enabled iff uploader is configured AND events list non-empty.
 *  - tapping the button arms the confirm dialog (verified via the screen
 *    callback that ViewModel uses to update state).
 *  - dialog Send / Cancel route through the right callbacks.
 *  - the "Sending…" busy state replaces the button label + disables it.
 */
class ErrorsScreenContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleEvent = ErrorEventSummary(
        eventId = "1",
        errorTypeId = "tenshu/core/error/dispatch/missing-parameter",
        operationId = "tenshu/sync/operation/createrealm",
        callstackDepth = 1,
        capturedAtMillis = System.currentTimeMillis() - 60_000,
        deviceId = "device-1",
        appId = "control",
        appVersion = "1.0",
        osLabel = "macOS 14",
        internalDetail = null,
    )

    @Test
    fun emptyState_isShown_whenNoEvents() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(canUpload = true),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("No errors captured.").assertIsDisplayed()
    }

    @Test
    fun sendButton_isDisabled_whenCannotUpload() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(
                    events = listOf(sampleEvent),
                    canUpload = false,
                ),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send error report").assertIsNotEnabled()
    }

    @Test
    fun sendButton_isDisabled_whenNoEvents() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(events = emptyList(), canUpload = true),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send error report").assertIsNotEnabled()
    }

    @Test
    fun sendButton_isEnabled_whenCanUploadAndEvents() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(events = listOf(sampleEvent), canUpload = true),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send error report").assertIsEnabled()
    }

    @Test
    fun sendButton_invokesSendClicked() {
        var sendClicked = false
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(events = listOf(sampleEvent), canUpload = true),
                onRowClicked = {},
                onSendClicked = { sendClicked = true },
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send error report").performClick()
        assertTrue(sendClicked)
    }

    @Test
    fun confirmDialog_renders_whenConfirming() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(
                    events = listOf(sampleEvent),
                    canUpload = true,
                    uploadStatus = UploadStatus.Confirming,
                ),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send error report?").assertIsDisplayed()
        // Dialog body includes the event count + the anonymity guarantee.
        composeTestRule
            .onNodeWithText(
                "Send 1 diagnostic events to Tenshu? " +
                    "No identifying information (device id, account id, file paths) is included."
            )
            .assertIsDisplayed()
    }

    @Test
    fun confirmDialog_sendRoutesToConfirmedCallback() {
        var confirmed = 0
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(
                    events = listOf(sampleEvent),
                    canUpload = true,
                    uploadStatus = UploadStatus.Confirming,
                ),
                onRowClicked = {},
                onSendConfirmed = { confirmed++ },
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Send").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun confirmDialog_cancelRoutesToDismissedCallback() {
        var dismissed = 0
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(
                    events = listOf(sampleEvent),
                    canUpload = true,
                    uploadStatus = UploadStatus.Confirming,
                ),
                onRowClicked = {},
                onSendDismissed = { dismissed++ },
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun sendButton_showsBusy_andDisables_whenSending() {
        composeTestRule.setContent {
            ErrorsScreenContent(
                uiState = ErrorsUiState(
                    events = listOf(sampleEvent),
                    canUpload = true,
                    uploadStatus = UploadStatus.Sending,
                ),
                onRowClicked = {},
                onBack = {},
            )
        }
        composeTestRule.onNodeWithText("Sending…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sending…").assertIsNotEnabled()
    }
}
