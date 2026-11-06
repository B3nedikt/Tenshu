package app.tenshu.drive.integration

import androidx.paging.PagingData
import app.tenshu.drive.integration.utils.PagingSettleProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.boundedSnapshot
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortBy
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.File
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.name
import org.tenshu.core.file.path
import org.tenshu.core.fold
import org.tenshu.core.lowercase
import org.tenshu.file.client.fileService
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.tenshu.viewmodel.test.OwnedViewModels

/**
 * End-to-end coverage for [FilesRepository.getFilesPaged] over the real
 * in-process proxy chain: it must project the same metadata as `getFiles` and
 * page across `drop().take()` window boundaries (the whole point of
 * `:core-paging`'s `toPagingData` + `TenshuPagingSource`).
 */
class FilesPagingIntegrationTest {

    private lateinit var filesRepository: FilesRepository
    private lateinit var probeScope: CoroutineScope

    private lateinit var appKoin: AppIntegrationKoin

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        filesRepository = appKoin.get()
        probeScope = CoroutineScope(Job())
    }

    @After
    fun tearDown() {
        // cancel-AND-JOIN, per OwnedViewModels' rule: a bare cancel returns while the presenter's
        // collectors are still unwinding, and a throw after the test has finished lands on whatever
        // runs next.
        runBlocking { probeScope.coroutineContext.job.cancelAndJoin() }
        appKoin.stop()
    }

    /**
     * [boundedSnapshot] with the lid off: same chain, same assertions, but driven through a
     * [PagingSettleProbe] so a failure to settle prints the differ's **load-state timeline** —
     * generations, `CombinedLoadStates` and `PagingDataEvent`s, in order.
     *
     * ⚠⚠ **This swaps the presenter, and that is the experiment.** `asSnapshot` owns its presenter
     * and exposes none of its state, so the listing-never-settles hang (`docs/tech-debt.md`
     * § "The hang, narrowed to one seam") cannot name its cause through it. Driving our own
     * `PagingDataPresenter` is the only way to see inside — and it has two informative outcomes:
     * the hang reproduces here and hands over its timeline, or it does **not** reproduce here,
     * which implicates `paging-testing`'s own presenter rather than our pipeline.
     *
     * ⚠ Used only at the call sites that have hung. The others keep `boundedSnapshot`, so the
     * suite still exercises the real `asSnapshot` path, and so a presenter that silently does not
     * hang cannot mask the bug.
     */
    private suspend fun Flow<PagingData<FileModel>>.settledItemsWithTimeline(): List<FileModel> {
        val probe = PagingSettleProbe<FileModel>(probeScope)
        val items = probe.awaitSettle(this)
        // Printed on success too: a healthy timeline is the control a sick one is read against.
        println("PROBE-TIMELINE (settled)\n" + probe.timelineSnapshot().joinToString("\n"))
        return items
    }

    @Test
    fun pagedListingReturnsUploadedFiles() = runBlocking {
        repeat(3) { i ->
            filesRepository.uploadFile(
                uriString = "mock://file$i.jpg",
                parentPath = "/",
                fileName = "file$i.jpg"
            )
        }

        // ⚠ Probe-driven: this site has hit the listing-never-settles hang (`docs/tech-debt.md`
        // § "The hang, narrowed to one seam"), and the probe yields its load-state timeline.
        // ⚠⚠ Deliberately NOT applied to every site in the class: if the presenter path does
        // not hang, converting everything would hide the bug instead of catching it. Both paths
        // stay in the population.
        val snapshot: List<FileModel> = filesRepository.getFilesPaged("/").settledItemsWithTimeline()

        val names = snapshot.map { it.name }
        assertEquals(3, snapshot.size)
        assertTrue(names.containsAll(listOf("file0.jpg", "file1.jpg", "file2.jpg")))
    }

    @Test
    fun folderRow_carriesChildCount_withoutProjectingChildren() = runBlocking {
        // The "N files" subline resolves through `Folder.FileCount` (a
        // member-hash count on the proxy) — NOT by projecting the folder's
        // FILES field, which would materialise and ship every child entity per
        // folder row. Pins the count round-trip over the real in-process
        // proxy, including that a plain-file row in the same mixed listing
        // tolerates the Folder-only relation (skipped, not crashed).
        filesRepository.createFolder("/docs")
        repeat(4) { i ->
            filesRepository.uploadFile(
                uriString = "mock://doc$i.txt",
                parentPath = "/docs",
                fileName = "doc$i.txt"
            )
        }
        filesRepository.uploadFile(
            uriString = "mock://loose.txt",
            parentPath = "/",
            fileName = "loose.txt"
        )

        // ⚠ This call has hung a gate — see [boundedSnapshot], which carries the mechanism and
        // the standing "do not raise this" rule.
        val snapshot: List<FileModel> =
            filesRepository.getFilesPaged("/").settledItemsWithTimeline()
        val folderRow = snapshot.filterIsInstance<FileModel.Folder>().single { it.name == "docs" }
        assertEquals("4 files", folderRow.subLine)
        assertTrue(snapshot.any { it.name == "loose.txt" }, "file rows must survive the Folder-only relation")
    }

    @Test
    fun pagedListingIsMetadataOnly_previewLoadsPerRow() = runBlocking<Unit> {
        // The paged listing is METADATA ONLY by design: `File.Preview` inside
        // the page projection would resolve synchronously per row (full-file
        // read+decrypt+decode for files without stored thumbnails) and block
        // the first paint of a large folder. Row thumbnails load through
        // the per-row loader instead (`HorizontalFileItem` → `loadPreviewBytes`
        // → `FilesRepository.getPreview`): viewport-driven, bounded, cancelled
        // on scroll. Pin both halves of that contract here, over the real
        // in-process proxy: (1) paged rows carry NO inline preview bytes,
        // (2) the per-row fetch resolves the preview for the same file.
        filesRepository.uploadFile(
            uriString = "mock://photo.jpg?w=8&h=8",
            parentPath = "/",
            fileName = "photo.jpg"
        )

        val snapshot: List<FileModel> =
            filesRepository.getFilesPaged("/").settledItemsWithTimeline()
        val photo = snapshot.single { it.name == "photo.jpg" }
        assertEquals(
            null,
            photo.previewBytes,
            "the paged projection must stay metadata-only — an inline " +
                "File.Preview resolves per row and blocks the first paint"
        )

        assertNotNull(
            filesRepository.getPreview(photo.path, width = 100, height = 100),
            "the per-row loader path must resolve the preview the projection omits"
        )
    }

    @Test
    fun pagedListingEmitsNewGenerationWhenFolderCreatedWhileCollecting() = runBlocking {
        // Over the REAL proxy: with ONE collection
        // held open (as `collectAsLazyPagingItems` does on the live screen), a
        // folder created while collecting must drive the `pagingSource()`
        // factory observer to invalidate -> emit a NEW PagingData generation.
        // Counting generations (not asSnapshot) keeps this bounded — no hang.
        filesRepository.createFolder("/alpha")

        // Collect in a DETACHED scope so `runBlocking` doesn't await the
        // collector's teardown (the paged observer's `endStream` finalizer can
        // itself hang).
        val collectorScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
        )
        val generations = java.util.concurrent.atomic.AtomicInteger(0)
        collectorScope.launch {
            filesRepository.getFilesPaged("/").collect { _: PagingData<FileModel> ->
                generations.incrementAndGet()
            }
        }

        // Wait for the initial generation to land.
        withTimeout(5_000) { while (generations.get() < 1) delay(20) }
        val afterInitial = generations.get()

        // Create a folder while the same collection stays open.
        filesRepository.createFolder("/beta")

        // The live-invalidation must produce a new generation. Bounded wait.
        val newGeneration = try {
            withTimeout(5_000) {
                while (generations.get() <= afterInitial) delay(20)
                true
            }
        } catch (e: TimeoutCancellationException) {
            false
        }
        collectorScope.cancel()

        assertTrue(
            newGeneration,
            "creating a folder while collecting must emit a new paging generation " +
                "via live invalidation (had $afterInitial generations, no new one arrived)"
        )
    }

    @Test
    fun sortByName_ordersDerivedNames_bothDirections() = runBlocking {
        // The Name half of the "Sort by" data path, mirroring Drive's applySort:
        // the SELECTOR-LAMBDA form with a `.lowercase()` fold (`sortBy {
        // name.lowercase() }`), because File.Name is a derived dynamic property the
        // property-handle branch can't read, and case-insensitivity is a call-site
        // choice (the engine comparator stays case-sensitive). MIXED CASE on
        // purpose: case-insensitive asc = apple, Banana, Cherry; a case-SENSITIVE
        // compare would put "Banana"/"Cherry" (uppercase) before "apple", so the
        // assertion discriminates the lowercase() fold, not just the sort.
        val client: TenshuClient = appKoin.get()
        listOf("Banana.txt", "apple.txt", "Cherry.txt").forEach { name ->
            filesRepository.uploadFile(uriString = "mock://$name", parentPath = "/", fileName = name)
        }

        suspend fun sortedNames(descending: Boolean): List<String> =
            client.fileService.defaultFileSystem.root.files
                .let {
                    if (descending) it.sortByDescending { name.lowercase() }
                    else it.sortBy { name.lowercase() }
                }
                .select { fields(File.Field.PATH) }
                .flow()
                .first()
                .fold(
                    onSuccess = { list ->
                        list.toList().map { (it as Entity<out File>).path.get().substringAfterLast("/") }
                    },
                    onFailure = { emptyList() },
                )

        assertEquals(listOf("apple.txt", "Banana.txt", "Cherry.txt"), sortedNames(descending = false))
        assertEquals(listOf("Cherry.txt", "Banana.txt", "apple.txt"), sortedNames(descending = true))
    }

    // The Date-modified direction toggle lives in [SortToggleIntegrationTest] — it is
    // a *sort* case and that is where the sort cases are.

    @Test
    fun sortedListingSettles_soItIsReadableLikeAnyOtherListing() = runBlocking<Unit> {
        // ⚠⚠ THE GUARD on the sorted listing's load states, and the reason
        // it is written as a bounded `asSnapshot()` rather than an ordering assertion:
        // `asSnapshot` returns on exactly two conditions — the differ's refresh
        // settles, or the upstream flow COMPLETES. The sorted overload's `.flow()` is
        // a live folder listener that never completes, so the load states are its only
        // exit. Drop them (the deprecated single-argument `PagingData.from`, which
        // pins all three in `Loading`) and this call waits forever.
        //
        // ⚠ Hence the bound, and it is load-bearing rather than tidiness: a killed
        // test worker records the case as SKIPPED, so the run still reports
        // failures="0" and the regression is SILENT. The deadline converts that into an
        // ordinary loud failure; [boundedSnapshot] keeps the rule in one spelling.
        //
        // The same states drive the end-of-list footer and the "loading more"
        // indicator, so this pins a UI contract too: one whole-folder page, nothing
        // more to append.
        filesRepository.uploadFile(uriString = "mock://a.txt", parentPath = "/", fileName = "a.txt")

        val snapshot = filesRepository
            .getFilesPaged("/", FileSort(FileSortKey.MODIFIED, descending = false))
            .boundedSnapshot()

        assertEquals(listOf("a.txt"), snapshot.map { it.name })
    }

    @Test
    fun thenBy_breaksPrimaryKeyTiesByName() = runBlocking {
        // Pins the `thenBy` fold end-to-end over the real proxy: `sortBy(File.Size)`
        // where every row TIES (folders are all size 0), so the ordering is decided
        // entirely by the `.thenBy { name.lowercase() }` secondary key (mirroring
        // Drive's applySort tiebreak). Mixed case + out of insertion order — without
        // thenBy the size-tie would keep stable INSERTION order (Charlie/apple/
        // Banana); case-insensitive alphabetical output proves the secondary key
        // took effect AND the lowercase() fold composes inside a thenBy selector
        // (a property primary + a transformed lambda secondary).
        val client: TenshuClient = appKoin.get()
        listOf("Charlie", "apple", "Banana").forEach { filesRepository.createFolder("/$it") }

        val ordered = client.fileService.defaultFileSystem.root.files
            .sortBy(File.Size)
            .thenBy { name.lowercase() }
            .select { fields(File.Field.PATH) }
            .flow()
            .first()
            .fold(
                onSuccess = { list ->
                    list.toList().map { (it as Entity<out File>).path.get().substringAfterLast("/") }
                },
                onFailure = { emptyList() },
            )

        assertEquals(listOf("apple", "Banana", "Charlie"), ordered)
    }

    @Test
    fun pagingLoadsBeyondTheFirstPage() = runBlocking {
        // Default page size is 20 (core-common's pagingSource). Seed enough to
        // force a second page so we actually exercise pagination, not just one
        // window.
        val total = 25
        repeat(total) { i ->
            val name = "f%02d.jpg".format(i)
            filesRepository.uploadFile(
                uriString = "mock://$name",
                parentPath = "/",
                fileName = name
            )
        }

        val snapshot: List<FileModel> = filesRepository.getFilesPaged("/").boundedSnapshot {
            // Drives a second load() past the first 20-item window.
            scrollTo(total - 1)
        }

        assertEquals(total, snapshot.size)
    }
}
