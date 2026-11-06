package org.tenshu.sync.relay

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.PushedEdge
import org.tenshu.sync.ReviveEntry
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.manifestRefOf
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.mailbox.ContentBlobRegistry
import org.tenshu.sync.relay.mailbox.ContentBlobStore
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.mailbox.ContentFreedStore
import org.tenshu.sync.relay.mailbox.ContentFreeing
import org.tenshu.sync.relay.mailbox.ContentRefRegistry
import org.tenshu.sync.relay.mailbox.ContentRefStore
import org.tenshu.sync.relay.storage.FileBlobStore
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentBlobStore
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteContentFreedStore
import org.tenshu.sync.relay.storage.SqliteContentRefStore
import org.tenshu.sync.relay.storage.SqliteSyncMailbox
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B4r through the real routes (`content-plane-cr2-b4-spec.md` §1.3, §1.4, §1.6): G3's refusal on
 * `/sync/push`, the regenerable rung, REVIVE and `revive_incomplete`, and the describe's refusal.
 *
 * ⚠ Every scenario runs on BOTH backends — the SQLite set `main` wires (one database) and the in-memory
 * twin — because the twin is what dev mode serves (§1.5), and a check present in only one is a relay that
 * answers differently depending on how it was started.
 */
class ContentFreedRoutingTest {

    private val owner = "alice-b4"
    private val ctx: TContext
    private val serializer: BinarySerializer

    init {
        val c = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { c },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        c.components = mapOf(SchemaProvider.COMPONENT_ID to registry, DataFactory.COMPONENT_ID to registry)
        ctx = c
        serializer = BinarySerializer(registry)
    }

    private fun ref(label: String): String =
        "blob-" + label.encodeToByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0').take(64)

    private fun block(physical: Long, target: String = "holder-1") = SyncBlock.create(
        context = ctx,
        blockId = "b-$target-$physical",
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, physical, 0, "device-a"),
        encryptedPayload = byteArrayOf(1, 2, 3),
        targetEntityIdHash = target,
    )

    private fun edge(property: String, content: String?, regenerable: Boolean = false) =
        PushedEdge.create(ctx, property, content, regenerable)

    private fun revive(contentId: String, vararg refs: String) = ReviveEntry.create(ctx, contentId, refs.toList())

    private suspend fun HttpClient.push(
        block: SyncBlock,
        vararg edges: PushedEdge,
        revive: List<ReviveEntry> = emptyList(),
    ): HttpResponse = post("/sync/push") {
        contentType(ContentType.Application.OctetStream)
        header("X-Dev-Owner", owner)
        setBody(serializer.serialize(edgedPush(ctx, block, edges.toList(), revive = revive)))
    }

    private suspend fun HttpClient.describe(vararg contents: Pair<String, List<String>>): HttpResponse =
        post("/sync/content/declare") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", owner)
            setBody(
                """{"contents":[""" + contents.joinToString(",") { (id, refs) ->
                    """{"contentId":"$id","refs":[${refs.joinToString(",") { "\"$it\"" }}]}"""
                } + "]}",
            )
        }

    /** The 409 body, parsed: `(error, contentIds)`. */
    private suspend fun HttpResponse.refusal(): Pair<String, List<String>> {
        val json = Json.parseToJsonElement(bodyAsText()).jsonObject
        return json.getValue("error").jsonPrimitive.content to
            json.getValue("contentIds").jsonArray.map { it.jsonPrimitive.content }
    }

    /** The stores one relay runs over, plus what only the SQLite set can say (the queue). */
    private class Relay(
        val edges: ContentEdgeRegistry,
        val refs: ContentRefRegistry,
        val blobs: ContentBlobRegistry,
        val mailbox: MailboxStore,
        val freeing: ContentFreeing,
        val db: RelayDatabase?,
    ) {
        /** SQLite only: whether [ref] is still in the deletion queue. In-memory has no queue. */
        fun queued(ownerId: String, ref: String): Boolean? = db?.withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM content_free_queue WHERE owner_id = ? AND ref = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.setString(2, ref)
                ps.executeQuery().use { it.next() }
            }
        }

        fun blockCount(ownerId: String): Int = mailbox.getChanges(ownerId, 0).blocks.size
    }

    private fun inMemory(): Relay {
        val edges = ContentEdgeStore()
        val refs = ContentRefStore()
        val blobs = ContentBlobStore()
        return Relay(edges, refs, blobs, SyncMailbox(), ContentFreedStore(edges, refs, blobs), null)
    }

    private fun <T> withSqlite(block: (Relay, File) -> T): T {
        val dbFile = File.createTempFile("b4r-routing", ".db").also { it.delete() }
        val blobDir = File.createTempFile("b4r-blobs", "").also { it.delete(); it.mkdirs() }
        try {
            return RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
                val refs = SqliteContentRefStore(db)
                block(
                    Relay(
                        edges = SqliteContentEdgeStore(db),
                        refs = refs,
                        blobs = SqliteContentBlobStore(db, FileBlobStore(blobDir)),
                        mailbox = SqliteSyncMailbox(db, RelayBinaryCodec()),
                        freeing = SqliteContentFreedStore(db, refs),
                        db = db,
                    ),
                    blobDir,
                )
            }
        } finally {
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
            blobDir.deleteRecursively()
        }
    }

    /** Run [body] once per backend, each against a fresh relay. */
    private fun bothBackends(
        edgeOverride: ((ContentEdgeRegistry) -> ContentEdgeRegistry)? = null,
        body: suspend io.ktor.server.testing.ApplicationTestBuilder.(Relay) -> Unit,
    ) {
        fun run(relay: Relay) = testApplication {
            application {
                module(
                    config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                    contentEdgeStore = edgeOverride?.invoke(relay.edges) ?: relay.edges,
                    contentRefStore = relay.refs,
                                        contentBlobStore = relay.blobs,
                    contentFreeing = relay.freeing,
                    syncMailbox = relay.mailbox,
                    ed25519 = org.tenshu.sync.relay.auth.StubEd25519Backend(),
                )
            }
            body(relay)
        }
        run(inMemory())
        withSqlite { relay, _ -> run(relay) }
    }

    /**
     * A described content `c` with two chunks and its manifest, all stored, then FREED. What B5's sweep
     * would leave once the last edge went.
     */
    private fun Relay.freedContent(c: String): List<String> {
        val refs = listOf(ref("$c-chunk-1"), ref("$c-chunk-2"), manifestRefOf(c))
        refs.forEach { blobs.put(owner, it, byteArrayOf(7)) }
        this.refs.declareRefs(owner, c, refs)
        assertEquals(ContentFreeing.FreeResult.FREED, freeing.free(owner, c))
        return refs
    }

    // ── G3 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPushNamingAFreedId_is409ListingIt_andWritesNothing() = bothBackends { relay ->
        relay.freedContent("c1")
        val r = client.push(block(1_000), edge("file/data", "c1"))
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertEquals("content_freed" to listOf("c1"), r.refusal())
        assertEquals(0, relay.blockCount(owner), "the block is NOT in the mailbox")
        assertNull(relay.edges.register(owner, "holder-1", "file/data"), "no edge written")
        assertEquals(setOf("c1"), relay.freeing.freedAmong(owner, listOf("c1")))
    }

    @Test
    fun everyFreedIdIsListed_deduplicated_inRequestOrder() = bothBackends { relay ->
        relay.freedContent("c2")
        relay.freedContent("c1")
        val r = client.push(
            block(1_000),
            edge("file/data", "c2"),
            edge("file/other", "live"),
            edge("file/extra", "c1"),
            edge("file/again", "c2"),
        )
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("content_freed" to listOf("c2", "c1"), r.refusal())
        assertEquals(0, relay.blockCount(owner))
        assertTrue(relay.edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aRegenerableRungNamingAFreedId_is200_leavesFreed_andItsRefsStayQueued() = bothBackends { relay ->
        val refs = relay.freedContent("thumb")
        val r = client.push(block(1_000), edge("file/thumbnail", "thumb", regenerable = true))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(relay.freeing.freedAmong(owner, listOf("thumb")).isEmpty(), "the content_freed row is gone")
        assertEquals(setOf("thumb"), relay.edges.referencedContentIds(owner))
        assertEquals(1, relay.blockCount(owner))
        if (relay.db != null) refs.forEach { assertEquals(true, relay.queued(owner, it), "$it stays queued") }
        // Its describe after the re-mint is accepted again.
        assertEquals(HttpStatusCode.OK, client.describe("thumb" to listOf(ref("thumb-new"))).status)
    }

    // ── REVIVE ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aReviveWithEveryRefRePut_is200_restoresTheRefs_andAppendsBlockAndEdge() = bothBackends { relay ->
        relay.freedContent("c1")
        // The repairing device re-uploads under a fresh CEK: new chunks, and the manifest at its derived ref.
        val fresh = listOf(ref("c1-new-1"), ref("c1-new-2"), manifestRefOf("c1"))
        fresh.forEach { relay.blobs.put(owner, it, byteArrayOf(9)) }

        val r = client.push(block(1_000), edge("file/data", "c1"), revive = listOf(revive("c1", *fresh.toTypedArray())))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(fresh.toSet(), relay.refs.refsOf(owner, "c1"), "content_ref restored to the revive's refs")
        assertTrue(relay.freeing.freedAmong(owner, listOf("c1")).isEmpty(), "freed row gone")
        assertEquals(1, relay.blockCount(owner), "block appended")
        assertEquals(setOf("c1"), relay.edges.referencedContentIds(owner), "edge written")
        if (relay.db != null) fresh.forEach { assertEquals(false, relay.queued(owner, it)) }
    }

    @Test
    fun aReviveIsOneTransactionWithTheBlockAndItsEdges() {
        // ⚠⚠ Fault injected AFTER the revive's writes, at the edge write: everything must roll back — the
        // freed row stays, content_ref stays empty, no block. SQLite only: the twin cannot roll back.
        withSqlite { relay, _ ->
            val failing = object : ContentEdgeRegistry by relay.edges {
                override fun writeEdges(
                    ownerId: String,
                    holderHash: String,
                    stamp: HlcStamp,
                    edges: List<ContentEdgeRegistry.EdgeWrite>,
                ): Int = error("disk full")
            }
            relay.freedContent("c1")
            val fresh = listOf(ref("c1-new"), manifestRefOf("c1"))
            fresh.forEach { relay.blobs.put(owner, it, byteArrayOf(9)) }
            testApplication {
                application {
                    module(
                        config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                        contentEdgeStore = failing,
                        contentRefStore = relay.refs,
                                                contentBlobStore = relay.blobs,
                        contentFreeing = relay.freeing,
                        syncMailbox = relay.mailbox,
                        ed25519 = org.tenshu.sync.relay.auth.StubEd25519Backend(),
                    )
                }
                val r = client.push(block(1_000), edge("file/data", "c1"), revive = listOf(revive("c1", *fresh.toTypedArray())))
                assertEquals(HttpStatusCode.InternalServerError, r.status)
            }
            assertEquals(setOf("c1"), relay.freeing.freedAmong(owner, listOf("c1")), "still FREED")
            assertTrue(relay.refs.refsOf(owner, "c1").isEmpty(), "the revive's describe rolled back")
            assertEquals(0, relay.blockCount(owner), "and the block with it")
        }
    }

    @Test
    fun aReviveWithARefMissing_is409ReviveIncomplete_andWritesNothing() = bothBackends { relay ->
        relay.freedContent("c1")
        relay.blobs.put(owner, manifestRefOf("c1"), byteArrayOf(9))
        val r = client.push(
            block(1_000),
            edge("file/data", "c1"),
            revive = listOf(revive("c1", ref("never-uploaded"), manifestRefOf("c1"))),
        )
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("revive_incomplete" to listOf("c1"), r.refusal())
        assertEquals(0, relay.blockCount(owner))
        assertNull(relay.edges.register(owner, "holder-1", "file/data"))
        assertEquals(setOf("c1"), relay.freeing.freedAmong(owner, listOf("c1")))
        assertTrue(relay.refs.refsOf(owner, "c1").isEmpty())
    }

    @Test
    fun aReviveWhoseManifestWasNotRePut_is409_becauseItIsStillQueued() {
        // ⚠ SQLite only: "still queued" is the queue, which the twin does not have (§1.5).
        withSqlite { relay, _ ->
            testApplication {
                application {
                    module(
                        config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                        contentEdgeStore = relay.edges,
                        contentRefStore = relay.refs,
                                                contentBlobStore = relay.blobs,
                        contentFreeing = relay.freeing,
                        syncMailbox = relay.mailbox,
                        ed25519 = org.tenshu.sync.relay.auth.StubEd25519Backend(),
                    )
                }
                relay.freedContent("c1")
                // New chunk PUT, but the manifest's PUT never came: its old bytes are present AND queued, so
                // the drain may delete them the moment this push commits.
                relay.blobs.put(owner, ref("c1-new"), byteArrayOf(9))
                assertEquals(true, relay.queued(owner, manifestRefOf("c1")))
                val r = client.push(
                    block(1_000),
                    edge("file/data", "c1"),
                    revive = listOf(revive("c1", ref("c1-new"), manifestRefOf("c1"))),
                )
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("revive_incomplete" to listOf("c1"), r.refusal())
                assertEquals(0, relay.blockCount(owner))
                assertEquals(setOf("c1"), relay.freeing.freedAmong(owner, listOf("c1")))
            }
        }
    }

    @Test
    fun aReviveNamingNoNonRegenerableEdgeOfThePush_is400() = bothBackends { relay ->
        relay.freedContent("c1")
        relay.blobs.put(owner, manifestRefOf("c1"), byteArrayOf(9))
        val entry = listOf(revive("c1", manifestRefOf("c1")))
        val noEdge = client.push(block(1_000), edge("file/data", "other"), revive = entry)
        assertEquals(HttpStatusCode.BadRequest, noEdge.status)
        assertTrue(noEdge.bodyAsText().contains("revive entry names no edge of this push"), noEdge.bodyAsText())
        // A regenerable edge is never revived: it leaves FREED on its own.
        val rung = client.push(block(1_000), edge("file/thumbnail", "c1", regenerable = true), revive = entry)
        assertEquals(HttpStatusCode.BadRequest, rung.status)
        val malformed = client.push(block(1_000), edge("file/data", "c1"), revive = listOf(revive("c1", "not-a-ref")))
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val empty = client.push(block(1_000), edge("file/data", "c1"), revive = listOf(revive("c1")))
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(0, relay.blockCount(owner))
        assertEquals(setOf("c1"), relay.freeing.freedAmong(owner, listOf("c1")))
    }

    @Test
    fun aReviveForANonFreedId_is200_andActsAsADescribe() = bothBackends { relay ->
        // A client whose first revive's 200 was lost retries the same push: by then c1 is not FREED.
        val fresh = listOf(ref("c1-new"), manifestRefOf("c1"))
        fresh.forEach { relay.blobs.put(owner, it, byteArrayOf(9)) }
        val r = client.push(block(1_000), edge("file/data", "c1"), revive = listOf(revive("c1", *fresh.toTypedArray())))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(fresh.toSet(), relay.refs.refsOf(owner, "c1"))
        assertEquals(setOf("c1"), relay.edges.referencedContentIds(owner))
    }

    // ── describe ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aDescribeOfAFreedId_is409_andAMixedRequestWritesNothing() = bothBackends { relay ->
        relay.freedContent("c1")
        val r = client.describe("live" to listOf(ref("live-1")), "c1" to listOf(ref("c1-late")))
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertEquals("content_freed" to listOf("c1"), r.refusal())
        assertTrue(relay.refs.refsOf(owner, "live").isEmpty(), "refused whole: not even the live entry")
        assertTrue(relay.refs.refsOf(owner, "c1").isEmpty())
        // The same live entry alone still describes.
        assertEquals(HttpStatusCode.OK, client.describe("live" to listOf(ref("live-1"))).status)
        assertEquals(setOf(ref("live-1")), relay.refs.refsOf(owner, "live"))
    }

    @Test
    fun aPendingContentFreedBeforeItsDescribe_refusesTheLateDescribe() = bothBackends { relay ->
        // §10.12 Q2: the last edge went before the upload landed — no refs, but still FREED.
        assertEquals(ContentFreeing.FreeResult.FREED, relay.freeing.free(owner, "pending"))
        val r = client.describe("pending" to listOf(ref("pending-1")))
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("content_freed" to listOf("pending"), r.refusal())
    }

    // ── a push and a free serialise ──────────────────────────────────────────────────────────────

    @Test
    fun aPushAndAFreeOnOneContentSerialise() {
        // The push holds its transaction (paused inside the edge write); a free() racing it must wait, and
        // then see the edge the push committed — so it refuses, rather than freeing content just named.
        // ⚠ One gate pair PER backend, keyed by the store the override wraps: shared latches would already be
        // released when the second backend runs, and its free() would never wait.
        val gates = java.util.concurrent.ConcurrentHashMap<ContentEdgeRegistry, Pair<CountDownLatch, CountDownLatch>>()
        fun gate(edges: ContentEdgeRegistry) = gates.getOrPut(edges) { CountDownLatch(1) to CountDownLatch(1) }
        bothBackends(edgeOverride = { real ->
            val (entered, release) = gate(real)
            object : ContentEdgeRegistry by real {
                override fun writeEdges(
                    ownerId: String,
                    holderHash: String,
                    stamp: HlcStamp,
                    edges: List<ContentEdgeRegistry.EdgeWrite>,
                ): Int {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "test never released the push" }
                    return real.writeEdges(ownerId, holderHash, stamp, edges)
                }
            }
        }) { relay ->
            val (entered, release) = gate(relay.edges)
            var outcome: ContentFreeing.FreeResult? = null
            val freer = thread(start = false) { outcome = relay.freeing.free(owner, "c1") }
            val pusher = thread {
                kotlinx.coroutines.runBlocking { client.push(block(1_000), edge("file/data", "c1")) }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the push reached its edge write")
            freer.start()
            freer.join(300)
            assertTrue(freer.isAlive, "free() waits for the push's transaction")
            release.countDown()
            pusher.join(10_000)
            freer.join(10_000)
            assertFalse(freer.isAlive)
            assertEquals(ContentFreeing.FreeResult.NOT_FREED, outcome, "the free saw the committed edge")
            assertTrue(relay.freeing.freedAmong(owner, listOf("c1")).isEmpty())
        }
    }
}
