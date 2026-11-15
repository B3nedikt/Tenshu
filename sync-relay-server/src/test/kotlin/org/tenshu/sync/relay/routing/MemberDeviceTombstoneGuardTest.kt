package org.tenshu.sync.relay.routing

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.Base64
import org.tenshu.core.common.RELAY_PROTOCOL_VERSION
import org.tenshu.core.common.module.coreCommonModule
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.createDefaultSchemaRegistry
import org.tenshu.core.common.security.JwtClaims
import org.tenshu.core.common.security.encodeJwt
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.core.DataFactory
import org.tenshu.core.core.SchemaProvider
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.MemberDeviceBinding
import org.tenshu.sync.common.MembershipCapability
import org.tenshu.sync.common.deviceIdForPublicKey
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.cascadedTombstone
import org.tenshu.sync.relay.mailbox.MembershipRegistry
import org.tenshu.sync.relay.mailbox.MembershipStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.storage.RelayDatabase
import org.tenshu.sync.relay.storage.SqliteMembershipStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shared-realm **member** device may revoke only itself through a `Device` tombstone
 * (threat model T-GR-08). Owner and member devices share one registry under the realm's ownerId,
 * the relay cannot verify a tombstone's tag, and the HLC is the sender's choice — so before this
 * guard any member could evict the owner's and every other member's devices from the relay.
 *
 * The realm is built through the real routes: `POST /sync/realm` for the owner's device and
 * `POST /sync/realm-member/admit` for the member's, as in [MembershipGrantRoutingTest].
 */
class MemberDeviceTombstoneGuardTest {

    private val masterKey = "shared-realm-master-key".encodeToByteArray().copyOf(32)
    private val ownerDeviceKey = "owner-device-pubkey".encodeToByteArray().copyOf(32)
    private val ownerDeviceId = deviceIdForPublicKey(ownerDeviceKey)
    private val realmId = ownerIdForMasterPublicKey(masterKey)

    private val memberAuthority = "bob-member-authority".encodeToByteArray().copyOf(32)
    private val memberDeviceKey = "bob-realm-device-pubkey".encodeToByteArray().copyOf(32)
    private val memberDeviceId = deviceIdForPublicKey(memberDeviceKey)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private val ctx = object : TContext {
        override var components: Map<String, TComponent> = emptyMap()
    }
    private val serializer: BinarySerializer = run {
        val registry = createDefaultSchemaRegistry(
            tContextProvider = { ctx },
            extraConfigs = listOf(coreCommonModule, syncServiceCommonModule, identityServiceCommonModule),
        )
        ctx.components = mapOf(SchemaProvider.COMPONENT_ID to registry, DataFactory.COMPONENT_ID to registry)
        BinarySerializer(registry)
    }

    private suspend fun ApplicationTestBuilder.bootstrapAndAdmitMember() {
        val bootSig = stubEd25519Signature(
            masterKey,
            MasterDelegationVerifier.realmBootstrapMessage(realmId, masterKey, ownerDeviceKey, ownerDeviceId),
        )
        val boot = client.post("/sync/realm") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"relayProtocolVersion":"$RELAY_PROTOCOL_VERSION","ownerId":"$realmId",""" +
                    """"masterPublicKey":"${b64(masterKey)}","firstDevicePublicKey":"${b64(ownerDeviceKey)}",""" +
                    """"firstDeviceId":"$ownerDeviceId","masterSelfSignature":"${b64(bootSig)}"}"""
            )
        }
        assertEquals(HttpStatusCode.Created, boot.status, boot.bodyAsText())

        val capSig = stubEd25519Signature(
            masterKey,
            MembershipCapability.message(realmId, memberAuthority, 0L, issuedAt = 1_000L),
        )
        val admit = admitUnderMemberAuthority(memberDeviceId, memberDeviceKey, boundAt = 2_000L)
        assertEquals(HttpStatusCode.OK, admit, "the member's own device is admitted")
    }

    /** `POST /sync/realm-member/admit` binding [deviceId] under [memberAuthority]'s capability. */
    private suspend fun ApplicationTestBuilder.admitUnderMemberAuthority(
        deviceId: String,
        deviceKey: ByteArray,
        boundAt: Long,
    ): HttpStatusCode {
        val capSig = stubEd25519Signature(
            masterKey,
            MembershipCapability.message(realmId, memberAuthority, 0L, issuedAt = 1_000L),
        )
        val bindSig = stubEd25519Signature(
            memberAuthority,
            MemberDeviceBinding.message(realmId, deviceId, deviceKey, boundAt = boundAt),
        )
        return client.post("/sync/realm-member/admit") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"realmId":"$realmId","capability":{"memberAuthorityPublicKey":"${b64(memberAuthority)}",""" +
                    """"epoch":0,"issuedAt":1000,"signature":"${b64(capSig)}"},"deviceBinding":{""" +
                    """"deviceId":"$deviceId","perRealmDevicePublicKey":"${b64(deviceKey)}",""" +
                    """"boundAt":$boundAt,"signature":"${b64(bindSig)}"}}"""
            )
        }.also { it.bodyAsText() }.status
    }

    @Test
    fun aMemberCannotBindAnOwnerDeviceIdUnderItsOwnAuthority() = testApplication {
        val knownDevices = KnownDevicesStore()
        val membershipStore = MembershipStore()
        mountRelay(knownDevices, membershipStore)
        bootstrapAndAdmitMember()

        assertEquals(
            HttpStatusCode.Conflict,
            admitUnderMemberAuthority(ownerDeviceId, ownerDeviceKey, boundAt = 3_000L),
        )
        assertFalse(membershipStore.isMemberDevice(realmId, ownerDeviceId), "the owner's device stays an owner device")
        // The owner's device keeps its power to revoke the member.
        assertEquals(HttpStatusCode.OK, pushDeviceTombstone(ownerDeviceId, ownerDeviceKey, memberDeviceId))
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId))
    }

    @Test
    fun aMemberDeviceMayBeReAdmitted() = testApplication {
        mountRelay(KnownDevicesStore())
        bootstrapAndAdmitMember()

        assertEquals(HttpStatusCode.OK, admitUnderMemberAuthority(memberDeviceId, memberDeviceKey, boundAt = 2_000L))
    }

    /** Push a `Device` tombstone naming [revokedDeviceId], as [pusherDeviceId], at a far-future HLC. */
    private suspend fun ApplicationTestBuilder.pushDeviceTombstone(
        pusherDeviceId: String,
        pusherKey: ByteArray,
        revokedDeviceId: String,
    ): HttpStatusCode {
        val block = DeleteBlock.create(
            context = ctx,
            blockId = "tombstone-$pusherDeviceId-$revokedDeviceId",
            ownerId = realmId,
            hlc = HybridLogicalClock.create(ctx, Long.MAX_VALUE / 2, 0, pusherDeviceId),
            targetEntityIdHash = "device-row-hash",
            targetEntityType = "tenshu/identity/device",
            revokedDeviceId = revokedDeviceId,
            authTag = ByteArray(32) { 1 },
        )
        val now = System.currentTimeMillis() / 1000
        val token = encodeJwt(JwtClaims(realmId, pusherDeviceId, now - 5, now + 60)) { m ->
            stubEd25519Signature(pusherKey, m)
        }
        return client.post("/sync/push-tombstone") {
            contentType(ContentType.Application.OctetStream)
            header("Authorization", "Bearer $token")
            setBody(serializer.serialize(cascadedTombstone(ctx, block)))
        }.also { it.bodyAsText() }.status
    }

    private fun ApplicationTestBuilder.mountRelay(
        knownDevices: KnownDevicesStore,
        membershipStore: MembershipStore = MembershipStore(),
    ) = application {
        testModule(
            config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
            knownDevices = knownDevices,
            realmStore = RealmStore(),
            membershipStore = membershipStore,
        )
    }

    @Test
    fun aMemberDeviceCannotEvictTheOwnersDevice() = testApplication {
        val knownDevices = KnownDevicesStore()
        mountRelay(knownDevices)
        bootstrapAndAdmitMember()

        // Accepted and stored (an honest client never sends this; refusing would change nothing
        // for peers, which never pull a shared realm's tombstones) — but it revokes nothing.
        assertEquals(HttpStatusCode.OK, pushDeviceTombstone(memberDeviceId, memberDeviceKey, ownerDeviceId))
        assertNotNull(knownDevices.getPublicKey(realmId, ownerDeviceId), "the owner's device stays registered")
        assertFalse(knownDevices.wasRemoved(realmId, ownerDeviceId), "and is not written to the removal ledger")
    }

    @Test
    fun aMemberDeviceCanStillRevokeItself() = testApplication {
        val knownDevices = KnownDevicesStore()
        mountRelay(knownDevices)
        bootstrapAndAdmitMember()

        assertEquals(HttpStatusCode.OK, pushDeviceTombstone(memberDeviceId, memberDeviceKey, memberDeviceId))
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId), "a member leaving retires its own device")
    }

    @Test
    fun theOwnersDeviceCanStillRevokeAMemberDevice() = testApplication {
        val knownDevices = KnownDevicesStore()
        mountRelay(knownDevices)
        bootstrapAndAdmitMember()

        assertEquals(HttpStatusCode.OK, pushDeviceTombstone(ownerDeviceId, ownerDeviceKey, memberDeviceId))
        assertNull(knownDevices.getPublicKey(realmId, memberDeviceId), "the guard applies to member pushers only")
    }

    /** Both backends answer [MembershipRegistry.isMemberDevice] the same way. */
    @Test
    fun isMemberDevice_agreesAcrossBothBackends() {
        val dbFile = File.createTempFile("relay-member-device-test", ".db")
        val db = RelayDatabase("jdbc:sqlite:${dbFile.absolutePath}")
        try {
            for (store in listOf<MembershipRegistry>(MembershipStore(), SqliteMembershipStore(db))) {
                store.recordAdmission("realm-a", memberAuthority, "member-device")
                assertTrue(store.isMemberDevice("realm-a", "member-device"))
                assertFalse(store.isMemberDevice("realm-a", "owner-device"))
                assertFalse(store.isMemberDevice("realm-b", "member-device"), "scoped to the realm")
                store.clearAdmissions("realm-a", memberAuthority)
                assertFalse(store.isMemberDevice("realm-a", "member-device"), "a revoked member is no longer one")
            }
        } finally {
            db.close()
            dbFile.delete()
            File("${dbFile.absolutePath}-wal").delete()
            File("${dbFile.absolutePath}-shm").delete()
        }
    }
}
