package org.tenshu.sync.relay.routing

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.SyncLogResponse
import org.tenshu.sync.common.hlcTipCanonicalMessage
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.DeviceRevocation
import org.tenshu.sync.relay.auth.StubEd25519Backend
import org.tenshu.sync.relay.cascadedTombstone
import org.tenshu.sync.relay.mailbox.HlcTipRegistry
import org.tenshu.sync.relay.module
import org.tenshu.sync.relay.storage.RelayBinaryCodec
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteDeviceRegistry
import org.tenshu.sync.relay.storage.SqliteHlcTipStore
import org.tenshu.sync.relay.storage.SqliteSyncMailbox
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F11 / Phase 4 (b) end to end, over the **SQLite** stores production runs, asserting on the tables:
 * an applied `Device` tombstone deletes the revoked device's `hlc_tip` row, peers stop being served
 * it, and the revoked device's own in-flight request cannot put it back.
 */
class RevokedDeviceSyncStatePruningTest {

    private val owner = "owner-f11-route"
    private val admin = "admin-device"
    private val adminKey = "admin-key".encodeToByteArray()
    private val victim = "victim-device"
    private val victimKey = "victim-key".encodeToByteArray()

    private lateinit var dbFile: File
    private lateinit var db: RelayDatabase
    private lateinit var devices: SqliteDeviceRegistry
    private lateinit var tips: SqliteHlcTipStore

    private val ctx = object : TContext {
        override var components: Map<String, TComponent> = emptyMap()
    }
    private val registry = createDefaultSchemaRegistry(
        tContextProvider = { ctx },
        extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
    ).also { ctx.components = mapOf(SchemaProvider.COMPONENT_ID to it, DataFactory.COMPONENT_ID to it) }
    private val serializer = BinarySerializer(registry)
    private val parser = BinaryParser(registry, registry)

    @BeforeTest
    fun setup() {
        dbFile = File.createTempFile("relay-f11-route", ".db")
        db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        devices = SqliteDeviceRegistry(db).apply {
            register(owner, admin, adminKey)
            register(owner, victim, victimKey)
        }
        tips = SqliteHlcTipStore(db, RelayBinaryCodec())
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }

    private fun rows(table: String, deviceId: String): Int = db.withConnection { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM $table WHERE owner_id = ? AND device_id = ?").use { ps ->
            ps.setString(1, owner)
            ps.setString(2, deviceId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun jwt(deviceId: String, key: ByteArray): String {
        val now = System.currentTimeMillis() / 1000
        return encodeJwt(JwtClaims(owner, deviceId, now - 5, now + 60)) { stubEd25519Signature(key, it) }
    }

    private fun tip(deviceId: String, key: ByteArray, physical: Long): SignedHlcTip {
        val hlc = HybridLogicalClock.create(ctx, physical, 0, deviceId)
        return SignedHlcTip.create(ctx, owner, deviceId, hlc, stubEd25519Signature(key, hlcTipCanonicalMessage(owner, deviceId, hlc)))
    }

    private suspend fun HttpClient.pollLog(deviceId: String, key: ByteArray, since: Long, physical: Long): HttpResponse =
        post("/sync/log") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt(deviceId, key)}")
            parameter("since", since)
            setBody(serializer.serialize(tip(deviceId, key, physical)))
        }

    private suspend fun HttpClient.pollChanges(deviceId: String, key: ByteArray, physical: Long): HttpResponse =
        post("/sync/changes") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt(deviceId, key)}")
            parameter("since", 0L)
            setBody(serializer.serialize(tip(deviceId, key, physical)))
        }

    private fun deviceTombstone(revoked: String): DeleteBlock = DeleteBlock.create(
        context = ctx,
        blockId = "retire-$revoked",
        ownerId = owner,
        hlc = HybridLogicalClock.create(ctx, 3_000L, 0, admin),
        targetEntityIdHash = (DataId.StringDataId.create(ctx, Device.ID, revoked) as DataId).getDataIdHash().toString(),
        targetEntityType = Device.ID,
        revokedDeviceId = revoked,
        authTag = ByteArray(32) { 1 },
    )

    private fun io.ktor.server.application.Application.relay(
        tipStore: HlcTipRegistry = tips,
    ) = module(
        config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
        knownDevices = devices,
        hlcTipStore = tipStore,
        syncMailbox = SqliteSyncMailbox(db, RelayBinaryCodec()),
        relayDatabase = db,
        ed25519 = StubEd25519Backend(),
    )

    @Test
    fun anAppliedDeviceTombstone_deletesTheRows_andPeersStopBeingServedThem() = testApplication {
        application { relay() }

        assertEquals(HttpStatusCode.OK, client.pollLog(victim, victimKey, since = 5, physical = 1_000).status)
        val before = parser.parse<SyncLogResponse>(client.pollLog(admin, adminKey, since = 6, physical = 2_000).bodyAsBytes())
        assertEquals(listOf(victim), before.peerTips.toList().map { it.deviceId.get() }, "precondition")
        assertEquals(1, rows("hlc_tip", victim))

        val push = client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer ${jwt(admin, adminKey)}")
            setBody(serializer.serialize(cascadedTombstone(ctx, deviceTombstone(victim))))
        }
        assertEquals(HttpStatusCode.OK, push.status)

        assertEquals(0, rows("hlc_tip", victim), "the revoked device's tip must be deleted")
        assertEquals(1, rows("hlc_tip", admin), "the revoker's own tip is untouched")

        val after = parser.parse<SyncLogResponse>(client.pollLog(admin, adminKey, since = 6, physical = 2_100).bodyAsBytes())
        assertEquals(emptyList(), after.peerTips.toList(), "a revoked device is no longer served as a peer")

        // The revoked device's next poll is refused before it can write anything.
        assertEquals(HttpStatusCode.Unauthorized, client.pollLog(victim, victimKey, since = 9, physical = 1_100).status)
        assertEquals(0, rows("hlc_tip", victim))
    }

    /** Fires the revocation between the route's auth check and its write — the race the guard closes. */
    private fun revokesFirst(): DeviceRevocation = DeviceRevocation(devices, tips) { b -> db.inTransaction { b() } }

    @Test
    fun aRevocationLandingMidRequest_cannotBeUndoneByTheTipWrite() = testApplication {
        val trigger = revokesFirst()
        val racingTips = object : HlcTipRegistry by tips {
            override fun record(ownerId: String, tip: SignedHlcTip) {
                if (tip.deviceId.get() == victim) trigger.applyDeviceTombstone(owner, victim, null)
                tips.record(ownerId, tip)
            }
        }
        application { relay(tipStore = racingTips) }

        assertEquals(HttpStatusCode.Unauthorized, client.pollChanges(victim, victimKey, physical = 1_000).status)
        assertEquals(0, rows("hlc_tip", victim), "the late tip write must not survive the revocation")
    }
}
