package app.tenshu.drive.ui.common.uploadconflict

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsUiState
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.addfileactions.UploadConflict
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test

/**
 * The single-file upload's feedback surface. Only the conflict half existed before — every other
 * failure fell into an `else -> Unit` and the picked file silently never appeared.
 */
@OptIn(ExperimentalTestApi::class)
class UploadFeedbackTest {

    @Test
    fun aConflictOffersKeepBoth() = runComposeUiTest {
        setContent {
            TenshuTheme {
                UploadFeedback(
                    uiState = AddFileActionsUiState(
                        uploadConflict = UploadConflict("uri", "/dest", "resume.pdf"),
                    ),
                    onKeepBoth = {},
                    onCancelConflict = {},
                    onDismissStorageGate = {},
                    onDismissUploadError = {},
                )
            }
        }

        onNodeWithText("Keep both").assertIsDisplayed()
    }

    @Test
    fun aFailedUploadIsReported() = runComposeUiTest {
        setContent {
            TenshuTheme {
                UploadFeedback(
                    uiState = AddFileActionsUiState(
                        uploadError = AddFileActionsViewModel.ERROR_UPLOAD_FAILED,
                    ),
                    onKeepBoth = {},
                    onCancelConflict = {},
                    onDismissStorageGate = {},
                    onDismissUploadError = {},
                )
            }
        }

        onNodeWithText("Upload failed").assertIsDisplayed()
        onNodeWithText("Couldn't upload this file. Please try again.").assertIsDisplayed()
    }

    @Test
    fun runningOutOfCopyNamesIsItsOwnMessage() = runComposeUiTest {
        setContent {
            TenshuTheme {
                UploadFeedback(
                    uiState = AddFileActionsUiState(
                        uploadError = AddFileActionsViewModel.ERROR_TOO_MANY_COPIES,
                    ),
                    onKeepBoth = {},
                    onCancelConflict = {},
                    onDismissStorageGate = {},
                    onDismissUploadError = {},
                )
            }
        }

        // Not the generic fallback: after 99 attempts under "name (n)", the names are the problem
        // and the message has to say which lever the user has.
        onNodeWithText(
            "There are already too many copies of this file here. Rename it or choose a different folder."
        ).assertIsDisplayed()
    }

    @Test
    fun nothingIsShownWhenThereIsNothingToSay() = runComposeUiTest {
        setContent {
            TenshuTheme {
                UploadFeedback(
                    uiState = AddFileActionsUiState(),
                    onKeepBoth = {},
                    onCancelConflict = {},
                    onDismissStorageGate = {},
                    onDismissUploadError = {},
                )
            }
        }

        onNodeWithText("Upload failed").assertDoesNotExist()
        onNodeWithText("Keep both").assertDoesNotExist()
    }
}
