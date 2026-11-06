package app.tenshu.drive.ui.common.files

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import app.tenshu.liststate.ListLoadPhase
import app.tenshu.ui.theme.TenshuTheme
import org.junit.Test

/**
 * The non-paged sibling of [PagedFilesListStatesTest] — Home's *Recent files*.
 *
 * Same family, different plumbing: this list is fed by a reactive `flow()` read whose failure the
 * repository folded into `emptyList()`, so a broken read arrived indistinguishable from an empty
 * drive and rendered as "No files here yet". The list also started at `emptyList()` before its
 * first emission, so the same sentence was shown while the read was still in flight — two
 * conflations in one gate.
 *
 * ⚠ The failure state deliberately has **no *Try again***, unlike the paged sibling: a `flow()`
 * subscribe retries itself (`core-client-resubscription.md` R1b/R5), so the button would duplicate
 * what is already happening.
 */
@OptIn(ExperimentalTestApi::class)
class FilesListStatesTest {

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun ComposeUiTest.assertNeverShows(text: String) {
        waitForIdle()
        check(onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "\"$text\" must not be shown in this state"
        }
    }

    @Test
    fun aFailedReadSaysSoInsteadOfClaimingTheDriveIsEmpty() = runComposeUiTest {
        setContent {
            TenshuTheme {
                FilesList(
                    modifier = Modifier,
                    files = emptyList(),
                    load = ListLoadPhase.FAILED,
                    onFileClicked = {},
                    onMoreButtonOfFileClicked = {},
                )
            }
        }

        awaitText(ERROR_TITLE)
        // The whole point: the empty claim must be gone, not merely accompanied.
        assertNeverShows(EMPTY_TITLE)
        // And no retry affordance — the read is already retrying itself.
        assertNeverShows(RETRY_ACTION)
    }

    @Test
    fun aReadStillInFlightDoesNotClaimTheDriveIsEmptyEither() = runComposeUiTest {
        setContent {
            TenshuTheme {
                FilesList(
                    modifier = Modifier,
                    files = emptyList(),
                    load = ListLoadPhase.LOADING,
                    onFileClicked = {},
                    onMoreButtonOfFileClicked = {},
                )
            }
        }

        waitForIdle()
        assertNeverShows(EMPTY_TITLE)
        assertNeverShows(ERROR_TITLE)
    }

    @Test
    fun aGenuinelyEmptyDriveStillSaysSo() = runComposeUiTest {
        // The control. Without this the two tests above pass against an implementation that simply
        // never renders the empty state — which would be a different bug, not a fix.
        setContent {
            TenshuTheme {
                FilesList(
                    modifier = Modifier,
                    files = emptyList(),
                    load = ListLoadPhase.LOADED,
                    onFileClicked = {},
                    onMoreButtonOfFileClicked = {},
                )
            }
        }

        awaitText(EMPTY_TITLE)
        assertNeverShows(ERROR_TITLE)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val EMPTY_TITLE = "No files here yet"
        const val ERROR_TITLE = "Couldn't load this folder"
        const val RETRY_ACTION = "Try again"
    }
}
