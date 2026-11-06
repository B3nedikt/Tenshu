package app.tenshu.ui.chrome

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The [AppDialog] wrapper's contract.
 *
 * Worth pinning because the component used to be a **fork** of Material's `AlertDialog` — a
 * hand-rolled `Dialog { Card { … } }` justified by a desktop-focus claim that was never true
 * (see the KDoc). Now that it delegates, these tests are what stop it drifting back into a
 * reimplementation: they assert the shape callers depend on, not the Material internals.
 */
@OptIn(ExperimentalTestApi::class)
class AppDialogTest {

    @Test
    fun rendersTitleBodyAndBothButtons() = runComposeUiTest {
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "Delete file?",
                    onDismissRequest = {},
                    confirmLabel = "Delete",
                    onConfirm = {},
                    dismissLabel = "Cancel",
                    content = { Text("This removes report.pdf.") },
                )
            }
        }

        onNodeWithText("Delete file?").assertIsDisplayed()
        onNodeWithText("This removes report.pdf.").assertIsDisplayed()
        onNodeWithText("Delete").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun aNullDismissLabelRendersOneButton() = runComposeUiTest {
        // The acknowledge case — a dialog with nothing to decline. A wrapper that always drew
        // two buttons would make "OK / Cancel" of every notice.
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "Import finished",
                    onDismissRequest = {},
                    confirmLabel = "Done",
                    onConfirm = {},
                    content = { Text("42 contacts were added.") },
                )
            }
        }

        onNodeWithText("Done").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("Cancel").fetchSemanticsNodes().size)
    }

    @Test
    fun theButtonsInvokeTheirOwnCallbacks() = runComposeUiTest {
        // ⚠ `onDismiss` defaults to `onDismissRequest`, so a caller that passes only the
        // request gets a working Cancel — and one that passes both must not have them
        // crossed.
        var confirmed = 0
        var dismissed = 0
        var requested = 0
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "Delete file?",
                    onDismissRequest = { requested++ },
                    confirmLabel = "Delete",
                    onConfirm = { confirmed++ },
                    dismissLabel = "Cancel",
                    onDismiss = { dismissed++ },
                    content = { Text("body") },
                )
            }
        }

        onNodeWithText("Delete").performClick()
        onNodeWithText("Cancel").performClick()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
        assertEquals(0, requested, "an explicit onDismiss must not also fire onDismissRequest")
    }

    @Test
    fun theIconCarriesItsContentDescription() = runComposeUiTest {
        // The reason `icon` is an ImageVector plus a separate description rather than a
        // composable slot: the description is a parameter someone has to decide about.
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "Delete file?",
                    onDismissRequest = {},
                    confirmLabel = "Delete",
                    onConfirm = {},
                    icon = Icons.Default.Delete,
                    iconContentDescription = "Destructive action",
                )
            }
        }

        onNodeWithContentDescription("Destructive action").assertIsDisplayed()
    }

    @Test
    fun aDialogWithNoBodyStillRenders() = runComposeUiTest {
        // `content` is nullable and two call sites leave it out — a title-and-buttons dialog
        // must not collapse or crash.
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "Uninstall Drive?",
                    onDismissRequest = {},
                    confirmLabel = "Uninstall",
                    onConfirm = {},
                    dismissLabel = "Cancel",
                )
            }
        }

        onNodeWithText("Uninstall Drive?").assertIsDisplayed()
        onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test
    fun theSlotOverloadTakesArbitraryButtons() = runComposeUiTest {
        // The three-way conflict dialogs (Drive's upload conflict) use the slot overload
        // rather than the label one, so it has to keep working independently.
        setContent {
            TenshuTheme {
                AppDialog(
                    title = "File already exists",
                    onDismissRequest = {},
                    confirmButton = { Text("Keep both") },
                    dismissButton = { Text("Replace") },
                    content = { Text("report.pdf is already in this folder.") },
                )
            }
        }

        assertTrue(onAllNodesWithText("Keep both").fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodesWithText("Replace").fetchSemanticsNodes().isNotEmpty())
    }
}
