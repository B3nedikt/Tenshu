package app.tenshu.drive.ui.common.rename

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
 * The rename dialog's two refusal-shaped states, which had no UI at all before: a rename can be
 * rejected (the parent already holds that name), and the current name is not submittable.
 */
@OptIn(ExperimentalTestApi::class)
class RenameFileDialogContentTest {

    @Test
    fun aRefusalIsShownAgainstTheField() = runComposeUiTest {
        setContent {
            TenshuTheme {
                RenameFileDialogContent(
                    uiState = RenameFileDialogUiState(
                        newName = "budget.pdf",
                        canRename = true,
                        errorMessage = RenameFileDialogViewModel.ERROR_ALREADY_EXISTS,
                    ),
                    onNewNameChanged = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // The view model reports a token; this is the localised copy the screen owns (ADR 0016).
        onNodeWithText("An item with this name already exists here.").assertIsDisplayed()
        // …and the dialog is still up, which is the whole point — it used to close on the tap, so
        // a refusal was indistinguishable from a rename that worked.
        onNodeWithTag("rename_field").assertIsDisplayed()
    }

    @Test
    fun aSeparatorInTheNameIsItsOwnMessage() = runComposeUiTest {
        setContent {
            TenshuTheme {
                RenameFileDialogContent(
                    uiState = RenameFileDialogUiState(
                        newName = "sub/budget.pdf",
                        canRename = false,
                        errorMessage = RenameFileDialogViewModel.ERROR_NAME_HAS_SEPARATOR,
                    ),
                    onNewNameChanged = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // Its own copy, not the generic fallback: an unmapped token would render "Couldn't rename
        // the item", which says nothing about what to change.
        onNodeWithText(
            "A name cannot contain a slash (/). Use Move to put the item in another folder."
        ).assertIsDisplayed()
        onNodeWithTag("rename_confirm").assertIsNotEnabled()
    }

    @Test
    fun anUnsubmittableNameDisablesConfirm() = runComposeUiTest {
        setContent {
            TenshuTheme {
                RenameFileDialogContent(
                    // canRename = false is what the view model reports for the file's OWN name
                    // (and for a blank one): `MoveFile` rejects a target that exists, and the
                    // file's own path exists, so submitting it would report a collision with
                    // itself.
                    uiState = RenameFileDialogUiState(newName = "budget.pdf", canRename = false),
                    onNewNameChanged = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("rename_confirm").assertIsNotEnabled()
    }

    @Test
    fun aChangedNameEnablesConfirm() = runComposeUiTest {
        setContent {
            TenshuTheme {
                RenameFileDialogContent(
                    uiState = RenameFileDialogUiState(newName = "budget-2026.pdf", canRename = true),
                    onNewNameChanged = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("rename_confirm").assertIsEnabled()
    }

    @Test
    fun confirmIsDisabledWhileTheWriteIsInFlight() = runComposeUiTest {
        setContent {
            TenshuTheme {
                RenameFileDialogContent(
                    uiState = RenameFileDialogUiState(
                        newName = "budget-2026.pdf",
                        canRename = true,
                        isRenaming = true,
                    ),
                    onNewNameChanged = {},
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("rename_confirm").assertIsNotEnabled()
    }
}
