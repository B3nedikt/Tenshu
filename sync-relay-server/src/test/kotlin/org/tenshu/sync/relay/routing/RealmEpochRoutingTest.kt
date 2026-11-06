package org.tenshu.sync.relay.routing

import org.tenshu.sync.relay.testModule

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.SyncMailbox
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for `GET /sync/realm-epoch` per `docs/proposals/sync-relay-api.md` §3.6.
 * Closes the recovery-race carve-out from `docs/adr/0007-sync-device-management.md`: a recovering /
 * pairing-claim device fetches the realm's authoritative epoch out-of-band before
 * stamping its self-pushed Device entity.
 *
 * ⚠⚠ Device-JWT authenticated since F8 (`relay-metadata-minimisation.md`): the realm is the JWT's
 * owner, and a stranger holding an ownerId (every pairing QR carries one) learns nothing.
 *
 * Each test constructs its own [SyncMailbox] (one-per-relay shape, mirroring
 * `Application.module(syncMailbox = ...)`) and threads it through both
 * [testModule] and the direct push helpers below.
 */
class RealmEpochRoutingTest {

    private val mailbox = SyncMailbox()

    private fun makeTContext(): TContext {
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
        return ctx
    }

    private fun seedRealm(realmStore: RealmStore, ownerId: String) {
        realmStore.createIfAbsent(
            RealmStore.RealmRecord(
                ownerId = ownerId,
                masterPublicKey = byteArrayOf(0x42),
                createdAt = 0L
            )
        )
    }

    private val deviceKey = "epoch-device-key".encodeToByteArray() // stub: pub == priv

    /** Register a device for [ownerId] and return a JWT it signed — what a just-admitted device holds. */
    private fun admittedJwt(knownDevices: KnownDevicesStore, ownerId: String): String {
        knownDevices.register(ownerId, "epoch-device", deviceKey)
        val nowSec = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(ownerId, "epoch-device", nowSec - 5, nowSec + 60)) { msg ->
            stubEd25519Signature(deviceKey, msg)
        }
    }

    private fun pushDeviceTombstone(ctx: TContext, ownerId: String, deviceId: String, time: Long) {
        mailbox.pushTombstone(
            ownerId,
            DeleteBlock.create(
                context = ctx,
                blockId = "tombstone-$ownerId-$time",
                ownerId = ownerId,
                hlc = HybridLogicalClock.create(ctx, time, 0, "device-revoker"),
                targetEntityIdHash = "device-hash-$deviceId",
                targetEntityType = org.tenshu.identity.Device.ID,
                revokedDeviceId = deviceId
            )
        )
    }

    @Test
    fun ownerWithNoTombstones_returnsZero() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                syncMailbox = mailbox
            )
        }
        seedRealm(realmStore, "owner-epoch-zero")

        val jwt = admittedJwt(knownDevices, "owner-epoch-zero")
        val response = client.get("/sync/realm-epoch") { header("Authorization", "Bearer $jwt") }
        assertEquals(HttpStatusCode.OK, response.status)
        // Body is JSON `{"epoch":"<number>"}`. We don't pull a JSON dep into this test
        // (matching the recovery-routing tests' style of regex/contains checks).
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"epoch\":\"0\""),
            "Expected epoch 0 for an owner with no tombstones; got $body"
        )
    }

    @Test
    fun ownerWithThreeDeviceTombstones_returnsThree() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                syncMailbox = mailbox
            )
        }
        seedRealm(realmStore, "owner-epoch-three")

        val ctx = makeTContext()
        pushDeviceTombstone(ctx, "owner-epoch-three", "victim-1", time = 100L)
        pushDeviceTombstone(ctx, "owner-epoch-three", "victim-2", time = 200L)
        pushDeviceTombstone(ctx, "owner-epoch-three", "victim-3", time = 300L)

        val jwt = admittedJwt(knownDevices, "owner-epoch-three")
        val response = client.get("/sync/realm-epoch") { header("Authorization", "Bearer $jwt") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"epoch\":\"3\""),
            "Expected epoch 3 after three Device tombstones; got $body"
        )
    }

    @Test
    fun nonDeviceTombstones_doNotCount() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                syncMailbox = mailbox
            )
        }
        seedRealm(realmStore, "owner-epoch-mixed")

        val ctx = makeTContext()
        // One Device tombstone — counts.
        pushDeviceTombstone(ctx, "owner-epoch-mixed", "victim-1", time = 100L)
        // One File tombstone — must not count.
        mailbox.pushTombstone(
            "owner-epoch-mixed",
            DeleteBlock.create(
                context = ctx,
                blockId = "file-tombstone",
                ownerId = "owner-epoch-mixed",
                hlc = HybridLogicalClock.create(ctx, 200L, 0, "device-revoker"),
                targetEntityIdHash = "file-hash",
                targetEntityType = "tenshu/file"
            )
        )

        val jwt = admittedJwt(knownDevices, "owner-epoch-mixed")
        val response = client.get("/sync/realm-epoch") { header("Authorization", "Bearer $jwt") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(
            body.contains("\"epoch\":\"1\""),
            "Expected epoch 1 (only Device tombstones count); got $body"
        )
    }

    /**
     * ⚠⚠ F8: a stranger with an ownerId learns nothing — not the count, and not whether the realm
     * exists. Before F8 this answered `?ownerId=` unauthenticated: 404 for no realm, the device-removal
     * count otherwise.
     */
    @Test
    fun stranger_withAnOwnerId_getsTheSame401_forARealmAndForNone() = testApplication {
        val realmStore = RealmStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = KnownDevicesStore(),
                realmStore = realmStore,
                syncMailbox = mailbox
            )
        }
        seedRealm(realmStore, "owner-probed")
        pushDeviceTombstone(makeTContext(), "owner-probed", "victim-1", time = 100L)

        val existing = client.get("/sync/realm-epoch?ownerId=owner-probed")
        val absent = client.get("/sync/realm-epoch?ownerId=never-seen")
        assertEquals(HttpStatusCode.Unauthorized, existing.status)
        assertEquals(existing.status, absent.status)
        assertEquals(existing.bodyAsText(), absent.bodyAsText())
    }

    /** The realm comes from the JWT: a query parameter naming another realm is ignored. */
    @Test
    fun theRealmIsTheJwtsOwner_notAQueryParameter() = testApplication {
        val realmStore = RealmStore()
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                syncMailbox = mailbox
            )
        }
        seedRealm(realmStore, "owner-mine")
        seedRealm(realmStore, "owner-theirs")
        val ctx = makeTContext()
        pushDeviceTombstone(ctx, "owner-theirs", "victim-1", time = 100L)
        pushDeviceTombstone(ctx, "owner-theirs", "victim-2", time = 200L)

        val jwt = admittedJwt(knownDevices, "owner-mine")
        val response = client.get("/sync/realm-epoch?ownerId=owner-theirs") { header("Authorization", "Bearer $jwt") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"epoch\":\"0\""), "my realm's count, not theirs")
    }
}
