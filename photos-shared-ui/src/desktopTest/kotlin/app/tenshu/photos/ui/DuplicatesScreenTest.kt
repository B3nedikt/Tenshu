package app.tenshu.photos.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.photos.data.DuplicateGroup
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PHOTO_TILE_TAG
import app.tenshu.photos.ui.screens.duplicates.DuplicatesConfirmation
import app.tenshu.photos.ui.screens.duplicates.DuplicatesReport
import app.tenshu.photos.ui.screens.duplicates.DuplicatesScreen
import app.tenshu.photos.ui.screens.duplicates.DuplicatesUiState
import app.tenshu.photos.ui.screens.feed.FeedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.tenshu.ui.theme.TenshuTheme

/**
 * What the Duplicates surface renders (`photos-app.md` S4 / PH-D2 / PH-6).
 *
 * ⚠⚠ The load-bearing tests are the two the design decision turns on: **a tap keeps rather than
 * deletes**, and the destructive mode is opt-in and says so. A screen that got either backwards
 * would be a data-loss bug wearing a cleanup tool's label.
 */
@OptIn(ExperimentalTestApi::class)
class DuplicatesScreenTest {

    private fun photo(i: Int) =
        Photo("/Backup/Phone/p$i.jpg", 1_000L - i, isVideo = false, contentId = "c$i", contentHash = "h1")

    private val group = DuplicateGroup("h1", listOf(photo(1), photo(2)), sizeBytes = 4_000L)

    private fun screen(
        state: DuplicatesUiState,
        onKeepOnly: (DuplicatesConfirmation) -> Unit = {},
        onSetDeletePermanently: (Boolean) -> Unit = {},
        onRetry: () -> Unit = {},
        onDismissReport: () -> Unit = {},
    ) = @androidx.compose.runtime.Composable {
        TenshuTheme {
            DuplicatesScreen(
                state = state,
                loadPreview = { _, _ -> null },
                onKeepOnly = onKeepOnly,
                onSetDeletePermanently = onSetDeletePermanently,
                onBack = {},
                onRetry = onRetry,
                onDismissReport = onDismissReport,
            )
        }
    }

    @Test
    fun anEmptyResultSaysWhatWasComparedAndWhatWasNot() = runComposeUiTest {
        // ⚠ "No duplicates" alone would read as a guarantee. The count says how much was looked at
        // and the second sentence says which copies this method cannot see.
        setContent(screen(DuplicatesUiState(loading = false, filesScanned = 812)))

        waitUntil {
            onAllNodesWithText(
                "No duplicates.\nWe compared 812 files. " +
                    "Edited or re-encoded copies aren't counted — they're different files.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theRowSaysATapKeepsRatherThanDeletes() = runComposeUiTest {
        setContent(screen(DuplicatesUiState(groups = listOf(group), loading = false, filesScanned = 2)))

        waitUntil {
            onAllNodesWithText("2 identical copies — tap the one to keep")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theCheckboxDescribesWhatEachModeCosts() = runComposeUiTest {
        setContent(screen(DuplicatesUiState(groups = listOf(group), loading = false)))

        waitUntil {
            onAllNodesWithText("Extra copies go to the trash, where you can still get them back.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theDestructiveCopyOnlyAppearsWhenTheBoxIsTicked() = runComposeUiTest {
        setContent(
            screen(
                DuplicatesUiState(groups = listOf(group), loading = false, deletePermanently = true)
            )
        )

        waitUntil {
            onAllNodesWithText("Extra copies are removed from all your devices right away.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun nothingIsActedOnUntilTheDialogIsConfirmed() = runComposeUiTest {
        val kept = mutableListOf<DuplicatesConfirmation>()
        setContent(
            screen(
                DuplicatesUiState(groups = listOf(group), loading = false),
                onKeepOnly = { kept += it },
            )
        )

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size == 2 }
        // Tapping a tile opens the confirmation and does NOT act.
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performClick()
        waitUntil { onAllNodesWithText("Move the other copies to the trash?").fetchSemanticsNodes().isNotEmpty() }
        // ⚠ The dialog names the SURVIVOR and says what happens to the rest.
        onNodeWithText("Keeping p1.jpg.").assertIsDisplayed()
        onNodeWithText("1 other copy will move to the trash. You can restore it from there.").assertIsDisplayed()
        assertTrue(kept.isEmpty(), "a tile tap must not resolve a group on its own")

        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertTrue(kept.isEmpty(), "backing out of the dialog removes nothing")

        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performClick()
        waitUntil { onAllNodesWithText("Move to Trash").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Move to Trash").performClick()
        waitForIdle()
        // ⚠ What the user read is what is handed back: the survivor, the others, and the mode.
        val confirmed = kept.single()
        assertEquals(photo(1).path, confirmed.keepPath)
        assertEquals(listOf(photo(2).path), confirmed.otherPaths)
        assertFalse(confirmed.permanently)
    }

    @Test
    fun theDestructiveModeSaysWhatIsLost_andItsButtonSaysDelete() = runComposeUiTest {
        // ⚠⚠ "Delete for good" and "move to the trash" must never read alike.
        setContent(screen(DuplicatesUiState(groups = listOf(group), loading = false, deletePermanently = true)))

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performClick()
        waitUntil { onAllNodesWithText("Delete the other copies for good?").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1 other copy will be removed from all your devices. This can't be undone.").assertIsDisplayed()
        onNodeWithText("Delete for Good").assertIsDisplayed()
        assertTrue(onAllNodesWithText("Move to Trash").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anUnreachableProxyDoesNotRenderAsACleanLibrary_andOffersARetry() = runComposeUiTest {
        var retried = 0
        setContent(screen(DuplicatesUiState(loading = false, error = FeedError.Unreachable), onRetry = { retried++ }))

        waitUntil {
            onAllNodesWithText(
                "Can't reach Tenshu Control.\nYour photos are safe — Control just isn't running.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Try Again").performClick()
        waitForIdle()
        assertEquals(1, retried)
    }

    @Test
    fun couldNotLookIsNeverTheEmptySentence_evenOverGroupsAnEarlierReadSaw() = runComposeUiTest {
        // ⚠⚠ `photos-app.md` §40. And a list that could not be re-read is not drawn to be acted on.
        setContent(screen(DuplicatesUiState(groups = listOf(group), loading = false, error = FeedError.Failed("boom"))))

        waitUntil {
            onAllNodesWithText("Couldn't look for duplicates.\nNothing was compared — that doesn't mean there are none.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Try Again").assertIsDisplayed()
        assertTrue(onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().isEmpty(), "no stale tiles")
        assertTrue(onAllNodesWithText("No duplicates.", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anEmptyResultOffersNoRetry() = runComposeUiTest {
        setContent(screen(DuplicatesUiState(loading = false, filesScanned = 812)))
        waitUntil { onAllNodesWithText("No duplicates.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodesWithText("Try Again").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aKeepOnlyThatFellShortIsSaid_andDismissed() = runComposeUiTest {
        // ⚠⚠ Every result here used to be dropped: the report must be drawn.
        var dismissed = false
        setContent(
            screen(
                DuplicatesUiState(
                    groups = listOf(group),
                    loading = false,
                    report = DuplicatesReport("Couldn't Move Every Copy to the Trash", "1 copy of 1 couldn't be moved to the trash."),
                ),
                onDismissReport = { dismissed = true },
            ),
        )
        waitUntil { onAllNodesWithText("Couldn't Move Every Copy to the Trash").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("1 copy of 1 couldn't be moved to the trash.").assertIsDisplayed()
        onNodeWithText("OK").performClick()
        waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun nothingIsOfferedWhileAWriteRuns() = runComposeUiTest {
        // ⚠ The writes are single-flight: a confirmation the view model then ignores is a keep-only
        // that silently never happened.
        var toggled = 0
        setContent(
            screen(
                DuplicatesUiState(groups = listOf(group), loading = false, writing = true),
                onSetDeletePermanently = { toggled++ },
            ),
        )
        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size == 2 }
        onAllNodesWithTag(PHOTO_TILE_TAG).onFirst().performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Move the other copies to the trash?").fetchSemanticsNodes().isEmpty(), "no confirmation")
        // ⚠ Nor leaving: on Android a pop cancels the write's scope (`photos-ios.md` §8.17 note 4).
        onNodeWithContentDescription("Back").assertIsNotEnabled()
        onNode(isToggleable()).assertIsNotEnabled()
        assertEquals(0, toggled)
    }

    @Test
    fun aLockedCopyIsNeverOfferedAsTheOneToKeep() = runComposeUiTest {
        // ⚠⚠ Its write went unanswered and may still land: kept, the others' removal could leave none.
        val three = DuplicateGroup("h1", listOf(photo(1), photo(2), photo(3)), sizeBytes = 4_000L)
        setContent(screen(DuplicatesUiState(groups = listOf(three), loading = false, uncertain = setOf(photo(1).path))))

        waitUntil { onAllNodesWithTag(PHOTO_TILE_TAG).fetchSemanticsNodes().size == 3 }
        onAllNodesWithTag(PHOTO_TILE_TAG)[0].performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty(), "the locked copy opens nothing")

        onAllNodesWithTag(PHOTO_TILE_TAG)[1].performClick()
        waitUntil { onAllNodesWithText("Keeping p2.jpg.").fetchSemanticsNodes().isNotEmpty() }
        // ⚠ And it is not counted among the copies that will go.
        onNodeWithText("1 other copy will move to the trash. You can restore it from there.").assertIsDisplayed()
    }
}
