package app.tenshu.drive.integration

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.compose.collectAsLazyPagingItems
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.integration.utils.startGlobalAppIntegrationKoin
import app.tenshu.drive.integration.utils.stopAppIntegrationKoin
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Drives the REAL paged repository through `collectAsLazyPagingItems` — the exact consumer the
 * live `FilesScreen` uses — and asserts that folders created while the screen stays open appear
 * WITHOUT re-navigating (not only after switching to Home and back).
 *
 * The other listing tests do not cover this path:
 *  - [FilesScreenIntegrationTest] swaps in a static `IdlingFilesRepository`,
 *    routing around the real `pagingSource` observer entirely.
 *  - [FilesPagingIntegrationTest] exercises the real repository flow (and proves
 *    it IS reactive — `pagedListingEmitsNewGenerationWhenFolderCreatedWhileCollecting`),
 *    but never through the Compose `LazyPagingItems` differ that the screen uses.
 *
 * ### ⚠⚠ It is the guard on `cachedIn` too
 *
 * `FilesScreenViewModel.pagedFiles` is `.cachedIn(viewModelScope)` (`core-paging-scroll-restoration.md`
 * §4.1). This test collects **that** flow through the real differ, so it is the live proof that
 * reactivity survives `cachedIn` — one of that doc's three reactivity guards. ⚠ **If it goes red,
 * take `cachedIn` back out** (§7 of that doc holds the design that routes around it), do not weaken
 * the test.
 *
 * It also pins that the differ settles: `TenshuPagingSource.load()` reads each window with a
 * one-shot `getAll()`, and a load that left a stream collector open would hang here. And it guards
 * the invalidate-swallow fix (`drive-folder-listing-consistency.md` §5): a change signal that fails
 * to trigger a reload would leave the new folder off-screen and fail here.
 *
 * NOTE: keep one test per class — a second `runComposeUiTest` in the same JVM
 * races the live observer's teardown.
 */
class FilesPagingReactivityUiTest {

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun folderCreatedWhileListingIsOpenAppearsWithoutRenavigating() = composeVmTest { vms ->
        startGlobalAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        val repo = GlobalContext.get().get<FilesRepository>()
        val offlinePreferences = GlobalContext.get().get<OfflinePreferences>()
        val syncRulesPreferences = GlobalContext.get().get<SyncRulesPreferences>()

        // Seed one folder so the initial load is non-empty.
        runBlocking { repo.createFolder("/alpha") }

        // The production VM, unchanged: pagedFiles = getFilesPaged(path).map{...},
        // `cachedIn(viewModelScope)` — see this class's KDoc.
        val viewModel = vms.own(FilesScreenViewModel(
            filesRepository = repo,
            selectionViewModel = vms.own(SelectionViewModel(repo, offlinePreferences, syncRulesPreferences)),
            folderPath = "/",
        ))

        setContent {
            TenshuTheme {
                val files = viewModel.pagedFiles.collectAsLazyPagingItems()
                Column {
                    for (i in 0 until files.itemCount) {
                        files[i]?.let { Text(it.name) }
                    }
                }
            }
        }

        // Initial generation renders the seeded folder.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("alpha").fetchSemanticsNodes().isNotEmpty()
        }

        // Create more folders while the SAME listing collection stays open —
        // successive creates, so a fast second create that goes missing until
        // re-navigation fails here. Each must appear via live invalidation, no tab switch.
        runBlocking { repo.createFolder("/beta") }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("beta").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { repo.createFolder("/gamma") }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("gamma").fetchSemanticsNodes().isNotEmpty()
        }

        // All three visible together — no earlier row dropped while adding later ones.
        onNodeWithText("alpha").assertIsDisplayed()
        onNodeWithText("beta").assertIsDisplayed()
        onNodeWithText("gamma").assertIsDisplayed()
    }
}
