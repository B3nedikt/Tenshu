package app.tenshu.photos

import app.tenshu.ingest.RealmContentReads
import app.tenshu.photos.data.DuplicatesResult
import app.tenshu.photos.data.TenshuPhotosRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.filter
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.relations
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.traverse
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.ReplySizes
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.proxy.db.ReentrantLockDatabaseLock
import org.tenshu.core.common.proxy.db.openSqliteObjectStore
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.File
import org.tenshu.core.file.files
import org.tenshu.core.file.notTrashed
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
 * each, in ONE reply — ~640 bytes a file, so a realm past ~1,600 files is past Binder's ~1 MB
 * transaction ceiling, where the screen got no answer at all.
 *
 * ⚠ The realm's size is derived, not chosen: files enough to put even the narrowest whole-realm read
 * past [RealmContentReads.REPLY_BYTE_BUDGET], and to spread over three hash pages
 * ([RealmContentReads.HASH_PAGE_SIZE]). A precondition proves the first; a bigger realm buys nothing
 * but seeding time.
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
        val page = RealmContentReads.HASH_PAGE_SIZE
        val files = maxOf(PAST_THE_BUDGET, 2 * page + 3)
        // `files` files, sorted by path as `f_00000.jpg …`. Every file's bytes are its own except the
        // sets below, so the expected groups are known without asking the code under test.
        // ⚠ Pages break at multiples of `page`: these paths are short, so the walk's byte sizing never
        // asks for fewer rows than its ceiling.
        val copies = listOf(
            // Adjacent across the first page boundary, and again across the second.
            page - 2 to "x", page - 1 to "x", page to "x", page + 1 to "x",
            2 * page - 1 to "y", 2 * page to "y",
            // The first file and the last: as far apart as two copies can be.
            0 to "z", files - 1 to "z",
            // One copy on each of the three pages.
            page / 2 to "w", page + page / 2 to "w", (2 * page + files - 1) / 2 to "w",
        )
        val sameAs = copies.toMap()
        check(sameAs.size == copies.size) { "two copies placed on one file: $copies" }
        // Not media: identical bytes, but a PDF pair is not the Duplicates album's subject.
        val notMedia = setOf(10, 11)
        check(notMedia.none { it in sameAs })
        repeat(files) { i ->
            val ext = if (i in notMedia) "pdf" else "jpg"
            val bytes = (sameAs[i]?.let { "content $it" } ?: if (i in notMedia) "the pdf" else "content #$i")
                .encodeToByteArray()
            val r = fs.uploadFile("/", "f_${i.toString().padStart(5, '0')}.$ext", bytes, sourceModifiedAt = 1_000L + i)
            check(r is TResult.Success) { "seeding $i: $r" }
        }
        // Precondition: the bound below can tell a bounded read from a whole-realm one only if the
        // realm, read whole, is past it — even as the narrowest rows that can answer "same bytes?".
        replies.clear()
        val everyHash = fs.root.files.traverse().filter<File> { notTrashed() }.select { relations(File.ContentHash) }
        check(everyHash.getAll() is TResult.Success)
        assertTrue(
            replies.largest > RealmContentReads.REPLY_BYTE_BUDGET,
            "precondition: every hash in ONE reply is ${replies.largest} bytes, within the budget — grow the realm",
        )
        replies.clear()

        val result = TenshuPhotosRepository(client, clockMs = { 1_000L }, reachability = null /* test: no reachability engine */).duplicates()

        val groups = (result as? DuplicatesResult.Groups ?: throw AssertionError("expected groups, got $result")).groups
        fun name(i: Int) = "/f_${i.toString().padStart(5, '0')}.jpg"
        val expected = sameAs.entries.groupBy({ it.value }, { name(it.key) }).values.map { it.toSet() }.toSet()
        assertEquals(expected, groups.map { g -> g.photos.map { it.path }.toSet() }.toSet())
        assertEquals(files, result.filesScanned)
        assertTrue(replies.count > 0, "the read never asked the realm")
        assertTrue(
            replies.largest <= RealmContentReads.REPLY_BYTE_BUDGET,
            "a reply of ${replies.largest} bytes (all: ${replies.all}) — Duplicates read the whole realm in one",
        )
        assertEquals(
            0,
            proxy.executionContext.streamRegistry.activeStreamCount.value,
            "Duplicates subscribed a whole-realm stream",
        )
    }

    private companion object {
        /**
         * A lower bound on a hash-only row on the wire, the narrowest row a whole-realm duplicates
         * read could carry: measured at ~200 B (`photos-app.md` §40.4), most of it the entity's type
         * ids. ⚠ Under the measurement on purpose — the precondition, not this number, is the proof.
         */
        const val NARROWEST_ROW_BYTES = 190

        /** Files enough that their hash-only rows, at [NARROWEST_ROW_BYTES] each, overflow one reply. */
        const val PAST_THE_BUDGET = RealmContentReads.REPLY_BYTE_BUDGET / NARROWEST_ROW_BYTES + 1
    }
}
