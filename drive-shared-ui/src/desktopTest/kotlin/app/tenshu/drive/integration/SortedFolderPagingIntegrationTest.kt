package app.tenshu.drive.integration

import app.tenshu.drive.data.FileModel
import app.tenshu.drive.data.FileSort
import app.tenshu.drive.data.FileSortKey
import app.tenshu.drive.data.FilesRepository
import app.tenshu.drive.data.FilesRepositoryPlatformDelegate
import app.tenshu.drive.integration.utils.AppIntegrationKoin
import app.tenshu.drive.integration.utils.boundedSnapshot
import app.tenshu.drive.integration.utils.startAppIntegrationKoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.file.system.moveFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.core.paging.TenshuPagingPresenter
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sorted folder listing **pages** — `drive-sorted-folder-paging.md` SP-3, over the real
 * in-process proxy. Until 2026-09-15 `getFilesPaged(path, sort)` loaded and sorted the whole
 * folder as one page and grouped folders first on the client; it is now the same paged pipeline
 * as the natural listing with the order in the chain, a projected change observer and a
 * per-generation row dedupe. Each test here pins one of the four blockers §3 of that doc named:
 *
 *  - B1 — folders first is a *sort key*, so it holds across page boundaries, not within a page;
 *  - B3 — the Name key and every tie-break are case-insensitive (`path.lowercase()`);
 *  - B5 — an in-place overwrite (same path, new size and date) reorders the live listing, which
 *    only a *projected* observer can see;
 *  - B4 — writes faster than the debounce still rebuild within the maximum wait, a rebuild during
 *    an in-flight scroll does not throw, and no row is ever served twice.
 *
 * `SortToggleIntegrationTest` covers the direction toggle through the view model;
 * `FilesListingEfficiencyTest` pins that each sorted page is one fused thin pass.
 */
class SortedFolderPagingIntegrationTest {

    private lateinit var filesRepository: FilesRepository
    private lateinit var appKoin: AppIntegrationKoin
    private lateinit var scope: CoroutineScope

    @Before
    fun setup() {
        appKoin = startAppIntegrationKoin(clientId = "app.tenshu.drive.test")
        filesRepository = appKoin.get()
        scope = CoroutineScope(Job() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        // cancel-AND-JOIN, per OwnedViewModels' rule: a bare cancel returns while a presenter's
        // collectors are still unwinding, and a throw after the test has finished lands on
        // whatever runs next.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        appKoin.stop()
    }

    private val newestFirst = FileSort(FileSortKey.MODIFIED, descending = true)
    private val byName = FileSort(FileSortKey.NAME, descending = false)

    /** Uploads with strictly increasing modified times — three uploads inside one millisecond tie. */
    private suspend fun uploadSpaced(names: List<String>, parent: String = "/") {
        names.forEach { name ->
            filesRepository.uploadFile(uriString = "mock://$name", parentPath = parent, fileName = name)
            delay(5)
        }
    }

    private suspend fun TenshuPagingPresenter<FileModel>.awaitNames(expected: List<String>, label: String) {
        try {
            withTimeout(TIMEOUT_MS) { snapshot.first { snap -> snap.map { it.name } == expected } }
        } catch (e: Throwable) {
            throw AssertionError(
                "$label: expected $expected, last snapshot was ${snapshot.value.map { it.name }}",
                e,
            )
        }
    }

    @Test
    fun foldersFirst_holdsAcrossPageBoundaries_underADateSort() = runBlocking {
        // B1. 45 files then 2 folders: the folders are the NEWEST rows, but they must lead the
        // listing — on page 1, ahead of files that page 3 would otherwise never see. A per-page
        // partition (the old client-side `foldersFirst`) cannot do this; a first sort key can.
        val files = (0 until 45).map { "f%02d.txt".format(it) }
        uploadSpaced(files)
        filesRepository.createFolder("/Beta")
        filesRepository.createFolder("/alpha")

        val snapshot = filesRepository.getFilesPaged("/", newestFirst).boundedSnapshot { scrollTo(46) }

        assertEquals(47, snapshot.size)
        assertEquals(setOf("Beta", "alpha"), snapshot.take(2).map { it.name }.toSet(), "folders lead")
        assertTrue(snapshot.take(2).all { it is FileModel.Folder })
        assertEquals(files.reversed(), snapshot.drop(2).map { it.name }, "then every file, newest first")
        assertEquals(snapshot.size, snapshot.map { it.path }.toSet().size, "no row served twice")
    }

    /**
     * "Modified" orders by the file's own date, not by when it reached the realm. Uploaded in
     * the reverse of their age, a sort on the upload moment (`File.LastModifiedAt`, the key until
     * FileService v17) answers with exactly the opposite order — which is what a library backed
     * up in one day looked like: a 2011 photo on top because it happened to upload last.
     */
    @Test
    fun dateSort_ordersByTheFilesOwnDate_notByWhenItWasUploaded() = runBlocking {
        val fileSystem = appKoin.get<TenshuClient>().fileService.defaultFileSystem
        // Newest content first into the realm, oldest last.
        listOf("y2024.jpg" to 1_704_067_200_000L, "y2018.jpg" to 1_514_764_800_000L, "y2011.jpg" to 1_300_000_000_000L)
            .forEach { (name, ownDate) ->
                assertTrue(fileSystem.uploadFile("/", name, byteArrayOf(1), sourceModifiedAt = ownDate) is TResult.Success)
                delay(5)
            }

        val newest = filesRepository.getFilesPaged("/", newestFirst).boundedSnapshot()
        assertEquals(listOf("y2024.jpg", "y2018.jpg", "y2011.jpg"), newest.map { it.name })

        val oldest = filesRepository
            .getFilesPaged("/", FileSort(FileSortKey.MODIFIED, descending = false))
            .boundedSnapshot()
        assertEquals(listOf("y2011.jpg", "y2018.jpg", "y2024.jpg"), oldest.map { it.name })
    }

    /**
     * A folder has no content date; under "Modified" folders order by the date they show, which
     * is `LastModifiedAt`. Renaming the OLDER folder makes it the most recently modified one —
     * a tie-break on the creation date, or on the name, would leave it second.
     */
    @Test
    fun dateSort_ordersFoldersByWhenTheyWereLastTouched() = runBlocking {
        val fileSystem = appKoin.get<TenshuClient>().fileService.defaultFileSystem
        filesRepository.createFolder("/older")
        delay(5)
        filesRepository.createFolder("/newer")
        delay(5)
        assertTrue(fileSystem.moveFile("/older", "/zz-renamed") is TResult.Success)

        val snapshot = filesRepository.getFilesPaged("/", newestFirst).boundedSnapshot()

        assertEquals(listOf("zz-renamed", "newer"), snapshot.map { it.name })
    }

    @Test
    fun nameSort_isCaseInsensitive_andFoldersLead() = runBlocking {
        // B3. Mixed case on purpose: a case-SENSITIVE key would put "Banana"/"Cherry" before
        // "apple". The key and the tie-break are `path.lowercase()`, which orders siblings
        // exactly like the derived `name.lowercase()` it replaced. 22 numbered files push the
        // named ones across the 20-row page boundary.
        uploadSpaced(listOf("Cherry.txt", "apple.txt", "Banana.txt") + (0 until 22).map { "z%02d.txt".format(it) })
        filesRepository.createFolder("/zeta")
        filesRepository.createFolder("/Alpha")

        val snapshot = filesRepository.getFilesPaged("/", byName).boundedSnapshot { scrollTo(26) }

        val expected = listOf("Alpha", "zeta", "apple.txt", "Banana.txt", "Cherry.txt") +
            (0 until 22).map { "z%02d.txt".format(it) }
        assertEquals(expected, snapshot.map { it.name })
    }

    @Test
    fun anInPlaceOverwrite_reordersTheLiveDateListing() = runBlocking {
        // B5. `ReplaceFile` keeps the path (the row's identity) and bumps size + modified time.
        // A bare observer over `Folder.files` diffs composites by path alone and would signal
        // nothing — the listing would stay [c, b, a] with `a` now the newest. The projected
        // observer diffs rows as entities and sees the date move.
        uploadSpaced(listOf("a.txt", "b.txt", "c.txt"))
        val presenter = TenshuPagingPresenter<FileModel>(scope, Dispatchers.Default)
        presenter.collect(filesRepository.getFilesPaged("/", newestFirst))
        presenter.awaitNames(listOf("c.txt", "b.txt", "a.txt"), "before the overwrite")

        val client: TenshuClient = appKoin.get()
        val delegate: FilesRepositoryPlatformDelegate = appKoin.get()
        delegate.replacePlatformFile(client, parentPath = "/", fileName = "a.txt", uriString = "mock://a.txt?w=8&h=8")

        presenter.awaitNames(listOf("a.txt", "c.txt", "b.txt"), "after overwriting a.txt in place")
    }

    @Test
    fun anInPlaceOverwrite_ofAnUnloadedTailRow_isServedAtTheTop() = runBlocking {
        // B5, the shape §7 SP-3 actually asks for: the overwritten row sits in the UNLOADED tail
        // of a 45-row date listing (only page 1's 20 rows are on screen), and its new modified
        // time moves it above the loaded boundary. Nothing is duplicated — the row is simply
        // missing until a rebuild serves it — so the dedupe cannot help; only the observer
        // signalling on a property change and the reload that follows can. It is also the
        // update-after-baseline that would catch a projection missing `PATH`.
        val seeded = (0 until 45).map { "seed%02d.txt".format(it) }
        uploadSpaced(seeded)
        val presenter = TenshuPagingPresenter<FileModel>(scope, Dispatchers.Default)
        presenter.collect(filesRepository.getFilesPaged("/", newestFirst))
        withTimeout(TIMEOUT_MS) { presenter.snapshot.first { it.size >= 20 } }
        val loaded = presenter.snapshot.value.map { it.name }
        assertEquals("seed44.txt", loaded.first(), "newest first")
        assertTrue("seed00.txt" !in loaded, "the oldest row must start in the unloaded tail: $loaded")

        val client: TenshuClient = appKoin.get()
        val delegate: FilesRepositoryPlatformDelegate = appKoin.get()
        delegate.replacePlatformFile(client, parentPath = "/", fileName = "seed00.txt", uriString = "mock://seed00.txt?w=8&h=8")

        try {
            withTimeout(TIMEOUT_MS) { presenter.snapshot.first { it.firstOrNull()?.name == "seed00.txt" } }
        } catch (e: Throwable) {
            throw AssertionError(
                "the overwritten tail row never reached the top of the loaded window; last snapshot: " +
                    presenter.snapshot.value.map { it.name },
                e,
            )
        }
        val after = presenter.snapshot.value
        assertEquals(after.size, after.map { it.path }.toSet().size, "no row served twice after the reload")
        assertEquals(listOf("seed00.txt", "seed44.txt", "seed43.txt"), after.take(3).map { it.name })
    }

    @Test
    fun anUploadIntoASubfolder_refreshesItsFileCount_underANameSort() = runBlocking {
        // B5's third case: `Folder.FileCount` changes with every upload into a subfolder while
        // the parent listing shows the folder row. The parent's own array is untouched, so only
        // the projected observer — which resolved the count and therefore registered the
        // subfolder's array — can see it. Under a Name sort nothing moves; the subline does.
        filesRepository.createFolder("/sub")
        uploadSpaced(listOf("one.txt"), parent = "/sub")
        uploadSpaced(listOf("a.txt"))
        val presenter = TenshuPagingPresenter<FileModel>(scope, Dispatchers.Default)
        presenter.collect(filesRepository.getFilesPaged("/", byName))

        suspend fun awaitCount(expected: String, label: String) {
            try {
                withTimeout(TIMEOUT_MS) {
                    presenter.snapshot.first { snap -> snap.firstOrNull { it.name == "sub" }?.subLine == expected }
                }
            } catch (e: Throwable) {
                throw AssertionError(
                    "$label: expected the folder row to read '$expected', last snapshot was " +
                        presenter.snapshot.value.map { "${it.name}: ${it.subLine}" },
                    e,
                )
            }
        }
        awaitCount("1 files", "before the upload")
        assertEquals(listOf("sub", "a.txt"), presenter.snapshot.value.map { it.name }, "folder first, then the file")

        uploadSpaced(listOf("two.txt"), parent = "/sub")

        awaitCount("2 files", "after an upload into the subfolder")
        assertEquals(listOf("sub", "a.txt"), presenter.snapshot.value.map { it.name }, "a Name sort keeps its order")
    }

    @Test
    fun anInPlaceOverwrite_refreshesTheSubline_underANameSort() = runBlocking {
        // B5, the half a Name sort exposes: the order does not change (the path is the key), but
        // the row's size and date do, and `FileMapper` renders both in the subline. The same
        // projected observer has to see a change that moves nothing.
        uploadSpaced(listOf("a.txt", "b.txt"))
        val presenter = TenshuPagingPresenter<FileModel>(scope, Dispatchers.Default)
        presenter.collect(filesRepository.getFilesPaged("/", byName))
        presenter.awaitNames(listOf("a.txt", "b.txt"), "before the overwrite")
        val before = presenter.snapshot.value.first { it.name == "a.txt" }.subLine

        val client: TenshuClient = appKoin.get()
        val delegate: FilesRepositoryPlatformDelegate = appKoin.get()
        delegate.replacePlatformFile(client, parentPath = "/", fileName = "a.txt", uriString = "mock://a.txt?w=64&h=64")

        try {
            withTimeout(TIMEOUT_MS) {
                presenter.snapshot.first { snap -> snap.firstOrNull { it.name == "a.txt" }?.subLine.let { it != null && it != before } }
            }
        } catch (e: Throwable) {
            throw AssertionError(
                "the overwritten row's subline never refreshed (still '$before'): " +
                    "the sorted listing's observer is not seeing in-place property changes",
                e,
            )
        }
        assertEquals(listOf("a.txt", "b.txt"), presenter.snapshot.value.map { it.name }, "a Name sort keeps its order")
    }

    @Test
    fun continuousWritesDuringAScroll_rebuildWithinTheMaxWait_andNeverDuplicateARow() = runBlocking {
        // B4. A backup writes faster than the 400 ms debounce, so a pure trailing debounce would
        // never rebuild until the burst ended — and every write under "newest first" lands at
        // the HEAD, ahead of every loaded page. Three things must hold at once while the writes
        // keep coming: the listing rebuilds within the maximum wait; a rebuild landing while the
        // reader is paging forward does not throw (the `IndexOutOfBoundsException` the debounce
        // KDoc records); and no snapshot ever holds a row twice, because Drive keys its list by
        // path and a duplicate key is a crash.
        //
        // ⚠⚠ ONE collector, deliberately. A first draft ran a timing collector beside a
        // `boundedSnapshot` of the same chain and saw the signals stop dead at ~500 ms: two
        // observers of an identical chain in one session share a stream id
        // (`LocalRequestChain.createStreamId` hashes the callstack), so the snapshot's close
        // ended the other's registration too. `docs/tech-debt.md` "Two identical observers in
        // one session share a stream id". The presenter here is the screen's shape anyway.
        val seeded = (0 until 45).map { "seed%02d.txt".format(it) }
        uploadSpaced(seeded)

        val presenterScope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val startedAt = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
            val generationsAt = mutableListOf<Long>()
            val presenter = TenshuPagingPresenter<FileModel>(presenterScope, Dispatchers.Default)
            presenter.collect(
                filesRepository.getFilesPaged("/", newestFirst).onEach { synchronized(generationsAt) { generationsAt += elapsedMs() } },
            )
            withTimeout(TIMEOUT_MS) { presenter.snapshot.first { it.isNotEmpty() } }

            val burst = scope.launch {
                repeat(BURST_SIZE) { i ->
                    filesRepository.uploadFile(uriString = "mock://live$i.txt", parentPath = "/", fileName = "live%02d.txt".format(i))
                    delay(BURST_INTERVAL_MS)
                }
            }
            val burstStartedAt = elapsedMs()

            // Page forward the whole time — `get` on the last loaded row is what a scrolling
            // screen does — and check every snapshot on the way for a repeated path.
            while (burst.isActive) {
                val snap = presenter.snapshot.value
                val duplicates = snap.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys
                assertTrue(duplicates.isEmpty(), "a snapshot held a row twice mid-burst: $duplicates")
                if (snap.isNotEmpty()) presenter.get(snap.lastIndex)
                delay(25)
            }
            val burstEndedAt = elapsedMs()
            assertTrue(presenterScope.isActive, "a rebuild during paging must not throw and kill the presenter")

            // Rebuilt mid-burst: at least one generation landed after the first second of the
            // burst and before it ended. The ceiling fires at ~3 s into a burst of ~4 s or more.
            val midBurst = synchronized(generationsAt) { generationsAt.filter { it in (burstStartedAt + 1_000)..(burstEndedAt - 200) } }
            assertTrue(
                midBurst.isNotEmpty(),
                "no rebuild landed while the burst was still running (${burstStartedAt}..${burstEndedAt} ms); " +
                    "generations at ${synchronized(generationsAt) { generationsAt.toList() }} ms — the bounded reload is not firing",
            )

            // Settled: keep paging until every row is there, newest first, each exactly once.
            val expected = (BURST_SIZE - 1 downTo 0).map { "live%02d.txt".format(it) } + seeded.reversed()
            try {
                withTimeout(TIMEOUT_MS) {
                    while (presenter.snapshot.value.map { it.name } != expected) {
                        val snap = presenter.snapshot.value
                        if (snap.isNotEmpty()) presenter.get(snap.lastIndex)
                        delay(25)
                    }
                }
            } catch (e: Throwable) {
                throw AssertionError(
                    "the listing never settled to all ${expected.size} rows newest-first; last snapshot: " +
                        presenter.snapshot.value.map { it.name },
                    e,
                )
            }
            val settled = presenter.snapshot.value
            assertEquals(settled.size, settled.map { it.path }.toSet().size, "no row served twice once settled")
        } finally {
            presenterScope.coroutineContext.job.cancelAndJoin()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** ~4 s of writes at 100 ms — longer than the 3 s reload ceiling, faster than the 400 ms debounce. */
        const val BURST_SIZE = 40
        const val BURST_INTERVAL_MS = 100L
    }
}
