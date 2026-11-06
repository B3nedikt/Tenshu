package app.tenshu.ingest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Service
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.relations
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.entity.get
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.File
import org.tenshu.core.file.path
import org.tenshu.core.file.system.createFolder
import org.tenshu.core.file.system.deleteFile
import org.tenshu.core.file.system.uploadFile
import org.tenshu.file.client.fileService
import org.tenshu.file.client.fileServiceClientModule
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.core.schema.Type
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RealmContentReads] against a real realm (`photos-app.md` §40): the answers its bounded replies
 * add up to, at page and chunk sizes small enough that every boundary is crossed many times.
 *
 * ⚠ The byte bound itself is pinned on the production store by the consumers' tests
 * (`DuplicatesBoundedReadTest`, `SyncedFolderControllerTest`); this file is about **correctness
 * across boundaries**, which a page size of 1,000 would never reach in a unit test.
 */
class RealmContentReadsTest {

    private class Realm(scope: CoroutineScope) {
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(fileServiceCommonModule)
                registerConfig(fileServiceClientModule)
                registerConfig(syncServiceCommonModule)
            },
            coroutineScope = scope,
            implementationFactoryOverride = { id, p -> fileServiceProxyImplementationFactory(id, p.fileDatabase) },
        ).also { it.executionContext.initFileService() }

        val client: TenshuClient = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )

        suspend fun put(name: String, content: String) {
            val r = client.fileService.defaultFileSystem.uploadFile(
                "/", name, content.encodeToByteArray(), sourceModifiedAt = 1_000L,
            )
            check(r is TResult.Success) { "seeding /$name: $r" }
        }

        /** Every reply that was delivered, in bytes, once [refuseRepliesOver] is on. */
        val delivered = mutableListOf<Int>()
        var refused = 0
            private set

        /**
         * Binder, as far as a reply is concerned: one that serialises past [ceiling] bytes is not
         * delivered, and the call fails the way `BinderTaskTransport` reports it.
         */
        fun refuseRepliesOver(ceiling: Int) {
            val serializer = BinarySerializer(proxy.schemaRegistry)
            val type = Type.create(baseSchema = TResult.Schema)
            proxy.replyObserver = { result ->
                val bytes = serializer.serialize(result, expectedType = type).size
                if (bytes > ceiling) {
                    refused++
                    error("Proxy call failed: TransactionTooLargeException")
                }
                delivered += bytes
            }
        }

        /** What a reply of [rows] path-only rows weighs — the first page of a walk that size. */
        suspend fun replyBytes(rows: Int): Int {
            refuseRepliesOver(Int.MAX_VALUE)
            delivered.clear()
            walk(pageSize = rows, overlap = 1)
            return delivered.first()
        }

        suspend fun delete(name: String) {
            check(client.fileService.defaultFileSystem.deleteFile("/$name") is TResult.Success) { "deleting /$name" }
        }

        /** Every path the walk returns, in order — duplicates kept, so a repeat shows. */
        suspend fun walk(pageSize: Int, overlap: Int, afterPage: suspend () -> Unit = {}): List<String>? =
            RealmContentReads.readSortedByPath(
                client, pageSize, narrow = { this }, overlap = overlap, afterPage = afterPage,
            ) { fields(File.Field.PATH) }?.map { it.path.get() }

        /** The hash the realm stamped on each named file. */
        suspend fun hashes(): Map<String, String> =
            RealmContentReads.readSortedByPath(client, 1_000, narrow = { this }) {
                fields(File.Field.PATH)
                relations(File.ContentHash)
            }!!.associate { it.path.get().removePrefix("/") to it.get(File.ContentHash)!!.get() }
    }

    private fun name(i: Int) = "f${i.toString().padStart(3, '0')}.jpg"

    @Test
    fun aPagedWalkReturnsEveryFileOnce_acrossManyPageBoundaries() = runTest {
        val realm = Realm(this)
        repeat(23) { realm.put(name(it), "c$it") }

        val paths = realm.walk(pageSize = 4, overlap = 2)

        assertEquals((0 until 23).map { "/" + name(it) }, paths)
    }

    @Test
    fun filesInsertedAndDeletedBeforeTheCursorMidWalk_loseAndRepeatNothing() = runTest {
        // ⚠⚠ The case offset paging gets wrong: a delete before the cursor pulls the next page's
        // first rows back behind it, an insert pushes rows already read forward into it. Every file
        // that existed throughout must come back exactly once.
        val realm = Realm(this)
        repeat(30) { realm.put(name(it * 2), "c$it") }   // f000, f002, … f058
        var pages = 0

        val paths = realm.walk(pageSize = 5, overlap = 3) {
            pages++
            if (pages == 2) {
                // The cursor is at f018. Two deletions behind it (within the overlap) and two
                // insertions behind it.
                realm.delete(name(4))
                realm.delete(name(10))
                realm.put(name(3), "late-a")
                realm.put(name(7), "late-b")
            }
            if (pages == 4) {
                // The cursor is at f038. Five deletions behind it — more than the overlap holds.
                listOf(0, 2, 6, 8, 12).forEach { realm.delete(name(it)) }
            }
        }!!

        val throughout = (0 until 30).map { it * 2 }.filterNot { it in setOf(0, 2, 4, 6, 8, 10, 12) }
            .map { "/" + name(it) }
        assertEquals(paths.size, paths.toSet().size, "a file came back twice: $paths")
        throughout.forEach { assertTrue(it in paths, "$it existed throughout and was lost: $paths") }
        assertEquals(paths.sorted(), paths, "out of order")
    }

    @Test
    fun heldHashes_answerExactlyTheOnesTheRealmCarries() = runTest {
        val realm = Realm(this)
        realm.put("a.jpg", "alpha")
        realm.put("b.jpg", "beta")
        val held = realm.hashes()

        val answer = RealmContentReads.heldContentHashes(
            realm.client,
            listOf(held.getValue("a.jpg"), held.getValue("b.jpg"), "no-such-hash", ""),
        )

        assertEquals(setOf(held.getValue("a.jpg"), held.getValue("b.jpg")), answer)
    }

    @Test
    fun heldHashes_overARealmWithFolders_answersRatherThanFailing() = runTest {
        // ⚠⚠ Every real realm has folders, and a folder carries no content hash. A hash predicate
        // that RAISED on the first one ("Could not resolve property reference") would fail the
        // whole read, so on any real realm the question could not be asked at all. A stored
        // property the row does not carry is "no match" (`evaluatePredicateOn`, §40.2).
        val realm = Realm(this)
        check(realm.client.fileService.defaultFileSystem.createFolder("/Camera") is TResult.Success)
        val r = realm.client.fileService.defaultFileSystem.uploadFile(
            "/Camera", "a.jpg", "alpha".encodeToByteArray(), sourceModifiedAt = 1_000L,
        )
        check(r is TResult.Success)
        realm.put("b.jpg", "beta")
        val held = RealmContentReads.everyContentHash(realm.client)!!

        val answer = RealmContentReads.heldContentHashes(realm.client, held + "no-such-hash")

        assertEquals(held, answer)
    }

    @Test
    fun heldHashes_aReplyFilledByOneHashesCopies_stillFindsTheOthers() = runTest {
        // ⚠ The reply is capped in rows, and the rows are sorted by hash — so a hash held by more
        // files than the cap fills the reply, and every hash sorting after it would read as absent
        // (a re-upload) unless the lookup asks again for what the full reply did not name.
        val realm = Realm(this)
        repeat(5) { realm.put("x$it.jpg", "many copies of x") }
        repeat(5) { realm.put("y$it.jpg", "many copies of y") }
        realm.put("z.jpg", "just one z")
        val h = realm.hashes()
        val asked = listOf(h.getValue("x0.jpg"), h.getValue("y0.jpg"), h.getValue("z.jpg"))

        val answer = RealmContentReads.heldContentHashes(realm.client, asked, rowLimit = 3)

        assertEquals(asked.toSet(), answer)
    }

    @Test
    fun theDuplicateScanGroupsCopiesAcrossPages_andCountsWhatItCompared() = runTest {
        val realm = Realm(this)
        repeat(20) { realm.put(name(it), "c$it") }
        // One copy at each end, one group straddling the page boundary at 4/5 and 9/10.
        realm.put(name(20), "c0")
        realm.put("f004b.jpg", "c4")
        realm.put("f009b.jpg", "c9")
        realm.put("notes.pdf", "c9") // not media: the group with it is not the album's subject

        val scan = RealmContentReads.duplicateScan(realm.client, hashPageSize = 5, pathChunk = 2)!!

        assertEquals(
            setOf(setOf("/f000.jpg", "/f020.jpg"), setOf("/f004.jpg", "/f004b.jpg")),
            scan.groups.map { g -> g.map { it.path }.toSet() }.toSet(),
        )
        assertEquals(24, scan.filesScanned)
    }

    @Test
    fun aRealmOfLongPaths_isStillScanned_inRepliesATransactionCanCarry() = runTest {
        // ⚠⚠ Pins replies bounded in BYTES, not rows: a row carries its whole path, which nothing
        // limits. At ~6 KB a path, a 500-row page is ~3 MB and the copies' read-back ~1.3 MB: past
        // Binder's ~1 MB, no answer, and Duplicates would say "could not look" for good. The
        // production page and chunk sizes, against a transport that refuses what Binder would.
        val realm = Realm(this)
        fun long(i: Int) = "f${i.toString().padStart(3, '0')}-" + "d".repeat(6_000) + ".jpg"
        // 100 pairs of copies, then 120 files of their own.
        repeat(320) { realm.put(long(it), if (it < 200) "pair ${it / 2}" else "single $it") }
        realm.refuseRepliesOver(BINDER_TRANSACTION_BYTES)

        val answer = RealmContentReads.duplicateScan(realm.client)

        assertTrue(realm.refused > 0, "no reply met the ceiling — this realm does not test it")
        val scan = assertNotNull(answer, "could not look: ${realm.refused} replies refused, delivered ${realm.delivered}")
        assertEquals(320, scan.filesScanned)
        assertEquals(
            (0 until 100).map { setOf("/" + long(it * 2), "/" + long(it * 2 + 1)) }.toSet(),
            scan.groups.map { g -> g.map { it.path }.toSet() }.toSet(),
        )
        // ⚠ And not merely "under the limit": Binder's buffer is shared by every transaction in
        // flight, so replies are sized to a quarter of it. Only the first page that got through can
        // be heavier — nothing had measured a row before it.
        val heavy = realm.delivered.filter { it > RealmContentReads.REPLY_BYTE_BUDGET }
        assertTrue(heavy.size <= 1, "replies over the budget: $heavy of ${realm.delivered}")
    }

    @Test
    fun aRealmWhoseRowsFitATransactionOnlyAlone_isStillScanned() = runTest {
        // ⚠ Halving must not stop at "one new row", which is still TWO rows — the new one and the
        // overlap before it. Paths of ~600 KB each fit Binder's 1 MB alone and never in pairs, so
        // that smallest page gets no answer and the scan would be "could not look" for good.
        val realm = Realm(this)
        fun huge(i: Int) = "h$i-" + "d".repeat(600_000) + ".jpg"
        repeat(3) { realm.put(name(it), "small $it") }
        repeat(4) { realm.put(huge(it), "pair ${it / 2}") }   // two pairs of copies, all adjacent
        repeat(3) { realm.put("z$it.jpg", "tail $it") }
        realm.refuseRepliesOver(BINDER_TRANSACTION_BYTES)

        val answer = RealmContentReads.duplicateScan(realm.client)

        assertTrue(realm.refused > 0, "no reply met the ceiling — this realm does not test it")
        val scan = assertNotNull(answer, "could not look: ${realm.refused} replies refused, delivered ${realm.delivered}")
        assertEquals(10, scan.filesScanned)
        assertEquals(
            setOf(setOf("/" + huge(0), "/" + huge(1)), setOf("/" + huge(2), "/" + huge(3))),
            scan.groups.map { g -> g.map { it.path }.toSet() }.toSet(),
        )
    }

    @Test
    fun whenOnlyOneRowFitsAReply_theWalkStillReturnsEveryFileOnce() = runTest {
        val realm = Realm(this)
        repeat(12) { realm.put(name(it), "c$it") }
        // Between one row and two: a single row is delivered, the smallest overlapping page is not.
        realm.refuseRepliesOver((realm.replyBytes(rows = 1) + realm.replyBytes(rows = 2)) / 2)

        val paths = realm.walk(pageSize = 4, overlap = 2)

        assertTrue(realm.refused > 0, "no reply met the ceiling — this realm does not test it")
        assertEquals((0 until 12).map { "/" + name(it) }, paths)
    }

    @Test
    fun aDeleteBehindTheCursor_whileReadingOneRowAtATime_isSeenAndNothingIsSkipped() = runTest {
        // ⚠⚠ With one row to a reply there is no overlap to notice that the rows moved: a delete
        // behind the cursor slides the next file into a position already read, and the walk would
        // step over it. The second read — "is the row before this one still the last I hold?" — is
        // what notices.
        val realm = Realm(this)
        repeat(12) { realm.put(name(it), "c$it") }
        realm.refuseRepliesOver((realm.replyBytes(rows = 1) + realm.replyBytes(rows = 2)) / 2)
        var steps = 0

        val paths = realm.walk(pageSize = 4, overlap = 2) {
            // The cursor is on f005; f006 is next.
            if (++steps == 6) realm.delete(name(1))
        }

        assertEquals((0 until 12).filterNot { it == 1 }.map { "/" + name(it) }, paths)
    }

    @Test
    fun pathsAreChunkedByTheBytesTheirRowsWeigh_andNoneIsDropped() {
        val short = (0 until 5).map { "/s$it.jpg" }
        val long = (0 until 3).map { "/l$it-" + "d".repeat(1_000) }

        // Rows first: three short paths fit the count, not four.
        assertEquals(listOf(3, 2), RealmContentReads.chunkWithinReply(short, maxPaths = 3).map { it.size })
        // Bytes first: two ~1.7 KB rows fit 4 KB, not three.
        assertEquals(listOf(2, 1), RealmContentReads.chunkWithinReply(long, maxPaths = 200, byteBudget = 4_000).map { it.size })
        // ⚠ A path over the whole budget goes out alone rather than not at all.
        assertEquals(long.map { listOf(it) }, RealmContentReads.chunkWithinReply(long, maxPaths = 200, byteBudget = 100))
    }

    /** A client whose `getService` throws, exactly as an unregistered schema makes it. */
    private class UnregisteredSchemaClient : TenshuClient {
        override val environment: TenshuEnvironment
            get() = error("No schema registered for service id: tenshu/file/service")
        override val proxyConnected: StateFlow<Boolean> = MutableStateFlow(true)
        override fun <T : Service> getService(id: String): T = error("No schema registered for service id: $id")
        override fun init() = Unit
        override fun destroy() = Unit
    }

    @Test
    fun aReadThatThrows_isCouldNotLook_neverAnEmptyAnswer() = runTest {
        // ⚠⚠ Not hypothetical: `photos-app.md` §18.4 records services unregistered in Photos'
        // client graph, each making `getService` throw. An empty set here is a re-upload of
        // everything; an empty
        // scan is "you have no duplicates".
        assertNull(RealmContentReads.heldContentHashes(UnregisteredSchemaClient(), listOf("h")))
        assertNull(RealmContentReads.duplicateScan(UnregisteredSchemaClient()))
    }

    private companion object {
        /** Binder's transaction buffer — what one reply can be at the very most. */
        const val BINDER_TRANSACTION_BYTES = 1024 * 1024
    }
}
