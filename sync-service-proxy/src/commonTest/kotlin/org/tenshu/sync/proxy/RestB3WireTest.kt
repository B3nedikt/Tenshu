package org.tenshu.sync.proxy

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.CascadedTombstonePush
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.EdgedPush
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.ContentDescription
import org.tenshu.sync.common.ReapRemoval
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B3d, the client's wire (`content-plane-cr2-b3-wire-spec.md` §3): what `RestSyncRelay` and
 * `RestContentBlobStore` actually send. Every client — Android, desktop, iOS and the JS runtime —
 * pushes through these two classes, and this suite is `commonTest`, so it is also the JS runtime's
 * pin that it sends the new bodies.
 *
 * - `/sync/push` is an `EdgedPush` stamped with [RELAY_PROTOCOL_VERSION], carrying the edges handed
 *   in and nothing else;
 * - `/sync/push-tombstone` is a `CascadedTombstonePush` carrying the cascade;
 * - `/sync/content/reap` is JSON, at most 1,000 removals per request;
 * - the describe is the only declare left, and nothing calls `/sync/content/undeclare`.
 */
class RestB3WireTest {

    private val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry().apply { registerConfig(syncServiceCommonModule) })
    private val context = proxy.tContext
    private val parser = BinaryParser(proxy.executionContext.schemaProvider, context.dataFactory())
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(
        MockEngine { request ->
            seen += request
            respond("7", status, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
    ) { install(ContentNegotiation) { json() } }

    private fun relay(status: HttpStatusCode = HttpStatusCode.OK) = RestSyncRelay(
        baseUrl = "https://relay.example",
        httpClient = client(status),
        context = context,
        authTokenProvider = { "jwt" },
        ownerIdProvider = { "owner" },
    )

    private fun store(status: HttpStatusCode = HttpStatusCode.OK) = RestContentBlobStore(
        baseUrl = "https://relay.example",
        httpClient = client(status),
        context = context,
        authTokenProvider = { "jwt" },
    )

    private fun hlc() = HybridLogicalClock.create(context, 1_000, 2, "device-a")

    private fun block(target: String? = "holder-1") = SyncBlock.create(
        context = context,
        blockId = "block-1",
        ownerId = "owner",
        hlc = hlc(),
        encryptedPayload = byteArrayOf(1, 2, 3),
        targetEntityIdHash = target,
    )

    private fun bytesOf(request: HttpRequestData): ByteArray = (request.body as OutgoingContent.ByteArrayContent).bytes()
    private fun textOf(request: HttpRequestData): String = (request.body as TextContent).text

    @Test
    fun aPushIsAnEdgedPush_carryingTheEdgesAndTheProtocolVersion() = runTest {
        // ⚠ Already tagged: every drain hands `RestSyncRelay` keyed property tags (wire spec §11).
        val edges = listOf(
            ContentEdge(TAG_A, "cid-1", regenerable = false),
            ContentEdge(TAG_B, null, regenerable = true),
        )

        relay().pushBlock(block(), edges)

        val request = seen.single()
        assertEquals("https://relay.example/sync/push", request.url.toString())
        val pushed = parser.parse<EdgedPush>(bytesOf(request))
        assertEquals(RELAY_PROTOCOL_VERSION.toLong(), pushed.relayProtocolVersion.get())
        assertEquals("block-1", pushed.block.blockId.get())
        assertEquals("holder-1", pushed.block.targetEntityIdHash?.get())
        assertEquals(
            edges,
            pushed.edges.toList().map { ContentEdge(it.propertyId.get(), it.contentId?.get(), it.regenerable.get()) },
            "exactly the edges handed in — none as an ABSENT content id, never a sentinel",
        )
        assertTrue(pushed.revive.toList().isEmpty(), "revive is reserved for B4 and must be empty")
    }

    @Test
    fun anUntaggedEdgeNeverLeavesTheDevice() = runTest {
        // ⚠⚠ wire spec §11: a schema property id on the wire is the leak the tag exists to close. A
        // caller that forgot to tag fails locally — its block stays queued — and nothing is sent.
        val result = relay().pushEdged(block(), listOf(ContentEdge("tenshu/file/file/data", "cid-1", regenerable = false)))

        assertTrue(result is org.tenshu.sync.common.PushOutcome.Failed, "refused, not sent: $result")
        assertTrue(seen.isEmpty(), "no request may carry a plaintext property id: ${seen.map { it.url }}")
    }

    @Test
    fun aPushWithNoEdgesStillSendsTheNewBody() = runTest {
        // The default every non-content block takes — and every batch, which the relay 400s with edges.
        relay().pushBlock(block(target = null))

        val pushed = parser.parse<EdgedPush>(bytesOf(seen.single()))
        assertTrue(pushed.edges.toList().isEmpty())
        assertEquals(RELAY_PROTOCOL_VERSION.toLong(), pushed.relayProtocolVersion.get())
    }

    @Test
    fun aTombstoneIsACascadedTombstonePush_carryingItsCascade() = runTest {
        val tombstone = DeleteBlock.create(
            context = context,
            blockId = "t-1",
            ownerId = "owner",
            hlc = hlc(),
            targetEntityIdHash = "folder",
            targetEntityType = "tenshu/file/system/file",
            revokedDeviceId = null,
        )

        val result = relay().pushTombstone(tombstone, listOf("child-a", "child-b"))

        val request = seen.single()
        assertEquals("https://relay.example/sync/push-tombstone", request.url.toString())
        val pushed = parser.parse<CascadedTombstonePush>(bytesOf(request))
        assertEquals(RELAY_PROTOCOL_VERSION.toLong(), pushed.relayProtocolVersion.get())
        assertEquals("folder", pushed.block.targetEntityIdHash.get())
        assertEquals(listOf("child-a", "child-b"), pushed.cascade.toList().map { it.get() })
        assertTrue(result is org.tenshu.core.TResult.Success<*>, "the assigned seq still comes back")
    }

    @Test
    fun reapsGoOutAsJson_atMostOneThousandPerRequest() = runTest {
        val removals = (1..2_500).map { ReapRemoval(holder = "h$it", hlc = "1000:0:device-a") }

        assertTrue(store().reapRemovals(removals))

        assertEquals(3, seen.size)
        seen.forEach { request ->
            assertEquals("https://relay.example/sync/content/reap", request.url.toString())
            val body = Json.parseToJsonElement(textOf(request)).jsonObject
            assertEquals(RELAY_PROTOCOL_VERSION, body.getValue("relayProtocolVersion").jsonPrimitive.int)
            val batch = body.getValue("removals").jsonArray
            assertTrue(batch.size <= 1_000, "a batch of ${batch.size}")
            val first = batch.first().jsonObject
            assertEquals(setOf("holder", "hlc"), first.keys)
            assertEquals("1000:0:device-a", first.getValue("hlc").jsonPrimitive.content)
        }
        assertEquals(2_500, seen.sumOf { Json.parseToJsonElement(textOf(it)).jsonObject.getValue("removals").jsonArray.size })
    }

    @Test
    fun aRefusedReapIsFalse_soTheOutboxKeepsIt() = runTest {
        assertFalse(store(HttpStatusCode.ServiceUnavailable).reapRemovals(listOf(ReapRemoval("h", "1:0:d"))))
    }

    /** CR-2 B6 §1: the describe sends a content and its refs — no `referenceId`, no `hlc` stamp. */
    @Test
    fun theDescribeIsTheOnlyDeclare_withNoEdgeForm_andNoReferenceHandle() = runTest {
        assertTrue(
            store().describeContent(
                listOf(
                    ContentDescription("cid-1", listOf("blob-a")),
                    ContentDescription("cid-2", listOf("blob-b")),
                )
            )
        )

        val request = seen.single()
        assertEquals("https://relay.example/sync/content/declare", request.url.toString())
        val body = Json.parseToJsonElement(textOf(request)).jsonObject
        assertFalse("edges" in body, "the edge form is deleted at the relay: $body")
        val contents = body.getValue("contents").jsonArray.map { it.jsonObject }
        assertEquals(listOf("cid-1", "cid-2"), contents.map { it.getValue("contentId").jsonPrimitive.content })
        contents.forEach { entry ->
            assertFalse("referenceId" in entry, "B6 retired the handle; the describe must not carry one: $entry")
            assertFalse("hlc" in entry, "the handle's stamp went with it: $entry")
        }
    }

    @Test
    fun nothingTheClientSendsNamesADeletedRoute() = runTest {
        // ⚠ The deleted routes (`/sync/content/undeclare`, the edge form of `/declare`) have no method left
        // on either port, so this is mostly a compile-time fact. Pinned at the wire anyway, over every
        // write the two classes make.
        val r = relay()
        val s = store()
        r.pushBlock(block(), listOf(ContentEdge(TAG_A, "c", regenerable = false)))
        r.pushTombstone(
            DeleteBlock.create(context, "t", "owner", hlc(), "x", "tenshu/file/system/file", null),
            listOf("y"),
        )
        s.describeContent(listOf(ContentDescription("c", listOf("blob"))))
        s.reapRemovals(listOf(ReapRemoval("y", "1:0:d")))

        val paths = seen.map { it.url.encodedPath }
        assertEquals(listOf("/sync/push", "/sync/push-tombstone", "/sync/content/declare", "/sync/content/reap"), paths)
        assertFalse(seen.any { it.url.encodedPath == "/sync/content/undeclare" })
    }

    private companion object {
        val TAG_A = "a".repeat(ContentPropertyTag.TAG_HEX_LENGTH)
        val TAG_B = "b".repeat(ContentPropertyTag.TAG_HEX_LENGTH)
    }
}
