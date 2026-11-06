package org.tenshu.file.proxy

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.GetAll
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortBy
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.thenBy
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.evaluateCallstack
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.implementationFactory
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.contains
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalTo
import org.tenshu.core.file.Category
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.FileService
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.category
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.name
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.fold
import org.tenshu.core.id
import org.tenshu.core.inRange
import org.tenshu.core.isA
import org.tenshu.core.request.RequestObject
import org.tenshu.file.test.createFileProxy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filter-aware ordered-window fusion (`core-ordered-window-fusion.md` §9, phase F4): a
 * `traverse()`/`filter`-headed, sorted, windowed, projected chain runs as ONE thin walk with a
 * bounded stable top-N instead of materialise-everything → filter → full sort → window.
 *
 * ⚠⚠ Every chain here is built with the **real** DSL and the **real** predicates the Photos feed
 * ships (`isMedia()` — `oneOf` over a constant-valued property; `createdAt.inRange(…)` — an integer
 * value property; `notTrashed()` — a *dynamic* property `path.contains("/Trash/")` compared with
 * `equalTo`). A simplified stand-in predicate would prove a fusion the feed never takes.
 *
 * ⚠ The path assertions count executions of `Traverse`/`Filter`/`SortBy`/`Drop`/`Take` through
 * delegates — a row assertion alone passes fused or unfused (cf. `TenshuPagingSourceTest`'s window
 * shape pin), so the count is what tells the two paths apart.
 *
 * **Parity reference.** The unfused result is computed by evaluating the chain's prefix (up to, not
 * including, the sort) on its own — which can never fuse, since nothing sorted follows — and then
 * the sort/window/select suffix over the materialised list, which is exactly what the unfused
 * walker does and is how the fused result must compare.
 */
class FilteredWindowFusionTest {

    private val noon = 1_700_000_000_000L
    private val minute = 60_000L

    private class Counts {
        val byOp = mutableMapOf<String, Int>()
        fun of(id: String) = byOp[id] ?: 0
        fun reset() = byOp.clear()
    }

    private fun counted(proxy: TestProxy): Counts {
        val counts = Counts()
        for (id in listOf(Traverse.ID, Filter.ID, SortBy.ID, Drop.ID, Take.ID)) {
            val delegate = implementationFactory(id) as SyncOperationImplementation
            proxy.additionalImplementations[id] = object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?,
                ): T {
                    counts.byOp[id] = counts.of(id) + 1
                    return delegate.execute(
                        executionContext, operationContext, implementationFactory, caller, params,
                    )
                }
            }
        }
        return counts
    }

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        val env = TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        )
        return env.getService(FileService.ID)
    }

    private suspend fun FileService.upload(parent: String, name: String, capturedAtMs: Long) {
        val result = defaultFileSystem.uploadFile(
            parent, name, byteArrayOf(1, 2, 3), sourceModifiedAt = capturedAtMs,
        )
        assertTrue(result is TResult.Success, "seeding $parent/$name should succeed: $result")
    }

    private suspend fun FileService.folder(path: String) {
        assertTrue(defaultFileSystem.createFolder(path) is TResult.Success, "creating $path")
    }

    /** A realm shaped like a backed-up phone: media at three depths, documents, and a trash. */
    private suspend fun FileService.seedRealm() {
        folder("/Backup")
        folder("/Backup/Camera")
        folder("/Backup/Camera/2023")
        folder("/Trash")
        upload("/Backup/Camera", "IMG_0001.jpg", noon - 5 * minute)
        upload("/Backup/Camera", "IMG_0002.jpg", noon - 4 * minute)
        upload("/Backup/Camera/2023", "IMG_0003.jpg", noon - 3 * minute)
        upload("/Backup/Camera/2023", "IMG_0006.jpg", noon - 3 * minute) // a tie with 0003
        upload("/", "IMG_0004.jpg", noon - 2 * minute)
        upload("/", "IMG_0005.jpg", noon - 1 * minute)
        upload("/", "notes.txt", noon)
        upload("/Backup/Camera", "clip.mp4", noon)
        upload("/Trash", "IMG_9999.jpg", noon + minute) // newest of all, and trashed
    }

    /** The Photos feed page, as `TenshuPhotosRepository.feedPage` issues it. */
    private fun FileService.feedChain(beforeMs: Long, skip: Int, pageSize: Int): TList<Entity<File>> {
        val exclusiveEnd = if (beforeMs == Long.MAX_VALUE) beforeMs else beforeMs + 1
        return defaultFileSystem.root.files
            .traverse()
            .filter<File> { isMedia() }
            .filter<File> { createdAt.inRange(0L, exclusiveEnd) }
            .filter<File> { notTrashed() }
            .sortByDescending(File.CreatedAt)
            .drop(skip)
            .take(pageSize)
            .select {
                fields(File.Field.PATH)
                relations(File.CreatedAt, File.Category)
            }
    }

    private fun TList<*>.callstack(): List<Data> =
        (this as RequestObject).requestChain.getCallstack()

    /** The fused-or-not production evaluation: what `LocalTaskExecutor.execute` runs. */
    private fun TestProxy.evaluate(list: TList<*>): List<Data> {
        val result = evaluateCallstack(
            executionContext = executionContext,
            contextData = null,
            callstack = list.callstack(),
            allowDeferring = false,
        )
        return (result as TList<*>).toList().map { it as Data }
    }

    /**
     * The unfused reference: evaluate everything ahead of the (first) `SortBy` on its own — a
     * chain with no sort cannot reach any ordered-window fusion — then run the sort/window/select
     * suffix in memory over the materialised list. That is the unfused walker's own composition.
     */
    private fun TestProxy.evaluateUnfused(list: TList<*>): List<Data> {
        val callstack = list.callstack()
        val sortAt = callstack.indexOfFirst { (it as? Operation)?.id == SortBy.ID }
        check(sortAt > 0) { "the chain has no sort" }
        val materialised = evaluateCallstack(
            executionContext = executionContext,
            contextData = null,
            callstack = callstack.subList(0, sortAt),
            allowDeferring = false,
        )
        check(materialised is TList<*>) { "prefix did not materialise: $materialised" }
        val result = evaluateCallstack(
            executionContext = executionContext,
            contextData = materialised,
            callstack = callstack.subList(sortAt, callstack.size),
            allowDeferring = false,
        )
        return (result as TList<*>).toList().map { it as Data }
    }

    /** One comparable line per row: its path and every selected property, whatever its shape. */
    private fun signature(row: Data): String {
        val path = when (row) {
            is Entity<*> -> (row as Entity<File>).path.get()
            is Composite -> row.getField<Data>(File.Field.PATH).toString()
            else -> row.toString()
        }
        val props = (row as? Entity<*>)?.properties
            ?.entries
            ?.sortedBy { it.key.get() }
            ?.joinToString { "${it.key.get().substringAfterLast('/')}=${it.value}" }
        return "$path [$props]"
    }

    private fun paths(rows: List<Data>): List<String> = rows.map { signature(it).substringBefore(" [") }

    // ─── the feed takes the fused path ───

    @Test
    fun theFeedPage_takesTheFusedPath_withTheRealPredicates() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        val counts = counted(proxy)

        val page = proxy.evaluate(service.feedChain(Long.MAX_VALUE, skip = 0, pageSize = 3))

        assertEquals(
            listOf("/Backup/Camera/clip.mp4", "/IMG_0005.jpg", "/IMG_0004.jpg"),
            paths(page),
            "newest first across depths, videos included; the trashed photo, the document and " +
                "the folders excluded",
        )
        assertEquals(0, counts.of(Traverse.ID), "the walk is the fusion's, not TraverseImplementation's")
        assertEquals(0, counts.of(Filter.ID), "no predicate runs as a list-wide Filter op")
        assertEquals(0, counts.of(SortBy.ID), "no full in-memory sort")
        assertEquals(0, counts.of(Drop.ID), "the window is consumed")
        assertEquals(0, counts.of(Take.ID), "the window is consumed")
    }

    @Test
    fun theFeedPage_isTheUnfusedPage_rowForRow() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()

        for (skip in 0..3) for (size in 1..8) {
            val chain = service.feedChain(Long.MAX_VALUE, skip, size)
            assertEquals(
                proxy.evaluateUnfused(chain).map(::signature),
                proxy.evaluate(chain).map(::signature),
                "skip=$skip size=$size",
            )
        }
        // The keyset cursor sits ON the tie (0003/0006 share noon-3min): skip=1 must hand back
        // the second of the tied pair, which only a stable selection does.
        val tie = service.feedChain(noon - 3 * minute, skip = 1, pageSize = 2)
        assertEquals(proxy.evaluateUnfused(tie).map(::signature), proxy.evaluate(tie).map(::signature))
        assertEquals("/Backup/Camera/2023/IMG_0006.jpg", paths(proxy.evaluate(tie)).first())
    }

    @Test
    fun theFeedPage_throughTheStreamPath_isFusedAndReEmitsOnAWriteInASubfolder() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        val counts = counted(proxy)

        val first = service.feedChain(Long.MAX_VALUE, 0, 2).flow().first()
        val firstRows = (first as TResult.Success<TList<Entity<File>>>).value!!.toList()
        assertEquals(listOf("/Backup/Camera/clip.mp4", "/IMG_0005.jpg"), paths(firstRows))
        assertEquals(0, counts.of(Filter.ID) + counts.of(SortBy.ID) + counts.of(Traverse.ID))

        // A write three folders down must reach a fused stream: the walk records every folder's
        // array it reads, which is what the stream registry subscribes on.
        val collected = mutableSetOf<ArrayDatabaseReference>()
        proxy.executionContext.referenceResolver.collectingReferences(collected) {
            proxy.evaluate(service.feedChain(Long.MAX_VALUE, 0, 2))
        }
        val unfusedCollected = mutableSetOf<ArrayDatabaseReference>()
        proxy.executionContext.referenceResolver.collectingReferences(unfusedCollected) {
            proxy.evaluateUnfused(service.feedChain(Long.MAX_VALUE, 0, 2))
        }
        assertEquals(
            unfusedCollected.map { it.arrayId }.toSet(),
            collected.map { it.arrayId }.toSet(),
            "the fused walk must observe exactly the arrays the unfused traverse observes",
        )

        service.upload("/Backup/Camera/2023", "IMG_0100.jpg", noon + 5 * minute)
        counts.reset()
        val after = service.feedChain(Long.MAX_VALUE, 0, 2).flow().first()
        assertEquals(
            listOf("/Backup/Camera/2023/IMG_0100.jpg", "/Backup/Camera/clip.mp4"),
            paths((after as TResult.Success<TList<Entity<File>>>).value!!.toList()),
        )
        assertEquals(0, counts.of(Filter.ID) + counts.of(SortBy.ID) + counts.of(Traverse.ID))
    }

    // ─── Drive Home recents ───

    @Test
    fun driveHomeRecents_traverseSortTake_fuses_andMatchesUnfused() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        val counts = counted(proxy)

        val chain = service.defaultFileSystem.root.files
            .traverse()
            .sortByDescending(File.LastModifiedAt)
            .take(4)
            .select {
                fields(File.Field.PATH)
                relations(File.Category, File.CreatedAt, File.LastModifiedAt)
            }

        val fused = proxy.evaluate(chain)
        assertEquals(0, counts.of(Traverse.ID) + counts.of(SortBy.ID) + counts.of(Take.ID))
        assertEquals(4, fused.size)
        assertEquals(proxy.evaluateUnfused(chain).map(::signature), fused.map(::signature))
    }

    // ─── randomized parity ───

    @Test
    fun randomizedRealms_fusedEqualsUnfused_acrossFiltersKeysAndWindows() = runTest {
        for (seed in 1..6) {
            val proxy = createFileProxy(backgroundScope)
            val service = fileService(proxy)
            val random = Random(seed)
            val folders = mutableListOf("/")
            listOf("/A", "/A/B", "/A/B/C", "/D", "/Trash", "/Trash/Old").forEach {
                service.folder(it); folders += it
            }
            val extensions = listOf("jpg", "jpg", "png", "mp4", "txt", "pdf")
            repeat(40) { i ->
                val parent = folders[random.nextInt(folders.size)]
                val ext = extensions[random.nextInt(extensions.size)]
                // Few distinct capture times ⇒ many ties.
                val at = noon - random.nextInt(6) * minute
                service.upload(parent, "f$i.$ext", at)
            }

            val fs = service.defaultFileSystem
            val bases: List<Pair<String, () -> TList<File>>> = listOf(
                "traverse" to { fs.root.files.traverse() },
                "root" to { fs.root.files },
            )
            val filterSets: List<Pair<String, (TList<File>) -> TList<File>>> = listOf(
                "none" to { l -> l },
                "media" to { l -> l.filter<File> { isMedia() } },
                "feed" to { l ->
                    l.filter<File> { isMedia() }
                        .filter<File> { createdAt.inRange(0L, noon - 2 * minute) }
                        .filter<File> { notTrashed() }
                },
                "image+name" to { l ->
                    l.filter<File> { category equalTo FileCategory.Image }
                        .filter<File> { name.contains("F1", ignoreCase = true) }
                },
            )
            val sorts: List<Pair<String, (TList<File>) -> TList<File>>> = listOf(
                "createdDesc" to { l -> l.sortByDescending(File.CreatedAt) },
                "createdAsc" to { l -> l.sortBy(File.CreatedAt) },
                "modifiedDesc" to { l -> l.sortByDescending(File.LastModifiedAt) },
                "createdThenPath" to { l -> l.sortBy(File.CreatedAt).thenBy<File> { path } },
            )
            val windows: List<Pair<String, (TList<File>) -> TList<File>>> = listOf(
                "take3" to { l -> l.take(3) },
                "drop2take4" to { l -> l.drop(2).take(4) },
                "take7drop3" to { l -> l.take(7).drop(3) },
                "take100" to { l -> l.take(100) },
            )

            for ((bn, base) in bases) for ((fn, f) in filterSets) for ((sn, s) in sorts) for ((wn, w) in windows) {
                if (bn == "root" && fn == "none") continue // plain F1, pinned elsewhere
                val chain = w(s(f(base()))).select {
                    fields(File.Field.PATH)
                    relations(File.CreatedAt, File.Category)
                }
                val counts = counted(proxy)
                val fused = proxy.evaluate(chain)
                assertEquals(
                    0,
                    counts.of(Filter.ID) + counts.of(SortBy.ID) + counts.of(Traverse.ID),
                    "seed=$seed $bn/$fn/$sn/$wn must fuse",
                )
                assertEquals(
                    proxy.evaluateUnfused(chain).map(::signature),
                    fused.map(::signature),
                    "seed=$seed $bn/$fn/$sn/$wn",
                )
            }
        }
    }

    // ─── fall-throughs: any shape the fused path cannot evaluate on a thin row ───

    private fun assertFallsThrough(
        proxy: TestProxy,
        chain: TList<*>,
        why: String,
    ) {
        val counts = counted(proxy)
        val rows = proxy.evaluate(chain)
        assertTrue(counts.of(SortBy.ID) >= 1, "$why: the in-memory sort must run")
        assertEquals(proxy.evaluateUnfused(chain).map(::signature), rows.map(::signature), why)
    }

    @Test
    fun aPredicateThatIsAnOpOnTheRow_fallsThrough() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        assertFallsThrough(
            proxy,
            service.defaultFileSystem.root.files.traverse()
                .filter<File> { isA<Folder>() }
                .sortByDescending(File.CreatedAt).take(2)
                .select { fields(File.Field.PATH) },
            "a type test is an op on the row, not a field/property predicate",
        )
    }

    @Test
    fun noSelect_orAnUnboundedWindow_fallsThrough() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        val counts = counted(proxy)
        proxy.evaluate(
            service.defaultFileSystem.root.files.traverse()
                .filter<File> { isMedia() }
                .sortByDescending(File.CreatedAt).take(2),
        )
        assertTrue(counts.of(SortBy.ID) >= 1, "no select ⇒ thin rows would escape ⇒ unfused")
        assertFallsThrough(
            proxy,
            service.defaultFileSystem.root.files.traverse()
                .filter<File> { isMedia() }
                .sortByDescending(File.CreatedAt).drop(1)
                .select { fields(File.Field.PATH) },
            "a drop-only window has no bound",
        )
    }

    @Test
    fun aFilterAfterTheSort_isNotThisShape_andFallsThrough() = runTest {
        val proxy = createFileProxy(backgroundScope)
        val service = fileService(proxy)
        service.seedRealm()
        assertFallsThrough(
            proxy,
            service.defaultFileSystem.root.files.traverse()
                .sortByDescending(File.CreatedAt)
                .filter<File> { isMedia() }
                .take(2)
                .select { fields(File.Field.PATH) },
            "a filter between the sort and the window",
        )
    }
}
