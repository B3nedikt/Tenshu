package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * End-to-end coverage for T-K-02 push rejection per `docs/sync/threat-model.md` §8.5
 * + `docs/adr/0006-sync-tombstones.md`. Two facets:
 *
 *  1. Pushing a Device-targeted `DeleteBlock` carrying a `revokedDeviceId` causes the
 *     relay to revoke that device's `KnownDevicesStore` entry.
 *  2. After revocation, `/sync/push` from the revoked device's JWT returns 401 (the
 *     JWT signature no longer verifies because the public key is gone).
 *
 *  Companion to `KnownDevicesStoreTest.revoke` (unit-tests the store primitive) and
 *  `RevokeDeviceImplementationTest` (client-side emit). This is the wire-layer
 *  enforcement test — without it, T-K-02's "relay refuses pushes from revoked
 *  devices" defense is documented but not actually enforced.
 */
class RevokedDeviceRejectionTest {

    private val ownerId = "alice-revoked"
    private val devicePrivateKey = "device-revoked-key".encodeToByteArray()
    private val devicePublicKey = devicePrivateKey  // stub: pub == priv
    private val deviceId = "device-being-revoked"

    private fun makeContext(): Pair<TContext, BinarySerializer> {
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
        return ctx to BinarySerializer(registry)
    }

    private fun jwt(deviceId: String, deviceKey: ByteArray): String {
        val now = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, deviceId, now - 5, now + 60)) { msg ->
            stubEd25519Signature(deviceKey, msg)
        }
    }

    private fun makeDeviceTombstone(ctx: TContext, deviceIdToRevoke: String): DeleteBlock {
        val deviceDataId = DataId.StringDataId.create(ctx, Device.ID, deviceIdToRevoke)
        return DeleteBlock.create(
            context = ctx,
            blockId = "tombstone-revoke-$deviceIdToRevoke",
            ownerId = ownerId,
            hlc = HybridLogicalClock.create(ctx, 1_000L, 0, "admin-device"),
            targetEntityIdHash = (deviceDataId as DataId).getDataIdHash().toString(),
            targetEntityType = Device.ID,
            revokedDeviceId = deviceIdToRevoke,
            // Protocol 5: `/sync/push-tombstone` requires a 32-byte tag (format only).
            authTag = ByteArray(32) { 1 },
        )
    }

    private fun makeBlock(ctx: TContext): SyncBlock = SyncBlock.create(
        context = ctx,
        blockId = "block-after-revoke",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, 2_000L, 0, deviceId),
        encryptedPayload = "junk".encodeToByteArray()
    )

    @Test
    fun deviceTombstone_withRevokedDeviceId_revokesKnownDevicesEntry() = testApplication {
        // Set up: two devices in the same realm. Admin will push a tombstone targeting
        // `deviceId`. The admin's own JWT is what authenticates the push.
        val knownDevices = KnownDevicesStore().apply {
            register(ownerId, "admin-device", "admin-key".encodeToByteArray())
            register(ownerId, deviceId, devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer) = makeContext()
        val pushResponse = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header(
                "Authorization",
                "Bearer ${jwt("admin-device", "admin-key".encodeToByteArray())}"
            )
            setBody(serializer.serialize(org.tenshu.sync.relay.cascadedTombstone(ctx, makeDeviceTombstone(ctx, deviceId))))
        }
        assertEquals(HttpStatusCode.OK, pushResponse.status)
        pushResponse.bodyAsBytes()

        // The revoked device's entry is gone — getPublicKey returns null.
        assertNull(knownDevices.getPublicKey(ownerId, deviceId))
        // Admin's entry is unaffected.
        kotlin.test.assertNotNull(knownDevices.getPublicKey(ownerId, "admin-device"))
    }

    @Test
    fun afterRevocation_pushFromRevokedDeviceReturns401() = testApplication {
        // Simulate the steady state after a revocation: the tombstone has already
        // arrived, KnownDevicesStore no longer holds the device's key. The next push
        // from the revoked device's JWT must fail.
        val knownDevices = KnownDevicesStore().apply {
            register(ownerId, deviceId, devicePublicKey)
        }
        // The revocation tombstone happened before this test moment.
        knownDevices.revoke(ownerId, deviceId)
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer) = makeContext()
        val pushResponse = client.post("/sync/push") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt(deviceId, devicePrivateKey)}")
            setBody(serializer.serialize(org.tenshu.sync.relay.edgedPush(ctx, makeBlock(ctx))))
        }
        // 401 (Unauthorized) because the JWT verifier can't find a public key for the
        // claimed (iss, sub). Distinct from 403 (Forbidden) — we don't tell the
        // attacker whether (a) the device never existed, (b) the realm is wrong, or
        // (c) the device was revoked. Same response shape as "unknown device" by
        // design (per JwtAuthenticator KDoc).
        assertEquals(HttpStatusCode.Unauthorized, pushResponse.status)
    }

    @Test
    fun nonDeviceTombstone_doesNotRevokeAnyDevice() = testApplication {
        // A tombstone for an entity OTHER than Device.ID must NOT touch
        // KnownDevicesStore even if `revokedDeviceId` is somehow populated. The relay's
        // type-check is the gate (`targetEntityType == Device.ID`).
        val knownDevices = KnownDevicesStore().apply {
            register(ownerId, "admin-device", "admin-key".encodeToByteArray())
            register(ownerId, deviceId, devicePublicKey)
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices
            )
        }

        val (ctx, serializer) = makeContext()
        // A file-deletion tombstone with a stray (and ignored) revokedDeviceId field.
        val fileTombstone = DeleteBlock.create(
            context = ctx,
            blockId = "tombstone-file",
            ownerId = ownerId,
            hlc = HybridLogicalClock.create(ctx, 1_000L, 0, "admin-device"),
            targetEntityIdHash = "some-file-hash",
            targetEntityType = "tenshu/file",
            revokedDeviceId = deviceId,  // would be a server-side bug to honor this
            authTag = ByteArray(32) { 1 },
        )

        val pushResponse = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header(
                "Authorization",
                "Bearer ${jwt("admin-device", "admin-key".encodeToByteArray())}"
            )
            setBody(serializer.serialize(org.tenshu.sync.relay.cascadedTombstone(ctx, fileTombstone)))
        }
        assertEquals(HttpStatusCode.OK, pushResponse.status)
        pushResponse.bodyAsBytes()

        // Both devices remain registered — the type-gate prevented spurious revocation.
        kotlin.test.assertNotNull(knownDevices.getPublicKey(ownerId, deviceId))
        kotlin.test.assertNotNull(knownDevices.getPublicKey(ownerId, "admin-device"))
    }

    /**
     * Both pairing flows register the new device when it FETCHES its response, so its admission's
     * HLC lives only in the queue until then. A `Device` tombstone authored BEFORE that admission but
     * delivered inside the window must still be stale against it, or it would write the removal
     * ledger and the delivery would refuse the re-admitted device for good.
     */
    private fun admittedAt(physical: Long) = org.tenshu.core.common.util.HlcStamp(physical, 0, "admin-device")

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pushDeviceTombstone() {
        val (ctx, serializer) = makeContext()
        val response = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt("admin-device", "admin-key".encodeToByteArray())}")
            setBody(serializer.serialize(org.tenshu.sync.relay.cascadedTombstone(ctx, makeDeviceTombstone(ctx, deviceId))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        response.bodyAsBytes()
    }

    private fun queuedOffer(admitted: org.tenshu.core.common.util.HlcStamp) =
        org.tenshu.sync.relay.mailbox.PairingOfferStore().also {
            it.post(
                "c".repeat(64),
                org.tenshu.sync.relay.mailbox.PairingOfferRegistry.OfferResponse(
                    ownerId = ownerId,
                    status = "approved",
                    publicKeyA = ByteArray(32),
                    payload = byteArrayOf(1),
                    registration = org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(
                        ownerId, deviceId, devicePublicKey, admitted,
                    ),
                ),
                expiresAtMs = System.currentTimeMillis() + RelayConfig.PAIRING_OFFER_RESPONSE_TTL_MS,
            )
        }

    @Test
    fun aTombstoneOlderThanAQueuedOfferAdmission_isStale_andTheOffererStillJoins() = testApplication {
        val knownDevices = KnownDevicesStore().apply { register(ownerId, "admin-device", "admin-key".encodeToByteArray()) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                pairingOfferStore = queuedOffer(admittedAt(5_000L)), // tombstone is at 1_000
            )
        }
        pushDeviceTombstone()
        kotlin.test.assertFalse(knownDevices.wasRemoved(ownerId, deviceId), "a stale tombstone writes no removal")

        val poll = client.get("/sync/pairing/offer-response?hash=${"c".repeat(64)}")
        assertEquals(HttpStatusCode.OK, poll.status)
        kotlin.test.assertNotNull(knownDevices.getPublicKey(ownerId, deviceId), "registered on delivery")
    }

    @Test
    fun aTombstoneNewerThanAQueuedOfferAdmission_applies_andDeliveryIsRefused() = testApplication {
        val knownDevices = KnownDevicesStore().apply { register(ownerId, "admin-device", "admin-key".encodeToByteArray()) }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                pairingOfferStore = queuedOffer(admittedAt(500L)), // tombstone at 1_000 is newer
            )
        }
        pushDeviceTombstone()
        kotlin.test.assertTrue(knownDevices.wasRemoved(ownerId, deviceId))
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/sync/pairing/offer-response?hash=${"c".repeat(64)}").status,
        )
        assertNull(knownDevices.getPublicKey(ownerId, deviceId))
    }

    @Test
    fun aTombstoneOlderThanAQueuedJoinAdmission_isStale_andTheJoinerStillJoins() = testApplication {
        val knownDevices = KnownDevicesStore().apply { register(ownerId, "admin-device", "admin-key".encodeToByteArray()) }
        val mailbox = org.tenshu.sync.relay.SyncMailbox()
        val hash = "d".repeat(64)
        mailbox.addPairingResponse(
            hash,
            byteArrayOf(7),
            org.tenshu.sync.relay.PairingResponseInbox.PendingRegistration(ownerId, deviceId, devicePublicKey, admittedAt(5_000L)),
        )
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                syncMailbox = mailbox,
            )
        }
        pushDeviceTombstone()
        kotlin.test.assertFalse(knownDevices.wasRemoved(ownerId, deviceId))

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$hash"}""")
        }
        assertEquals(HttpStatusCode.OK, peek.status)
        kotlin.test.assertNotNull(knownDevices.getPublicKey(ownerId, deviceId))
    }
}
