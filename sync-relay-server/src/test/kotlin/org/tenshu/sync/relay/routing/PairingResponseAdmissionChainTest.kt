package org.tenshu.sync.relay.routing

import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.encodeAdmissionCertChain
import org.tenshu.sync.relay.RelayConfig
import org.tenshu.sync.relay.auth.KnownDevicesStore
import org.tenshu.sync.relay.auth.MasterDelegationVerifier
import org.tenshu.sync.relay.mailbox.AdmissionRevocationStore
import org.tenshu.sync.relay.mailbox.RealmStore
import org.tenshu.sync.relay.testModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P2 coverage of the chain-verified `POST /sync/pairing/response` path
 * (`delegated-device-admission-authority.md` §4.3/§4.4): when the body carries
 * `admissionCertChain`, the delegation is signed by the *admitting device's
 * A33 key* and the relay walks the presented chain to the realm's stored
 * master key + checks the admission revocation list — instead of requiring an
 * A1 signature. The A1 path (no chain) is untouched and stays covered by
 * [PairingResponseMasterDelegationTest].
 *
 * Runs on the stub Ed25519 backend (`publicKey == privateKey`), like the rest
 * of the relay routing tests — what's exercised is the verification wiring.
 */
class PairingResponseAdmissionChainTest {

    private val masterKey = "master-recovery-key".encodeToByteArray().copyOf(32)
    private val ownerId = "owner-delegated"
    private val admitterA33 = ByteArray(32) { (it + 50).toByte() }   // device X's A33
    private val parentA33 = ByteArray(32) { (it + 90).toByte() }     // device W's A33 (X's admitter)
    private val wrongKey = ByteArray(32) { (it + 200).toByte() }
    private val newDevicePubKey = ByteArray(32) { (0xB0 + it).toByte() }
    private val newDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(newDevicePubKey)
    private val pairingSecretHash = "e".repeat(64) // F7: the hash, never the secret
    private val epoch = 0L

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun seedRealm(realmStore: RealmStore) {
        realmStore.createIfAbsent(
            RealmStore.RealmRecord(ownerId = ownerId, masterPublicKey = masterKey, createdAt = 0L)
        )
    }

    private fun cert(deviceId: String, subjectKey: ByteArray, signer: ByteArray): AdmissionCapabilityCert {
        val message = AdmissionCapabilityCert.message(ownerId, subjectKey, deviceId, mintedAtEpoch = 0L)
        return AdmissionCapabilityCert(ownerId, subjectKey, deviceId, 0L, stubEd25519Signature(signer, message))
    }

    /** X's cert minted directly by the owner (A1) — the depth-1 chain. */
    private fun depth1Chain() = listOf(cert("device-x", admitterA33, signer = masterKey))

    /** Owner admits W, W admits X — X's depth-2 chain, leaf → root. */
    private fun depth2Chain() = listOf(
        cert("device-x", admitterA33, signer = parentA33),
        cert("device-w", parentA33, signer = masterKey),
    )

    private fun responseBody(
        chain: List<AdmissionCapabilityCert>,
        delegationKey: ByteArray = admitterA33,
        chainB64: String? = null,
    ): String {
        val message = MasterDelegationVerifier.pairingResponseMessage(
            newDevicePublicKey = newDevicePubKey,
            newDeviceId = newDeviceId,
            epoch = epoch
        )
        val delegation = stubEd25519Signature(delegationKey, message)
        return """
            {
                "pairingSecretHash": "$pairingSecretHash",
                "encryptedMasterKey": "${b64(byteArrayOf(5, 6, 7))}",
                "newDevicePublicKey": "${b64(newDevicePubKey)}",
                "newDeviceId": "$newDeviceId",
                "masterDelegation": "${b64(delegation)}",
                "epoch": "$epoch",
                "admissionCertChain": "${chainB64 ?: b64(encodeAdmissionCertChain(chain))}"
            }
        """.trimIndent()
    }

    private fun revokeBody(deviceId: String, key: ByteArray, signer: ByteArray = masterKey): String {
        val message = org.tenshu.sync.common.AdmissionRevocation.message(
            ownerId, deviceId, epoch = 5L, revokedAt = 1_000L, deviceA33PublicKey = key,
        )
        return """
            {
                "ownerId": "$ownerId",
                "deviceId": "$deviceId",
                "epoch": 5,
                "revokedAt": 1000,
                "signature": "${b64(stubEd25519Signature(signer, message))}",
                "deviceA33PublicKey": "${b64(key)}"
            }
        """.trimIndent()
    }

    private fun runCase(
        body: String,
        expectedStatus: HttpStatusCode,
        expectRegistered: Boolean,
        preRevoke: String? = null,
        removedDeviceIds: Set<String> = emptySet(),
    ) = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore().apply { removedDeviceIds.forEach { recordRemoval(ownerId, it) } }
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }

        if (preRevoke != null) {
            val revoke = client.post("/sync/admission/revoke") {
                contentType(ContentType.Application.Json)
                setBody(preRevoke)
            }
            assertEquals(HttpStatusCode.NoContent, revoke.status)
        }

        val response = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(body)
        }
        assertEquals(expectedStatus, response.status)
        response.bodyAsBytes()

        // The relay registers the joiner when it FETCHES the response (PairingResponseInbox), so
        // the joiner's peek comes first; a refused post queued nothing, and its peek is a 404.
        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(if (expectRegistered) HttpStatusCode.OK else HttpStatusCode.NotFound, peek.status)
        peek.bodyAsBytes()

        val registered = knownDevices.getPublicKey(ownerId, newDeviceId)
        if (expectRegistered) {
            assertNotNull(registered)
            assertTrue(registered.contentEquals(newDevicePubKey))
        } else {
            assertNull(registered)
        }
    }

    @Test
    fun depth1Chain_admitsAndRegisters() =
        runCase(responseBody(depth1Chain()), HttpStatusCode.OK, expectRegistered = true)

    @Test
    fun depth2Chain_admitsAndRegisters() =
        runCase(responseBody(depth2Chain()), HttpStatusCode.OK, expectRegistered = true)

    @Test
    fun chainNotRootedAtMaster_returns401_andDoesNotRegister() =
        runCase(
            responseBody(listOf(cert("device-x", admitterA33, signer = wrongKey))),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
        )

    @Test
    fun delegationNotSignedByChainLeaf_returns401_andDoesNotRegister() =
        // Valid chain, but the delegation is signed by some other key — only the
        // presenting admitter's A33 (chain[0]'s subject) may sign it.
        runCase(
            responseBody(depth1Chain(), delegationKey = wrongKey),
            HttpStatusCode.Unauthorized,
            expectRegistered = false,
        )

    @Test
    fun revokedAdmitter_returns403_andDoesNotRegister() =
        runCase(
            responseBody(depth1Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-x", admitterA33),
        )

    @Test
    fun revokedIntermediate_returns403_forTheWholeSubtreeChain() =
        // Revoking W (X's admitter) kills X's depth-2 chain too — the §5 subtree
        // property at verify time.
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            preRevoke = revokeBody("device-w", parentA33),
        )

    @Test
    fun undecodableChain_returns400() =
        runCase(
            responseBody(depth1Chain(), chainB64 = b64(byteArrayOf(1, 2, 3))),
            HttpStatusCode.BadRequest,
            expectRegistered = false,
        )

    /**
     * ⚠⚠ S1: W (X's admitter) was REMOVED — and no revocation names its key, the case a device
     * that blanked its own `admissionPublicKey` produces. The F2′ ledger still kills every chain
     * through it.
     */
    @Test
    fun aChainThroughARemovedAdmitter_returns403_withoutAnyRevocation() =
        runCase(
            responseBody(depth2Chain()),
            HttpStatusCode.Forbidden,
            expectRegistered = false,
            removedDeviceIds = setOf("device-w"),
        )

    /** B1: the joiner's key must be an Ed25519 key — a 31-byte one is refused before any verification. */
    @Test
    fun aJoinerKeyThatIsNot32Bytes_returns400() =
        runCase(
            responseBody(depth1Chain()).replace(b64(newDevicePubKey), b64(newDevicePubKey.copyOf(31))),
            HttpStatusCode.BadRequest,
            expectRegistered = false,
        )

    /**
     * ⚠⚠ S1 at delivery: the response was verified while W was a member; W is removed before the
     * joiner peeks. The queued registration names W in its chain and must not land.
     */
    @Test
    fun anAdmitterRemovedBeforeTheJoinerFetches_registersNothing() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val post = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.OK, post.status, "precondition: W is still a member at post time")
        post.bodyAsBytes()

        knownDevices.recordRemoval(ownerId, "device-w")

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, peek.status)
        peek.bodyAsBytes()
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration must not land")
    }

    /**
     * ⚠⚠ S1 at delivery, the revocation half: W's A33 key is revoked (key-scoped, as the revoker's
     * strip does at once) before the joiner peeks, while W's removal tombstone has not landed. The
     * delivery re-check must re-run the whole chain against the revocation list, not only the
     * removal ledger.
     */
    @Test
    fun anAdmitterRevokedBeforeTheJoinerFetches_registersNothing_evenWhileNotYetRemoved() = testApplication {
        val realmStore = RealmStore().apply { seedRealm(this) }
        val knownDevices = KnownDevicesStore()
        application {
            testModule(
                config = RelayConfig(devMode = true, bindHost = "127.0.0.1", port = 8080),
                knownDevices = knownDevices,
                realmStore = realmStore,
                admissionRevocationStore = AdmissionRevocationStore(),
            )
        }
        val post = client.post("/sync/pairing/response") {
            contentType(ContentType.Application.Json)
            header("X-Dev-Owner", ownerId)
            setBody(responseBody(depth2Chain()))
        }
        assertEquals(HttpStatusCode.OK, post.status, "precondition: W is unrevoked at post time")
        post.bodyAsBytes()

        val revoke = client.post("/sync/admission/revoke") {
            contentType(ContentType.Application.Json)
            setBody(revokeBody("device-w", parentA33))
        }
        assertEquals(HttpStatusCode.NoContent, revoke.status)

        val peek = client.post("/sync/pairing/response/peek") {
            contentType(ContentType.Application.Json)
            setBody("""{"pairingSecretHash":"$pairingSecretHash"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, peek.status)
        peek.bodyAsBytes()
        assertNull(knownDevices.getPublicKey(ownerId, newDeviceId), "the queued registration must not land")
    }
}
