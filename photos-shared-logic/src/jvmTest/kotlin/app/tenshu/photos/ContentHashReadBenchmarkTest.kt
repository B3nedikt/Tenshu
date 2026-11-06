package app.tenshu.photos

import app.tenshu.ingest.RealmContentReads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.ReplySizes
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.Category
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.Size
import org.tenshu.core.file.files
import org.tenshu.core.file.notTrashed
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test

/**
 * What the Duplicates scan and the backup drain's content skip cost, before and after §40 —
 * reply bytes (serialised as the IPC transport would) and wall time, on the production store pair.
 *
 * ⚠ A measurement, not a gate: it asserts nothing, and it is **opt-in** because seeding a realm
 * through the real upload op takes a while. A synthetic realm: no photographs, ~5% duplicated bytes.
 *
 * ```
 * TENSHU_CONTENT_BENCH=1400,10000 TENSHU_CONTENT_BENCH_OUT=/tmp/bench.txt \
 *     ./gradlew :photos-shared-logic:jvmTest --tests 'app.tenshu.photos.ContentHashReadBenchmarkTest'
 * ```
 */
class ContentHashReadBenchmarkTest {

    @Test
    fun measure() {
        val sizes = System.getenv("TENSHU_CONTENT_BENCH")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        val out = java.io.File(System.getenv("TENSHU_CONTENT_BENCH_OUT") ?: "build/content-bench.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { for (n in sizes) measure(scope, n, out) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun measure(scope: CoroutineScope, n: Int, out: java.io.File) {
        fun log(s: String) { println(s); out.appendText(s + "\n") }
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            objectStore = openSqliteObjectStore(createTempDirectory("content-bench").resolve("objects.db").toString(), scope),
            lock = ReentrantLockDatabaseLock(),
            implementationFactoryOverride = { id, p -> fileServiceProxyImplementationFactory(id, p.fileDatabase) },
        ).also { it.executionContext.initFileService() }
        val replies = ReplySizes.measure(proxy)
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        val fs = client.fileService.defaultFileSystem
        val folders = mutableListOf<String>()
        fs.createFolder("/Backup")
        fs.createFolder("/Backup/Pixel 8")
        for (year in 2019..2025) {
            "/Backup/Pixel 8/Camera $year".also { fs.createFolder(it); folders += it }
        }
        val random = Random(11)
        val t0 = System.nanoTime()
        repeat(n) { i ->
            val parent = folders[random.nextInt(folders.size)]
            // ~5% of files repeat an earlier file's bytes.
            val content = if (i > 0 && random.nextInt(20) == 0) random.nextInt(i) else i
            val r = fs.uploadFile(
                parent, "PXL_2024${1_000_000 + i}_123456789.jpg", "photograph $content".encodeToByteArray(),
                sourceModifiedAt = 1_700_000_000_000L - random.nextLong(5L * 365 * 24 * 3600 * 1000),
            )
            check(r is TResult.Success) { "seed $i: $r" }
        }
        log("CONTENT-BENCH n=$n seeded in ${(System.nanoTime() - t0) / 1_000_000} ms")

        suspend fun run(name: String, block: suspend () -> Any?) {
            repeat(2) { block() } // warm-up
            val samples = (1..3).map {
                replies.clear()
                val s = System.nanoTime()
                block()
                (System.nanoTime() - s) / 1_000_000
            }.sorted()
            val all = replies.all
            log(
                "CONTENT-BENCH n=$n [$name]: median ${samples[1]} ms, replies=${all.size}, " +
                    "largest=${all.maxOrNull() ?: 0} B, total=${all.sum()} B",
            )
        }

        // Before: the one projection both consumers read (RealmContentIndex / ContentIndex.load).
        run("before: whole-realm projection (one reply)") {
            fs.root.files.traverse().filter<File> { notTrashed() }.select {
                fields(File.Field.PATH)
                relations(
                    File.ContentHash, File.ContentId, File.CreatedAt, File.Category, File.Size,
                    File.CaptureLatE7, File.CaptureLngE7, File.PixelWidth, File.PixelHeight,
                )
            }.getAll()
        }
        run("after: duplicateScan") { checkNotNull(RealmContentReads.duplicateScan(client)) }
        val every = checkNotNull(RealmContentReads.everyContentHash(client))
        val candidates = every.shuffled(random).take(16) + (1..16).map { "not-held-$it" }
        run("after: heldContentHashes, one 32-candidate drain batch") {
            checkNotNull(RealmContentReads.heldContentHashes(client, candidates))
        }
        run("after: everyContentHash (skip fallback, pre-§40 Control)") {
            checkNotNull(RealmContentReads.everyContentHash(client))
        }
    }
}
