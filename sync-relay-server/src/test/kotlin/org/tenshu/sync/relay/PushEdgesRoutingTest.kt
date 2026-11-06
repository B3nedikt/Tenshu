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
import org.tenshu.core.common.RELAY_PROTOCOL_MIN_SUPPORTED_VERSION
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
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
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.PushedEdge
import org.tenshu.sync.ReviveEntry
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.mailbox.ContentEdgeRegistry
import org.tenshu.sync.relay.mailbox.ContentEdgeStore
import org.tenshu.sync.relay.routing.MAX_REAP_REMOVALS
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteContentEdgeStore
import org.tenshu.sync.relay.storage.SqliteSyncMailbox
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CR-2 B3c through the real routes (`content-plane-cr2-b3-wire-spec.md` §3, §8's relay list):
 * `/sync/push` carries the row's edges, `/sync/push-tombstone` its cascade, `/sync/content/reap` a
 * device's reaps — and every one is ordered by HLC and gated per request on the protocol version.
 *
 * Dev-mode auth (`X-Dev-Owner`) throughout: auth is pinned by [SyncPushAuthTest] and
 * [SyncTombstoneEndpointTest]; this file pins what the bodies DO.
 */
class PushEdgesRoutingTest {

    private val owner = "alice-b3"
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

    private fun hlc(physical: Long) = HybridLogicalClock.create(ctx, physical, 0, "device-a")
    private fun stamp(physical: Long) = HlcStamp(physical, 0, "device-a")

    private fun block(physical: Long, target: String? = "holder-1", id: String = "b-$target-$physical") =
        SyncBlock.create(
            context = ctx,
            blockId = id,
            ownerId = owner,
            hlc = hlc(physical),
            encryptedPayload = byteArrayOf(1, 2, 3),
            targetEntityIdHash = target,
        )

    private fun edge(property: String, content: String?, regenerable: Boolean = false) =
        PushedEdge.create(ctx, property, content, regenerable)

    private suspend fun HttpClient.push(
        block: SyncBlock,
        vararg edges: PushedEdge,
        version: Int = RELAY_PROTOCOL_VERSION,
        revive: List<ReviveEntry> = emptyList(),
    ): HttpResponse = post("/sync/push") {
        contentType(ContentType.Application.OctetStream)
        header("X-Dev-Owner", owner)
        setBody(serializer.serialize(edgedPush(ctx, block, edges.toList(), version, revive)))
    }

    private suspend fun HttpClient.tombstone(
        target: String,
        physical: Long,
        cascade: List<String> = emptyList(),
        version: Int = RELAY_PROTOCOL_VERSION,
    ): HttpResponse = post("/sync/push-tombstone") {
        contentType(ContentType.Application.OctetStream)
        header("X-Dev-Owner", owner)
        setBody(
            serializer.serialize(
                cascadedTombstone(
                    ctx,
                    DeleteBlock.create(
                        context = ctx,
                        blockId = "t-$target-$physical",
                        ownerId = owner,
                        hlc = hlc(physical),
                        targetEntityIdHash = target,
                        targetEntityType = "tenshu/file",
                        // Protocol 5: the relay requires a 32-byte tag (format only).
                        authTag = ByteArray(32) { 1 },
                    ),
                    cascade,
                    version,
                )
            )
        )
    }

    private suspend fun HttpClient.reap(body: String): HttpResponse = post("/sync/content/reap") {
        contentType(ContentType.Application.Json)
        header("X-Dev-Owner", owner)
        setBody(body)
    }

    private fun reapBody(vararg removals: Pair<String, Long>, version: Int? = RELAY_PROTOCOL_VERSION): String =
        "{" + (version?.let { "\"relayProtocolVersion\":$it," } ?: "") + "\"removals\":[" +
            removals.joinToString(",") { (h, t) -> """{"holder":"$h","hlc":"${stamp(t).encode()}"}""" } + "]}"

    private fun harness(body: suspend io.ktor.server.testing.ApplicationTestBuilder.(ContentEdgeStore, SyncMailbox) -> Unit) =
        testApplication {
            val edges = ContentEdgeStore()
            val mailbox = SyncMailbox()
            application { testModule(contentEdgeStore = edges, syncMailbox = mailbox) }
            body(edges, mailbox)
        }

    // ── /sync/push ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPushStoresTheBlockAndWritesItsEdges_holderAndStampFromTheBlock() = harness { edges, mailbox ->
        val r = client.push(block(1_000), edge("file/data", "c1"), edge("file/thumbnail", "t1", regenerable = true))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())

        assertEquals(1, mailbox.getChanges(owner, 0).blocks.size)
        assertEquals(ContentEdgeRegistry.Register("c1", stamp(1_000)), edges.register(owner, "holder-1", "file/data"))
        assertEquals(ContentEdgeRegistry.Register("t1", stamp(1_000)), edges.register(owner, "holder-1", "file/thumbnail"))
        assertEquals(setOf("c1", "t1"), edges.referencedContentIds(owner))
    }

    @Test
    fun rule1_bothArrivalOrdersLandOnTheNewerWrite() {
        harness { edges, _ ->
            client.push(block(1_000), edge("file/data", "old"))
            client.push(block(2_000), edge("file/data", "new"))
            assertEquals(setOf("new"), edges.referencedContentIds(owner))
        }
        harness { edges, mailbox ->
            client.push(block(2_000), edge("file/data", "new"))
            val late = client.push(block(1_000), edge("file/data", "old"))
            assertEquals(HttpStatusCode.OK, late.status, "a late block is still a valid block")
            assertEquals(2, mailbox.getChanges(owner, 0).blocks.size, "the mailbox keeps it for peers")
            assertEquals(setOf("new"), edges.referencedContentIds(owner), "but its edge loses to the newer register")
        }
    }

    @Test
    fun anAbsentContentIdIsWrittenAsNone_andNoneSurvivesAsARegister() = harness { edges, _ ->
        client.push(block(1_000), edge("file/data", "c1"))
        client.push(block(2_000), edge("file/data", null))
        assertEquals(ContentEdgeRegistry.Register(null, stamp(2_000)), edges.register(owner, "holder-1", "file/data"))
        assertTrue(edges.referencedContentIds(owner).isEmpty())
        // A stale push of the old version delivered late still loses to the none.
        client.push(block(1_500), edge("file/data", "c1"))
        assertTrue(edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aPushWithNoEdgesTouchesNoRegister() = harness { edges, mailbox ->
        client.push(block(1_000), edge("file/data", "c1"))
        val batch = client.push(block(2_000, target = null, id = "batch"))
        assertEquals(HttpStatusCode.OK, batch.status, batch.bodyAsText())
        assertEquals(2, mailbox.getChanges(owner, 0).blocks.size)
        assertEquals(setOf("c1"), edges.referencedContentIds(owner))
    }

    @Test
    fun edgesOnABatchBlock_are400_andNothingIsStored() = harness { edges, mailbox ->
        val r = client.push(block(1_000, target = null), edge("file/data", "c1"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty())
        assertTrue(edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aDuplicatePropertyId_is400() = harness { edges, mailbox ->
        val r = client.push(block(1_000), edge("file/data", "c1"), edge("file/data", "c2"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty())
        assertTrue(edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aReviveNamingBytesTheRelayDoesNotHold_is409ReviveIncomplete_andRefusedWhole() = harness { edges, mailbox ->
        // ⚠ No longer reserved (CR-2 B4r): a revive is accepted, but only when every ref it names is stored.
        // The full matrix is `ContentFreedRoutingTest`; this pins that the B3 400 is gone and nothing lands.
        val r = client.push(
            block(1_000),
            edge("file/data", "c1"),
            revive = listOf(ReviveEntry.create(ctx, "c1", listOf("blob-" + "a".repeat(64)))),
        )
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("\"revive_incomplete\""), r.bodyAsText())
        assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty(), "refused whole: no block either")
        assertTrue(edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aPushOutsideTheProtocolRange_is426() = harness { edges, mailbox ->
        for (version in listOf(RELAY_PROTOCOL_MIN_SUPPORTED_VERSION - 1, RELAY_PROTOCOL_VERSION + 1)) {
            val r = client.push(block(1_000), edge("file/data", "c1"), version = version)
            assertEquals(HttpStatusCode.UpgradeRequired, r.status, "version $version")
            assertTrue(r.bodyAsText().contains("$RELAY_PROTOCOL_MIN_SUPPORTED_VERSION..$RELAY_PROTOCOL_VERSION"))
        }
        assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty())
        assertTrue(edges.referencedContentIds(owner).isEmpty())
    }

    @Test
    fun aBareLegacyBody_isAMalformed400() = harness { _, mailbox ->
        // The pre-B3 bodies (a bare block, no version) got a 426 until the 2026-10 reset; no client
        // that sends one reaches this relay any more, so they are malformed like any other bytes.
        val push = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(serializer.serialize(block(1_000)))
        }
        assertEquals(HttpStatusCode.BadRequest, push.status, push.bodyAsText())
        val tomb = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(
                serializer.serialize(
                    DeleteBlock.create(ctx, "t", owner, hlc(1_000), "holder-1", "tenshu/file")
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, tomb.status, tomb.bodyAsText())
        assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty())
        assertTrue(mailbox.getTombstones(owner, 0).tombstones.isEmpty())
    }

    // ── /sync/push-tombstone ─────────────────────────────────────────────────────────────────────

    @Test
    fun aTombstoneRemovesTheTargetAndItsCascadeAtT_andARecreateAboveTSurvives() = harness { edges, mailbox ->
        client.push(block(1_000, target = "folder"), edge("file/data", "cf"))
        client.push(block(1_000, target = "child-a"), edge("file/data", "ca"))
        client.push(block(1_000, target = "child-b"), edge("file/data", "cb"))
        // A peer recreated child-b ABOVE the delete, and that push arrived first.
        client.push(block(9_000, target = "child-b"), edge("file/data", "cb-new"))
        client.push(block(1_000, target = "bystander"), edge("file/data", "cx"))

        val r = client.tombstone("folder", 5_000, cascade = listOf("child-a", "child-b"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().toLong() > 0, "the assigned seq is still the response")

        assertEquals(1, mailbox.getTombstones(owner, 0).tombstones.size)
        assertEquals(setOf("cb-new", "cx"), edges.referencedContentIds(owner))
        for (h in listOf("folder", "child-a", "child-b")) assertEquals(stamp(5_000), edges.watermark(owner, h))
    }

    @Test
    fun theWatermarkBeatsALateOlderBlock() = harness { edges, mailbox ->
        client.tombstone("holder-1", 5_000)
        // Authored before the delete, delivered after it (a queued push from an offline device).
        val late = client.push(block(4_000), edge("file/data", "c1"))
        assertEquals(HttpStatusCode.OK, late.status)
        assertEquals(1, mailbox.getChanges(owner, 0).blocks.size, "the block is still logged for peers")
        assertTrue(edges.referencedContentIds(owner).isEmpty(), "but it may not resurrect the edge")
        // A genuine recreate above the delete does land.
        client.push(block(6_000), edge("file/data", "c2"))
        assertEquals(setOf("c2"), edges.referencedContentIds(owner))
    }

    @Test
    fun aTombstoneOutsideTheProtocolRange_is426_andRemovesNothing() = harness { edges, mailbox ->
        client.push(block(1_000), edge("file/data", "c1"))
        val r = client.tombstone("holder-1", 5_000, version = RELAY_PROTOCOL_MIN_SUPPORTED_VERSION - 1)
        assertEquals(HttpStatusCode.UpgradeRequired, r.status)
        assertTrue(mailbox.getTombstones(owner, 0).tombstones.isEmpty())
        assertEquals(setOf("c1"), edges.referencedContentIds(owner))
    }

    // ── /sync/content/reap ───────────────────────────────────────────────────────────────────────

    @Test
    fun aReapRemovesAtT_isIdempotent_andARecreateAboveTSurvives() = harness { edges, _ ->
        client.push(block(1_000, target = "child-a"), edge("file/data", "ca"))
        client.push(block(9_000, target = "child-b"), edge("file/data", "cb"))

        val first = client.reap(reapBody("child-a" to 5_000, "child-b" to 5_000))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertTrue(first.bodyAsText().contains("\"edgesCleared\":1"), first.bodyAsText())
        assertEquals(setOf("cb"), edges.referencedContentIds(owner))

        // Every reaping device sends the same (holder, T).
        val again = client.reap(reapBody("child-a" to 5_000, "child-b" to 5_000))
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue(again.bodyAsText().contains("\"edgesCleared\":0"), again.bodyAsText())
        assertEquals(setOf("cb"), edges.referencedContentIds(owner))

        // The child a peer pushed before the deleter pulled it: a late older push still loses.
        client.push(block(4_000, target = "child-a"), edge("file/data", "ca-late"))
        assertEquals(setOf("cb"), edges.referencedContentIds(owner))
    }

    @Test
    fun aReapOutsideTheProtocolRange_is426_andWithoutOne_isMalformed() = harness { edges, _ ->
        client.push(block(1_000), edge("file/data", "c1"))
        assertEquals(
            HttpStatusCode.UpgradeRequired,
            client.reap(reapBody("holder-1" to 5_000, version = RELAY_PROTOCOL_MIN_SUPPORTED_VERSION - 1)).status,
        )
        // ⚠ Required since the 2026-10 reset: a body without a version is malformed, not "too old".
        assertEquals(HttpStatusCode.BadRequest, client.reap(reapBody("holder-1" to 5_000, version = null)).status)
        assertEquals(setOf("c1"), edges.referencedContentIds(owner))
    }

    @Test
    fun aReapIsBoundedAndShapeChecked() = harness { edges, _ ->
        client.push(block(1_000), edge("file/data", "c1"))
        val tooMany = (0..MAX_REAP_REMOVALS).map { "h-$it" to 5_000L }.toTypedArray()
        assertEquals(HttpStatusCode.BadRequest, client.reap(reapBody(*tooMany)).status, "over 1,000 is refused, not truncated")
        assertEquals(HttpStatusCode.BadRequest, client.reap(reapBody()).status, "an empty batch is a caller bug")
        val badStamp = client.reap("""{"relayProtocolVersion":$RELAY_PROTOCOL_VERSION,"removals":[{"holder":"holder-1","hlc":"not-a-stamp"}]}""")
        assertEquals(HttpStatusCode.BadRequest, badStamp.status, "an unreadable stamp cannot be ordered")
        assertEquals(setOf("c1"), edges.referencedContentIds(owner), "a refused batch removes nothing")
        assertEquals(HttpStatusCode.OK, client.reap(reapBody(*(0 until MAX_REAP_REMOVALS).map { "h-$it" to 5_000L }.toTypedArray())).status)
    }

    // ── one transaction ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theBlockAndItsEdgesCommitTogether_orNotAtAll() {
        // ⚠⚠ Spec §3.1: a block stored without its edges is a row whose content the relay believes
        // unreferenced. Over the SQLite pair `main` wires (one database), a failing register write
        // must take the block append down with it.
        val dbFile = File.createTempFile("b3-atomic", ".db").also { it.delete() }
        try {
            RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}").use { db ->
                val real = SqliteContentEdgeStore(db)
                val failing = object : ContentEdgeRegistry by real {
                    override fun writeEdges(
                        ownerId: String,
                        holderHash: String,
                        stamp: HlcStamp,
                        edges: List<ContentEdgeRegistry.EdgeWrite>,
                    ): Int = error("disk full")
                }
                val mailbox = SqliteSyncMailbox(db, RelayBinaryCodec())
                testApplication {
                    application {
                        module(
                            config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                            contentEdgeStore = failing,
                            syncMailbox = mailbox,
                        )
                    }
                    val r = client.push(block(1_000), edge("file/data", "c1"))
                    assertEquals(HttpStatusCode.InternalServerError, r.status)
                }
                assertTrue(mailbox.getChanges(owner, 0).blocks.isEmpty(), "the block must roll back with its edges")
                assertNull(real.register(owner, "holder-1", "file/data"))
            }
        } finally {
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
