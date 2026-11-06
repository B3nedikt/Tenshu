package app.tenshu.drive.ui.common.createfolder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test

/**
 * The create-folder dialog's failure states. The dialog used to read its error as a mere presence
 * flag, so *every* failure announced a name collision and offered *Keep both* — a remedy for a
 * problem the user might not have.
 */
@OptIn(ExperimentalTestApi::class)
class CreateFolderDialogContentTest {

    private fun state(
        name: String = "Holiday",
        canCreate: Boolean = true,
        isCreating: Boolean = false,
        errorMessage: String? = null,
    ) = CreateFolderDialogUiState(
        folderName = name,
        canCreate = canCreate,
        isCreating = isCreating,
        errorMessage = errorMessage,
    )

    @Test
    fun aCollisionOffersKeepBoth() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(errorMessage = CreateFolderDialogViewModel.ERROR_ALREADY_EXISTS),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Name already taken. Keep both or choose a different name.").assertIsDisplayed()
        onNodeWithTag("create_folder_keep_both").assertIsDisplayed()
    }

    @Test
    fun aNonCollisionFailureDoesNotOfferKeepBoth() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(
                        errorMessage = CreateFolderDialogViewModel.ERROR_INSUFFICIENT_STORAGE,
                    ),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        // Its own message, and no counter remedy — "Holiday (1)" would fail for the same reason.
        onNodeWithText("Not enough storage to create this folder.").assertIsDisplayed()
        onNodeWithTag("create_folder_keep_both").assertDoesNotExist()
        onNodeWithTag("create_folder_confirm").assertIsDisplayed()
    }

    @Test
    fun aSeparatorInTheNameIsItsOwnMessage() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(
                        name = "a/b",
                        canCreate = false,
                        errorMessage = CreateFolderDialogViewModel.ERROR_NAME_HAS_SEPARATOR,
                    ),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText(
            "A name cannot contain a slash (/). Create the folder, then open it to add folders inside."
        ).assertIsDisplayed()
        onNodeWithTag("create_folder_keep_both").assertDoesNotExist()
        onNodeWithTag("create_folder_confirm").assertIsNotEnabled()
    }

    @Test
    fun anEmptyNameDisablesConfirm() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(name = "", canCreate = false),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        // The dialog opens in exactly this state, and Confirm used to be live — creating a folder
        // with no name at all.
        onNodeWithTag("create_folder_confirm").assertIsNotEnabled()
    }

    @Test
    fun aNamedFolderEnablesConfirm() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("create_folder_confirm").assertIsEnabled()
    }

    @Test
    fun confirmIsDisabledWhileTheWriteIsInFlight() = runComposeUiTest {
        setContent {
            TenshuTheme {
                CreateFolderDialogContent(
                    uiState = state(isCreating = true),
                    onFolderNameChanged = {},
                    onConfirm = {},
                    onKeepBoth = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("create_folder_confirm").assertIsNotEnabled()
    }
}
