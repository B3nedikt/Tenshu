package org.tenshu.file.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.take
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.LocalTaskExecutor
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.proxy.evaluateCallstack
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.File
import org.tenshu.core.file.FileService
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.files
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.id
import org.tenshu.core.inRange
import org.tenshu.core.request.RequestObject
import org.tenshu.file.test.createFileProxy
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **What a Photos feed page costs, fused vs unfused**, on a synthetic realm over the production
 * store (`SqliteObjectStore` + the real lock) — `core-ordered-window-fusion.md` §9.4 quotes its
 * output. JVM-only because the store is.
 *
 * ⚠ A measurement, not a gate: wall-clock assertions on a shared machine are flaky, so the only
 * assertion is that fused and unfused agree row for row. It is **opt-in** because seeding the
 * realm through the real upload op takes a while:
 *
 * ```
 * TENSHU_FUSION_BENCH=10000 ./gradlew :file-service-proxy:jvmTest \
 *     --tests org.tenshu.file.proxy.FilteredWindowFusionBenchmarkTest
 * ```
 *
 * The number is the realm size; the results print to stdout (this module's test task shows
 * standard streams).
 */
class FilteredWindowFusionBenchmarkTest {

    @Test
    fun feedPageCost_fusedVsUnfused() {
        val files = System.getenv("TENSHU_FUSION_BENCH")?.toIntOrNull() ?: return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { measure(scope, files) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun measure(scope: CoroutineScope, fileCount: Int) {
        val dbPath = createTempDirectory("owf-bench").resolve("objects.db").toString()
        val proxy = createFileProxy(
            coroutineScope = scope,
            objectStore = openSqliteObjectStore(dbPath, scope),
            lock = ReentrantLockDatabaseLock(),
        )
        val service = fileService(proxy)
        val fs = service.defaultFileSystem
        val noon = 1_700_000_000_000L

        // A phone-shaped realm: a camera roll split by year/month, a few other folders, a trash.
        val folders = mutableListOf("/")
        for (year in 2019..2024) {
            fs.createFolder("/Camera$year")
            folders += "/Camera$year"
            for (month in 1..6) {
                fs.createFolder("/Camera$year/M$month")
                folders += "/Camera$year/M$month"
            }
        }
        listOf("/Documents", "/Downloads", "/Trash").forEach { fs.createFolder(it); folders += it }

        val random = Random(42)
        val seedStart = System.nanoTime()
        repeat(fileCount) { i ->
            val parent = folders[random.nextInt(folders.size)]
            val ext = when (random.nextInt(10)) {
                in 0..6 -> "jpg"
                7 -> "mp4"
                8 -> "pdf"
                else -> "txt"
            }
            val at = noon - random.nextLong(5L * 365 * 24 * 3600 * 1000)
            val result = fs.uploadFile(parent, "f$i.$ext", byteArrayOf(1, 2, 3), sourceModifiedAt = at)
            check(result is TResult.Success) { "seed $i: $result" }
        }
        println("OWF-BENCH seeded $fileCount files in ${(System.nanoTime() - seedStart) / 1_000_000} ms")

        fun feedPage(beforeMs: Long, skip: Int, pageSize: Int): TList<*> {
            val end = if (beforeMs == Long.MAX_VALUE) beforeMs else beforeMs + 1
            return fs.root.files
                .traverse()
                .filter<File> { isMedia() }
                .filter<File> { createdAt.inRange(0L, end) }
                .filter<File> { notTrashed() }
                .sortByDescending(File.CreatedAt)
                .drop(skip)
                .take(pageSize)
                .select {
                    fields(File.Field.PATH)
                    relations(File.CreatedAt, File.Category, File.ContentId, File.DurationMs)
                }
        }

        val recents = fs.root.files
            .traverse()
            .sortByDescending(File.LastModifiedAt)
            .take(20)
            .select {
                fields(File.Field.PATH)
                relations(File.Category, File.CreatedAt, File.LastModifiedAt)
            }

        val cases = listOf(
            "feed head page (60)" to feedPage(Long.MAX_VALUE, 0, 60),
            "feed page mid-library (60)" to feedPage(noon - 2L * 365 * 24 * 3600 * 1000, 0, 60),
            "drive home recents (20)" to recents,
        )
        for ((name, chain) in cases) {
            val callstack = (chain as RequestObject).requestChain.getCallstack()
            val asShipped = time { proxy.evaluate(callstack) }
            val unfused = time { proxy.evaluateUnfused(callstack) }
            assertEquals(
                proxy.evaluateUnfused(callstack).map { it.toString() },
                proxy.evaluate(callstack).map { it.toString() },
                "$name: the shipped path and the unfused composition must agree",
            )
            println(
                "OWF-BENCH $name over $fileCount files: as-shipped median ${asShipped.first} ms " +
                    "(min ${asShipped.second}), unfused median ${unfused.first} ms " +
                    "(min ${unfused.second}); as-shipped ${"%.1f".format(asShipped.first * 1000.0 / fileCount)} µs/file",
            )
            assertTrue(asShipped.first >= 0)
        }
    }

    /** Median and min wall-clock ms over 7 runs after 2 warm-ups. */
    private fun time(block: () -> Unit): Pair<Long, Long> {
        repeat(2) { block() }
        val samples = (1..7).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        return samples[samples.size / 2] to samples.first()
    }

    private fun TestProxy.evaluate(callstack: List<Data>): List<Data> =
        (evaluateCallstack(executionContext, null, callstack, allowDeferring = false) as TList<*>)
            .toList().map { it as Data }

    /** See `FilteredWindowFusionTest.evaluateUnfused`: prefix alone, then the sorted suffix. */
    private fun TestProxy.evaluateUnfused(callstack: List<Data>): List<Data> {
        val sortAt = callstack.indexOfFirst { (it as? Operation)?.id == SortBy.ID }
        val materialised = evaluateCallstack(
            executionContext, null, callstack.subList(0, sortAt), allowDeferring = false,
        )
        return (evaluateCallstack(
            executionContext, materialised, callstack.subList(sortAt, callstack.size),
            allowDeferring = false,
        ) as TList<*>).toList().map { it as Data }
    }

    private fun fileService(proxy: TestProxy): FileService {
        val executor = LocalTaskExecutor(proxy.executionContext)
        return TenshuEnvironment(
            taskExecutor = executor,
            schemaRegistry = proxy.schemaRegistry,
            requestChainFactory = { context -> LocalRequestChain(executor, context) },
            tContext = proxy.tContext,
        ).getService(FileService.ID)
    }
}
