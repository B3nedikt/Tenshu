package org.tenshu.sync.relay.routing

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.common.RelayRefusal
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.DeviceAccessRevocation
import org.tenshu.sync.common.DeviceAccessRevocation.Authority
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /sync/device-access/revoke` (`sync-entity-identity.md` §5b precondition 0, phase R1; threat
 * model T-K-02, T-GR-08): the explicit device-access revocation route.
 *
 * Pins what the design asks of it:
 *  - **authority by signature, never by realm kind**: A1 may name any device; a device's own key may
 *    name only that device. So a shared-realm member cannot remove the owner's devices or another
 *    member's (T-GR-08), and neither can a non-owner personal device — one rule, one route;
 *  - **the same effect as an applied `Device` tombstone**: key gone, removal ledger written;
 *  - **the same staleness rule**: a removal authored before the admission on record changes nothing.
 *
 * Stub Ed25519: a key's public half equals its private half.
 */
class DeviceAccessRevokeRoutingTest {

    private val realmId = "realm-access"
    private val masterKey = "realm-master-key".encodeToByteArray()

    // Shaped like a shared realm: the owner's device and two members' devices, all registered under
    // the one realm id (as `/sync/realm-member/admit` does). The route neither knows nor asks.
    private val ownerDevice = "owner-device"
    private val memberA = "member-a-device"
    private val memberB = "member-b-device"
    private val keys = mapOf(
        ownerDevice to "owner-device-key".encodeToByteArray(),
        memberA to "member-a-key".encodeToByteArray(),
        memberB to "member-b-key".encodeToByteArray(),
    )

    private val authored = HlcStamp(5_000L, 0L, ownerDevice)

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    /**
     * A request naming [deviceId], claiming [authority], signed by [signer] over the message for
     * [signedAuthority] (default: the claimed one) and [signedAuthoredAt].
     */
    private fun body(
        deviceId: String,
        authority: Authority,
        signer: ByteArray,
        signedAuthority: Authority = authority,
        sentAuthoredAt: String? = authored.encode(),
        signedAuthoredAt: HlcStamp = authored,
        owner: String = realmId,
        sentAuthority: String = authority.wire,
    ): String {
        val message = DeviceAccessRevocation.message(signedAuthority, owner, deviceId, signedAuthoredAt)
        val fields = listOfNotNull(
            """"ownerId": "$owner"""",
            """"deviceId": "$deviceId"""",
            sentAuthoredAt?.let { """"authoredAt": "$it"""" },
            """"authority": "$sentAuthority"""",
            """"signature": "${b64(stubEd25519Signature(signer, message))}"""",
        )
        return fields.joinToString(prefix = "{", postfix = "}")
    }

    private fun byMaster(deviceId: String, signer: ByteArray = masterKey) = body(deviceId, Authority.MASTER, signer)

    private fun bySelf(deviceId: String, signer: ByteArray = keys.getValue(deviceId)) = body(deviceId, Authority.SELF, signer)

    private class Harness(val devices: KnownDevicesStore, val client: HttpClient)

    private fun runCase(
        seedRealm: Boolean = true,
        admittedAt: HlcStamp? = null,
        block: suspend Harness.() -> Unit,
    ) = testApplication {
        val realms = RealmStore().apply {
            if (seedRealm) createIfAbsent(RealmStore.RealmRecord(ownerId = realmId, masterPublicKey = masterKey, createdAt = 0L))
        }
        val devices = KnownDevicesStore().apply {
            keys.forEach { (id, key) -> register(realmId, id, key, admittedAt = admittedAt) }
        }
        application {
            testModule(
                config = RelayConfig(devMode = false, bindHost = "127.0.0.1", port = 8080),
                knownDevices = devices,
                realmStore = realms,
            )
        }
        Harness(devices, client).block()
    }

    private suspend fun Harness.revoke(body: String) =
        client.post("/sync/device-access/revoke") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Harness.assertRevoked(deviceId: String) {
        assertNull(devices.getPublicKey(realmId, deviceId), "$deviceId's key must be gone")
        assertTrue(devices.wasRemoved(realmId, deviceId), "$deviceId must be in the removal ledger")
    }

    private fun Harness.assertUntouched(vararg deviceIds: String) {
        for (deviceId in deviceIds.ifEmpty { keys.keys.toTypedArray() }) {
            assertNotNull(devices.getPublicKey(realmId, deviceId), "$deviceId must still be registered")
            assertFalse(devices.wasRemoved(realmId, deviceId), "$deviceId must not be in the removal ledger")
        }
    }

    private fun String.compact() = filterNot { it.isWhitespace() }

    // ── master (A1) form ────────────────────────────────────────────────────────────────────────

    @Test
    fun anA1SignedRequest_revokesTheNamedDevice_andWritesTheLedger() = runCase {
        val response = revoke(byMaster(memberA))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"applied":true}""", response.bodyAsText().compact())
        assertRevoked(memberA)
        assertUntouched(ownerDevice, memberB)
    }

    @Test
    fun aSignatureByAnythingButA1_is401_andRevokesNothing() = runCase {
        assertEquals(HttpStatusCode.Unauthorized, revoke(byMaster(memberA, signer = keys.getValue(ownerDevice))).status)
        assertUntouched()
    }

    /** The authored stamp is signed: making a stale request look fresh breaks the signature. */
    @Test
    fun aStampOtherThanTheSignedOne_is401() = runCase {
        val forged = body(memberA, Authority.MASTER, masterKey, sentAuthoredAt = HlcStamp(9_000L, 0L, ownerDevice).encode())
        assertEquals(HttpStatusCode.Unauthorized, revoke(forged).status)
        assertUntouched()
    }

    /** The two forms' tags differ: an A1 signature over the self message is not a master request. */
    @Test
    fun aSignatureOverTheOtherFormsMessage_is401() = runCase {
        val crossed = body(memberA, Authority.MASTER, masterKey, signedAuthority = Authority.SELF)
        assertEquals(HttpStatusCode.Unauthorized, revoke(crossed).status)
        assertUntouched()
    }

    @Test
    fun anUnknownRealm_is404() = runCase(seedRealm = false) {
        assertEquals(HttpStatusCode.NotFound, revoke(byMaster(memberA)).status)
        assertUntouched()
    }

    @Test
    fun repeatingAnAppliedMasterRevocation_isHarmless() = runCase {
        assertEquals(HttpStatusCode.OK, revoke(byMaster(memberA)).status)
        assertEquals(HttpStatusCode.OK, revoke(byMaster(memberA)).status)
        assertRevoked(memberA)
    }

    // ── staleness: the tombstone's rule ─────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The 2026-08-17 case on this route: a removal authored before a legitimate re-admission and
     * delivered after it must not revoke the re-admitted device. Also what bounds a replayed request.
     */
    @Test
    fun aRemovalAuthoredBeforeTheAdmissionOnRecord_isStale_andChangesNothing() =
        runCase(admittedAt = HlcStamp(6_000L, 0L, ownerDevice)) {
            val response = revoke(byMaster(memberA))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"applied":false}""", response.bodyAsText().compact())
            assertUntouched()
        }

    @Test
    fun aRemovalAuthoredAfterTheAdmission_applies() =
        runCase(admittedAt = HlcStamp(4_000L, 0L, ownerDevice)) {
            assertEquals(HttpStatusCode.OK, revoke(byMaster(memberA)).status)
            assertRevoked(memberA)
        }

    @Test
    fun aStaleSelfRemoval_isAlsoStale() = runCase(admittedAt = HlcStamp(6_000L, 0L, ownerDevice)) {
        assertEquals("""{"applied":false}""", revoke(bySelf(memberA)).bodyAsText().compact())
        assertUntouched()
    }

    // ── self form, and T-GR-08 ──────────────────────────────────────────────────────────────────

    @Test
    fun aDeviceMayRevokeItself_withItsOwnKey() = runCase {
        val response = revoke(bySelf(memberA))
        assertEquals(HttpStatusCode.OK, response.status)
        assertRevoked(memberA)
        assertUntouched(ownerDevice, memberB)
    }

    /** Its key is the one it removed, so a repeat is a 401 — carrying the token a client queue reads as done. */
    @Test
    fun repeatingAnAppliedSelfRevocation_is401_markedNotRegistered() = runCase {
        assertEquals(HttpStatusCode.OK, revoke(bySelf(memberA)).status)
        val repeat = revoke(bySelf(memberA))
        assertEquals(HttpStatusCode.Unauthorized, repeat.status)
        assertTrue(RelayRefusal.matches(repeat.bodyAsText(), RelayRefusal.DEVICE_NOT_REGISTERED))
    }

    /** ⚠⚠ A bad signature on a still-registered device is the OTHER 401: no token, so no client reads it as done. */
    @Test
    fun aSelfRemovalWithABadSignature_is401_withoutTheToken() = runCase {
        val response = revoke(bySelf(memberA, signer = keys.getValue(memberB)))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(RelayRefusal.matches(response.bodyAsText(), RelayRefusal.DEVICE_NOT_REGISTERED))
        assertUntouched()
    }

    /** ⚠⚠ T-GR-08: a member signs a self-form removal naming the owner's device. */
    @Test
    fun aMemberCannotRemoveTheOwnersDevice_withItsOwnKey() = runCase {
        assertEquals(HttpStatusCode.Unauthorized, revoke(bySelf(ownerDevice, signer = keys.getValue(memberA))).status)
        assertUntouched()
    }

    /** ⚠⚠ T-GR-08: a member signs a self-form removal naming another member's device. */
    @Test
    fun aMemberCannotRemoveAnotherMembersDevice_withItsOwnKey() = runCase {
        assertEquals(HttpStatusCode.Unauthorized, revoke(bySelf(memberB, signer = keys.getValue(memberA))).status)
        assertUntouched()
    }

    /** ⚠⚠ T-GR-08: a member claims the master form with the only key it has. */
    @Test
    fun aMemberCannotClaimTheMasterForm() = runCase {
        assertEquals(HttpStatusCode.Unauthorized, revoke(byMaster(ownerDevice, signer = keys.getValue(memberA))).status)
        assertEquals(HttpStatusCode.Unauthorized, revoke(byMaster(memberB, signer = keys.getValue(memberA))).status)
        assertUntouched()
    }

    @Test
    fun aSelfRemovalForAnUnknownDevice_is401() = runCase {
        val unknown = body("never-registered", Authority.SELF, "whatever".encodeToByteArray())
        assertEquals(HttpStatusCode.Unauthorized, revoke(unknown).status)
        assertFalse(devices.wasRemoved(realmId, "never-registered"))
    }

    // ── input ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMissingAuthoredStamp_is400() = runCase {
        assertEquals(HttpStatusCode.BadRequest, revoke(body(memberA, Authority.MASTER, masterKey, sentAuthoredAt = null)).status)
        assertUntouched()
    }

    @Test
    fun aMalformedAuthoredStamp_is400() = runCase {
        assertEquals(HttpStatusCode.BadRequest, revoke(body(memberA, Authority.MASTER, masterKey, sentAuthoredAt = "yesterday")).status)
        assertUntouched()
    }

    @Test
    fun anUnknownAuthority_is400() = runCase {
        assertEquals(HttpStatusCode.BadRequest, revoke(body(memberA, Authority.MASTER, masterKey, sentAuthority = "member")).status)
        assertUntouched()
    }

    @Test
    fun aMalformedDeviceId_is400() = runCase {
        assertEquals(HttpStatusCode.BadRequest, revoke(byMaster("bad id")).status)
        assertUntouched()
    }
}
