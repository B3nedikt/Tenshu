package org.tenshu.sync.relay

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
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
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.auth.KnownDevicesStore
import kotlin.test.Test
import org.tenshu.core.common.util.HlcStamp
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import io.ktor.client.statement.bodyAsText
import kotlin.test.assertTrue

/**
 * Coverage for the tombstone wire-layer endpoints added in `docs/adr/0006-sync-tombstones.md` Phase 3:
 * `POST /sync/push-tombstone` and `GET /sync/tombstones`. Mirrors [SyncPushAuthTest] for
 * auth — same JWT shape, same ownerId-on-block must match authenticated owner. The
 * cross-proxy `RevokeDevicePropagationTest` exercises the operation pipeline through
 * [MockSyncRelay]; this file pins the actual Ktor route + JWT enforcement so a regression
 * there can't slip past in-process tests.
 */
class SyncTombstoneEndpointTest {

    private val devicePrivateKey = "device-tombstone-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey  // stub-level: public == private

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

    private fun makeTombstone(
        ctx: TContext,
        ownerId: String,
        physicalTime: Long = 1_000L,
        targetIdHash: String = "victim-hash",
        targetType: String = "tenshu/identity/device",
        // Protocol 5: the relay requires a 32-byte tag (format only — it cannot verify it).
        authTag: ByteArray? = ByteArray(32) { 1 },
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "tombstone-${ownerId}-${physicalTime}",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, "device-tombstone"),
        targetEntityIdHash = targetIdHash,
        targetEntityType = targetType,
        authTag = authTag,
    )

    private fun makeDeviceTombstone(
        ctx: TContext,
        ownerId: String,
        revokedDeviceId: String,
        physicalTime: Long,
    ): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "tombstone-$ownerId-$physicalTime",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, physicalTime, 0, "device-tombstone"),
        targetEntityIdHash = "victim-hash",
        targetEntityType = "tenshu/identity/device",
        revokedDeviceId = revokedDeviceId,
        authTag = ByteArray(32) { 1 },
    )

    @Test
    fun devMode_pushTombstoneWithoutAValidAuthTag_returns400_andIsNotStored() = testApplication {
        // Protocol 5 (`sync-envelope-authentication.md` §3.1/§5): a tombstone with no tag, or a tag
        // that is not exactly 32 bytes, is rejected at the format level and never stored — every
        // peer would drop it, and the relay would keep it for ever.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer, parser) = makeContext()
        val owner = "alice-tomb-untagged"
        val untagged = makeTombstone(ctx, owner, physicalTime = 100L, targetIdHash = "no-tag", authTag = null)
        val shortTag = makeTombstone(
            ctx, owner, physicalTime = 200L, targetIdHash = "short-tag", authTag = ByteArray(16) { 1 },
        )

        for (block in listOf(untagged, shortTag)) {
            val push = client.post("/sync/push-tombstone") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", owner)
                setBody(serializer.serialize(cascadedTombstone(ctx, block)))
            }
            assertEquals(
                HttpStatusCode.BadRequest,
                push.status,
                "a tombstone with authTag=${block.authTag?.get()?.size ?: "none"} must be refused",
            )
            push.bodyAsBytes()
        }

        val getResponse = client.get("/sync/tombstones") {
            header("X-Dev-Owner", owner)
            url { parameters.append("since", "0") }
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val stored = parser.parse<org.tenshu.sync.TombstonesResponse>(getResponse.bodyAsBytes())
        assertEquals(0, stored.tombstones.toList().size, "a refused tombstone must not be stored")
    }

    @Test
    fun aDeviceTombstoneOlderThanTheAdmissionDoesNotRevoke() = testApplication {
        // ⚠⚠ End to end over the real route, because the incident (2026-08-17) was not a storage
        // bug — the endpoint applied `revokedDeviceId` the moment it arrived. A retirement authored
        // in the morning could not push (401 on a wiped relay, then 402 with no entitlement), sat in
        // a client queue, and executed that evening AFTER the device had been re-admitted.
        val store = KnownDevicesStore().apply {
            register("alice-stale", "device-tombstone", devicePublicKey)
            // The VICTIM was admitted long after the tombstone below was authored.
            register("alice-stale", "victim-device", devicePublicKey, admittedAt = HlcStamp(9_000, 0, "admitter"))
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice-stale", "device-tombstone", nowSec - 5, nowSec + 60))

        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(cascadedTombstone(ctx, makeDeviceTombstone(ctx, "alice-stale", "victim-device", physicalTime = 1_000))))
        }

        assertEquals(HttpStatusCode.OK, response.status, "the tombstone is still stored for peers")
        response.bodyAsBytes()
        assertNotNull(
            store.getPublicKey("alice-stale", "victim-device"),
            "a tombstone authored before the admission must not revoke the registration",
        )
    }

    @Test
    fun aDeviceTombstoneNewerThanTheAdmissionStillRevokes() = testApplication {
        // The half that must not regress: this is a real security control (T-K-02) — a revoked
        // device keeps posting to the mailbox until its registration is gone.
        val store = KnownDevicesStore().apply {
            register("alice-fresh", "device-tombstone", devicePublicKey)
            register("alice-fresh", "victim-device", devicePublicKey, admittedAt = HlcStamp(1_000, 0, "admitter"))
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store,
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice-fresh", "device-tombstone", nowSec - 5, nowSec + 60))

        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(cascadedTombstone(ctx, makeDeviceTombstone(ctx, "alice-fresh", "victim-device", physicalTime = 9_000))))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
        assertNull(
            store.getPublicKey("alice-fresh", "victim-device"),
            "a retirement authored after the admission must still revoke it",
        )
    }

    private fun signToken(claims: JwtClaims): String =
        encodeJwt(claims) { msg -> stubEd25519Signature(devicePrivateKey, msg) }

    @Test
    fun production_pushTombstoneWithValidJwt_succeeds() = testApplication {
        val store = KnownDevicesStore().apply {
            register("alice-tomb-1", "device-tombstone", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token =
            signToken(JwtClaims("alice-tomb-1", "device-tombstone", nowSec - 5, nowSec + 60))

        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(cascadedTombstone(ctx, makeTombstone(ctx, "alice-tomb-1"))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
    }

    @Test
    fun pushTombstone_returnsTheAssignedSeq_andItAdvancesPerPush() = testApplication {
        // RH-1a (`sync-retention-horizons.md` §3.3, D9). The relay has always assigned this seq
        // and thrown it away, which is why a reclamation candidate — identified by a millisecond —
        // had nothing commensurable to compare against a peer's cursor.
        val store = KnownDevicesStore().apply {
            register("alice-seq", "device-seq", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token = signToken(JwtClaims("alice-seq", "device-seq", nowSec - 5, nowSec + 60))

        suspend fun push(): Long {
            val response = client.post("/sync/push-tombstone") {
                contentType(ContentType.Application.OctetStream)
                header("Authorization", "Bearer $token")
                setBody(serializer.serialize(cascadedTombstone(ctx, makeTombstone(ctx, "alice-seq"))))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            return body.toLongOrNull()
                ?: throw AssertionError("expected the assigned seq as the body, got: '$body'")
        }

        val first = push()
        val second = push()

        // ⚠ The assertion is *strictly* increasing, not merely non-null. A route that answered a
        // constant — the "OK" this replaced, or a hard-coded 0 — would satisfy "parses as a Long"
        // while telling the barrier nothing about ordering, which is the whole point of the value.
        assertTrue(first > 0L, "the first assigned seq should be positive, was $first")
        assertTrue(
            second > first,
            "each push must take a later seq from the relay's counter; got $first then $second",
        )
    }

    @Test
    fun production_pushTombstoneWithoutAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer, _) = makeContext()
        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            setBody(serializer.serialize(cascadedTombstone(ctx, makeTombstone(ctx, "alice-tomb-2"))))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_pushTombstoneWithMismatchedOwner_returns403() = testApplication {
        // Mirror of SyncPushAuthTest.production_pushWithMismatchedOwner_returns403: JWT
        // identifies Alice; the tombstone claims to belong to Mallory. Without this check
        // a compromised Alice device could ship a tombstone deleting Mallory's entities.
        val store = KnownDevicesStore().apply {
            register("alice-tomb-3", "device-tombstone", devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = store
            )
        }

        val (ctx, serializer, _) = makeContext()
        val nowSec = System.currentTimeMillis() / 1000
        val token =
            signToken(JwtClaims("alice-tomb-3", "device-tombstone", nowSec - 5, nowSec + 60))
        val malloryTombstone = makeTombstone(ctx, "mallory-tomb")

        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(cascadedTombstone(ctx, malloryTombstone)))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun production_getTombstonesWithoutAuth_returns401() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val response = client.get("/sync/tombstones") {
            url { parameters.append("since", "0") }
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun devMode_pushAndGetTombstone_roundTrip() = testApplication {
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer, parser) = makeContext()
        val owner = "alice-tomb-roundtrip"
        val tombstone = makeTombstone(
            ctx = ctx,
            ownerId = owner,
            physicalTime = 5_000L,
            targetIdHash = "device-victim-hash",
            targetType = "tenshu/identity/device"
        )

        val pushResponse = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", owner)
            setBody(serializer.serialize(cascadedTombstone(ctx, tombstone)))
        }
        assertEquals(HttpStatusCode.OK, pushResponse.status)
        pushResponse.bodyAsBytes()

        val getResponse = client.get("/sync/tombstones") {
            header("X-Dev-Owner", owner)
            url { parameters.append("since", "0") }
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)

        val response = parser.parse<org.tenshu.sync.TombstonesResponse>(getResponse.bodyAsBytes())
        val tombstones = response.tombstones.toList()
        assertEquals(1, tombstones.size)
        val received = tombstones.single()
        assertEquals(tombstone.blockId.get(), received.blockId.get())
        assertEquals("device-victim-hash", received.targetEntityIdHash.get())
        assertEquals("tenshu/identity/device", received.targetEntityType.get())
        assertEquals(5_000L, received.hlc.physicalTime.get())
        assertTrue(
            response.nextCursor.get() > 0L,
            "nextCursor must reflect the arrival-time seq of the returned tombstone"
        )
    }

    @Test
    fun devMode_getTombstones_filtersBySinceCursor() = testApplication {
        // Cursor semantics are now arrival-time (mirrors `/sync/changes`): every push
        // assigns a strictly-monotonic seq, the filter is `seq > since`, and the response's
        // `nextCursor` lets the receiver advance without ever colliding on a same-millisecond
        // HLC tick. The receiver passes back the prior `nextCursor` as the next `since`.
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore()
            )
        }

        val (ctx, serializer, parser) = makeContext()
        val owner = "alice-tomb-filter"

        val early = makeTombstone(ctx, owner, physicalTime = 100L, targetIdHash = "early-target")
        val late = makeTombstone(ctx, owner, physicalTime = 500L, targetIdHash = "late-target")

        for (block in listOf(early, late)) {
            val push = client.post("/sync/push-tombstone") {
                contentType(ContentType.Application.OctetStream)
                header("X-Dev-Owner", owner)
                setBody(serializer.serialize(cascadedTombstone(ctx, block)))
            }
            assertEquals(HttpStatusCode.OK, push.status)
            push.bodyAsBytes()
        }

        // First pull from cursor=0 returns both tombstones and a cursor past them.
        val firstResp = client.get("/sync/tombstones") {
            header("X-Dev-Owner", owner)
            url { parameters.append("since", "0") }
        }
        assertEquals(HttpStatusCode.OK, firstResp.status)
        val firstParsed =
            parser.parse<org.tenshu.sync.TombstonesResponse>(firstResp.bodyAsBytes())
        val firstReceived = firstParsed.tombstones.toList()
        assertEquals(2, firstReceived.size)
        val advancedCursor = firstParsed.nextCursor.get()
        assertTrue(advancedCursor > 0L)

        // Second pull from the advanced cursor returns nothing — the cursor has moved past
        // both arrivals, regardless of their HLC physicalTime.
        val secondResp = client.get("/sync/tombstones") {
            header("X-Dev-Owner", owner)
            url { parameters.append("since", advancedCursor.toString()) }
        }
        assertEquals(HttpStatusCode.OK, secondResp.status)
        val secondParsed =
            parser.parse<org.tenshu.sync.TombstonesResponse>(secondResp.bodyAsBytes())
        assertEquals(
            0,
            secondParsed.tombstones.toList().size,
            "advanced cursor should exclude all prior tombstones"
        )
        assertEquals(
            advancedCursor,
            secondParsed.nextCursor.get(),
            "empty response cursor stays at the passed-in `since` so other-stream " +
                    "(blocks vs tombstones) pushes in the same tick aren't skipped"
        )
    }
}
