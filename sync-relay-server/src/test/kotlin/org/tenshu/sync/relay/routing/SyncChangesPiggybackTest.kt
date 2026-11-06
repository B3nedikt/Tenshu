package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule

import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.HlcTipStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for the `POST /sync/changes` HLC-piggyback semantics
 * (sync-backend-relay-plan.md §5.1, threat-model §11.3 / T-T-04). Pins:
 *
 *  - JWT auth + per-call tip-signature verification reject forgeries.
 *  - A relay-stored tip from one device is returned as a `peerTip` to another.
 *  - tip.deviceId / tip.ownerId mismatched against the JWT claimant are 403.
 *
 * Companion to `HlcTipStoreTest` (unit-tests the storage primitive) and the
 * proxy-side `HlcDivergenceTrackerTest` (unit-tests receiver-side detection).
 */
class SyncChangesPiggybackTest {

    private val devicePrivateKey = "device-piggyback-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey  // stub: pub == priv
    private val ownerId = "alice-piggyback"
    private val deviceId = "device-piggyback"

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

    private fun jwt(): String {
        val now = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, deviceId, now - 5, now + 60)) { msg ->
            stubEd25519Signature(devicePrivateKey, msg)
        }
    }

    private fun signedTip(
        ctx: TContext,
        ownerId: String = this.ownerId,
        deviceId: String = this.deviceId,
        physicalTime: Long = 1_000L,
        signingKey: ByteArray = devicePrivateKey
    ): SignedHlcTip {
        val hlc = HybridLogicalClock.create(ctx, physicalTime, 0, deviceId)
        val signature = stubEd25519Signature(
            signingKey,
            hlcTipCanonicalMessage(ownerId, deviceId, hlc)
        )
        return SignedHlcTip.create(ctx, ownerId, deviceId, hlc, signature)
    }

    @Test
    fun production_validJwtAndSignature_storesAndReturnsEmptyPeerTips() = testApplication {
        val knownDevices =
            KnownDevicesStore().apply { register(ownerId, deviceId, devicePublicKey) }
        val tipStore = HlcTipStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                hlcTipStore = tipStore
            )
        }

        val (ctx, serializer, parser) = makeContext()
        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt()}")
            parameter("since", 0L)
            setBody(serializer.serialize(signedTip(ctx)))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val parsed = parser.parse<ChangesResponse>(response.bodyAsBytes())
        assertEquals(0, parsed.blocks.toList().size)
        assertEquals(0, parsed.peerTips.toList().size)  // No peers yet.

        // Tip was recorded server-side.
        assertEquals(1, tipStore.allTips(ownerId).size)
        assertEquals(1_000L, tipStore.allTips(ownerId).first().hlc.physicalTime.get())
    }

    @Test
    fun peerTipsAreReturned_excludingRequester() = testApplication {
        // Pre-seed a tip from a peer device. From the requester's perspective, this
        // peer's tip should appear in the response while the requester's own tip is
        // omitted (the requester already knows what they published).
        val peerKey = "peer-key".encodeToByteArray()
        val peerId = "peer-device"
        val knownDevices = KnownDevicesStore().apply {
            register(ownerId, deviceId, devicePublicKey)
            register(ownerId, peerId, peerKey)
        }
        val tipStore = HlcTipStore()

        val (ctx, serializer, parser) = makeContext()
        val peerHlc = HybridLogicalClock.create(ctx, 5_000L, 0, peerId)
        val peerTip = SignedHlcTip.create(
            ctx, ownerId, peerId, peerHlc,
            stubEd25519Signature(peerKey, hlcTipCanonicalMessage(ownerId, peerId, peerHlc))
        )
        tipStore.record(ownerId, peerTip)

        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                hlcTipStore = tipStore
            )
        }

        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt()}")
            parameter("since", 0L)
            setBody(serializer.serialize(signedTip(ctx, physicalTime = 1_000L)))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val parsed = parser.parse<ChangesResponse>(response.bodyAsBytes())
        val tips = parsed.peerTips.toList()
        assertEquals(1, tips.size)
        assertEquals(peerId, tips.first().deviceId.get())
        assertEquals(5_000L, tips.first().hlc.physicalTime.get())
    }

    @Test
    fun production_invalidTipSignature_returns401() = testApplication {
        val knownDevices =
            KnownDevicesStore().apply { register(ownerId, deviceId, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer, _) = makeContext()
        // Sign the tip with a different key — JWT auth still passes, tip signature does not.
        val forged = signedTip(ctx, signingKey = "wrong-key".encodeToByteArray())

        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt()}")
            parameter("since", 0L)
            setBody(serializer.serialize(forged))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun production_tipDeviceIdMismatch_returns403() = testApplication {
        val knownDevices =
            KnownDevicesStore().apply { register(ownerId, deviceId, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer, _) = makeContext()
        // JWT identifies `deviceId`; tip claims to be from a different device.
        val mismatched = signedTip(ctx, deviceId = "different-device")

        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt()}")
            parameter("since", 0L)
            setBody(serializer.serialize(mismatched))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun production_tipOwnerIdMismatch_returns403() = testApplication {
        val knownDevices =
            KnownDevicesStore().apply { register(ownerId, deviceId, devicePublicKey) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer, _) = makeContext()
        val mismatched = signedTip(ctx, ownerId = "mallory")

        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt()}")
            parameter("since", 0L)
            setBody(serializer.serialize(mismatched))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun devMode_xDevOwnerSkipsTipSignatureVerification() = testApplication {
        // Dev-mode test fixtures have no real device key; the bypass accepts any tip
        // signature so legacy `X-Dev-Owner` integration tests keep working.
        val tipStore = HlcTipStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                hlcTipStore = tipStore
            )
        }

        val (ctx, serializer, _) = makeContext()
        val unsignedTip = SignedHlcTip.create(
            ctx, "dev-owner", "dev-device",
            HybridLogicalClock.create(ctx, 100L, 0, "dev-device"),
            byteArrayOf()  // empty signature — accepted in dev mode
        )

        val response = client.post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("X-Dev-Owner", "dev-owner")
            header("X-Dev-Device", "dev-device")
            parameter("since", 0L)
            setBody(serializer.serialize(unsignedTip))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(tipStore.allTips("dev-owner").isNotEmpty())
    }
}
