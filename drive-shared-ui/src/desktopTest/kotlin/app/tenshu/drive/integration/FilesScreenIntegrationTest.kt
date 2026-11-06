package app.tenshu.drive.integration

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.TenshuFilesRepository
import app.tenshu.drive.integration.utils.startGlobalAppIntegrationKoin
import app.tenshu.drive.integration.utils.stopAppIntegrationKoin
import app.tenshu.drive.ui.App
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.preview.previewImageTestTag
import app.tenshu.ui.theme.TenshuTheme
import app.tenshu.drive.composeVmTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * Ceiling for every wait in this class: each one awaits work on real threads (Compose frames
 * rendered on the AWT thread, the in-process proxy on `Dispatchers.Default`), so its duration is
 * whatever the scheduler gives this JVM.
 *
 * ⚠ `waitUntil`'s 1 s default is NOT ample: a wait takes 20–180 ms on a box other builds are
 * using, but measured up to 1.2 s under 28 CPU burners and 3.7 s beside a sibling Gradle build,
 * with the JVM getting a third of a core for the duration. It only
 * elapses on a genuine failure — a satisfied condition returns at once — so the price of the
 * margin is a slower red, never a slower green.
 *
 * ⚠⚠ It cannot paper over a lost update — a listing that never re-emits, the test thread
 * spinning frames at full speed for the whole wait (`ReferenceCollectorConfinementTest` pins
 * one). An update that is lost never arrives at any ceiling.
 */
private const val UI_WAIT_TIMEOUT_MS = 10_000L

/** How long `testTheShellWaitsForTheRealmRead` keeps the realm read unanswered. */
private const val REALM_READ_HELD_MS = 200L

class FilesScreenIntegrationTest {

    // Shared owner for the App's ViewModels (JUnit news up a fresh instance per
    // method, so it's effectively per-test). Each test hands the store to its
    // [composeVmTest], which joins every view model in it before `@After` shuts the
    // proxy down; without that, nothing calls `clear()` on this store and the view
    // models are never even cancelled.
    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    /**
     * Held by the one test that looks at the window before the realm read answers; `null`
     * everywhere else, where the read answers at once.
     */
    private var realmReadHeld: CompletableDeferred<Unit>? = null

    @Before
    fun setup() {
        // Override the listing's paged source with a static one (see
        // [StaticPagingFilesRepository]). The live TenshuPagingSource keeps an
        // infinite reactive observer open, which hangs a 2nd runComposeUiTest in
        // the same JVM. The real paging machinery is covered separately by
        // FilesPagingIntegrationTest (asSnapshot over the real chain).
        startGlobalAppIntegrationKoin(
            clientId = "app.tenshu.drive.test",
            controlHasRealm = {
                realmReadHeld?.await()
                true
            },
            additionalModules = listOf(
                module {
                    single<FilesRepository> {
                        StaticPagingFilesRepository(
                            TenshuFilesRepository(get(), get(), get(), get())
                        )
                    }
                }
            )
        )
    }

    @After
    fun tearDown() {
        stopAppIntegrationKoin()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testInitialEmptyList() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    App()
                }
            }
        }

        openFilesTab()

        // ⚠ The assertion is what makes this test: without it, clicking through
        // passes whatever the tab renders, including a spinner (see
        // [StaticPagingFilesRepository]: a listing whose load states are never
        // stated is pinned in `Loading`, and `PagedFilesList` answers
        // zero-items-while-loading with an `AppLoadingIndicator`).
        //
        // An empty folder is "No files here yet", not a spinner and not the
        // error arm — absence of data is not evidence of absence of files, and
        // that distinction is the whole point of `PagedFilesList`'s three-way
        // branch.
        awaitUi {
            onAllNodesWithText("No files here yet").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("No files here yet").assertIsDisplayed()
    }

    /**
     * Until the realm read answers there is no shell, so there is nothing to click — which is why
     * every test here goes through [openFilesTab] rather than clicking after `setContent`.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testTheShellWaitsForTheRealmRead() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        val held = CompletableDeferred<Unit>().also { realmReadHeld = it }
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    App()
                }
            }
        }

        // The read is held, so this is the gate for as long as we care to look.
        onNodeWithText("Files").assertDoesNotExist()

        // Answer it from another thread a moment later, as the proxy would. Real time on purpose:
        // the read is real-threaded, and a helper that did not wait would click well inside this.
        val answered = AtomicBoolean(false)
        val answering = thread(name = "realm-read") {
            Thread.sleep(REALM_READ_HELD_MS)
            answered.set(true)
            held.complete(Unit)
        }
        try {
            openFilesTab()
            assertTrue(answered.get(), "the Files tab was opened before the realm read had answered")
            awaitUi { onAllNodesWithText("No files here yet").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            held.complete(Unit)
            answering.join()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testCreateFolderAndNavigation() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    App()
                }
            }
        }

        openFilesTab()

        // 1. Create a folder at root
        onNodeWithContentDescription("Add").performClick()

        awaitUi {
            onAllNodesWithText("Folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder").performClick()

        awaitUi {
            onAllNodesWithText("Create new folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder name").performTextReplacement("documents")
        onNodeWithText("Confirm").performClick()

        // Verify it appears in root list
        awaitUi {
            onAllNodesWithText("documents").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("documents").assertIsDisplayed()

        // 2. Navigate into /documents
        onNodeWithText("documents").performClick()
        awaitUi {
            onAllNodesWithText("/documents").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("/documents").assertIsDisplayed()

        // 3. Create a folder inside the folder
        onNodeWithContentDescription("Add").performClick()
        awaitUi {
            onAllNodesWithText("Folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder").performClick()

        awaitUi {
            onAllNodesWithText("Create new folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder name").performTextReplacement("work")
        onNodeWithText("Confirm").performClick()

        // Verify 'work' is there
        awaitUi {
            onAllNodesWithText("work").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("work").assertIsDisplayed()

        // 4. Navigate back to root
        onNodeWithContentDescription("Back").performClick()
        awaitUi {
            onAllNodesWithText("documents").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("documents").assertIsDisplayed()

        // Verify 'work' is NOT in root
        onNodeWithText("work").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileUploadConflictShowsDialog() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val fabViewModel: AddFileActionsViewModel = koinInject()
                    App(
                        fabViewModel = fabViewModel,
                        onUploadImages = { parentPath ->
                            fabViewModel.uploadFile("mock://test.jpg", parentPath)
                        }
                    )
                }
            }
        }

        openFilesTab()

        // 1. Upload first time
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()

        awaitUi { onAllNodesWithText("test.jpg").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("test.jpg").assertIsDisplayed()

        // 2. Upload second time (same name)
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()

        // 3. Verify conflict dialog appears
        awaitUi { onAllNodesWithText("File already exists").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("File already exists").assertIsDisplayed()
        onNodeWithText("Keep both").assertIsDisplayed()

        // 4. Resolve by keeping both
        onNodeWithText("Keep both").performClick()

        // 5. Verify both files are in the list
        awaitUi { onAllNodesWithText("test (1).jpg").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("test.jpg").assertIsDisplayed()
        onNodeWithText("test (1).jpg").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testSearchBarIsNotAutomaticallyActivatedAfterCreatingFolder() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    App()
                }
            }
        }

        openFilesTab()

        // Create a folder
        onNodeWithContentDescription("Add").performClick()
        awaitUi {
            onAllNodesWithText("Folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder").performClick()

        awaitUi {
            onAllNodesWithText("Create new folder").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Folder name").performTextReplacement("test_folder")
        onNodeWithText("Confirm").performClick()

        // Wait for folder to appear
        awaitUi {
            onAllNodesWithText("test_folder").fetchSemanticsNodes().isNotEmpty()
        }

        // Check if SearchBar has focus (it shouldn't)
        onNodeWithText("Search").assertIsNotFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileUploadShowsUpInList() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val fabViewModel: AddFileActionsViewModel = koinInject()
                    App(
                        fabViewModel = fabViewModel,
                        onUploadImages = { parentPath ->
                            fabViewModel.uploadFile("mock://test.jpg", parentPath)
                        }
                    )
                }
            }
        }

        openFilesTab()

        // Trigger upload
        onNodeWithContentDescription("Add").performClick()
        awaitUi {
            onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Images").performClick()

        // Verify it appears in root list
        awaitUi {
            onAllNodesWithText("test.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("test.jpg").assertIsDisplayed()

        // Verify preview is displayed (via content description)
        onNodeWithContentDescription("File preview").assertIsDisplayed()
    }

    /**
     * A file uploaded inside a folder shows up in that folder's listing — not only as the
     * parent listing's file count over a folder that opens empty.
     * [testCreateFolderAndNavigation] covers folder-INSIDE-folder; this is the
     * FILE-inside-folder case.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileUploadInsideSubfolderShowsUpInList() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val fabViewModel: AddFileActionsViewModel = koinInject()
                    App(
                        fabViewModel = fabViewModel,
                        onUploadImages = { parentPath ->
                            fabViewModel.uploadFile("mock://nested.jpg", parentPath)
                        }
                    )
                }
            }
        }

        openFilesTab()

        // 1. Create /photos at root.
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Folder").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Folder").performClick()
        awaitUi { onAllNodesWithText("Create new folder").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Folder name").performTextReplacement("photos")
        onNodeWithText("Confirm").performClick()
        awaitUi { onAllNodesWithText("photos").fetchSemanticsNodes().isNotEmpty() }

        // 2. Navigate into /photos
        onNodeWithText("photos").performClick()
        awaitUi { onAllNodesWithText("/photos").fetchSemanticsNodes().isNotEmpty() }

        // 3. Upload a file *inside* /photos (the FAB's parentPath is the
        // current navigation path, so the upload lands at `/photos/nested.jpg`).
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()

        // 4. Verify the file shows up in the /photos detail listing.
        awaitUi {
            onAllNodesWithText("nested.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("nested.jpg").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileDeletion() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val scope = rememberCoroutineScope()
                    val filesRepository: FilesRepository = koinInject()
                    App(
                        onUploadImages = { parentPath ->
                            scope.launch {
                                filesRepository.uploadFile(
                                    "mock://to_delete.jpg",
                                    parentPath,
                                    "to_delete.jpg"
                                )
                            }
                        }
                    )
                }
            }
        }

        openFilesTab()

        // 1. Upload a file
        onNodeWithContentDescription("Add").performClick()
        awaitUi {
            onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Images").performClick()

        // Verify it appears
        awaitUi {
            onAllNodesWithText("to_delete.jpg").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("to_delete.jpg").assertIsDisplayed()

        // 2. Delete it
        onNodeWithContentDescription("More actions for to_delete.jpg").performClick()
        awaitUi {
            onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Delete").performClick()

        // Verify it's gone
        awaitUi {
            onAllNodesWithText("to_delete.jpg").fetchSemanticsNodes().isEmpty()
        }
        onNodeWithText("to_delete.jpg").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testClickOnFileTriggersDownload() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val fabViewModel: AddFileActionsViewModel = koinInject()
                    App(
                        fabViewModel = fabViewModel,
                        onUploadImages = { parentPath ->
                            // Use the repository directly to ensure we have a file
                            fabViewModel.uploadFile("mock://to_click.jpg", parentPath)
                        }
                    )
                }
            }
        }

        openFilesTab()

        // 1. Upload a file
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()

        // Wait for it to appear
        awaitUi { onAllNodesWithText("to_click.jpg").fetchSemanticsNodes().isNotEmpty() }

        // 2. Fetch the file's bytes through `getRemoteFile`, the repository
        // surface a click on the file uses.
        val filesRepository: FilesRepository = GlobalContext.get().get()
        runBlocking {
            filesRepository.getRemoteFile("/to_click.jpg")
        }

        // If no crash occurred, the test passes
    }

    /**
     * Opening a second photo shows the second photo's image, not the first's. Two things must
     * hold on desktop: `KmpNavHost.desktop.kt` gives each backstack entry its own
     * `LocalViewModelStoreOwner`, and `LocalImagePreviewViewModelFactory`'s
     * `koinViewModel(parameters = …)` is keyed per file — otherwise Compose returns the cached
     * `ImagePreviewViewModel` (still holding the *first* file's bitmap) on the second
     * `Destination.FilePreview` navigation.
     *
     * The mock delegate generates valid PNGs whose dimensions encode the
     * URI's `?w=&h=` hint so the rendered `Image` carries a distinct
     * `previewImageTestTag(w, h)` per file — we can assert *which* bitmap
     * is on screen, not just which `filePath` the destination received.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testTwoUploadsShowCorrectDetailImage() = composeVmTest { vms ->
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        var nextUri = "mock://first.jpg?w=2&h=2"
        setContent {
            TenshuTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides viewModelStoreOwner
                ) {
                    val scope = rememberCoroutineScope()
                    val filesRepository: FilesRepository = koinInject()
                    App(
                        onUploadImages = { parentPath ->
                            val uri = nextUri
                            scope.launch {
                                filesRepository.uploadFile(
                                    uri,
                                    parentPath,
                                    uri.substringAfter("//")
                                        .substringBefore("?")
                                )
                            }
                        }
                    )
                }
            }
        }

        openFilesTab()

        // 1. Upload first file (2x2 PNG).
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()
        awaitUi { onAllNodesWithText("first.jpg").fetchSemanticsNodes().isNotEmpty() }

        // 2. Upload second file with different dimensions (3x3 PNG).
        nextUri = "mock://second.jpg?w=3&h=3"
        onNodeWithContentDescription("Add").performClick()
        awaitUi { onAllNodesWithText("Images").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Images").performClick()
        awaitUi { onAllNodesWithText("second.jpg").fetchSemanticsNodes().isNotEmpty() }

        // 3. Open detail view for the first file. Title comes from filePath
        // and would look correct even with a stale view model; the tag derived
        // from the bitmap's dimensions is what actually catches it.
        onNodeWithText("first.jpg").performClick()
        awaitUi {
            onAllNodesWithTag(previewImageTestTag(2, 2)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(previewImageTestTag(2, 2)).assertIsDisplayed()

        // 4. Back to list and open the second file.
        onNodeWithContentDescription("Back").performClick()
        awaitUi { onAllNodesWithText("second.jpg").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("second.jpg").performClick()

        // A cached `ImagePreviewViewModel` would still hold
        // `filePath="/first.jpg"` and show the *first* file's bitmap (2x2).
        awaitUi {
            onAllNodesWithTag(previewImageTestTag(3, 3)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(previewImageTestTag(3, 3)).assertIsDisplayed()
    }
}

/** [ComposeUiTest.waitUntil] under this class's ceiling — see [UI_WAIT_TIMEOUT_MS]. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitUi(condition: () -> Boolean) =
    waitUntil(timeoutMillis = UI_WAIT_TIMEOUT_MS, condition = condition)

/**
 * Opens the Files tab once there is a shell to click.
 *
 * ⚠⚠ `App()` does not start on its shell. It renders the non-interactive checking gate until
 * Control has answered whether this device has a realm (ADR 0025 §8.6), and that read runs on the
 * proxy's own scope, on a real thread. Clicking straight after `setContent` races it: "Failed to
 * inject mouse input … could not find any node … 'Files'", in a different test each run, and more
 * often in a warm JVM, where the test outruns the read. `testTheShellWaitsForTheRealmRead` holds
 * the read to pin the order.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openFilesTab() {
    awaitUi { onAllNodesWithText("Files").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithText("Files").performClick()
}

/**
 * Delegates everything to the real [FilesRepository] — so these tests still
 * exercise the real proxy chain for uploads, deletes, navigation and the
 * listing *data* — but replaces the paged listing with a static [PagingData]
 * wrap of the real reactive [FilesRepository.getFiles].
 *
 * The production `getFilesPaged` uses the live `TenshuPagingSource`, whose
 * infinite reactive observer never lets a `runComposeUiTest` reach idle; a 2nd
 * such test in the same JVM then GC-thrashes into a hang. Wrapping `getFiles`
 * keeps reactivity (an upload re-emits → new page) without that observer. Real
 * paging (windowing) is covered by `FilesPagingIntegrationTest` via `asSnapshot`.
 *
 * ⚠⚠ The [LoadStates] are stated because without them this fake MISREPRESENTS
 * the empty folder — the one case `PagedFilesList` takes the most care over. The
 * deprecated single-argument `PagingData.from(list)` pins refresh in
 * `LoadState.Loading` forever, and that composable branches three ways on
 * `refresh` when `itemCount == 0`: Loading → spinner, Error → "couldn't read it",
 * else → "No files here yet". Pinned, an empty folder spins forever and the
 * empty state is unreachable, so a test over this fake could never see what the
 * app really renders. [testInitialEmptyList] asserts it, and that assertion
 * fails against the single-argument form.
 *
 * ⚠ It is NOT an idle hazard, though the spinner makes that look likely: the
 * indicator is an indeterminate `CircularProgressIndicator`, i.e. an infinite
 * animation, and those are a classic reason a `runComposeUiTest` never reaches
 * idle. Measured: the suite passes either way, so the infinite animation does
 * not stall this harness and the hang this class dodges has the one cause named
 * above.
 *
 * (The same trap as any reactive source whose only settle signal is the load
 * states — `FilesPagingIntegrationTest.sortedListingSettles_soItIsReadableLikeAnyOtherListing`.)
 */
private class StaticPagingFilesRepository(
    private val delegate: FilesRepository
) : FilesRepository by delegate {
    override fun getFilesPaged(folderPath: String, sort: FileSort): Flow<PagingData<FileModel>> = flow {
        emitAll(delegate.getFiles(folderPath).map { PagingData.from(it, WHOLE_LISTING_LOAD_STATES) })
    }
}

/** One page, refresh done, neither end can append — the truth for a whole-listing wrap. */
private val WHOLE_LISTING_LOAD_STATES = LoadStates(
    refresh = LoadState.NotLoading(endOfPaginationReached = true),
    prepend = LoadState.NotLoading(endOfPaginationReached = true),
    append = LoadState.NotLoading(endOfPaginationReached = true),
)
