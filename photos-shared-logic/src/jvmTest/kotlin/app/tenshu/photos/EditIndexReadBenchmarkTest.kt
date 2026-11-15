package app.tenshu.photos

import app.tenshu.photos.data.PhotoPageResult
import app.tenshu.photos.data.TenshuPhotosRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.photo.PhotoEdit
import org.tenshu.photo.client.photoService
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory
import org.tenshu.photo.setPhotoEdit
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * What the feed pays for preferring each edited photo's rendition (`photos-editing.md` §12.5, §12.9):
 * the edit index — every `PhotoEdit`'s contentId, rendition path and flag — read once, against a
 * first feed page of 60.
 *
 * ⚠ A measurement, not a gate — opt-in, asserts nothing (the `WholeLibraryReadBenchmarkTest` shape),
 * production store pair (`SqliteObjectStore` + the real lock), in process (no IPC):
 *
 * ```
 * TENSHU_EDIT_BENCH=0,100,1000 TENSHU_EDIT_BENCH_OUT=/tmp/edit-bench.txt \
 *     ./gradlew :photos-shared-logic:jvmTest --tests 'app.tenshu.photos.EditIndexReadBenchmarkTest'
 * ```
 */
class EditIndexReadBenchmarkTest {

    @Test
    fun indexAgainstAFeedPage() {
        val sizes = System.getenv("TENSHU_EDIT_BENCH")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        val out = java.io.File(System.getenv("TENSHU_EDIT_BENCH_OUT") ?: "build/edit-bench.txt")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { for (n in sizes) measure(scope, n, LIBRARY, out) }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun measure(scope: CoroutineScope, edits: Int, library: Int, out: java.io.File) {
        fun log(s: String) { println(s); out.appendText(s + "\n") }
        val dbPath = createTempDirectory("edit-bench").resolve("objects.db").toString()
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(photoServiceCommonModule)
                registerConfig(photoServiceClientModule)
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            objectStore = openSqliteObjectStore(dbPath, scope),
            lock = ReentrantLockDatabaseLock(),
            implementationFactoryOverride = { id, p ->
                photoServiceProxyImplementationFactory(id, nowEpochMs = { 1_000L })
                    ?: fileServiceProxyImplementationFactory(id, p.fileDatabase)
            },
        ).also {
            it.executionContext.initPhotoService()
            it.executionContext.initFileService()
        }
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        repeat(library) { i ->
            val r = client.fileService.defaultFileSystem.uploadFile(
                "/", "IMG_${10_000 + i}.jpg", byteArrayOf(1, 2, (i and 0x7f).toByte()), sourceModifiedAt = 1_700_000_000_000L - i * 1000L,
            )
            check(r is TResult.Success) { "seed $i: $r" }
        }
        // A realistic recipe size: a transform, sliders, a look and a short stroke.
        val recipe = """{"v":1,"transform":{"quarterTurns":1,"crop":[0.1,0.1,0.9,0.9]},"adjust":{"brightness":20},""" +
            """"filter":{"look":"ember","intensity":80},"markup":[{"kind":"stroke","id":1,"points":[0.1,0.1,0.2,0.2,0.3,0.3],"color":"red","size":0.01}]}"""
        repeat(edits) { i ->
            val r = client.photoService.setPhotoEdit("content-$i", recipe, "/Trash/.edits/content-$i-1.jpg", false)
            check(r is TResult.Success) { "edit $i: $r" }
        }

        val indexRead = suspend {
            client.photoService.photoEdits
                .select { fields(PhotoEdit.Field.CONTENT_ID, PhotoEdit.Field.RENDITION_PATH, PhotoEdit.Field.REDACTED) }
                .getAll()
        }
        repeat(2) { indexRead() }
        val index = (1..7).map { val s = System.nanoTime(); indexRead(); (System.nanoTime() - s) / 1_000 }.sorted()

        val page = suspend {
            // A fresh repository each time, so every page pays for its index read (no observer).
            check(TenshuPhotosRepository(client, reachability = null).feedPage(Long.MAX_VALUE, pageSize = 60) is PhotoPageResult.Page)
        }
        repeat(2) { page() }
        val pages = (1..7).map { val s = System.nanoTime(); page(); (System.nanoTime() - s) / 1_000 }.sorted()
        log("EDIT-BENCH library=$library edits=$edits: index read median ${index[3]} µs (min ${index[0]}); first feed page incl. index median ${pages[3]} µs (min ${pages[0]})")
    }

    private companion object {
        const val LIBRARY = 2_000
    }
}
