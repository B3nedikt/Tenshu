package app.tenshu.drive.integration

import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import org.tenshu.viewmodel.test.OwnedViewModels
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.test.assertEquals

/**
 * **G3 for Drive** (`core-paging-scroll-restoration.md`): the loaded pages must survive the screen
 * leaving composition, which is what makes a deep-scrolled folder come back where you left it
 * rather than clamped to the top.
 *
 * ### Why the assertion is on the page window, not on a scroll offset
 *
 * `LazyListState`'s index is already saved and restored correctly — `rememberSaveable` under the
 * nav host's `SaveableStateHolder` does that, and has all along. The bug was never the offset; it
 * was that the *list* had shrunk back to one page by the time the offset was applied, so
 * `LazyColumn` clamped it. Asserting the window size measures the actual cause, and does it over
 * the real proxy without needing a UI to scroll. (Fit's `HistoryNavRoundTripTest` covers the same
 * thing end-to-end through a real `LazyColumn`; the two are complementary, not duplicates.)
 *
 * ### What "leaving composition" is, here
 *
 * A `TenshuPagingPresenter` **is** the presenter `LazyPagingItems` wraps, so cancelling its
 * collection and building a fresh one is exactly what a nav round-trip does to the screen: the
 * `LazyPagingItems` is `remember`ed, not saved, so it is rebuilt from scratch — while the view model
 * (and therefore `viewModelScope`, and therefore the `cachedIn` cache) survives below it.
 */
class FilesPagingRoundTripTest {

    private lateinit var filesRepository: FilesRepository
    private lateinit var viewModel: FilesScreenViewModel

    /** Three pages' worth at Drive's `pageSize = initialLoadSize = 20`. */
    private val fileCount = 55

    private lateinit var appKoin: AppIntegrationKoin

    /** The view model is built in `@Before`, so its owner lives in a field and closes in `@After`. */
    private val owned = OwnedViewModels()

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        filesRepository = appKoin.get()
        viewModel = owned.own(FilesScreenViewModel(
            filesRepository = filesRepository,
            selectionViewModel = owned.own(SelectionViewModel(
                filesRepository,
                appKoin.get<OfflinePreferences>(),
                appKoin.get<SyncRulesPreferences>(),
            )),
            folderPath = "/",
        ))
    }

    @After
    fun tearDown() {
        // View models first (they collect the proxy's flows), then the proxy — and joined, so
        // the shutdown's last emission has no collector left to dispatch onto `Main`.
        runBlocking { owned.closeAll() }
        owned.assertNothingActive()
        appKoin.stop()
    }

    @Test
    fun loadedPagesSurviveTheScreenLeavingComposition() = runBlocking {
        repeat(fileCount) { i ->
            filesRepository.uploadFile(
                uriString = "mock://file$i.txt",
                parentPath = "/",
                fileName = "file${i.toString().padStart(3, '0')}.txt",
            )
        }

        val scope = owned.ownScope(CoroutineScope(Dispatchers.Default))
        try {
            val first = TenshuPagingPresenter<FileUiModel>(scope, Dispatchers.Default)
            val firstCollection = first.collect(viewModel.pagedFiles)
            first.awaitAtLeast(20)

            // Page forward the way a scrolling user does — `get` is what records the access that
            // triggers the append (the very contract `TenshuPagingPresenter`'s KDoc warns about).
            val loaded = pageToAll(first)
            assertEquals(
                fileCount,
                loaded,
                "the fixture never paged to the end — there would be nothing to lose",
            )

            // Leave composition: the presenter's collection goes, the view model stays. ⚠ The
            // *job*, not the scope — the scope stands in for the process, not the screen.
            firstCollection.cancel()

            // Come back: a brand-new presenter over the SAME view-model flow.
            val second = TenshuPagingPresenter<FileUiModel>(scope, Dispatchers.Default)
            second.collect(viewModel.pagedFiles)
            // ⚠ Fall back to whatever it settled on rather than letting the wait time out: the
            // failure this test exists to catch is "came back with fewer rows", and a
            // TimeoutCancellationException reports that as a hang instead of naming the number.
            val restored = runCatching { second.awaitAtLeast(loaded, timeoutMs = 5_000).size }
                .getOrElse { second.snapshot.value.size }

            assertEquals(
                loaded,
                restored,
                "the feed came back with fewer rows than it had — without cachedIn this is one " +
                    "page, and LazyListState's restored index clamps into it",
            )
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    /**
     * Pages to the end of the fixture — `get` records the access that triggers an append, so this is
     * the programmatic form of scrolling.
     *
     * ⚠⚠ Loops until the **known total** rather than until a page fails to arrive. The first draft
     * treated "no growth within 2 s" as "no more data", which is a race, not a signal: under a full
     * `./gradlew desktopTest jvmTest test` the appends run through the real in-process proxy against
     * a loaded machine, an append missed its window, and the test failed with *"never paged past the
     * first window (loaded=20)"* — green in isolation, red in the suite. The data here is finite and
     * counted, so waiting for [fileCount] is deterministic; a slow append just costs another poke.
     */
    private suspend fun pageToAll(presenter: TenshuPagingPresenter<FileUiModel>): Int {
        withTimeout(60_000) {
            while (presenter.snapshot.value.size < fileCount) {
                val before = presenter.snapshot.value.size
                presenter.get(before - 1)
                withTimeoutOrNull(5_000) { presenter.snapshot.first { it.size > before } }
            }
        }
        return presenter.snapshot.value.size
    }

    private suspend fun TenshuPagingPresenter<FileUiModel>.awaitAtLeast(
        n: Int,
        timeoutMs: Long = 10_000,
    ): List<FileUiModel> = withTimeout(timeoutMs) { snapshot.first { it.size >= n } }
}
