package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.TrashedItem
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.trash.TrashScreen
import app.tenshu.photos.ui.screens.trash.TrashUiState
import app.tenshu.ui.theme.TenshuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What S8 renders (`photos-app.md`).
 *
 * ⚠⚠ The load-bearing tests are about the **confirmation**: this is the only screen in the app that
 * can destroy a photograph, and the dialog must say what is lost rather than "Are you sure?".
 */
@OptIn(ExperimentalTestApi::class)
class TrashScreenTest {

    private fun item(i: Int) = TrashedItem(
        contentId = "c$i",
        photo = Photo("/Trash/c$i-p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i"),
        originalPath = "/Backup/Phone/p$i.jpg",
        trashedAtMs = i.toLong(),
    )

    private fun screen(
        state: TrashUiState,
        onRestore: (String) -> Unit = {},
        onDeleteForever: (String) -> Unit = {},
        onEmptyTrash: () -> Unit = {},
        onDismissReport: () -> Unit = {},
    ) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            TrashScreen(
                state = state,
                loadPreview = { _, _ -> null },
                onRestore = onRestore,
                onDeleteForever = onDeleteForever,
                onEmptyTrash = onEmptyTrash,
                onBack = {},
                onDismissReport = onDismissReport,
            )
        }
    }

    @Test
    fun anEmptyTrashSaysWhatTheTrashIsFor() = runComposeUiTest {
        setContent(screen(TrashUiState(items = emptyList(), loading = false)))

        waitUntil {
            onAllNodesWithText("Trash is empty.\nDeleted photos wait here until you empty it.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun anUnreachableProxyIsNotAnEmptyTrash() = runComposeUiTest {
        setContent(screen(TrashUiState(loading = false, error = FeedError.Unreachable)))

        waitUntil {
            onAllNodesWithText(
                "Can't reach Tenshu Control.\nYour photos are safe — Control just isn't running."
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            onAllNodesWithText("Trash is empty.\nDeleted photos wait here until you empty it.")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theEmptyActionNamesWhatIsLostAndCanBeBackedOutOf() = runComposeUiTest {
        var emptied = false
        setContent(
            screen(
                TrashUiState(items = listOf(item(1), item(2)), loading = false),
                onEmptyTrash = { emptied = true },
            )
        )

        waitUntil { onAllNodesWithText("Empty").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Empty").performClick()

        // ⚠⚠ Says the count and that it cannot be undone — not "Are you sure?".
        waitUntil {
            onAllNodesWithText(
                "2 photos will be deleted for good. This can't be undone — they're removed from all your devices."
            ).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText("Keep them").performClick()
        assertTrue(!emptied, "backing out of the dialog must destroy nothing")
    }

    @Test
    fun confirmingTheEmptyActuallyEmpties() = runComposeUiTest {
        var emptied = false
        setContent(
            screen(
                TrashUiState(items = listOf(item(1)), loading = false),
                onEmptyTrash = { emptied = true },
            )
        )

        waitUntil { onAllNodesWithText("Empty").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Empty").performClick()
        waitUntil { onAllNodesWithText("Delete them").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Delete them").performClick()

        assertEquals(true, emptied)
    }

    @Test
    fun thereIsNoEmptyActionWhenThereIsNothingToEmpty() = runComposeUiTest {
        setContent(screen(TrashUiState(items = emptyList(), loading = false)))

        waitUntil {
            onAllNodesWithText("Trash is empty.\nDeleted photos wait here until you empty it.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(onAllNodesWithText("Empty").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aWriteThatDidNotHappenIsSaid_andDismissed() = runComposeUiTest {
        // ⚠⚠ The report must be drawn, not dropped (`photos-app.md` §22).
        var dismissed = false
        setContent(
            screen(
                TrashUiState(
                    items = listOf(item(1)),
                    loading = false,
                    report = app.tenshu.photos.ui.screens.trash.TrashReport("Couldn't Restore the Photo", "It's still in the trash."),
                ),
                onDismissReport = { dismissed = true },
            ),
        )
        waitUntil { onAllNodesWithText("Couldn't Restore the Photo").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun nothingIsOfferedWhileAWriteRuns() = runComposeUiTest {
        // ⚠ The writes are single-flight: a tap the view model then ignores is a restore that never happened.
        val restored = mutableListOf<String>()
        setContent(screen(TrashUiState(items = listOf(item(1)), loading = false, writing = true), onRestore = { restored += it }))
        onAllNodesWithTag(app.tenshu.photos.ui.components.PHOTO_TILE_TAG).onFirst().performClick()
        waitForIdle()
        assertTrue(restored.isEmpty())
        onNodeWithText("Empty").assertIsNotEnabled()
        // ⚠ Nor leaving: on Android a pop cancels the write's scope.
        onNodeWithContentDescription("Back").assertIsNotEnabled()
    }
}
