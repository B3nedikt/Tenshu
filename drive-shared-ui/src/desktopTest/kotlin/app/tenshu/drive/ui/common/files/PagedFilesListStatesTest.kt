package app.tenshu.drive.ui.common.files

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.compose.ui.Modifier
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.drive.data.FileViewMode
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/**
 * The empty listing has **three** states, not two.
 *
 * A refresh that *failed* also arrives with zero items, and rendering that as "No files here yet"
 * is the app asserting the folder is empty at the one moment it could not read it. The reachable
 * cause is ordinary rather than exotic: a cold start issues its first load before the schema
 * session is admitted (measured at ~6 s on a real device), the load fails, `TenshuPagingSource`
 * correctly returns `LoadResult.Error` — and Paging does not retry an error by itself, so the
 * listing stayed "empty" until something rebuilt the Pager. Switching tabs and coming back did
 * exactly that, which is why it looked like navigation was the cure.
 *
 * Same family as the iOS `FilesView` "empty == loading" conflation and the R4
 * "no signal == nothing to report" banner gap: absence of data is not evidence of absence of files.
 */
@OptIn(ExperimentalTestApi::class)
class PagedFilesListStatesTest {

    /**
     * Wait for [text] to appear rather than asserting against whatever frame happens to be current
     * — see the note in the error test. Still fails honestly against a wrong implementation: the
     * node never appears and this times out.
     */
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = TIMEOUT_MS) {
            onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object { const val TIMEOUT_MS = 5_000L }

    private fun loadStates(refresh: LoadState) = LoadStates(
        refresh = refresh,
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = LoadState.NotLoading(endOfPaginationReached = true),
    )

    @Test
    fun aFailedRefreshOffersRetryRatherThanClaimingTheFolderIsEmpty() = runComposeUiTest {
        setContent {
            TenshuTheme {
                PagedFilesList(
                    modifier = Modifier,
                    files = flowOf(
                        PagingData.empty<FileUiModel>(
                            sourceLoadStates = loadStates(LoadState.Error(RuntimeException("no session")))
                        )
                    ).collectAsLazyPagingItems(),
                    viewMode = FileViewMode.LIST,
                    onFileClicked = {},
                )
            }
        }

        // ⚠ Wait, do not assert on the first frame. `collectAsLazyPagingItems` delivers the
        // PagingData asynchronously, so the composition's opening frame has no items AND no load
        // state — which renders the empty state and made both tests in this class flake (~1 run in
        // 3) rather than fail honestly.
        //
        // ⚠ `useUnmergedTree`: the error state carries an action button, whose semantics merge the
        // surrounding text into one node, so the default (merged) finder cannot see the title.
        awaitText("Couldn't load this folder")
        awaitText("Try again")
    }

    @Test
    fun aGenuinelyEmptyFolderStillSaysSo() = runComposeUiTest {
        setContent {
            TenshuTheme {
                PagedFilesList(
                    modifier = Modifier,
                    files = flowOf(
                        PagingData.empty<FileUiModel>(
                            sourceLoadStates = loadStates(
                                LoadState.NotLoading(endOfPaginationReached = true)
                            )
                        )
                    ).collectAsLazyPagingItems(),
                    viewMode = FileViewMode.LIST,
                    onFileClicked = {},
                )
            }
        }

        awaitText("No files here yet")
    }
}
