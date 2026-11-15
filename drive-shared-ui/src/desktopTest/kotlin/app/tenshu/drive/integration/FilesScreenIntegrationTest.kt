package app.tenshu.drive.integration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
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
import app.tenshu.drive.composeDesktopVmTest
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.RepositoryResult
import app.tenshu.drive.data.TenshuFilesRepository
import app.tenshu.drive.integration.utils.IdlingFilesRepository
import app.tenshu.drive.integration.utils.RepositoryIdling
import app.tenshu.drive.integration.utils.startGlobalAppIntegrationKoin
import app.tenshu.drive.integration.utils.stopAppIntegrationKoin
import app.tenshu.drive.ui.App
import app.tenshu.drive.ui.common.addfileactions.AddFileActionsViewModel
import app.tenshu.drive.ui.common.preview.previewImageTestTag
import app.tenshu.ui.theme.TenshuTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.tenshu.viewmodel.test.OwnedViewModels
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hang guard for the two waits that remain condition waits — see [awaitUi]. Each awaits work
 * on a thread no idling resource covers, so its duration is whatever the scheduler gives this
 * JVM. It only elapses on a genuine failure; a satisfied condition returns at once.
 *
 * ⚠⚠ It cannot paper over a lost update, and raising it is never the fix: an update that is
 * lost never arrives at any ceiling.
 */
private const val UI_WAIT_TIMEOUT_MS = 10_000L

/** How long `testTheShellWaitsForTheRealmRead` keeps the realm read unanswered. */
private const val REALM_READ_HELD_MS = 200L

/**
 * How many of Compose's idle polls `testASlowUploadIsAwaitedNotTimed` holds an upload open for.
 * A count, not a duration: the test is about *what* the UI waits on, not how long.
 */
private const val UPLOAD_HELD_FOR_POLLS = 10

/**
 * Drive's file screen over a real in-process proxy: one UI round trip per behaviour.
 *
 * ⚠⚠ **Assertions follow an action directly; they do not poll.** [RepositoryIdling] is
 * registered with Compose, so the implicit `waitForIdle()` before every node lookup waits for
 * the repository calls the action started, the listing's re-emission that reflects them, and
 * the EDT work that presents both. That is the happens-before; a real-time `waitUntil` was not
 * one: under load its budget (1 000 ms when they were filed) is what failed
 * `testFileUploadConflictShowsDialog` and `testTwoUploadsShowCorrectDetailImage`
 * (`docs/testing-flakes.md`, category C), and a larger one only moves the line.
 * `testASlowUploadIsAwaitedNotTimed` pins it.
 *
 * What a repository-level test covers instead, with no UI: paging and listing data
 * (`FilesPagingIntegrationTest`), and the upload round trip, its `AlreadyExists` and the bytes
 * it reads back (`UploadFileIntegrationTest`).
 */
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

    private val idling = RepositoryIdling()

    /** The graph's [FilesRepository], which is the [IdlingFilesRepository] bound in [setup]. */
    private val filesRepository: IdlingFilesRepository
        get() = GlobalContext.get().get<FilesRepository>() as IdlingFilesRepository

    @Before
    fun setup() {
        // Override the listing's paged source with a static one (see [IdlingFilesRepository]).
        // The live TenshuPagingSource keeps an infinite reactive observer open, which hangs a
        // 2nd runComposeUiTest in the same JVM. The real paging machinery is covered separately
        // by FilesPagingIntegrationTest (asSnapshot over the real chain).
        startGlobalAppIntegrationKoin(
            clientId = "app.tenshu.drive.test",
            controlHasRealm = {
                realmReadHeld?.await()
                true
            },
            additionalModules = listOf(
                module {
                    single<FilesRepository> {
                        IdlingFilesRepository(TenshuFilesRepository(get(), get(), get(), get()), idling)
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
    private fun DesktopComposeUiTest.startApp(vms: OwnedViewModels, app: @Composable () -> Unit = { App() }) {
        vms.ownStore(viewModelStoreOwner.viewModelStore)
        // ⚠ A desktop test only: a plain `runComposeUiTest` never polls it (`composeDesktopVmTest`).
        registerIdlingResource(idling)
        setContent {
            TenshuTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
                    app()
                }
            }
        }
    }

    /** `App` whose image picker answers [uri], uploaded through the real FAB view model. */
    @Composable
    private fun AppUploading(uri: String) {
        val fabViewModel: AddFileActionsViewModel = koinInject()
        App(
            fabViewModel = fabViewModel,
            onUploadImages = { parentPath -> fabViewModel.uploadFile(uri, parentPath) }
        )
    }

    /** Puts a file in place before the UI exists, for tests whose subject is not the upload. */
    private fun seed(uri: String, fileName: String) = runBlocking {
        val result = filesRepository.uploadFile(uri, "/", fileName)
        assertTrue(result is RepositoryResult.Success, "seeding $fileName: $result")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testInitialEmptyList() = composeDesktopVmTest { vms ->
        startApp(vms)
        openFilesTab()

        // ⚠ The assertion is what makes this test: without it, clicking through
        // passes whatever the tab renders, including a spinner (see
        // [IdlingFilesRepository]: a listing whose load states are never
        // stated is pinned in `Loading`, and `PagedFilesList` answers
        // zero-items-while-loading with an `AppLoadingIndicator`).
        //
        // An empty folder is "No files here yet", not a spinner and not the
        // error arm — absence of data is not evidence of absence of files, and
        // that distinction is the whole point of `PagedFilesList`'s three-way
        // branch.
        onNodeWithText("No files here yet").assertIsDisplayed()
    }

    /**
     * Until the realm read answers there is no shell, so there is nothing to click — which is why
     * every test here goes through [openFilesTab] rather than clicking after `setContent`.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testTheShellWaitsForTheRealmRead() = composeDesktopVmTest { vms ->
        val held = CompletableDeferred<Unit>().also { realmReadHeld = it }
        startApp(vms)

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
            onNodeWithText("No files here yet").assertIsDisplayed()
        } finally {
            held.complete(Unit)
            answering.join()
        }
    }

    /**
     * Folders end to end: create one at the root, open it, create a folder and upload a file
     * inside it, and come back to a root that shows neither.
     *
     * Also: creating a folder does not hand focus to the search bar. And a file uploaded inside
     * a folder shows up in that folder's own listing, not only as the parent's file count over a
     * folder that opens empty — the FAB's `parentPath` is the current navigation path.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testCreateFolderAndNavigation() = composeDesktopVmTest { vms ->
        startApp(vms) { AppUploading("mock://nested.jpg") }
        openFilesTab()

        // 1. Create a folder at root.
        createFolder("documents")
        onNodeWithText("documents").assertIsDisplayed()
        onNodeWithText("Search").assertIsNotFocused()

        // 2. Navigate into /documents.
        onNodeWithText("documents").performClick()
        onNodeWithText("/documents").assertIsDisplayed()

        // 3. A folder and a file inside it.
        createFolder("work")
        onNodeWithText("work").assertIsDisplayed()
        onNodeWithContentDescription("Add").performClick()
        onNodeWithText("Images").performClick()
        onNodeWithText("nested.jpg").assertIsDisplayed()

        // 4. Back to the root, which holds neither.
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("documents").assertIsDisplayed()
        onNodeWithText("work").assertDoesNotExist()
        onNodeWithText("nested.jpg").assertDoesNotExist()
    }

    /** An upload shows up in the list with its preview; a second one of the same name asks. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileUploadConflictShowsDialog() = composeDesktopVmTest { vms ->
        startApp(vms) { AppUploading("mock://test.jpg") }
        openFilesTab()

        // 1. Upload first time.
        pickImage()
        onNodeWithText("test.jpg").assertIsDisplayed()
        onNodeWithContentDescription("File preview").assertIsDisplayed()

        // 2. Upload second time (same name): the conflict dialog.
        pickImage()
        onNodeWithText("File already exists").assertIsDisplayed()
        onNodeWithText("Keep both").assertIsDisplayed()

        // 3. Keep both: both files are listed.
        onNodeWithText("Keep both").performClick()
        onNodeWithText("test.jpg").assertIsDisplayed()
        onNodeWithText("test (1).jpg").assertIsDisplayed()
    }

    /**
     * The forced form of the old flake: the second upload's answer is held across
     * [UPLOAD_HELD_FOR_POLLS] of Compose's idle polls, and the dialog is asserted with no wait of
     * its own. ⚠ With [RepositoryIdling] unregistered this goes red at once ("File already exists"
     * is not there yet); the condition waits it replaced went red under load whenever the answer
     * outlived their real-time budget.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testASlowUploadIsAwaitedNotTimed() = composeDesktopVmTest { vms ->
        startApp(vms) { AppUploading("mock://slow.jpg") }
        seed("mock://slow.jpg", "slow.jpg")
        openFilesTab()

        val released = CompletableDeferred<Unit>()
        var heldPolls = 0
        idling.onBusyPoll = { _ -> if (++heldPolls >= UPLOAD_HELD_FOR_POLLS) released.complete(Unit) }
        filesRepository.beforeUploadReturns = { released.await() }
        try {
            pickImage()
            onNodeWithText("File already exists").assertIsDisplayed()
            assertTrue(released.isCompleted, "the dialog was asserted before the upload answered")
            assertTrue(heldPolls >= UPLOAD_HELD_FOR_POLLS, "held for $heldPolls polls")
        } finally {
            released.complete(Unit)
            filesRepository.beforeUploadReturns = {}
            idling.onBusyPoll = {}
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testFileDeletion() = composeDesktopVmTest { vms ->
        seed("mock://to_delete.jpg", "to_delete.jpg")
        startApp(vms)
        openFilesTab()
        onNodeWithText("to_delete.jpg").assertIsDisplayed()

        onNodeWithContentDescription("More actions for to_delete.jpg").performClick()
        onNodeWithText("Delete").performClick()

        onNodeWithText("to_delete.jpg").assertDoesNotExist()
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
     *
     * The two files are seeded through the repository: the uploads are not this test's subject,
     * and their call returning is the happens-before the old UI uploads lacked.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun testTwoUploadsShowCorrectDetailImage() = composeDesktopVmTest { vms ->
        seed("mock://first.jpg?w=2&h=2", "first.jpg")
        seed("mock://second.jpg?w=3&h=3", "second.jpg")
        startApp(vms)
        openFilesTab()

        // 1. Open the first file. The title comes from filePath and would look correct even with
        // a stale view model; the tag derived from the bitmap's dimensions is what catches it.
        onNodeWithText("first.jpg").performClick()
        awaitUi { onAllNodesWithTag(previewImageTestTag(2, 2)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(previewImageTestTag(2, 2)).assertIsDisplayed()

        // 2. Back to the list and open the second. A cached `ImagePreviewViewModel` would still
        // hold `filePath="/first.jpg"` and show the *first* file's bitmap (2x2).
        onNodeWithContentDescription("Back").performClick()
        onNodeWithText("second.jpg").performClick()
        awaitUi { onAllNodesWithTag(previewImageTestTag(3, 3)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(previewImageTestTag(3, 3)).assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag(previewImageTestTag(2, 2)).fetchSemanticsNodes().size)
    }
}

/**
 * [ComposeUiTest.waitUntil] under [UI_WAIT_TIMEOUT_MS], for the two waits no idling resource
 * can cover. ⚠ Use it only for those; anything a repository call or the EDT produces is already
 * awaited by [RepositoryIdling].
 *
 * - [openFilesTab]: the realm read that gates the shell is answered through Control's
 *   reachability state, which no repository call carries.
 * - The detail screen's bitmap: `ImagePreviewViewModel` decodes on `Dispatchers.Default`.
 */
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

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.createFolder(name: String) {
    onNodeWithContentDescription("Add").performClick()
    onNodeWithText("Folder").performClick()
    onNodeWithText("Create new folder").assertIsDisplayed()
    onNodeWithText("Folder name").performTextReplacement(name)
    onNodeWithText("Confirm").performClick()
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.pickImage() {
    onNodeWithContentDescription("Add").performClick()
    onNodeWithText("Images").performClick()
}
