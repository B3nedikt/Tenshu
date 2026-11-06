package app.tenshu.drive.integration

import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.NoopSyncRulesPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.TenshuDisplayPreferences
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import app.tenshu.drive.ui.model.FileUiModel
import app.tenshu.drive.ui.screens.files.FilesScreenViewModel
import app.tenshu.drive.ui.selection.SelectionViewModel
import org.tenshu.viewmodel.test.vmBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.paging.TenshuPagingPresenter

/**
 * The "Sort by" menu's **live** data path, composed exactly as iOS composes it
 * (`DriveReadSurface.create`): a real [FilesScreenViewModel] over the real
 * [TenshuDisplayPreferences], with one long-lived [TenshuPagingPresenter] collecting
 * `pagedFiles` for the screen's whole lifetime — then the sort flipped underneath it.
 *
 * The neighbouring tests stop short of this: `DisplayPreferencesIntegrationTest`
 * re-subscribes a *fresh* `observeSort()` per assertion (so it cannot catch a read flow
 * that emits once and completes), and `FilesPagingIntegrationTest` exercises
 * `sortBy`/`sortByDescending` on the chain directly, below the VM. This pins the whole
 * chain — menu write → preference row → `sort` StateFlow → `flatMapLatest` → a new
 * `PagingData` generation → a reordered presenter snapshot — against an already-running
 * collector, which is what a screen actually is.
 */
class SortToggleIntegrationTest {

    private lateinit var filesRepository: FilesRepository

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        filesRepository = appKoin.get()
    }

    @After
    fun tearDown() {
        appKoin.stop()
    }

    @Test
    fun directionToggleReordersTheLiveSnapshot() = vmBlocking { vms ->
        // ⚠ Upload order is deliberately NOT sorted, and that is load-bearing. Were the store
        // order identical to case-insensitive ascending, an ascending step could be satisfied
        // by the snapshot ALREADY on screen, before any re-sort ran — and a `setSort` that
        // silently did nothing would pass. Upload order differs from every order asserted
        // below, so each step needs a real reorder.
        //
        // Mixed case on purpose: a case-SENSITIVE compare would order these
        // Banana/Cherry/apple, so the ascending assertion discriminates the `lowercase()` fold.
        listOf("Cherry.txt", "apple.txt", "Banana.txt").forEach { name ->
            filesRepository.uploadFile(uriString = "mock://$name", parentPath = "/", fileName = name)
        }

        val client: TenshuClient = appKoin.get()
        val scope = vms.ownScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val viewModel = vms.own(FilesScreenViewModel(
            filesRepository,
            vms.own(SelectionViewModel(filesRepository, NoOfflinePreferences, NoopSyncRulesPreferences)),
            "/",
            TenshuDisplayPreferences(client),
        ))
        val presenter = TenshuPagingPresenter<FileUiModel>(scope, Dispatchers.Default)
        presenter.collect(viewModel.pagedFiles)

        suspend fun awaitNames(expected: List<String>, label: String) {
            try {
                withTimeout(TIMEOUT_MS) {
                    presenter.snapshot.first { snap -> snap.map { it.name } == expected }
                }
            } catch (e: Throwable) {
                throw AssertionError(
                    "$label: expected $expected, last snapshot was " +
                        presenter.snapshot.value.map { it.name },
                    e,
                )
            }
        }

        // There is no natural-order mode (`drive-sorted-folder-paging.md` D5): a fresh view
        // model opens on D6's default, Name ascending, and there is nothing to clear back to.
        awaitNames(listOf("apple.txt", "Banana.txt", "Cherry.txt"), "default sort: name ascending")

        viewModel.setSort(FileSort(FileSortKey.NAME, descending = true))
        awaitNames(listOf("Cherry.txt", "Banana.txt", "apple.txt"), "name descending")

        viewModel.setSort(FileSort(FileSortKey.NAME, descending = false))
        awaitNames(listOf("apple.txt", "Banana.txt", "Cherry.txt"), "back to name ascending")
    }

    @Test
    fun dateModifiedDirectionToggleReordersTheLiveSnapshot() = vmBlocking { vms ->
        // ⚠⚠ The half [directionToggleReordersTheLiveSnapshot] does NOT cover: a
        // PROPERTY-HANDLE key (`File.LastModifiedAt`) rather than Name's selector lambda.
        // `root.files` rows are plain composites (`File : Composite`, so the array resolves
        // `isEntity = false`); an engine that reads a property key off the loaded row reads
        // `null` for every key, every comparison ties, and Drive's always-ascending
        // `.thenBy { name.lowercase() }` decides the order — the same list whichever
        // direction was picked.
        //
        // ⚠ Upload order (b, a, c) is deliberately neither name order nor its reverse:
        // that failure returns name-ascending (a, b, c) for BOTH directions, so
        // each assertion below discriminates it. `delay` between uploads makes the
        // timestamps strictly increasing — `lastModified` is the clock at upload, and
        // three uploads inside one millisecond would tie legitimately.
        listOf("b.txt", "a.txt", "c.txt").forEach { name ->
            filesRepository.uploadFile(uriString = "mock://$name", parentPath = "/", fileName = name)
            delay(20)
        }

        val client: TenshuClient = appKoin.get()
        val scope = vms.ownScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val viewModel = vms.own(FilesScreenViewModel(
            filesRepository,
            vms.own(SelectionViewModel(filesRepository, NoOfflinePreferences, NoopSyncRulesPreferences)),
            "/",
            TenshuDisplayPreferences(client),
        ))
        val presenter = TenshuPagingPresenter<FileUiModel>(scope, Dispatchers.Default)
        presenter.collect(viewModel.pagedFiles)

        // The presenter, not `asSnapshot()` — because this case is about the *toggle*:
        // it needs one long-lived collection that survives a re-sort, which is what the
        // screen does and what `asSnapshot` (one settle, one result) cannot express.
        // `asSnapshot` can read the sorted overload; `FilesPagingIntegrationTest.sortedListingSettles_…`
        // guards that.
        suspend fun awaitNames(expected: List<String>, label: String) {
            try {
                withTimeout(TIMEOUT_MS) {
                    presenter.snapshot.first { snap -> snap.map { it.name } == expected }
                }
            } catch (e: Throwable) {
                throw AssertionError(
                    "$label: expected $expected, last snapshot was " +
                        presenter.snapshot.value.map { it.name },
                    e,
                )
            }
        }

        viewModel.setSort(FileSort(FileSortKey.MODIFIED, descending = false))
        awaitNames(listOf("b.txt", "a.txt", "c.txt"), "date modified ascending (oldest first)")

        viewModel.setSort(FileSort(FileSortKey.MODIFIED, descending = true))
        awaitNames(listOf("c.txt", "a.txt", "b.txt"), "date modified descending (newest first)")
    }

    /** The offline-pin seam is irrelevant here; [SelectionViewModel] just needs one. */
    private object NoOfflinePreferences : OfflinePreferences {
        override fun observe() = flowOf(emptyList<String>())
        override suspend fun setOffline(paths: List<String>) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
