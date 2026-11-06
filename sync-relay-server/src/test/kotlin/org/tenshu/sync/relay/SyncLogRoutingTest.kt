package org.tenshu.sync.relay

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.auth.KnownDevicesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `POST /sync/log` route (`sync-lane-cursor-split.md` Option C / L3) at the Ktor layer.
 *
 * Two things need pinning here that the store-level `MergedSyncLogTest` cannot reach:
 *
 *  1. **The route is additive.** `/sync/changes` must keep working untouched — that is the
 *     entire reason this slice owes no `RELAY_PROTOCOL_VERSION` bump and no coordinated
 *     redeploy. If it ever stops being true, the "no flag day" claim in `RelayProtocol.kt`
 *     silently becomes false for this route.
 *  2. **The auth + signed-tip preamble is shared, not copied.** `/log` and `/changes` now run
 *     one extracted helper. A security check that exists in two copies drifts; these tests
 *     assert the *new* route enforces what the old one does, so a future edit to one is
 *     caught if it doesn't reach both.
 */
class SyncLogRoutingTest {

    private val devicePrivateKey = "device-log-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey // stub-level: public == private

    private fun makeContext(): Triple<TContext, BinarySerializer, BinaryParser> {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(
                coreCommonModule,
                syncServiceCommonModule,
                identityServiceCommonModule
            )
        )
        ctx.components = mapOf(
            SchemaProvider.COMPONENT_ID to registry,
            DataFactory.COMPONENT_ID to registry
        )
        return Triple(ctx, BinarySerializer(registry), BinaryParser(registry, registry))
    }

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    private fun tip(ctx: TContext, owner: String, device: String, physical: Long = 5_000L): SignedHlcTip {
        val hlc = HybridLogicalClock.create(ctx, physical, 0, device)
        return SignedHlcTip.create(
            context = ctx,
            ownerId = owner,
            deviceId = device,
            hlc = hlc,
            signature = stubEd25519Signature(
                devicePrivateKey,
                hlcTipCanonicalMessage(owner, device, hlc)
            )
        )
    }

    private fun block(ctx: TContext, owner: String, id: String): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = id,
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, 1_000L, 0, "device-log"),
        encryptedPayload = "payload-$id".encodeToByteArray(),
        targetEntityIdHash = "H-$id",
    )

    private fun tombstone(ctx: TContext, owner: String, physical: Long): DeleteBlock =
        DeleteBlock.create(
            context = ctx,
            blockId = "tomb-$physical",
            ownerId = owner,
            hlc = HybridLogicalClock.create(ctx, physical, 0, "device-log"),
            targetEntityIdHash = "H-tomb-$physical",
            targetEntityType = "tenshu/test/entity",
        )

    @Test
    fun servesBothKindsInOneAuthenticatedResponse() = testApplication {
        val owner = "alice-log-1"
        val device = "device-log"
        val mailbox = SyncMailbox()
        val store = KnownDevicesStore().apply { register(owner, device, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
                syncMailbox = mailbox,
            )
        }

        val (ctx, serializer, parser) = makeContext()
        mailbox.pushBlock(owner, block(ctx, owner, "b1"))
        mailbox.pushTombstone(owner, tombstone(ctx, owner, 2_000L))
        mailbox.pushBlock(owner, block(ctx, owner, "b2"))

        val nowSec = System.currentTimeMillis() / 1000
        val response = client.post("/sync/log?since=0") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${signToken(JwtClaims(owner, device, nowSec - 5, nowSec + 60))}")
            setBody(serializer.serialize(tip(ctx, owner, device)))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val log = parser.parse<SyncLogResponse>(response.bodyAsBytes())
        assertEquals(listOf("b1", "b2"), log.blocks.toList().map { it.blockId.get() })
        assertEquals(1, log.tombstones.toList().size)
        assertEquals(3L, log.nextCursor.get())
        assertFalse(log.hasMore.get())
    }

    @Test
    fun theOldTwoLaneRoutesStillWork_theNewOneIsPurelyAdditive() = testApplication {
        // The no-bump, no-flag-day claim in one assertion: a client that never heard of
        // `/sync/log` must be served exactly as before by a relay that has it.
        val owner = "alice-log-2"
        val device = "device-log"
        val mailbox = SyncMailbox()
        val store = KnownDevicesStore().apply { register(owner, device, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
                syncMailbox = mailbox,
            )
        }

        val (ctx, serializer, parser) = makeContext()
        mailbox.pushBlock(owner, block(ctx, owner, "b1"))

        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims(owner, device, nowSec - 5, nowSec + 60))
        val response = client.post("/sync/changes?since=0") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(tip(ctx, owner, device)))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val changes = parser.parse<ChangesResponse>(response.bodyAsBytes())
        assertEquals(listOf("b1"), changes.blocks.toList().map { it.blockId.get() })
    }

    @Test
    fun rejectsAnUnauthenticatedCall() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
            )
        }
        val (ctx, serializer, _) = makeContext()
        val response = client.post("/sync/log?since=0") {
            contentType(ContentType.Application.OctetStream)
            setBody(serializer.serialize(tip(ctx, "alice-log-3", "device-log")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun rejectsATipClaimingToBeSomeoneElse() = testApplication {
        // The self-attribution check, asserted on the NEW route specifically: the whole point
        // of extracting the preamble was that this must not be a check `/changes` has and
        // `/log` forgot.
        val owner = "alice-log-4"
        val device = "device-log"
        val store = KnownDevicesStore().apply { register(owner, device, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val response = client.post("/sync/log?since=0") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${signToken(JwtClaims(owner, device, nowSec - 5, nowSec + 60))}")
            // JWT says alice-log-4; the tip claims mallory.
            setBody(serializer.serialize(tip(ctx, "mallory", device)))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun pagesTheMergedLogLosslessly() = testApplication {
        val owner = "alice-log-5"
        val device = "device-log"
        val mailbox = SyncMailbox()
        val store = KnownDevicesStore().apply { register(owner, device, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
                syncMailbox = mailbox,
            )
        }

        val (ctx, serializer, parser) = makeContext()
        repeat(4) { mailbox.pushBlock(owner, block(ctx, owner, "b$it")) }
        repeat(4) { mailbox.pushTombstone(owner, tombstone(ctx, owner, 3_000L + it)) }

        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims(owner, device, nowSec - 5, nowSec + 60))

        // Walk to exhaustion driven by `hasMore` — the client's actual loop condition.
        val seenBlocks = mutableListOf<String>()
        var seenTombstones = 0
        var cursor = 0L
        var pages = 0
        while (true) {
            val response = client.post("/sync/log?since=$cursor") {
                contentType(ContentType.Application.OctetStream)
                header("Authorization", "Bearer $token")
                setBody(serializer.serialize(tip(ctx, owner, device)))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val page = parser.parse<SyncLogResponse>(response.bodyAsBytes())
            seenBlocks += page.blocks.toList().map { it.blockId.get() }
            seenTombstones += page.tombstones.toList().size
            cursor = page.nextCursor.get()
            pages++
            check(pages < 20) { "paging did not terminate — cursor stuck at $cursor" }
            if (!page.hasMore.get()) break
        }

        assertEquals((0 until 4).map { "b$it" }, seenBlocks)
        assertEquals(4, seenTombstones)
        assertTrue(pages >= 1)
    }
}
