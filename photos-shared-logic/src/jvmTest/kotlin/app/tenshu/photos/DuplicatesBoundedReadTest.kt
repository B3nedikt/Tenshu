package app.tenshu.photos

import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.TenshuPhotosRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.ReplySizes
import org.tenshu.core.common.TenshuClient
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
import org.tenshu.photo.client.photoServiceClientModule
import org.tenshu.photo.common.photoServiceCommonModule
import org.tenshu.photo.proxy.initPhotoService
import org.tenshu.photo.proxy.photoServiceProxyImplementationFactory
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Duplicates screen's read, on the production store pair (`SqliteObjectStore` + the real lock),
 * through a client that measures every reply as the IPC transport would serialise it
 * (`photos-app.md` §40).
 *
 * ⚠⚠ The read this replaced was the shared content index: every file in the realm, nine properties
 * each, in ONE reply — ~640 bytes a file, so this realm's 2,500 files are ~1.6 MB, past Binder's
 * ~1 MB transaction ceiling, where the screen got no answer at all.
 */
class DuplicatesBoundedReadTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var replies: ReplySizes

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun realm(): Pair<TestProxy, TenshuClient> {
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(photoServiceCommonModule)
                registerConfig(photoServiceClientModule)
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            objectStore = runBlocking {
                openSqliteObjectStore(createTempDirectory("dup-read").resolve("objects.db").toString(), scope)
            },
            lock = ReentrantLockDatabaseLock(),
            implementationFactoryOverride = { id, p ->
                photoServiceProxyImplementationFactory(id, nowEpochMs = { 1_000L })
                    ?: fileServiceProxyImplementationFactory(id, p.fileDatabase)
            },
        ).also {
            it.executionContext.initPhotoService()
            it.executionContext.initFileService()
        }
        replies = ReplySizes.measure(proxy)
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx ->
                    LocalRequestChain(proxy.localTaskExecutor, ctx)
                },
            ),
        )
        return proxy to client
    }

    @Test
    fun duplicatesAreReadInBoundedReplies_andAGroupStraddlingAPageIsWhole() = runBlocking {
        val (proxy, client) = realm()
        val fs = client.fileService.defaultFileSystem
        // 2,500 files, sorted by path as `f_00000.jpg … f_02499.jpg`. Every file's bytes are its own
        // except the sets below, so the expected groups are known without asking the code under test.
        val sameAs = mapOf(
            // Adjacent across the first 1,000-row page boundary, and again across the second.
            998 to "x", 999 to "x", 1_000 to "x", 1_001 to "x",
            1_999 to "y", 2_000 to "y",
            // The first file and the last: as far apart as two copies can be.
            0 to "z", 2_499 to "z",
            // Spread across three pages.
            500 to "w", 1_500 to "w", 2_400 to "w",
        )
        // Not media: identical bytes, but a PDF pair is not the Duplicates album's subject.
        val notMedia = setOf(10, 11)
        repeat(2_500) { i ->
            val ext = if (i in notMedia) "pdf" else "jpg"
            val bytes = (sameAs[i]?.let { "content $it" } ?: if (i in notMedia) "the pdf" else "content #$i")
                .encodeToByteArray()
            val r = fs.uploadFile("/", "f_${i.toString().padStart(5, '0')}.$ext", bytes, sourceModifiedAt = 1_000L + i)
            check(r is TResult.Success) { "seeding $i: $r" }
        }
        replies.clear()

        val result = TenshuPhotosRepository(client, clockMs = { 1_000L }, reachability = null /* test: no reachability engine */).duplicates()

        val groups = (result as? DuplicatesResult.Groups ?: throw AssertionError("expected groups, got $result")).groups
        fun name(i: Int) = "/f_${i.toString().padStart(5, '0')}.jpg"
        val expected = sameAs.entries.groupBy({ it.value }, { name(it.key) }).values.map { it.toSet() }.toSet()
        assertEquals(expected, groups.map { g -> g.photos.map { it.path }.toSet() }.toSet())
        assertEquals(2_500, result.filesScanned)
        assertTrue(replies.count > 0, "the read never asked the realm")
        assertTrue(
            replies.largest <= BOUNDED_REPLY_BYTES,
            "a reply of ${replies.largest} bytes (all: ${replies.all}) — Duplicates read the whole realm in one",
        )
        assertEquals(
            0,
            proxy.executionContext.streamRegistry.activeStreamCount.value,
            "Duplicates subscribed a whole-realm stream",
        )
    }

    private companion object {
        /** A quarter of Binder's ~1 MB transaction, the budget §39 sized its pages to. */
        const val BOUNDED_REPLY_BYTES = 256 * 1024
    }
}
