package org.tenshu.sync.relay.routing

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.RELAY_PROTOCOL_TOMBSTONE_IS_DATA_VERSION
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.parser.BinarySerializer
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
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.TOMBSTONE_REVOKES_PROTOCOL
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.cascadedTombstone
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sync-entity-identity.md` §5b R2b — **data endpoints never mutate access control.** From protocol 2 a
 * client revokes through `POST /sync/device-access/revoke`, and its `Device` tombstone is data: stored,
 * served, counted for the realm epoch, but it revokes nothing. That is what makes the T-GR-08 attack (a
 * device evicting another by tombstone) structurally impossible for a current client, whoever pushes.
 *
 * ⚠ A push at protocol 1 still revokes ([TOMBSTONE_REVOKES_PROTOCOL]; `RevokedDeviceRejectionTest`),
 * so an old client's removal is never silently ignored until R5 refuses it with `426`.
 */
class DeviceTombstoneIsDataTest {

    private val ownerId = "realm-data-tombstone"
    private val pusher = "pusher-device"
    private val pusherKey = "pusher-key".encodeToByteArray()
    private val victim = "victim-device"
    private val victimKey = "victim-key".encodeToByteArray()

    private fun context(): Pair<TContext, BinarySerializer> {
        val ctx = object : TContext {
            override var components: Map<String, TComponent> = emptyMap()
        }
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(SchemaProvider.COMPONENT_ID to registry, DataFactory.COMPONENT_ID to registry)
        return ctx to BinarySerializer(registry)
    }

    private fun jwt(): String {
        val now = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, pusher, now - 5, now + 60)) { stubEd25519Signature(pusherKey, it) }
    }

    private fun deviceTombstone(ctx: TContext, named: String) = DeleteBlock.create(
        context = ctx,
        blockId = "tombstone-$named",
        ownerId = ownerId,
        hlc = HybridLogicalClock.create(ctx, 9_000L, 0, pusher),
        targetEntityIdHash = (DataId.StringDataId.create(ctx, Device.ID, named) as DataId).getDataIdHash().toString(),
        targetEntityType = Device.ID,
        revokedDeviceId = named,
        authTag = ByteArray(32) { 1 },
    )

    private fun runCase(version: Int, named: String = victim, check: (KnownDevicesStore, SyncMailbox) -> Unit) =
        testApplication {
            val devices = KnownDevicesStore().apply {
                register(ownerId, pusher, pusherKey)
                register(ownerId, victim, victimKey)
            }
            val mailbox = SyncMailbox()
            application {
                testModule(
                    config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                    knownDevices = devices,
                    syncMailbox = mailbox,
                )
            }
            val (ctx, serializer) = context()
            val response = client.post("/sync/push-tombstone") {
                contentType(ContentType.Application.OctetStream)
                header("Authorization", "Bearer ${jwt()}")
                setBody(serializer.serialize(cascadedTombstone(ctx, deviceTombstone(ctx, named), relayProtocolVersion = version)))
            }
            assertEquals(HttpStatusCode.OK, response.status, "the tombstone itself is accepted either way")
            check(devices, mailbox)
        }

    @Test
    fun theCurrentProtocol_isTheOneAtWhichTombstonesAreData() {
        assertTrue(RELAY_PROTOCOL_VERSION >= RELAY_PROTOCOL_TOMBSTONE_IS_DATA_VERSION)
        assertTrue(TOMBSTONE_REVOKES_PROTOCOL < RELAY_PROTOCOL_TOMBSTONE_IS_DATA_VERSION)
    }

    /** ⚠⚠ T-GR-08, closed for a current client: a tombstone naming another device evicts no one. */
    @Test
    fun aV2DeviceTombstone_namingAnotherDevice_revokesNothing() = runCase(RELAY_PROTOCOL_VERSION) { devices, _ ->
        assertNotNull(devices.getPublicKey(ownerId, victim), "the named device keeps its access")
        assertFalse(devices.wasRemoved(ownerId, victim), "and is not in the removal ledger")
    }

    @Test
    fun aV2DeviceTombstone_namingItsPusher_revokesNothingEither() = runCase(RELAY_PROTOCOL_VERSION, named = pusher) { devices, _ ->
        assertNotNull(devices.getPublicKey(ownerId, pusher), "self-removal is the route's self form, not a tombstone")
    }

    /** The realm epoch is unchanged in definition (§5b precondition 1 is R4): it still counts the tombstone. */
    @Test
    fun aV2DeviceTombstone_isStillStored_andStillCountedForTheEpoch() = runCase(RELAY_PROTOCOL_VERSION) { _, mailbox ->
        assertEquals(1L, mailbox.deviceTombstoneCount(ownerId))
    }

    @Test
    fun aV1DeviceTombstone_stillRevokes_untilTheMinimumIsRaised() = runCase(TOMBSTONE_REVOKES_PROTOCOL) { devices, mailbox ->
        assertNull(devices.getPublicKey(ownerId, victim), "a pre-R2b client's removal is never silently ignored")
        assertTrue(devices.wasRemoved(ownerId, victim))
        assertEquals(1L, mailbox.deviceTombstoneCount(ownerId))
    }
}
