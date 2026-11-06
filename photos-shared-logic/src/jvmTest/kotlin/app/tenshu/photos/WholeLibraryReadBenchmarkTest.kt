package app.tenshu.photos

import app.tenshu.ingest.ContentIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getDataIdHash
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.proxy.evaluateCallstack
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
import org.tenshu.core.file.category
import org.tenshu.core.file.FileCategory
import org.tenshu.core.equalTo
import org.tenshu.core.file.isMedia
import org.tenshu.core.file.createdAt
import org.tenshu.core.file.DurationMs
import org.tenshu.core.inRange
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.collection.list.drop
import org.tenshu.core.collection.list.take
import org.tenshu.core.entity.get
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.uploadFile
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.Type
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.sync.common.syncServiceCommonModule
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test

/**
 * Where the Collections tab's whole-library read spends its time (`photos-app.md` §39) — split into
 * the walk in Control (and its store reads per file), serialisation, the client's parse, the
 * client's row hashing and index build, then the paged read that replaced it.
 *
 * ⚠ A measurement, not a gate: it asserts nothing, and it is **opt-in** because seeding a realm
 * through the real upload op takes a while. Production store pair (`SqliteObjectStore` + the real
 * lock), a synthetic realm (no photographs, made-up coordinates):
 *
 * ```
 * TENSHU_LIBRARY_BENCH=1400,10000 TENSHU_LIBRARY_BENCH_OUT=/tmp/bench.txt \
 *     ./gradlew :photos-shared-logic:jvmTest --tests 'app.tenshu.photos.WholeLibraryReadBenchmarkTest'
 * ```
 *
 * ⚠⚠ Results go to the `_OUT` file: `println` from a test does not reliably reach the test XML.
 * ⚠ The JVM's absolute numbers say little about a phone — read the *split*, then measure on a device.
 */
class WholeLibraryReadBenchmarkTest {

    private class CountingStore(private val delegate: ObjectStore) : ObjectStore by delegate {
        val gets = AtomicLong()
        override fun getObject(dataIdHash: Long): DbEntry? {
            gets.incrementAndGet()
            return delegate.getObject(dataIdHash)
        }
    }

    @Test
    fun breakdown() {
        val sizes = System.getenv("TENSHU_LIBRARY_BENCH")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        val out = java.io.File(System.getenv("TENSHU_LIBRARY_BENCH_OUT") ?: "build/library-bench.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { for (n in sizes) measure(scope, n, out) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun measure(scope: CoroutineScope, n: Int, out: java.io.File) {
        fun log(s: String) { println(s); out.appendText(s + "\n") }
        val dbPath = createTempDirectory("lib-bench").resolve("objects.db").toString()
        val store = CountingStore(openSqliteObjectStore(dbPath, scope))
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            objectStore = store,
            lock = ReentrantLockDatabaseLock(),
            implementationFactoryOverride = { id, p -> fileServiceProxyImplementationFactory(id, p.fileDatabase) },
        ).also { it.executionContext.initFileService() }
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        val fs = client.fileService.defaultFileSystem
        val folders = mutableListOf("/")
        for (year in 2019..2025) {
            fs.createFolder("/Camera$year"); folders += "/Camera$year"
        }
        listOf("/Documents", "/Trash").forEach { fs.createFolder(it); folders += it }
        val random = Random(7)
        val t0 = System.nanoTime()
        repeat(n) { i ->
            val parent = folders[random.nextInt(folders.size)]
            val ext = if (random.nextInt(10) < 9) "jpg" else "pdf"
            val at = 1_700_000_000_000L - random.nextLong(5L * 365 * 24 * 3600 * 1000)
            val r = fs.uploadFile(
                parent, "IMG_${1000 + i}.$ext", byteArrayOf(1, 2, 3, (i and 0x7f).toByte(), (i shr 7).toByte()),
                sourceModifiedAt = at, captureLatE7 = 480000000L + i, captureLngE7 = 110000000L + i,
            )
            check(r is TResult.Success) { "seed $i: $r" }
        }
        log("LIB-BENCH n=$n seeded in ${(System.nanoTime() - t0) / 1_000_000} ms")

        val indexChain = fs.root.files.traverse()
            .filter<File> { notTrashed() }
            .select {
                fields(File.Field.PATH)
                relations(
                    File.ContentHash, File.ContentId, File.CreatedAt, File.Category, File.Size,
                    File.CaptureLatE7, File.CaptureLngE7, File.PixelWidth, File.PixelHeight,
                )
            }
        val pathOnly = fs.root.files.traverse().filter<File> { notTrashed() }.select { fields(File.Field.PATH) }
        val onePlusPath = fs.root.files.traverse().filter<File> { notTrashed() }
            .select { fields(File.Field.PATH); relations(File.CreatedAt) }

        val ctx = proxy.executionContext
        fun eval(chain: TList<*>): List<Data> {
            val cs = (chain as RequestObject).requestChain.getCallstack()
            return (evaluateCallstack(ctx, null, cs, allowDeferring = false) as TList<*>).toList().map { it as Data }
        }

        for ((name, chain) in listOf("path only" to pathOnly, "path+createdAt" to onePlusPath, "index (9 relations)" to indexChain)) {
            eval(chain); eval(chain)
            val before = store.gets.get()
            val samples = (1..5).map { val s = System.nanoTime(); eval(chain); (System.nanoTime() - s) / 1_000_000 }.sorted()
            val gets = (store.gets.get() - before) / 5
            log("LIB-BENCH n=$n proxy eval [$name]: median ${samples[2]} ms (min ${samples[0]}), store gets/read=$gets (${"%.1f".format(gets.toDouble() / n)}/file)")
        }

        val rows = eval(indexChain)
        val serializer = BinarySerializer(proxy.schemaRegistry)
        val resultType = Type.create(baseSchema = TResult.Schema)
        val result = TResult.Success.create(proxy.tContext, rows.toTList(proxy.tContext))
        var bytes = ByteArray(0)
        val ser = (1..5).map { val s = System.nanoTime(); bytes = serializer.serialize(result, expectedType = resultType); (System.nanoTime() - s) / 1_000_000 }.sorted()
        log("LIB-BENCH n=$n serialize: median ${ser[2]} ms, ${bytes.size} bytes (${bytes.size / rows.size} B/row, rows=${rows.size})")
        if (System.getenv("TENSHU_LIBRARY_BENCH_DUMP") != null) {
            val one = serializer.serialize(TResult.Success.create(proxy.tContext, rows.takeLast(1).toTList(proxy.tContext)), expectedType = resultType)
            log("LIB-BENCH one-row bytes=${one.size}: " + one.joinToString("") { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar().toString() else "." })
        }
        var parsed: TResult<Data>? = null
        val par = (1..5).map { val s = System.nanoTime(); parsed = proxy.parser.parse(bytes, resultType); (System.nanoTime() - s) / 1_000_000 }.sorted()
        log("LIB-BENCH n=$n parse: median ${par[2]} ms")
        @Suppress("UNCHECKED_CAST")
        val clientRows = ((parsed as TResult.Success<*>).value as TList<Data>).toList()
        val hash = (1..3).map { val s = System.nanoTime(); clientRows.forEach { it.getDataIdHash(proxy.tContext) }; (System.nanoTime() - s) / 1_000_000 }.sorted()
        log("LIB-BENCH n=$n client getDataIdHash x rows: median ${hash[1]} ms")
        val map = (1..3).map {
            val s = System.nanoTime()
            val idx = ContentIndex.of(clientRows.mapNotNull { ContentIndex.indexed(it) })
            idx.mediaDuplicateGroups().size
            (System.nanoTime() - s) / 1_000_000
        }.sorted()
        log("LIB-BENCH n=$n client ContentIndex build+group: median ${map[1]} ms")

        // (C) one head page of 500, split: predicates and projection varied one at a time.
        run {
            fun head(preds: TList<File>.() -> TList<File>, full: Boolean): TList<*> =
                fs.root.files.traverse().preds().sortByDescending(File.CreatedAt).take(500).select {
                    fields(File.Field.PATH)
                    if (full) relations(File.CreatedAt, File.Category, File.ContentId, File.DurationMs,
                        File.CaptureLatE7, File.CaptureLngE7, File.PixelWidth, File.PixelHeight)
                }
            val variants = listOf<Pair<String, TList<File>.() -> TList<File>>>(
                "isMedia+range+notTrashed+image" to { filter<File> { isMedia() }.filter<File> { createdAt.inRange(0L, Long.MAX_VALUE) }.filter<File> { notTrashed() }.filter<File> { category equalTo FileCategory.Image } },
                "image+range+notTrashed" to { filter<File> { category equalTo FileCategory.Image }.filter<File> { createdAt.inRange(0L, Long.MAX_VALUE) }.filter<File> { notTrashed() } },
                "range only" to { filter<File> { createdAt.inRange(0L, Long.MAX_VALUE) } },
            )
            for ((name, preds) in variants) for (full in listOf(false, true)) {
                val c = head(preds, full)
                eval(c); eval(c)
                val samples = (1..5).map { val t = System.nanoTime(); eval(c); (System.nanoTime() - t) / 1_000_000 }.sorted()
                log("LIB-BENCH n=$n head500 [$name] projection=${if (full) "feed" else "path"}: median ${samples[2]} ms")
            }
        }

        // (B) keyset pages over the feed's own window and projection, P rows a page.
        for (p in listOf(500, 600, 1000)) {
            fun page(before: Long, skip: Int): TList<*> {
                val end = if (before == Long.MAX_VALUE) before else before + 1
                return fs.root.files.traverse()
                    .filter<File> { category equalTo FileCategory.Image }
                    .filter<File> { createdAt.inRange(0L, end) }
                    .filter<File> { notTrashed() }
                    .sortByDescending(File.CreatedAt)
                    .drop(skip).take(p)
                    .select {
                        fields(File.Field.PATH)
                        relations(File.CreatedAt, File.ContentId,
                            File.CaptureLatE7, File.CaptureLngE7, File.PixelWidth, File.PixelHeight)
                    }
            }
            repeat(3) { attempt ->
                var evalMs = 0L; var serMs = 0L; var parseMs = 0L; var bytesTotal = 0L; var maxBytes = 0; var pages = 0; var total = 0
                var cursor = Long.MAX_VALUE; var skip = 0
                val held = mutableListOf<Long>()
                while (true) {
                    val s0 = System.nanoTime()
                    val rowsP = eval(page(cursor, skip))
                    val s1 = System.nanoTime()
                    val b = serializer.serialize(TResult.Success.create(proxy.tContext, rowsP.toTList(proxy.tContext)), expectedType = resultType)
                    val s2 = System.nanoTime()
                    @Suppress("UNCHECKED_CAST")
                    val back = ((proxy.parser.parse<Data>(b, resultType) as TResult.Success<*>).value as TList<Data>).toList()
                    val s3 = System.nanoTime()
                    evalMs += (s1 - s0) / 1_000_000; serMs += (s2 - s1) / 1_000_000; parseMs += (s3 - s2) / 1_000_000
                    bytesTotal += b.size; maxBytes = maxOf(maxBytes, b.size); pages++; total += back.size
                    val times = back.map { (it as org.tenshu.core.entity.Entity<*>).get(File.CreatedAt)?.get() ?: 0L }
                    held += times
                    if (back.size < p) break
                    cursor = times.last()
                    skip = held.count { it == cursor }
                }
                if (attempt == 2) log("LIB-BENCH n=$n keyset P=$p: pages=$pages rows=$total eval=${evalMs}ms ser=${serMs}ms parse=${parseMs}ms bytes=$bytesTotal (${bytesTotal / maxOf(total, 1)} B/row, max page $maxBytes B)")
            }
        }
    }
}
